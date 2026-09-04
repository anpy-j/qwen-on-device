package com.example.qwenondevice

import android.content.Context
import org.json.JSONArray

object Dictionary {

    data class Entry(
        val word: String,
        val phonetic: String,
        val definition: String,
        val translation: String,
        val inflection: String,
        val tag: String,
        val source: String
    )

    // 后台线程写入、主线程读取，需保证可见性
    @Volatile
    private var index: Map<String, Entry>? = null
    @Volatile
    var loadedCount: Int = 0
        private set

    fun cleanPhonetic(raw: String): String {
        if (raw.isBlank()) return ""
        var p = raw.trim().trim('/', '\'', '"', ' ')
        // 修复早期词库中历史遗留的西里尔/特殊字符，对齐标准国际音标 (IPA)
        p = p.replace('\u04d9', 'ə') // Cyrillic schwa -> IPA schwa
            .replace('\u0454', 'e')
            .replace('\u03b5', 'ɛ')
            .replace(':', 'ː')
            .replace(Regex("'([a-zA-Zʃʒθðŋʌæɑɔəɪʊɛ])"), "ˈ$1")
            .replace(Regex("\\.([a-zA-Zʃʒθðŋʌæɑɔəɪʊɛ])"), "ˌ$1")
            .replace("'", "ˈ")
            .replace("ai", "aɪ")
            .replace("ei", "eɪ")
            .replace("au", "aʊ")
            .replace("әu", "əʊ")
            .replace("əu", "əʊ")
            .replace("ɔi", "ɔɪ")
            .replace("iə", "ɪə")
            .replace("eə", "eə")
            .replace("uə", "ʊə")
        return p
    }

    fun load(context: Context, onDone: (Int) -> Unit) {
        if (index != null) {
            onDone(loadedCount)
            return
        }
        Thread {
            val count = try {
                context.assets.open("dict/words.json").bufferedReader().use { reader ->
                    val array = JSONArray(reader.readText())
                    val map = HashMap<String, Entry>(array.length() * 2)
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        val word = obj.optString("w")
                        map[word.lowercase()] = Entry(
                            word = word,
                            phonetic = cleanPhonetic(obj.optString("p")),
                            definition = obj.optString("d"),
                            translation = obj.optString("t"),
                            inflection = obj.optString("inf"),
                            tag = obj.optString("tag"),
                            source = "bundled"
                        )
                    }
                    index = map
                    map.size
                }
            } catch (e: Exception) {
                e.printStackTrace()
                index = HashMap()
                0
            }
            loadedCount = count
            onDone(count)
        }.start()
    }

    fun lookup(word: String): Entry? {
        val idx = index ?: return null
        val base = word.trim().lowercase()
        return idx[base]
            ?: idx[base.trimEnd('s')]
            ?: if (base.endsWith("es")) idx[base.removeSuffix("es")] else null
    }

    fun isLoaded(): Boolean = index != null
}
