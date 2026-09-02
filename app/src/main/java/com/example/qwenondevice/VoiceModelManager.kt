package com.example.qwenondevice

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

object VoiceModelManager {

    private const val MODEL_DIR_NAME = "sherpa_model"

    // 官方高质量双语流式 Zipformer INT8 轻量模型文件列表 (~38MB)
    private const val BASE_HF_URL = "https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/main"

    private val MODEL_FILES = listOf(
        "encoder-epoch-99-avg-1.int8.onnx" to "encoder.int8.onnx",
        "decoder-epoch-99-avg-1.int8.onnx" to "decoder.int8.onnx",
        "joiner-epoch-99-avg-1.int8.onnx" to "joiner.int8.onnx",
        "tokens.txt" to "tokens.txt"
    )

    interface ModelLoadCallback {
        fun onProgress(percentage: Int, message: String)
        fun onSuccess(modelDir: File)
        fun onError(errorMsg: String)
    }

    fun getModelDir(context: Context): File {
        return File(context.filesDir, MODEL_DIR_NAME)
    }

    fun isModelReady(context: Context): Boolean {
        val dir = getModelDir(context)
        if (!dir.exists()) return false
        val encoder = File(dir, "encoder.int8.onnx")
        val decoder = File(dir, "decoder.int8.onnx")
        val joiner = File(dir, "joiner.int8.onnx")
        val tokens = File(dir, "tokens.txt")

        if (encoder.exists() && decoder.exists() && joiner.exists() && tokens.exists()) {
            return true
        }

        val singleModel = File(dir, "model.int8.onnx")
        return singleModel.exists() && tokens.exists()
    }

    fun prepareModel(context: Context, callback: ModelLoadCallback) {
        val ui = Handler(Looper.getMainLooper())
        val targetDir = getModelDir(context)

        if (isModelReady(context)) {
            callback.onSuccess(targetDir)
            return
        }

        Thread {
            try {
                if (!targetDir.exists()) {
                    targetDir.mkdirs()
                }

                // 1. 尝试从 assets 解压
                if (hasAssetModel(context)) {
                    ui.post { callback.onProgress(10, "📦 正在解压内置端侧语音识别模型...") }
                    extractAssetModel(context, targetDir)
                    ui.post {
                        if (isModelReady(context)) {
                            callback.onSuccess(targetDir)
                        } else {
                            callback.onError("内置语音模型解压校验失败")
                        }
                    }
                    return@Thread
                }

                // 2. 否则从远程极速源下载 (~38MB)
                ui.post { callback.onProgress(0, "⬇️ 开始下载 38MB 端侧离线流式语音模型...") }

                val totalFiles = MODEL_FILES.size
                for ((index, pair) in MODEL_FILES.withIndex()) {
                    val (remoteName, localName) = pair
                    val localFile = File(targetDir, localName)

                    if (localFile.exists() && localFile.length() > 0) {
                        continue
                    }

                    val downloadUrl = "$BASE_HF_URL/$remoteName"
                    ui.post {
                        val progress = ((index.toFloat() / totalFiles) * 100).toInt()
                        callback.onProgress(progress, "⬇️ 正在下载语音模型组件 (${index + 1}/$totalFiles)...")
                    }

                    downloadFile(downloadUrl, localFile)
                }

                ui.post {
                    if (isModelReady(context)) {
                        callback.onSuccess(targetDir)
                    } else {
                        callback.onError("语音模型下载不完整")
                    }
                }

            } catch (e: Exception) {
                ui.post { callback.onError("语音模型准备失败: ${e.message}") }
            }
        }.start()
    }

    private fun hasAssetModel(context: Context): Boolean {
        return try {
            val list = context.assets.list("sherpa")
            !list.isNullOrEmpty()
        } catch (e: Exception) {
            try {
                context.assets.open("sherpa.zip").close()
                true
            } catch (e2: Exception) {
                false
            }
        }
    }

    private fun extractAssetModel(context: Context, targetDir: File) {
        // 支持 sherpa.zip 或 sherpa/ 目录
        try {
            val zipStream = context.assets.open("sherpa.zip")
            unzip(zipStream, targetDir)
            return
        } catch (ignored: Exception) {}

        try {
            val files = context.assets.list("sherpa") ?: return
            for (filename in files) {
                context.assets.open("sherpa/$filename").use { input ->
                    FileOutputStream(File(targetDir, filename)).use { output ->
                        input.copyTo(output)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun unzip(zipInputStream: InputStream, targetDirectory: File) {
        ZipInputStream(zipInputStream).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val file = File(targetDirectory, entry.name)
                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    FileOutputStream(file).use { fos ->
                        zis.copyTo(fos)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun downloadFile(urlStr: String, destFile: File) {
        var conn: HttpURLConnection? = null
        try {
            val url = URL(urlStr)
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 30000
            conn.readTimeout = 60000
            conn.instanceFollowRedirects = true
            conn.connect()

            val tempFile = File(destFile.parentFile, "${destFile.name}.tmp")
            conn.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output, bufferSize = 32 * 1024)
                }
            }

            if (tempFile.exists() && tempFile.length() > 0) {
                if (destFile.exists()) destFile.delete()
                tempFile.renameTo(destFile)
            }
        } finally {
            conn?.disconnect()
        }
    }
}
