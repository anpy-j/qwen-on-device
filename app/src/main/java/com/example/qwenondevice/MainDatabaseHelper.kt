package com.example.qwenondevice

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DashboardSummary(
    val expense: Double,
    val income: Double,
    val billCount: Int,
    val diaryCount: Int,
    val scheduleCount: Int,
    val meetingCount: Int,
    val riskCount: Int
)

/** SQLite store for the six original life-assistant modules. */
class MainDatabaseHelper(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "qwen_all_in_one.db"
        private const val DATABASE_VERSION = 2
        private const val BILLS = "bills"
        private const val SCHEDULES = "schedules"
        private const val DIARIES = "diaries"
        private const val MEETINGS = "meetings"
        private const val SMS = "sms_records"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE $BILLS (_id INTEGER PRIMARY KEY AUTOINCREMENT,type TEXT NOT NULL,category TEXT NOT NULL,amount REAL NOT NULL,pay_method TEXT,note TEXT,raw_text TEXT,created_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE $SCHEDULES (_id INTEGER PRIMARY KEY AUTOINCREMENT,type TEXT NOT NULL,title TEXT NOT NULL,time_str TEXT NOT NULL,timestamp INTEGER NOT NULL,location TEXT,note TEXT,created_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE $DIARIES (_id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT NOT NULL,content TEXT NOT NULL,polished_content TEXT,corrections TEXT,mood TEXT,created_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE $MEETINGS (_id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT NOT NULL,raw_content TEXT NOT NULL,consensus TEXT,action_items TEXT,risks TEXT,created_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE $SMS (_id INTEGER PRIMARY KEY AUTOINCREMENT,sender TEXT,content TEXT NOT NULL,category TEXT NOT NULL,risk_level TEXT NOT NULL,analysis TEXT,advice TEXT,created_at INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        listOf(BILLS, SCHEDULES, DIARIES, MEETINGS, SMS).forEach {
            db.execSQL("DROP TABLE IF EXISTS $it")
        }
        onCreate(db)
    }

    fun dashboardSummary(): DashboardSummary {
        var expense = 0.0
        var income = 0.0
        var bills = 0
        readableDatabase.rawQuery("SELECT type, amount FROM $BILLS", null).use { c ->
            while (c.moveToNext()) {
                bills++
                if (c.getString(0) == "收入") income += c.getDouble(1) else expense += c.getDouble(1)
            }
        }
        return DashboardSummary(
            expense, income, bills, count(DIARIES), count(SCHEDULES), count(MEETINGS),
            scalar("SELECT COUNT(*) FROM $SMS WHERE risk_level IN ('高危','中危')")
        )
    }

    fun insertBill(type: String, category: String, amount: Double, payMethod: String, note: String, raw: String): Long =
        writableDatabase.insert(BILLS, null, values(
            "type" to type, "category" to category, "amount" to amount,
            "pay_method" to payMethod, "note" to note, "raw_text" to raw,
            "created_at" to System.currentTimeMillis()
        ))

    fun insertSchedule(type: String, title: String, time: String, location: String, note: String): Long =
        writableDatabase.insert(SCHEDULES, null, values(
            "type" to type, "title" to title, "time_str" to time,
            "timestamp" to System.currentTimeMillis(), "location" to location,
            "note" to note, "created_at" to System.currentTimeMillis()
        ))

    fun insertDiary(raw: String, result: String): Long = writableDatabase.insert(DIARIES, null, values(
        "title" to "日记-${date("yyyy-MM-dd")}", "content" to raw,
        "polished_content" to result, "corrections" to "", "mood" to inferMood(result),
        "created_at" to System.currentTimeMillis()
    ))

    fun insertMeeting(raw: String, result: String): Long = writableDatabase.insert(MEETINGS, null, values(
        "title" to "会议纪要-${date("MM-dd HH:mm")}", "raw_content" to raw,
        "consensus" to result, "action_items" to "", "risks" to "",
        "created_at" to System.currentTimeMillis()
    ))

    fun insertSms(raw: String, result: String): Long {
        val risk = when {
            result.contains("高危") || result.contains("诈骗") -> "高危"
            result.contains("中危") || result.contains("推销") -> "中危"
            else -> "安全"
        }
        return writableDatabase.insert(SMS, null, values(
            "sender" to "短信分析", "content" to raw,
            "category" to if (risk == "安全") "普通" else "可疑",
            "risk_level" to risk, "analysis" to result, "advice" to "",
            "created_at" to System.currentTimeMillis()
        ))
    }

    fun history(module: ModuleType, limit: Int = 20): List<String> {
        val (table, columns) = when (module) {
            ModuleType.BOOKKEEPING -> BILLS to arrayOf("type", "category", "amount", "note", "created_at")
            ModuleType.SCHEDULE -> SCHEDULES to arrayOf("type", "title", "time_str", "location", "created_at")
            ModuleType.MEETING -> MEETINGS to arrayOf("title", "consensus", "created_at")
            ModuleType.DIARY -> DIARIES to arrayOf("title", "polished_content", "mood", "created_at")
            ModuleType.ANTI_FRAUD -> SMS to arrayOf("risk_level", "content", "analysis", "created_at")
            ModuleType.CONTRACT -> return emptyList()
        }
        val rows = mutableListOf<String>()
        readableDatabase.query(table, columns, null, null, null, null, "created_at DESC", limit.toString()).use { c ->
            while (c.moveToNext()) {
                val created = c.getLong(c.columnCount - 1)
                val body = when (module) {
                    ModuleType.BOOKKEEPING -> "${c.getString(0)} · ${c.getString(1)}  ¥${String.format(Locale.getDefault(), "%.2f", c.getDouble(2))}\n${c.getString(3).orEmpty()}"
                    ModuleType.SCHEDULE -> "${c.getString(0)} · ${c.getString(2)}  ${c.getString(1)}\n${c.getString(3).orEmpty()}"
                    ModuleType.MEETING -> "${c.getString(0)}\n${c.getString(1).take(180)}"
                    ModuleType.DIARY -> "${c.getString(0)} · ${c.getString(2)}\n${c.getString(1).take(180)}"
                    ModuleType.ANTI_FRAUD -> "${c.getString(0)} · ${c.getString(1).take(80)}\n${c.getString(2).take(160)}"
                    ModuleType.CONTRACT -> ""
                }
                rows += "${date("MM-dd HH:mm", created)}\n$body"
            }
        }
        return rows
    }

    fun clear(module: ModuleType): Int {
        val table = when (module) {
            ModuleType.BOOKKEEPING -> BILLS
            ModuleType.SCHEDULE -> SCHEDULES
            ModuleType.MEETING -> MEETINGS
            ModuleType.DIARY -> DIARIES
            ModuleType.ANTI_FRAUD -> SMS
            ModuleType.CONTRACT -> return 0
        }
        return writableDatabase.delete(table, null, null)
    }

    private fun count(table: String) = scalar("SELECT COUNT(*) FROM $table")
    private fun scalar(sql: String): Int = readableDatabase.rawQuery(sql, null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    private fun date(pattern: String, time: Long = System.currentTimeMillis()) =
        SimpleDateFormat(pattern, Locale.getDefault()).format(Date(time))
    private fun inferMood(text: String) = when {
        text.contains("焦虑") || text.contains("难过") -> "焦虑"
        text.contains("疲惫") -> "疲惫"
        text.contains("开心") -> "开心"
        text.contains("充实") -> "充实"
        else -> "平静"
    }

    private fun values(vararg entries: Pair<String, Any?>) = ContentValues().apply {
        entries.forEach { (key, value) ->
            when (value) {
                null -> putNull(key)
                is String -> put(key, value)
                is Long -> put(key, value)
                is Int -> put(key, value)
                is Double -> put(key, value)
                else -> put(key, value.toString())
            }
        }
    }
}
