package com.example.qwenondevice

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class WordRow(
    val id: Long,
    val word: String,
    val phonetic: String,
    val translation: String,
    val addedAt: Long,
    val stage: Int,
    val reviews: Int,
    val intervalDays: Int,
    val dueAt: Long
)

class AppDatabaseHelper(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "qwen_english.db"
        private const val DB_VERSION = 1
        private const val MIN_INTERVAL_MS = 2 * 60 * 1000L
        private const val FUZZY_INTERVAL_MS = 10 * 60 * 1000L
        val STAGE_INTERVAL_DAYS = intArrayOf(0, 1, 3, 7, 15, 30)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE words (
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                word TEXT NOT NULL UNIQUE,
                phonetic TEXT NOT NULL DEFAULT '',
                translation TEXT NOT NULL DEFAULT '',
                added_at INTEGER NOT NULL,
                stage INTEGER NOT NULL DEFAULT 0,
                interval_days INTEGER NOT NULL DEFAULT 0,
                reviews INTEGER NOT NULL DEFAULT 0,
                due_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE dict_cache (
                word TEXT PRIMARY KEY,
                example_en TEXT NOT NULL DEFAULT '',
                example_cn TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS words")
        db.execSQL("DROP TABLE IF EXISTS dict_cache")
        onCreate(db)
    }

    fun upsertWord(word: String, phonetic: String, translation: String): Boolean {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("word", word.trim())
            put("phonetic", phonetic)
            put("translation", translation)
            put("added_at", now)
            put("due_at", now)
        }
        return writableDatabase.insertWithOnConflict(
            "words", null, values, SQLiteDatabase.CONFLICT_REPLACE
        ) != -1L
    }

    fun deleteWord(word: String): Int =
        readableDatabase.delete("words", "word = ?", arrayOf(word.trim()))

    fun clearAllWords(): Int = readableDatabase.delete("words", null, null)

    private fun cursorToWord(cursor: android.database.Cursor): WordRow {
        return WordRow(
            id = cursor.getLong(cursor.getColumnIndexOrThrow("_id")),
            word = cursor.getString(cursor.getColumnIndexOrThrow("word")),
            phonetic = cursor.getString(cursor.getColumnIndexOrThrow("phonetic")),
            translation = cursor.getString(cursor.getColumnIndexOrThrow("translation")),
            addedAt = cursor.getLong(cursor.getColumnIndexOrThrow("added_at")),
            stage = cursor.getInt(cursor.getColumnIndexOrThrow("stage")),
            reviews = cursor.getInt(cursor.getColumnIndexOrThrow("reviews")),
            intervalDays = cursor.getInt(cursor.getColumnIndexOrThrow("interval_days")),
            dueAt = cursor.getLong(cursor.getColumnIndexOrThrow("due_at"))
        )
    }

    fun dueWords(now: Long = System.currentTimeMillis(), limit: Int = 20): List<WordRow> {
        val list = ArrayList<WordRow>()
        readableDatabase.rawQuery(
            "SELECT * FROM words WHERE due_at <= ? ORDER BY due_at ASC LIMIT ?",
            arrayOf(now.toString(), limit.toString())
        ).use { c ->
            while (c.moveToNext()) list.add(cursorToWord(c))
        }
        return list
    }

    fun latestWords(limit: Int = 100): List<WordRow> {
        val list = ArrayList<WordRow>()
        readableDatabase.rawQuery(
            "SELECT * FROM words ORDER BY added_at DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) list.add(cursorToWord(c))
        }
        return list
    }

    fun countWords(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM words", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    fun countDue(now: Long = System.currentTimeMillis()): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM words WHERE due_at <= ?", arrayOf(now.toString())
        ).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    fun reviewWord(word: String, rating: Int) {
        val now = System.currentTimeMillis()
        var id: Long = -1
        var stage = 0
        var reviews = 0
        readableDatabase.rawQuery(
            "SELECT _id, stage, reviews FROM words WHERE word = ?", arrayOf(word.trim())
        ).use { c ->
            if (c.moveToFirst()) {
                id = c.getLong(c.getColumnIndexOrThrow("_id"))
                stage = c.getInt(c.getColumnIndexOrThrow("stage"))
                reviews = c.getInt(c.getColumnIndexOrThrow("reviews"))
            }
        }
        if (id == -1L) return
        val values = ContentValues()
        when (rating) {
            0 -> {
                values.put("stage", 0)
                values.put("interval_days", 0)
                values.put("due_at", now + MIN_INTERVAL_MS)
            }
            1 -> {
                values.put("stage", stage.coerceAtLeast(1))
                values.put("interval_days", STAGE_INTERVAL_DAYS[stage.coerceIn(1, STAGE_INTERVAL_DAYS.size - 1)])
                values.put("due_at", now + FUZZY_INTERVAL_MS)
            }
            else -> {
                val s = (stage + 1).coerceAtMost(STAGE_INTERVAL_DAYS.size - 1)
                values.put("stage", s)
                values.put("interval_days", STAGE_INTERVAL_DAYS[s])
                values.put("due_at", now + STAGE_INTERVAL_DAYS[s].toLong() * 24 * 3600 * 1000L)
            }
        }
        values.put("reviews", reviews + 1)
        writableDatabase.update("words", values, "_id = ?", arrayOf(id.toString()))
    }

    fun getDictCache(word: String): Pair<String, String>? {
        readableDatabase.rawQuery(
            "SELECT example_en, example_cn FROM dict_cache WHERE word = ?",
            arrayOf(word.trim().lowercase())
        ).use { c ->
            if (!c.moveToFirst()) return null
            return c.getString(0) to c.getString(1)
        }
    }

    fun putDictCache(word: String, exampleEn: String, exampleCn: String) {
        val values = ContentValues().apply {
            put("word", word.trim().lowercase())
            put("example_en", exampleEn)
            put("example_cn", exampleCn)
            put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            "dict_cache", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }
}
