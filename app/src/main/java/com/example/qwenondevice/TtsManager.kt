package com.example.qwenondevice

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import java.io.File
import java.io.FileOutputStream

class TtsManager(context: Context) {

    companion object {
        private const val ASSET_DIR = "piper"
        private const val LOCAL_DIR = "piper_tts"
        private const val MODEL_NAME = "en_US-lessac-medium.onnx"
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var tts: OfflineTts? = null
    private var player: MediaPlayer? = null

    val isReady: Boolean
        get() = tts != null

    interface Callback {
        fun onSuccess(ok: Boolean)
    }

    fun prepareAsync(onDone: (Boolean) -> Unit) {
        if (tts != null) {
            mainHandler.post { onDone(true) }
            return
        }
        Thread {
            var ok = false
            try {
                val dir = File(appContext.filesDir, LOCAL_DIR)
                ensureModelFiles(dir)
                val config = OfflineTtsConfig()
                config.model.vits.model = File(dir, MODEL_NAME).absolutePath
                config.model.vits.dataDir = File(dir, "espeak-ng-data").absolutePath
                config.model.numThreads = 2
                config.model.debug = false
                tts = OfflineTts(appContext.assets, config)
                ok = true
            } catch (e: Exception) {
                e.printStackTrace()
                tts = null
            }
            mainHandler.post { onDone(ok) }
        }.start()
    }

    private fun ensureModelFiles(dir: File) {
        if (!dir.exists() && !dir.mkdirs()) throw RuntimeException("cannot create $dir")
        val model = File(dir, MODEL_NAME)
        if (!model.exists() || model.length() == 0L) {
            extractFile("$ASSET_DIR/$MODEL_NAME", model)
        }
        val meta = File(dir, MODEL_NAME + ".json")
        if (!meta.exists()) {
            extractFile("$ASSET_DIR/$MODEL_NAME.json", meta)
        }
        val espeakDir = File(dir, "espeak-ng-data")
        if (!espeakDir.exists()) {
            copyAssetTree("$ASSET_DIR/espeak-ng-data", espeakDir)
        }
    }

    private fun extractFile(assetPath: String, target: File) {
        target.parentFile?.mkdirs()
        appContext.assets.open(assetPath).use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output, 128 * 1024)
            }
        }
    }

    private fun copyAssetTree(assetPath: String, targetDir: File) {
        if (!targetDir.exists()) targetDir.mkdirs()
        val children = appContext.assets.list(assetPath) ?: emptyArray()
        for (name in children) {
            val childAsset = "$assetPath/$name"
            val sibling = appContext.assets.list(childAsset) ?: emptyArray()
            if (sibling.isNotEmpty()) {
                copyAssetTree(childAsset, File(targetDir, name))
            } else {
                extractFile(childAsset, File(targetDir, name))
            }
        }
    }

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
