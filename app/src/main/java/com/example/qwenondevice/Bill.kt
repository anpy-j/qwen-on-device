package com.example.qwenondevice

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 账单实体类
 */
data class Bill(
    val id: Long = 0,
    val type: String,        // "支出" 或 "收入"
    val category: String,    // "餐饮" / "交通" / "购物" / "娱乐" / "工资" / "居住" / "医疗" / "其他"
    val amount: Double,      // 金额
    val payMethod: String,   // "微信" / "支付宝" / "现金" / "银行卡" / "其他"
    val note: String,        // 备注信息
    val rawText: String,     // 用户的原始输入文本
    val createdAt: Long = System.currentTimeMillis() // 记录时间戳
) {
    val formattedTime: String
        get() {
            val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            return sdf.format(Date(createdAt))
        }

    val displayAmount: String
        get() {
            val sign = if (type == "支出") "-" else "+"
            return String.format(Locale.getDefault(), "%s¥%.2f", sign, amount)
        }

    val categoryIcon: String
        get() = when (category) {
            "餐饮" -> "🍱"
            "交通" -> "🚗"
            "购物" -> "🛍️"
            "娱乐" -> "🎮"
            "工资" -> "💰"
            "居住", "住房" -> "🏠"
            "医疗" -> "💊"
            "数码", "办公" -> "💻"
            else -> if (type == "收入") "💵" else "📝"
        }
}
