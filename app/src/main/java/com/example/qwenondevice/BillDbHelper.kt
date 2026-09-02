package com.example.qwenondevice

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class BillStatistics(
    val totalExpense: Double,
    val totalIncome: Double,
    val count: Int
) {
    val balance: Double
        get() = totalIncome - totalExpense
}

class BillDbHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "qwen_bookkeeping.db"
        const val DATABASE_VERSION = 1

        const val TABLE_BILLS = "bills"
        const val COLUMN_ID = "_id"
        const val COLUMN_TYPE = "type"
        const val COLUMN_CATEGORY = "category"
        const val COLUMN_AMOUNT = "amount"
        const val COLUMN_PAY_METHOD = "pay_method"
        const val COLUMN_NOTE = "note"
        const val COLUMN_RAW_TEXT = "raw_text"
        const val COLUMN_CREATED_AT = "created_at"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTableQuery = """
            CREATE TABLE $TABLE_BILLS (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_TYPE TEXT NOT NULL,
                $COLUMN_CATEGORY TEXT NOT NULL,
                $COLUMN_AMOUNT REAL NOT NULL,
                $COLUMN_PAY_METHOD TEXT,
                $COLUMN_NOTE TEXT,
                $COLUMN_RAW_TEXT TEXT,
                $COLUMN_CREATED_AT INTEGER NOT NULL
            )
        """.trimIndent()
        db.execSQL(createTableQuery)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_BILLS")
        onCreate(db)
    }

    fun insertBill(bill: Bill): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_TYPE, bill.type)
            put(COLUMN_CATEGORY, bill.category)
            put(COLUMN_AMOUNT, bill.amount)
            put(COLUMN_PAY_METHOD, bill.payMethod)
            put(COLUMN_NOTE, bill.note)
            put(COLUMN_RAW_TEXT, bill.rawText)
            put(COLUMN_CREATED_AT, bill.createdAt)
        }
        return db.insert(TABLE_BILLS, null, values)
    }

    fun getAllBills(): List<Bill> {
        val list = mutableListOf<Bill>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_BILLS,
            null,
            null,
            null,
            null,
            null,
            "$COLUMN_CREATED_AT DESC"
        )
        cursor.use {
            val idIndex = it.getColumnIndexOrThrow(COLUMN_ID)
            val typeIndex = it.getColumnIndexOrThrow(COLUMN_TYPE)
            val catIndex = it.getColumnIndexOrThrow(COLUMN_CATEGORY)
            val amountIndex = it.getColumnIndexOrThrow(COLUMN_AMOUNT)
            val payIndex = it.getColumnIndexOrThrow(COLUMN_PAY_METHOD)
            val noteIndex = it.getColumnIndexOrThrow(COLUMN_NOTE)
            val rawIndex = it.getColumnIndexOrThrow(COLUMN_RAW_TEXT)
            val createdIndex = it.getColumnIndexOrThrow(COLUMN_CREATED_AT)

            while (it.moveToNext()) {
                list.add(
                    Bill(
                        id = it.getLong(idIndex),
                        type = it.getString(typeIndex),
                        category = it.getString(catIndex),
                        amount = it.getDouble(amountIndex),
                        payMethod = it.getString(payIndex) ?: "",
                        note = it.getString(noteIndex) ?: "",
                        rawText = it.getString(rawIndex) ?: "",
                        createdAt = it.getLong(createdIndex)
                    )
                )
            }
        }
        return list
    }

    fun getStatistics(): BillStatistics {
        var totalExpense = 0.0
        var totalIncome = 0.0
        var count = 0

        val db = readableDatabase
        val cursor = db.rawQuery("SELECT $COLUMN_TYPE, $COLUMN_AMOUNT FROM $TABLE_BILLS", null)
        cursor.use {
            val typeIndex = it.getColumnIndexOrThrow(COLUMN_TYPE)
            val amountIndex = it.getColumnIndexOrThrow(COLUMN_AMOUNT)

            while (it.moveToNext()) {
                val type = it.getString(typeIndex)
                val amount = it.getDouble(amountIndex)
                count++
                if (type == "支出") {
                    totalExpense += amount
                } else if (type == "收入") {
                    totalIncome += amount
                }
            }
        }
        return BillStatistics(totalExpense, totalIncome, count)
    }

    fun clearAllBills(): Int {
        val db = writableDatabase
        return db.delete(TABLE_BILLS, null, null)
    }
}
