package com.example.qwenondevice

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Piper 离线 TTS 封装（sherpa-onnx OfflineTts VITS）。
 *
 * 模型与 espeak-ng-data 随 APK 打包在 assets/piper/，首次使用时原子解压到
 * files/piper_tts/，解压/校验全部完成前绝不进入 Sherpa 原生初始化：
 *   - Sherpa 的原生加载失败（文件缺失/损坏/路径语义错误）会直接 exit 进程，
 *     Java try/catch 无法拦截，因此所有校验必须发生在 OfflineTts 构造之前；
 *   - 模型文件按精确字节数校验，解压走临时文件 + rename，避免上次启动被中断
 *     留下的半成品文件让后续每次启动都崩溃；
 *   - espeak-ng-data 目录整树解压到临时目录后原子改名，并用完成标记文件标识
 *     完整；覆盖安装/上次崩溃留下的残缺目录会被识别并重新解压。
 *
 * 引擎构造使用 null AssetManager：模型路径是 files 下的绝对路径，sherpa 在
 * 传入非空 AssetManager 时会把它当作 APK 内的资源名去打开而失败并 abort
 * （参见 sherpa-onnx issue #2562），与项目内 RealtimeVoiceManager 的
 * OnlineRecognizer(null, config) 用法保持一致。
 */
class TtsManager(context: Context) {

    companion object {
        private const val TAG = "TtsManager"

        private const val ASSET_DIR = "piper"
        private const val LOCAL_DIR = "piper_tts"
        private const val MODEL_NAME = "en_US-lessac-medium.onnx"
        private const val MODEL_JSON_NAME = "$MODEL_NAME.json"
        private const val TOKENS_NAME = "tokens.txt"
        private const val ESPEAK_DIR = "espeak-ng-data"
        private const val ESPEAK_MARKER = "espeak-ng-data.ready"
        private const val ESPEAK_ASSET_VERSION = "sherpa-tts-models-2025-12-02"

        /** 官方 sherpa-onnx TTS 模型包内文件指纹，用于拒绝原始 Piper/半成品模型。 */
        private val MODEL_ASSET = AssetSpec(
            MODEL_NAME,
            63_149_198L,
            "4ba07d8549906668ee855fd9abf9faf66c5db74742712ff026a159f7277fca9f"
        )
        private val MODEL_JSON_ASSET = AssetSpec(
            MODEL_JSON_NAME,
            4_885L,
            "efe19c417bed055f2d69908248c6ba650fa135bc868b0e6abb3da181dab690a0"
        )
        private val TOKENS_ASSET = AssetSpec(
            TOKENS_NAME,
            921L,
            "87c8ef66eae5473ed0cc0366b3964c736ca6c5f676c979522ea31234e47430b9"
        )

        /** espeak-ng-data 中 TTS 运行必需的关键文件，用于快速校验目录完整。 */
        private val ESPEAK_REQUIRED_FILES = arrayOf(
            "phontab", "phondata", "phonindex", "intonations", "en_dict"
        )

        private data class AssetSpec(
            val name: String,
            val size: Long,
            val sha256: String
        )
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val prepLock = Any()
    private val preparing = AtomicBoolean(false)

    @Volatile
    private var tts: OfflineTts? = null
    private var player: MediaPlayer? = null

    @Volatile
    private var errorMessage: String? = null

    val isReady: Boolean
        get() = tts != null

    /** 最近一次准备失败的原因；成功就绪后为 null。 */
    val lastError: String?
        get() = errorMessage

    interface Callback {
        fun onSuccess(ok: Boolean)
    }

    /**
     * 在后台线程准备 TTS 引擎（解压 + 校验 + 原生初始化）。
     * 同一时刻只允许一个准备流程，重复调用会等前一个完成后立即回调。
     * 任何失败都不会触发原生 abort，应用保持可运行，可通过再次调用重试。
     */
    fun prepareAsync(onDone: (Boolean) -> Unit) {
        if (tts != null) {
            mainHandler.post { onDone(true) }
            return
        }
        if (!preparing.compareAndSet(false, true)) {
            // 已有准备流程在跑：等它结束再回调（此时状态已就绪或已失败）。
            Thread {
                synchronized(prepLock) { /* wait for the in-flight prepare */ }
                val ok = tts != null
                mainHandler.post { onDone(ok) }
            }.start()
            return
        }
        Thread {
            try {
                synchronized(prepLock) {
                    if (tts == null) {
                        prepareBlocking()
                    }
                }
            } finally {
                preparing.set(false)
            }
            val ok = tts != null
            mainHandler.post { onDone(ok) }
        }.start()
    }

    /**
     * 必须在持有 [prepLock] 且 tts == null 的后台线程中调用。
     * 返回 true 表示引擎就绪；失败时记录 [errorMessage] 并返回 false。
     */
    private fun prepareBlocking(): Boolean {
        try {
            val dir = File(appContext.filesDir, LOCAL_DIR)
            ensureModelFiles(dir)
            val config = OfflineTtsConfig()
            config.model.vits.model = File(dir, MODEL_NAME).absolutePath
            config.model.vits.tokens = File(dir, TOKENS_NAME).absolutePath
            config.model.vits.dataDir = File(dir, ESPEAK_DIR).absolutePath
            config.model.numThreads = 2
            config.model.debug = false
            // 关键：模型文件在文件系统上，AssetManager 必须传 null；
            // 传非空 AssetManager 会让 sherpa 把绝对路径当资源名打开 → 原生 abort。
            tts = OfflineTts(null, config)
            errorMessage = null
            Log.i(TAG, "TTS engine ready")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "TTS 引擎准备失败", e)
            tts = null
            errorMessage = "发音引擎准备失败：${e.message ?: e.javaClass.simpleName}（点发音可重试）"
            return false
        }
    }

    // =========================================================================
    // 资产解压：全部原子化 + 校验，失败抛异常（不会进入原生初始化）
    // =========================================================================

    private fun ensureModelFiles(dir: File) {
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("无法创建目录 $dir")
        }
        ensureAsset(dir, MODEL_ASSET)
        ensureAsset(dir, MODEL_JSON_ASSET)
        ensureAsset(dir, TOKENS_ASSET)
        ensureEspeakTree(dir)
    }

    /**
     * 同时校验大小和 SHA-256。这样覆盖安装时，不仅能识别半成品，也能淘汰
     * 曾打包的同名原始 Piper 模型（它缺少 Sherpa 元数据，无法创建 OfflineTts）。
     */
    private fun ensureAsset(dir: File, spec: AssetSpec) {
        val target = File(dir, spec.name)
        if (isValidAsset(target, spec)) {
            File(target.parentFile, target.name + ".tmp").delete()
            return
        }
        target.delete()
        extractAtomic("$ASSET_DIR/${spec.name}", target, spec)
    }

    private fun isValidAsset(file: File, spec: AssetSpec): Boolean {
        return file.isFile &&
            file.length() == spec.size &&
            sha256(file).equals(spec.sha256, ignoreCase = true)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(128 * 1024).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 先写 .tmp，校验后再 rename，避免正式路径留下半成品。 */
    private fun extractAtomic(assetPath: String, target: File, spec: AssetSpec? = null) {
        val parent = target.parentFile ?: throw IOException("目标无父目录: $target")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("无法创建目录 $parent")
        }
        val tmp = File(parent, target.name + ".tmp")
        tmp.delete()
        try {
            appContext.assets.open(assetPath).use { input ->
                FileOutputStream(tmp).use { output ->
                    input.copyTo(output, 128 * 1024)
                }
            }
            if (spec != null && !isValidAsset(tmp, spec)) {
                throw IOException(
                    "资产 $assetPath 解压校验失败：大小或 SHA-256 不匹配"
                )
            }
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) {
                    throw IOException("临时文件改名失败: $tmp -> $target")
                }
            }
        } finally {
            if (!target.isFile && tmp.exists()) tmp.delete()
        }
    }

    /**
     * espeak-ng-data 整树解压。完整解压完成后在父目录写完成标记；
     * 标记缺失（首次、覆盖安装或上次中断）→ 删除旧目录并整树重解压到
     * 临时目录，成功后再原子改名。
     */
    private fun ensureEspeakTree(dir: File) {
        val targetDir = File(dir, ESPEAK_DIR)
        val marker = File(dir, ESPEAK_MARKER)
        val staging = File(dir, ESPEAK_DIR + ".tmp")

        if (
            targetDir.isDirectory &&
            marker.isFile &&
            marker.readText() == ESPEAK_ASSET_VERSION &&
            requiredEspeakFilesPresent(targetDir)
        ) {
            staging.deleteRecursively()
            return
        }
        targetDir.deleteRecursively()
        staging.deleteRecursively()
        if (!staging.mkdirs()) {
            throw IOException("无法创建临时目录 $staging")
        }
        try {
            copyAssetTree("$ASSET_DIR/$ESPEAK_DIR", staging)
            if (!requiredEspeakFilesPresent(staging)) {
                throw IOException("espeak-ng-data 解压不完整，缺少关键文件")
            }
            if (!staging.renameTo(targetDir)) {
                targetDir.deleteRecursively()
                if (!staging.renameTo(targetDir)) {
                    throw IOException("espeak-ng-data 目录改名失败")
                }
            }
            marker.writeText(ESPEAK_ASSET_VERSION)
            if (!marker.isFile || marker.length() == 0L) {
                throw IOException("无法写入完成标记 $marker")
            }
        } finally {
            if (!targetDir.isDirectory && staging.exists()) staging.deleteRecursively()
        }
    }

    private fun requiredEspeakFilesPresent(espeakDir: File): Boolean {
        for (name in ESPEAK_REQUIRED_FILES) {
            val f = File(espeakDir, name)
            if (!f.isFile || f.length() == 0L) return false
        }
        return true
    }

    private fun copyAssetTree(assetPath: String, targetDir: File) {
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw IOException("无法创建目录 $targetDir")
        }
        val children = appContext.assets.list(assetPath) ?: emptyArray()
        for (name in children) {
            val childAsset = "$assetPath/$name"
            val child = File(targetDir, name)
            if ((appContext.assets.list(childAsset) ?: emptyArray()).isNotEmpty()) {
                copyAssetTree(childAsset, child)
            } else {
                extractAtomic(childAsset, child)
            }
        }
    }

    // =========================================================================
    // 合成与播放
    // =========================================================================

    fun speak(text: String, speed: Float = 1.0f, onDone: (() -> Unit)? = null) {
        val engine = tts
        if (engine == null || text.isBlank()) {
            onDone?.invoke()
            return
        }
        stopPlayback()
        Thread {
            try {
                val audio: GeneratedAudio = engine.generate(text, 0, speed.coerceIn(0.7f, 1.3f))
                val wav = File(appContext.cacheDir, "tts_${System.currentTimeMillis()}.wav")
                audio.save(wav.absolutePath)
                mainHandler.post { playWav(wav, onDone) }
            } catch (e: Exception) {
                e.printStackTrace()
                mainHandler.post { onDone?.invoke() }
            }
        }.start()
    }

    private fun playWav(wav: File, onDone: (() -> Unit)?) {
        try {
            val mp = MediaPlayer()
            mp.setDataSource(wav.absolutePath)
            mp.prepare()
            mp.setOnCompletionListener {
                releasePlayer(mp)
                cleanupWav(wav)
                onDone?.invoke()
            }
            mp.setOnErrorListener { p, _, _ ->
                releasePlayer(p)
                onDone?.invoke()
                true
            }
            mp.start()
            player = mp
        } catch (e: Exception) {
            e.printStackTrace()
            onDone?.invoke()
        }
    }

    private fun releasePlayer(mp: MediaPlayer) {
        try {
            if (mp == player) player = null
            mp.release()
        } catch (_: Exception) {
        }
    }

    private fun cleanupWav(wav: File) {
        try {
            wav.delete()
        } catch (_: Exception) {
        }
    }

    fun stopPlayback() {
        val mp = player
        player = null
        if (mp != null) {
            try {
                if (mp.isPlaying) mp.stop()
                mp.release()
            } catch (_: Exception) {
            }
        }
    }

    fun release() {
        stopPlayback()
        try {
            tts?.release()
        } catch (_: Exception) {
        }
        tts = null
    }
}
