package com.example.qwenondevice

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Home dashboard. Model work is deferred to each feature page to keep launch instant. */
class MainActivity : AppCompatActivity() {
    private lateinit var db: MainDatabaseHelper
    private lateinit var englishDb: AppDatabaseHelper
    private lateinit var input: EditText
    private lateinit var speedButton: TextView
    private val prefs by lazy { getSharedPreferences("english_app", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)

        db = MainDatabaseHelper(this)
        englishDb = AppDatabaseHelper(this)
        input = findViewById(R.id.globalInput)
        speedButton = findViewById(R.id.btnTtsSpeed)

        val routes = mapOf(
            R.id.cardBookkeeping to ModuleType.BOOKKEEPING,
            R.id.cardDiary to ModuleType.DIARY,
            R.id.cardSchedule to ModuleType.SCHEDULE,
            R.id.cardMeeting to ModuleType.MEETING,
            R.id.cardContract to ModuleType.CONTRACT,
            R.id.cardFraud to ModuleType.ANTI_FRAUD
        )
        routes.forEach { (viewId, module) ->
            findViewById<android.view.View>(viewId).setOnClickListener { openModule(module) }
        }
        findViewById<android.view.View>(R.id.cardEnglish).setOnClickListener { openEnglish() }

        findViewById<ImageButton>(R.id.btnGlobalSend).setOnClickListener { sendQuickAsk() }
        findViewById<ImageButton>(R.id.btnGlobalVoice).setOnClickListener {
            openEnglish(voice = true)
        }
        input.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                sendQuickAsk()
                true
            } else false
        }
        speedButton.setOnClickListener { showSpeedDialog() }
        refreshSpeed()

        val modelStatus = findViewById<TextView>(R.id.tvModelStatus)
        modelStatus.text = if (assets.list("")?.contains("qwen.task") == true) {
            "● 模型内置 · 语音/TTS 离线"
        } else {
            "● 模型将在功能页准备"
        }
    }

    override fun onResume() {
        super.onResume()
        refreshSummaries()
        refreshSpeed()
        input.setText(prefs.getString("dashboard_draft", "").orEmpty())
        input.setSelection(input.text.length)
    }

    override fun onPause() {
        prefs.edit().putString("dashboard_draft", input.text.toString()).apply()
        super.onPause()
    }

    private fun openModule(module: ModuleType) {
        hideKeyboard()
        startActivity(Intent(this, ModuleActivity::class.java).putExtra(ModuleActivity.EXTRA_MODULE, module.name))
    }

    private fun openEnglish(query: String = "", voice: Boolean = false) {
        hideKeyboard()
        startActivity(Intent(this, EnglishActivity::class.java).apply {
            putExtra(EnglishActivity.EXTRA_OPEN_CHAT, query.isNotEmpty() || voice)
            putExtra(EnglishActivity.EXTRA_QUERY, query)
            putExtra(EnglishActivity.EXTRA_OPEN_VOICE, voice)
        })
    }

    private fun sendQuickAsk() {
        val query = input.text.toString().trim()
        if (query.isEmpty()) {
            input.error = "先输入问题"
            return
        }
        prefs.edit().putString("dashboard_draft", "").apply()
        input.setText("")
        openEnglish(query)
    }

    private fun refreshSummaries() {
        val s = db.dashboardSummary()
        findViewById<TextView>(R.id.summaryBookkeeping).text = if (s.billCount == 0) {
            "本月暂无账单"
        } else "${s.billCount} 笔 · 收 ¥${money(s.income)} / 支 ¥${money(s.expense)}"
        findViewById<TextView>(R.id.summaryDiary).text = if (s.diaryCount == 0) "还没有写下今天" else "已私密保存 ${s.diaryCount} 篇"
        findViewById<TextView>(R.id.summarySchedule).text = if (s.scheduleCount == 0) "近期没有待办" else "已有 ${s.scheduleCount} 条日程"
        findViewById<TextView>(R.id.summaryMeeting).text = if (s.meetingCount == 0) "暂无归档纪要" else "已归档 ${s.meetingCount} 场会议"
        findViewById<TextView>(R.id.summaryFraud).text = if (s.riskCount == 0) "暂无风险记录" else "发现 ${s.riskCount} 条风险记录"
        val words = englishDb.countWords()
        val due = englishDb.countDue()
        findViewById<TextView>(R.id.summaryEnglish).text = if (words == 0) {
            "词典 · 口语 · 纠错 · 单词本 · 跟读"
        } else "单词本 $words 词 · 今日待复习 $due"
    }

    private fun showSpeedDialog() {
        val labels = arrayOf("0.3× 极慢", "0.5× 较慢", "0.7× 慢速", "0.9×", "1.0× 标准", "1.1×", "1.3× 快速")
        val values = floatArrayOf(0.3f, 0.5f, 0.7f, 0.9f, 1.0f, 1.1f, 1.3f)
        val speed = prefs.getFloat("tts_speed", 1.0f)
        val selected = values.indexOfFirst { kotlin.math.abs(it - speed) < 0.05f }.let { if (it < 0) 4 else it }
        AlertDialog.Builder(this)
            .setTitle("全局英文发音语速")
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                prefs.edit().putFloat("tts_speed", values[which]).apply()
                refreshSpeed()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun refreshSpeed() {
        speedButton.text = "发音 ${prefs.getFloat("tts_speed", 1.0f)}×"
    }

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
    }

    private fun money(value: Double) = String.format(java.util.Locale.getDefault(), "%.2f", value)
}
