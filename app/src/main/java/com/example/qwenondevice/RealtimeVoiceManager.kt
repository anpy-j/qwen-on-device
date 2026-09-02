package com.example.qwenondevice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import java.io.File
import kotlin.math.log10
import kotlin.math.sqrt

class RealtimeVoiceManager(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var isRecording = false
    @Volatile
    private var cancelled = false
    @Volatile
    private var released = false
    @Volatile
    private var finishCallback: VoiceCallback? = null
    private var sessionCallback: VoiceCallback? = null
    private var recordingThread: Thread? = null

    // VAD 与静音检测
    private val silenceThresholdMs = 1800L // 连续 1.8 秒无语音活动自动判停
    private val speechDbThreshold = -45f   // 低于该 dBFS 认为不是人声
    private var lastActivityTimestamp = 0L
    private var hasSpoken = false

    interface VoiceCallback {
        fun onReady()
        fun onPartialResult(partialText: String)
        fun onRmsChanged(rmsdB: Float)
        fun onFinished(finalText: String)
        fun onError(errorMsg: String)
    }

    fun isInitialized(): Boolean = recognizer != null

    /**
     * 初始化 Sherpa-ONNX 流式语音识别器（幂等：已初始化时直接返回 true）
     * @param modelDir 模型所在目录（包含 encoder, decoder, joiner, tokens）
     */
    @Synchronized
    fun initRecognizer(modelDir: File): Boolean {
        if (recognizer != null) return true
        try {
            val config = OnlineRecognizerConfig()

            val encoder = File(modelDir, "encoder.int8.onnx")
            val decoder = File(modelDir, "decoder.int8.onnx")
            val joiner = File(modelDir, "joiner.int8.onnx")
            val tokens = File(modelDir, "tokens.txt")

            if (!(encoder.exists() && decoder.exists() && joiner.exists() && tokens.exists())) {
                return false
            }

            config.modelConfig.transducer.encoder = encoder.absolutePath
            config.modelConfig.transducer.decoder = decoder.absolutePath
            config.modelConfig.transducer.joiner = joiner.absolutePath
            config.modelConfig.tokens = tokens.absolutePath
            config.modelConfig.numThreads = 2
            config.modelConfig.debug = false
            config.featConfig.sampleRate = 16000
            config.featConfig.featureDim = 80

            // 关键：关闭引擎内置 endpoint，且全程不 reset 流。
            // 引擎 Reset() 会把上一段尾部 token 作为新段"上下文"回填进结果文本
            // (v1.12.15 online-recognizer-transducer-impl.h)，停顿稍长就触发分段，
            // 尾部字被重复输出（如 买烟→买烟炎炎、十三→十十三）。
            // 短提示词场景单次说话就是一整段，交给下方 1.8s 静音判停即可。
            config.enableEndpoint = false

            recognizer = OnlineRecognizer(null, config)
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    /**
     * 异步初始化识别器：模型加载(构建 ONNX 会话)耗时数秒，
     * 必须在后台线程执行，避免阻塞主线程触发 ANR（应用无响应）。
     * onDone 始终回主线程调用。
     */
    fun initRecognizerAsync(modelDir: File, onDone: (Boolean) -> Unit) {
        Thread {
            val ok = initRecognizer(modelDir)
            mainHandler.post { onDone(ok) }
        }.start()
    }

    /**
     * 启动流式录音与实时识别。
     * 静音判停或显式调用 stopListening() 后，完整识别结果通过 onFinished() 回调。
     */
    fun startListening(callback: VoiceCallback) {
        val rec = recognizer
        if (rec == null) {
            callback.onError("语音引擎尚未就绪")
            return
        }

        // 等待上一个会话的录音线程收尾，避免共享资源竞争
        if (isRecording || recordingThread != null) {
            isRecording = false
            cancelled = true
            finishCallback = null
            waitForThread()
        }

        try {
            stream?.release()
            val activeStream = rec.createStream()
            stream = activeStream
            hasSpoken = false
            cancelled = false
            finishCallback = null
            sessionCallback = callback

            val sampleRate = 16000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat).coerceAtLeast(3200)

            // VOICE_RECOGNITION 音源经过最少的系统处理，更适合 ASR
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord?.release()
                audioRecord = null
                callback.onError("无法初始化麦克风录音设备")
                return
            }

            isRecording = true
            lastActivityTimestamp = System.currentTimeMillis()
            audioRecord?.startRecording()

            mainHandler.post { callback.onReady() }

            recordingThread = Thread {
                try {
                    runRecognitionLoop(rec, activeStream, sampleRate, callback)
                } catch (e: Exception) {
                    // 录音线程里任何未捕获异常都会直接让进程 FATAL 崩溃
                    e.printStackTrace()
                    isRecording = false
                    finishSession(rec, activeStream, "语音识别异常: ${e.message ?: e.javaClass.simpleName}", callback)
                }
            }.apply { start() }

        } catch (e: Exception) {
            isRecording = false
            sessionCallback = null
            callback.onError("录音启动失败: ${e.message}")
        }
    }

    /**
     * 请求停止录音并返回最终识别文本（异步，结果经 onFinished 回调）。
     */
    fun stopListening(callback: VoiceCallback? = null) {
        if (!isRecording) return
        if (callback != null) finishCallback = callback
        cancelled = false
        isRecording = false
    }

    /**
     * 取消本次录音：停止录音但不回调最终结果。
     */
    fun cancel() {
        cancelled = true
        finishCallback = null
        if (isRecording) {
            isRecording = false
        } else {
            sessionCallback = null
        }
    }

    /**
     * 录音主循环：100ms 一片，喂流式识别。
     * 全程单一流、无 endpoint/reset，文本随 getResult 单调累积，边界不会重复或丢字。
     */
    private fun runRecognitionLoop(
        rec: OnlineRecognizer,
        activeStream: OnlineStream,
        sampleRate: Int,
        callback: VoiceCallback
    ) {
        val buffer = ShortArray(1600) // 100ms 切片 (16000 * 0.1)
        var lastUiText = ""
        var loopError: String? = null

        while (isRecording) {
            try {
                val readSamples = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (readSamples <= 0) continue
                val now = System.currentTimeMillis()

                // 1. 计算 RMS（标准 dBFS：0 满刻度，语音约 -45 ~ -15，噪声底通常 < -60）
                var sum = 0.0
                for (i in 0 until readSamples) {
                    sum += buffer[i] * buffer[i]
                }
                val rms = sqrt(sum / readSamples)
                val db = if (rms > 0) (20 * log10(rms / 32768.0)).toFloat() else -95f
                mainHandler.post { callback.onRmsChanged(db) }

                // 2. 喂给 Sherpa-ONNX
                val floatSamples = FloatArray(readSamples) { buffer[it] / 32768.0f }
                activeStream.acceptWaveform(floatSamples, sampleRate)

                // 3. 解码
                while (rec.isReady(activeStream)) {
                    rec.decode(activeStream)
                }

                // 4. 当前完整文本（单流累积，不含任何分段拼接）
                val currentText = rec.getResult(activeStream).text.trim()

                if (currentText.isNotEmpty()) {
                    hasSpoken = true
                }

                // 5. 有真实语音活动才推进判停时间戳（能量超阈值 或 文本有新增）
                if (db > speechDbThreshold || currentText != lastUiText) {
                    lastActivityTimestamp = now
                }

                if (currentText.isNotEmpty() && currentText != lastUiText) {
                    lastUiText = currentText
                    mainHandler.post { callback.onPartialResult(currentText) }
                }

                // 6. VAD 静音自动判停
                if (hasSpoken && now - lastActivityTimestamp > silenceThresholdMs) {
                    cancelled = false
                    isRecording = false
                    break
                }
            } catch (e: Exception) {
                // JNI/录音异常不应直接杀掉进程：收尾并走 onError
                e.printStackTrace()
                loopError = "语音识别异常: ${e.message ?: e.javaClass.simpleName}"
                isRecording = false
            }
        }

        finishSession(rec, activeStream, loopError, callback)
    }

    /**
     * 在录音线程内收尾：停麦、冲刷尾部、取最终文本、释放流，最后回主线程回调。
     * @param loopError 非空时表示录音循环异常终止，回调 onError 而非 onFinished
     */
    private fun finishSession(
        rec: OnlineRecognizer,
        activeStream: OnlineStream,
        loopError: String?,
        callback: VoiceCallback
    ) {
        audioRecord?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        audioRecord = null

        var finalText = ""
        if (loopError == null) {
            try {
                // 刷入尾部，保证最后一两个字也被识别
                activeStream.inputFinished()
                while (rec.isReady(activeStream)) {
                    rec.decode(activeStream)
                }
                finalText = rec.getResult(activeStream).text.trim()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        try { activeStream.release() } catch (_: Exception) {}
        if (stream === activeStream) stream = null
        recordingThread = null

        val cb = finishCallback ?: sessionCallback
        finishCallback = null
        sessionCallback = null
        if (!cancelled && !released && cb != null) {
            if (loopError != null) {
                mainHandler.post { cb.onError(loopError) }
            } else {
                mainHandler.post { cb.onFinished(finalText) }
            }
        }
    }

    private fun waitForThread(timeoutMs: Long = 2000) {
        val t = recordingThread ?: return
        try {
            t.join(timeoutMs)
        } catch (_: InterruptedException) {}
        if (!t.isAlive) recordingThread = null
    }

    fun release() {
        released = true
        cancelled = true
        isRecording = false
        finishCallback = null
        sessionCallback = null

        // 先停录音，解除录音线程阻塞在原生 read() 上的状态，再等待线程结束
        audioRecord?.let {
            try { it.stop() } catch (_: Exception) {}
        }

        recordingThread?.let {
            try { it.join(2000) } catch (_: Exception) {}
        }
        val threadAlive = recordingThread?.isAlive ?: false
        recordingThread = null

        // 只有录音线程确实结束后才释放共享的 JNI 对象，
        // 避免与录音线程并发释放同一对象导致的原生崩溃
        if (!threadAlive) {
            audioRecord?.let {
                try { it.release() } catch (_: Exception) {}
            }
            audioRecord = null
            stream?.let {
                try { it.release() } catch (_: Exception) {}
            }
            stream = null
            recognizer?.let {
                try { it.release() } catch (_: Exception) {}
            }
            recognizer = null
        }
    }
}
