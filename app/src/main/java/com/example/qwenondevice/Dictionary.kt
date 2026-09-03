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

    private var index: Map<String, Entry>? = null
    @Volatile
    var loadedCount: Int = 0
        private set

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
                            phonetic = obj.optString("p"),
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
        return idx[base] ?: idx[base.trimEnd('s')]
    }

    fun isLoaded(): Boolean = index != null
}
