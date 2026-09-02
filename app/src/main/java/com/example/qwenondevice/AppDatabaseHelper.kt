package com.example.qwenondevice

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 日程数据实体
data class ScheduleItem(
    val id: Long = 0,
    val type: String, // "闹钟" 或 "日历"
    val title: String,
    val timeStr: String,
    val timestamp: Long,
    val location: String = "",
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    val formattedCreated: String
        get() = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(createdAt))
}

// 私密日记数据实体
data class DiaryItem(
    val id: Long = 0,
    val title: String,
    val content: String,
    val polishedContent: String,
    val corrections: String,
    val mood: String, // "开心" / "平静" / "疲惫" / "充实" / "焦虑"
    val createdAt: Long = System.currentTimeMillis()
) {
    val formattedTime: String
        get() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(createdAt))

    val moodIcon: String
        get() = when (mood) {
            "开心", "快乐", "兴奋" -> "🌟"
            "平静", "安宁" -> "☕"
            "疲惫", "劳累" -> "🔋"
            "充实", "成长" -> "💪"
            "焦虑", "难过" -> "🌧️"
            else -> "📝"
        }
}

// 会议纪要数据实体
data class MeetingSummaryItem(
    val id: Long = 0,
    val title: String,
    val rawContent: String,
    val consensus: String,
    val actionItems: String,
    val risks: String,
    val createdAt: Long = System.currentTimeMillis()
) {
    val formattedTime: String
        get() = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(createdAt))
}

// 短信反诈记录实体
data class SmsRecordItem(
    val id: Long = 0,
    val sender: String,
    val content: String,
    val category: String, // "快递" / "账单" / "出行" / "可疑" / "普通"
    val riskLevel: String, // "高危" / "中危" / "安全"
    val analysis: String,
    val advice: String,
    val createdAt: Long = System.currentTimeMillis()
) {
    val formattedTime: String
        get() = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(createdAt))

    val riskBadge: String
        get() = when (riskLevel) {
            "高危", "高" -> "🔴 高危诈骗"
            "中危", "中" -> "🟡 疑似推销"
            else -> "🟢 安全通知"
        }
}

class AppDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "qwen_all_in_one.db"
        const val DATABASE_VERSION = 2

        // 表名
        const val TABLE_BILLS = "bills"
        const val TABLE_SCHEDULES = "schedules"
        const val TABLE_DIARIES = "diaries"
        const val TABLE_MEETINGS = "meetings"
        const val TABLE_SMS = "sms_records"
    }

    override fun onCreate(db: SQLiteDatabase) {
        // 1. 记账表
        db.execSQL("""
            CREATE TABLE $TABLE_BILLS (
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                type TEXT NOT NULL,
                category TEXT NOT NULL,
                amount REAL NOT NULL,
                pay_method TEXT,
                note TEXT,
                raw_text TEXT,
                created_at INTEGER NOT NULL
            )
        """.trimIndent())

        // 2. 日程与闹钟表
        db.execSQL("""
            CREATE TABLE $TABLE_SCHEDULES (
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                type TEXT NOT NULL,
                title TEXT NOT NULL,
                time_str TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                location TEXT,
                note TEXT,
                created_at INTEGER NOT NULL
            )
        """.trimIndent())

        // 3. 私人日记表
        db.execSQL("""
            CREATE TABLE $TABLE_DIARIES (
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                content TEXT NOT NULL,
                polished_content TEXT,
                corrections TEXT,
                mood TEXT,
                created_at INTEGER NOT NULL
            )
        """.trimIndent())

        // 4. 会议纪要表
        db.execSQL("""
            CREATE TABLE $TABLE_MEETINGS (
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                raw_content TEXT NOT NULL,
                consensus TEXT,
                action_items TEXT,
                risks TEXT,
                created_at INTEGER NOT NULL
            )
        """.trimIndent())

        // 5. 短信反诈表
        db.execSQL("""
            CREATE TABLE $TABLE_SMS (
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                sender TEXT,
                content TEXT NOT NULL,
                category TEXT NOT NULL,
                risk_level TEXT NOT NULL,
                analysis TEXT,
                advice TEXT,
                created_at INTEGER NOT NULL
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_BILLS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_SCHEDULES")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_DIARIES")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_MEETINGS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_SMS")
        onCreate(db)
    }

    // ================= 记账操作 =================
    fun insertBill(bill: Bill): Long {
        val values = ContentValues().apply {
            put("type", bill.type)
            put("category", bill.category)
            put("amount", bill.amount)
            put("pay_method", bill.payMethod)
            put("note", bill.note)
            put("raw_text", bill.rawText)
            put("created_at", bill.createdAt)
        }
        return writableDatabase.insert(TABLE_BILLS, null, values)
    }

    fun getAllBills(): List<Bill> {
        val list = mutableListOf<Bill>()
        val cursor = readableDatabase.query(TABLE_BILLS, null, null, null, null, null, "created_at DESC")
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    Bill(
                        id = it.getLong(it.getColumnIndexOrThrow("_id")),
                        type = it.getString(it.getColumnIndexOrThrow("type")),
                        category = it.getString(it.getColumnIndexOrThrow("category")),
                        amount = it.getDouble(it.getColumnIndexOrThrow("amount")),
                        payMethod = it.getString(it.getColumnIndexOrThrow("pay_method")) ?: "",
                        note = it.getString(it.getColumnIndexOrThrow("note")) ?: "",
                        rawText = it.getString(it.getColumnIndexOrThrow("raw_text")) ?: "",
                        createdAt = it.getLong(it.getColumnIndexOrThrow("created_at"))
                    )
                )
            }
        }
        return list
    }

    fun getBillStatistics(): BillStatistics {
        var totalExpense = 0.0
        var totalIncome = 0.0
        var count = 0
        val cursor = readableDatabase.rawQuery("SELECT type, amount FROM $TABLE_BILLS", null)
        cursor.use {
            while (it.moveToNext()) {
                count++
                val type = it.getString(0)
                val amount = it.getDouble(1)
                if (type == "支出") totalExpense += amount else if (type == "收入") totalIncome += amount
            }
        }
        return BillStatistics(totalExpense, totalIncome, count)
    }

    fun clearAllBills(): Int = writableDatabase.delete(TABLE_BILLS, null, null)

    // ================= 日程与闹钟操作 =================
    fun insertSchedule(item: ScheduleItem): Long {
        val values = ContentValues().apply {
            put("type", item.type)
            put("title", item.title)
            put("time_str", item.timeStr)
            put("timestamp", item.timestamp)
            put("location", item.location)
            put("note", item.note)
            put("created_at", item.createdAt)
        }
        return writableDatabase.insert(TABLE_SCHEDULES, null, values)
    }

    fun getAllSchedules(): List<ScheduleItem> {
        val list = mutableListOf<ScheduleItem>()
        val cursor = readableDatabase.query(TABLE_SCHEDULES, null, null, null, null, null, "created_at DESC")
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    ScheduleItem(
                        id = it.getLong(it.getColumnIndexOrThrow("_id")),
                        type = it.getString(it.getColumnIndexOrThrow("type")),
                        title = it.getString(it.getColumnIndexOrThrow("title")),
                        timeStr = it.getString(it.getColumnIndexOrThrow("time_str")),
                        timestamp = it.getLong(it.getColumnIndexOrThrow("timestamp")),
                        location = it.getString(it.getColumnIndexOrThrow("location")) ?: "",
                        note = it.getString(it.getColumnIndexOrThrow("note")) ?: "",
                        createdAt = it.getLong(it.getColumnIndexOrThrow("created_at"))
                    )
                )
            }
        }
        return list
    }

    fun clearAllSchedules(): Int = writableDatabase.delete(TABLE_SCHEDULES, null, null)

    // ================= 私密日记操作 =================
    fun insertDiary(diary: DiaryItem): Long {
        val values = ContentValues().apply {
            put("title", diary.title)
            put("content", diary.content)
            put("polished_content", diary.polishedContent)
            put("corrections", diary.corrections)
            put("mood", diary.mood)
            put("created_at", diary.createdAt)
        }
        return writableDatabase.insert(TABLE_DIARIES, null, values)
    }

    fun getAllDiaries(): List<DiaryItem> {
        val list = mutableListOf<DiaryItem>()
        val cursor = readableDatabase.query(TABLE_DIARIES, null, null, null, null, null, "created_at DESC")
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    DiaryItem(
                        id = it.getLong(it.getColumnIndexOrThrow("_id")),
                        title = it.getString(it.getColumnIndexOrThrow("title")),
                        content = it.getString(it.getColumnIndexOrThrow("content")),
                        polishedContent = it.getString(it.getColumnIndexOrThrow("polished_content")) ?: "",
                        corrections = it.getString(it.getColumnIndexOrThrow("corrections")) ?: "",
                        mood = it.getString(it.getColumnIndexOrThrow("mood")) ?: "平静",
                        createdAt = it.getLong(it.getColumnIndexOrThrow("created_at"))
                    )
                )
            }
        }
        return list
    }

    fun clearAllDiaries(): Int = writableDatabase.delete(TABLE_DIARIES, null, null)

    // ================= 会议纪要操作 =================
    fun insertMeeting(item: MeetingSummaryItem): Long {
        val values = ContentValues().apply {
            put("title", item.title)
            put("raw_content", item.rawContent)
            put("consensus", item.consensus)
            put("action_items", item.actionItems)
            put("risks", item.risks)
            put("created_at", item.createdAt)
        }
        return writableDatabase.insert(TABLE_MEETINGS, null, values)
    }

    fun getAllMeetings(): List<MeetingSummaryItem> {
        val list = mutableListOf<MeetingSummaryItem>()
        val cursor = readableDatabase.query(TABLE_MEETINGS, null, null, null, null, null, "created_at DESC")
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    MeetingSummaryItem(
                        id = it.getLong(it.getColumnIndexOrThrow("_id")),
                        title = it.getString(it.getColumnIndexOrThrow("title")),
                        rawContent = it.getString(it.getColumnIndexOrThrow("raw_content")),
                        consensus = it.getString(it.getColumnIndexOrThrow("consensus")) ?: "",
                        actionItems = it.getString(it.getColumnIndexOrThrow("action_items")) ?: "",
                        risks = it.getString(it.getColumnIndexOrThrow("risks")) ?: "",
                        createdAt = it.getLong(it.getColumnIndexOrThrow("created_at"))
                    )
                )
            }
        }
        return list
    }

    fun clearAllMeetings(): Int = writableDatabase.delete(TABLE_MEETINGS, null, null)

    // ================= 短信反诈记录操作 =================
    fun insertSmsRecord(item: SmsRecordItem): Long {
        val values = ContentValues().apply {
            put("sender", item.sender)
            put("content", item.content)
            put("category", item.category)
            put("risk_level", item.riskLevel)
            put("analysis", item.analysis)
            put("advice", item.advice)
            put("created_at", item.createdAt)
        }
        return writableDatabase.insert(TABLE_SMS, null, values)
    }

    fun getAllSmsRecords(): List<SmsRecordItem> {
        val list = mutableListOf<SmsRecordItem>()
        val cursor = readableDatabase.query(TABLE_SMS, null, null, null, null, null, "created_at DESC")
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    SmsRecordItem(
                        id = it.getLong(it.getColumnIndexOrThrow("_id")),
                        sender = it.getString(it.getColumnIndexOrThrow("sender")) ?: "",
                        content = it.getString(it.getColumnIndexOrThrow("content")),
                        category = it.getString(it.getColumnIndexOrThrow("category")),
                        riskLevel = it.getString(it.getColumnIndexOrThrow("risk_level")),
                        analysis = it.getString(it.getColumnIndexOrThrow("analysis")) ?: "",
                        advice = it.getString(it.getColumnIndexOrThrow("advice")) ?: "",
                        createdAt = it.getLong(it.getColumnIndexOrThrow("created_at"))
                    )
                )
            }
        }
        return list
    }

    fun clearAllSmsRecords(): Int = writableDatabase.delete(TABLE_SMS, null, null)
}
