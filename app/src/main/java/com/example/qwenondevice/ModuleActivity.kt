package com.example.qwenondevice

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.MoreExecutors
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

enum class ModuleType { BOOKKEEPING, DIARY, SCHEDULE, MEETING, CONTRACT, ANTI_FRAUD }

private data class ModuleSpec(
    val title: String,
    val subtitle: String,
    val icon: Int,
    val hint: String,
    val chips: List<String>
)

class ModuleActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_MODULE = "extra_module"
        private const val MODEL_NAME = "qwen.task"
        private const val MODEL_BYTES = 546_660_344L
        private const val REQUEST_MIC = 2101
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var module: ModuleType
    private lateinit var spec: ModuleSpec
    private lateinit var db: MainDatabaseHelper
    private lateinit var input: EditText
    private lateinit var send: ImageButton
    private lateinit var status: TextView
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var chips: LinearLayout
    private var llm: LlmInference? = null
    private var ready = false
    private var generating = false
    private var selectedContract = 0
    private var voiceManager: RealtimeVoiceManager? = null
    private var voiceDialog: AlertDialog? = null
    private var voiceStatus: TextView? = null
    private var voiceText: TextView? = null
    private var voiceStart: Button? = null
    private val prefs by lazy { getSharedPreferences("qwen_app", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        module = runCatching {
            ModuleType.valueOf(intent.getStringExtra(EXTRA_MODULE).orEmpty())
        }.getOrDefault(ModuleType.BOOKKEEPING)
        spec = specFor(module)
        setContentView(R.layout.activity_module)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        db = MainDatabaseHelper(this)

        input = findViewById(R.id.inputText)
        send = findViewById(R.id.btnSend)
        status = findViewById(R.id.modelStatus)
        content = findViewById(R.id.contentContainer)
        scroll = findViewById(R.id.contentScroll)
        chips = findViewById(R.id.chipContainer)

        findViewById<TextView>(R.id.moduleTitle).text = spec.title
        findViewById<TextView>(R.id.moduleSubtitle).text = spec.subtitle
        findViewById<ImageView>(R.id.moduleIcon).setImageResource(spec.icon)
        findViewById<TextView>(R.id.btnBackHome).setOnClickListener { finish() }
        val clear = findViewById<TextView>(R.id.btnPageAction)
        if (module == ModuleType.CONTRACT) {
            clear.text = "示例合同"
            clear.setOnClickListener { selectContract((selectedContract + 1) % 3) }
        } else {
            clear.setOnClickListener { confirmClear() }
        }

        input.hint = spec.hint
        send.setOnClickListener { submit(input.text.toString()) }
        findViewById<ImageButton>(R.id.btnVoice).setOnClickListener { startVoiceDialog() }
        input.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                submit(input.text.toString())
                true
            } else false
        }

        buildChips()
        renderHistory()
        val draft = prefs.getString("draft_${module.name}", "").orEmpty()
        input.setText(draft)
        input.setSelection(draft.length)
        prepareModel()
    }

    override fun onPause() {
        prefs.edit().putString("draft_${module.name}", input.text.toString()).apply()
        super.onPause()
    }

    override fun onDestroy() {
        stopVoice()
        runCatching { llm?.close() }
        llm = null
        ready = false
        super.onDestroy()
    }

    private fun buildChips() {
        chips.removeAllViews()
        spec.chips.forEachIndexed { index, label ->
            val pill = TextView(this).apply {
                text = label
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@ModuleActivity, R.color.text_primary))
                background = ContextCompat.getDrawable(this@ModuleActivity, R.drawable.bg_chip_pill)
                gravity = Gravity.CENTER
                minHeight = dp(40)
                setPadding(dp(14), 0, dp(14), 0)
                setOnClickListener {
                    if (module == ModuleType.CONTRACT) selectContract(index.coerceAtMost(2))
                    input.setText(label)
                    input.setSelection(label.length)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { if (index > 0) marginStart = dp(8) }
            }
            chips.addView(pill)
        }
    }

    private fun selectContract(index: Int) {
        selectedContract = index
        val names = listOf("租房合同", "开发合同", "保密协议")
        Toast.makeText(this, "已选择${names[index]}", Toast.LENGTH_SHORT).show()
        input.hint = "询问${names[index]}的风险或要求摘要…"
    }

    private fun renderHistory() {
        content.removeAllViews()
        content.addView(infoCard("所有分析均由 Qwen 0.5B 在设备上完成；记录只保存在本机 SQLite。"))
        val rows = db.history(module)
        if (rows.isEmpty()) {
            content.addView(infoCard(if (module == ModuleType.CONTRACT) "选择上方合同示例，或直接提出审阅问题。" else "还没有记录。试试上方快捷示例，开始第一条。"))
        } else {
            content.addView(sectionTitle("最近记录 · ${rows.size}"))
            rows.forEach { content.addView(resultCard(it, false)) }
        }
    }

    private fun submit(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) {
            input.error = "请先输入内容"
            return
        }
        if (!ready || llm == null) {
            Toast.makeText(this, "本地模型仍在加载，请稍后再试", Toast.LENGTH_SHORT).show()
            return
        }
        if (generating) return
        generating = true
        setEnabled(false)
        hideKeyboard()
        prefs.edit().putString("draft_${module.name}", "").apply()
        input.setText("")
        content.addView(resultCard("你\n$text", false))
        val pending = resultCard("Qwen 正在端侧分析…", true)
        content.addView(pending)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

        val future = llm!!.generateResponseAsync(buildPrompt(text))
        Futures.addCallback(future, object : FutureCallback<String> {
            override fun onSuccess(result: String?) = finishResult(text, result.orEmpty(), pending)
            override fun onFailure(t: Throwable) = finishResult(text, "分析失败：${t.message}", pending)
        }, MoreExecutors.directExecutor())
    }

    private fun finishResult(raw: String, output: String, pending: View) {
        ui.post {
            if (isFinishing || isDestroyed) return@post
            content.removeView(pending)
            val result = output.trim().ifEmpty { "模型没有返回内容，请重试。" }
            val saved = persistResult(raw, result)
            content.addView(resultCard("${spec.title}结果\n$result${if (saved) "\n\n✓ 已保存到本机" else ""}", false))
            generating = false
            setEnabled(true)
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun persistResult(raw: String, result: String): Boolean = try {
        when (module) {
            ModuleType.BOOKKEEPING -> {
                jsonFrom(result)?.let { json ->
                    db.insertBill(
                        json.optString("type", "支出"), json.optString("category", "其他"),
                        json.optDouble("amount", 0.0), json.optString("pay_method", "其他"),
                        json.optString("note", raw), raw
                    ) >= 0
                } ?: false
            }
            ModuleType.SCHEDULE -> {
                jsonFrom(result)?.let { json ->
                    val type = json.optString("type", "日历")
                    val title = json.optString("title", raw)
                    val hour = json.optInt("hour", 8)
                    val minute = json.optInt("minute", 0)
                    val time = json.optString("time_str", String.format(Locale.getDefault(), "%02d:%02d", hour, minute))
                    val saved = db.insertSchedule(type, title, time, json.optString("location", ""), raw) >= 0
                    if (saved) openSystemSchedule(type, title, hour, minute, json.optString("location", ""))
                    saved
                } ?: false
            }
            ModuleType.MEETING -> db.insertMeeting(raw, result) >= 0
            ModuleType.DIARY -> db.insertDiary(raw, result) >= 0
            ModuleType.ANTI_FRAUD -> db.insertSms(raw, result) >= 0
            ModuleType.CONTRACT -> false
        }
    } catch (_: Exception) { false }

    private fun openSystemSchedule(type: String, title: String, hour: Int, minute: Int, location: String) {
        runCatching {
            val intent = if (type == "闹钟") {
                Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, minute)
                    putExtra(AlarmClock.EXTRA_MESSAGE, title)
                }
            } else {
                Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI).apply {
                    val start = Calendar.getInstance().apply {
                        add(Calendar.DAY_OF_MONTH, 1)
                        set(Calendar.HOUR_OF_DAY, hour)
                        set(Calendar.MINUTE, minute)
                    }.timeInMillis
                    putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
                    putExtra(CalendarContract.Events.TITLE, title)
                    putExtra(CalendarContract.Events.EVENT_LOCATION, location)
                }
            }
            startActivity(intent)
        }
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("清空${spec.title}记录")
            .setMessage("此操作只删除本模块的本地记录，且无法撤销。")
            .setPositiveButton("清空") { _, _ -> db.clear(module); renderHistory() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun prepareModel() {
        status.text = "正在准备 Qwen 0.5B 本地模型…"
        val target = File(filesDir, MODEL_NAME)
        Thread {
            try {
                if (!target.exists() || target.length() != MODEL_BYTES) {
                    val temp = File(filesDir, "$MODEL_NAME.tmp")
                    assets.open(MODEL_NAME).use { source ->
                        FileOutputStream(temp).use { sink -> source.copyTo(sink, 128 * 1024) }
                    }
                    if (temp.length() != MODEL_BYTES) error("模型文件校验失败")
                    if (target.exists()) target.delete()
                    if (!temp.renameTo(target)) error("模型文件部署失败")
                }
                val options = LlmInferenceOptions.builder()
                    .setModelPath(target.absolutePath)
                    .setMaxTokens(2048)
                    .build()
                val engine = LlmInference.createFromOptions(this, options)
                if (isFinishing || isDestroyed) {
                    engine.close()
                    return@Thread
                }
                llm = engine
                ready = true
                ui.post {
                    if (!isFinishing && !isDestroyed) {
                        status.text = "● 端侧 AI 就绪 · 全程离线推理"
                        status.setTextColor(Color.parseColor("#34D399"))
                        setEnabled(true)
                    }
                }
            } catch (e: Exception) {
                ui.post {
                    if (!isFinishing && !isDestroyed) {
                        status.text = "模型加载失败：${e.message}"
                        status.setTextColor(Color.parseColor("#FB7185"))
                        setEnabled(false)
                    }
                }
            }
        }.start()
        setEnabled(false)
    }

    private fun setEnabled(enabled: Boolean) {
        input.isEnabled = enabled
        send.isEnabled = enabled
        send.alpha = if (enabled) 1f else 0.55f
    }

    private fun buildPrompt(user: String): String {
        val system = when (module) {
            ModuleType.BOOKKEEPING -> """你是记账助手。从用户一句话提取信息，只输出 JSON：{"type":"支出或收入","category":"餐饮/交通/购物/娱乐/工资/居住/医疗/其他","amount":数字,"pay_method":"微信/支付宝/现金/银行卡/其他","note":"备注"}。"""
            ModuleType.SCHEDULE -> """你是智能日程管家。当前时间 ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())}。只输出 JSON：{"type":"闹钟或日历","title":"标题","hour":小时,"minute":分钟,"date_str":"日期","time_str":"HH:mm","location":"地点","note":"备注"}。"""
            ModuleType.MEETING -> """你是会议纪要秘书。输出：🎯【核心议题与共识】、✅【Action Items 待办清单】（含责任人和截止时间）、💡【风险与后续关注】。内容要准确、可执行。${if (user.contains("示例")) "会议内容：张总要求下周上线端侧 AI 功能；李工周五前提供测试包；王经理明日上午提供 20 条测试样本并在周四前确认 UI。" else ""}"""
            ModuleType.DIARY -> """你是私密日记和文字润色助手。输出：✏️【错别字与语病纠正】、✨【文采润色优美版】、🌈【今日情绪诊断】。保持原意，语气温暖克制。"""
            ModuleType.ANTI_FRAUD -> """你是反诈研判专家。输出：🚨【风险评级】、📦【短信分类】、🔍【套路拆解】、🛡️【防骗处置建议】。不要诱导用户回拨短信中的号码或点击链接。"""
            ModuleType.CONTRACT -> """你是合同审阅助手。基于提供的示例合同，指出关键条款、风险、缺失项和修改建议；明确这不是正式法律意见。\n${contractText(selectedContract)}"""
        }
        return "<|im_start|>system\n$system\n<|im_end|>\n<|im_start|>user\n$user\n<|im_end|>\n<|im_start|>assistant\n"
    }

    private fun contractText(index: Int) = when (index) {
        1 -> "【开发合同】总价8万元；预付50%；30日交付；需求变更未约定计价；验收期3日；源代码和知识产权归甲方。"
        2 -> "【保密协议】保密期永久；违约金100万元；保密信息范围包含口头信息；未约定依法披露和既有信息例外。"
        else -> "【租房合同】月租5000元，押二付三；租期一年；提前退租押金不退；房屋维修责任未区分；转租须房东书面同意。"
    }

    private fun jsonFrom(text: String): JSONObject? {
        val matcher = Pattern.compile("\\{.*?\\}", Pattern.DOTALL).matcher(text)
        return if (matcher.find()) JSONObject(matcher.group()) else null
    }

    private fun infoCard(text: String) = resultCard(text, true)

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(ContextCompat.getColor(this@ModuleActivity, R.color.text_subtle))
        setTypeface(null, Typeface.BOLD)
        setPadding(dp(4), dp(14), dp(4), dp(8))
    }

    private fun resultCard(text: String, muted: Boolean): TextView = TextView(this).apply {
        this.text = text
        textSize = if (muted) 13f else 14f
        setTextColor(ContextCompat.getColor(this@ModuleActivity, if (muted) R.color.text_subtle else R.color.text_primary))
        setLineSpacing(dp(4).toFloat(), 1f)
        background = ContextCompat.getDrawable(this@ModuleActivity, R.drawable.bg_card_white)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) }
    }

    @SuppressLint("SetTextI18n")
    private fun startVoiceDialog() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
            return
        }
        if (voiceDialog?.isShowing == true) return
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(18))
        }
        voiceStatus = TextView(this).apply {
            text = "正在加载离线语音模型…"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@ModuleActivity, R.color.text_subtle))
        }
        voiceText = TextView(this).apply {
            textSize = 17f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@ModuleActivity, R.color.text_primary))
            setPadding(0, dp(14), 0, dp(14))
        }
        voiceStart = Button(this).apply {
            text = "开始说话"
            isEnabled = false
            setTextColor(Color.WHITE)
            background = ContextCompat.getDrawable(this@ModuleActivity, R.drawable.bg_btn_primary)
            setOnClickListener { beginVoice() }
        }
        box.addView(voiceStatus)
        box.addView(voiceText)
        box.addView(voiceStart)
        voiceDialog = AlertDialog.Builder(this).setTitle("离线语音输入").setView(box).create().also { dialog ->
            dialog.setOnDismissListener { stopVoice() }
            dialog.show()
        }
        VoiceModelManager.prepareModel(this, object : VoiceModelManager.ModelLoadCallback {
            override fun onProgress(percentage: Int, message: String) { voiceStatus?.text = message }
            override fun onSuccess(modelDir: File) {
                val manager = RealtimeVoiceManager(this@ModuleActivity)
                manager.initRecognizerAsync(modelDir) { ok ->
                    voiceManager = if (ok) manager else null
                    voiceStatus?.text = if (ok) "就绪，点击开始后自然说话" else "语音引擎初始化失败"
                    voiceStart?.isEnabled = ok
                }
            }
            override fun onError(errorMsg: String) { voiceStatus?.text = "语音模型加载失败：$errorMsg" }
        })
    }

    private fun beginVoice() {
        val manager = voiceManager ?: return
        voiceStart?.isEnabled = false
        voiceStatus?.text = "正在录音，停顿后自动完成…"
        manager.startListening(object : RealtimeVoiceManager.VoiceCallback {
            override fun onReady() = Unit
            override fun onPartialResult(partialText: String) { voiceText?.text = partialText }
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onFinished(finalText: String) {
                val recognized = finalText.trim()
                voiceDialog?.dismiss()
                if (recognized.isNotEmpty()) {
                    input.setText(recognized)
                    input.setSelection(recognized.length)
                }
            }
            override fun onError(errorMsg: String) {
                voiceStatus?.text = errorMsg
                voiceStart?.isEnabled = true
            }
        })
    }

    private fun stopVoice() {
        val manager = voiceManager
        voiceManager = null
        runCatching { manager?.cancel(); manager?.release() }
        voiceDialog = null
    }

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun specFor(type: ModuleType) = when (type) {
        ModuleType.BOOKKEEPING -> ModuleSpec("记账管家", "一句话记账，自动分类并生成本地收支记录", R.drawable.ic_payments, "例如：午饭 38 元，微信支付", listOf("午饭 38 元微信支付", "地铁 6 元", "工资到账 12000 元"))
        ModuleType.DIARY -> ModuleSpec("秘密日记", "纠错、润色与情绪梳理，内容只留在设备中", R.drawable.ic_book_purple, "写下今天发生的事…", listOf("今天很充实", "帮我润色这段日记", "整理一下今天的心情"))
        ModuleType.SCHEDULE -> ModuleSpec("智能日程", "自然语言创建闹钟与日历事件", R.drawable.ic_calendar, "例如：明早 7:30 叫我起床", listOf("明早 7:30 叫我起床", "明天下午 3 点开会", "周五提醒我交周报"))
        ModuleType.MEETING -> ModuleSpec("会议纪要", "从发言中提炼共识、待办、责任人与风险", R.drawable.ic_mic, "粘贴或说出会议内容…", listOf("生成示例会议纪要", "提取待办和责任人", "总结风险与结论"))
        ModuleType.CONTRACT -> ModuleSpec("合同审阅", "快速检查关键条款、风险和缺失项", R.drawable.ic_doc, "询问当前合同的风险…", listOf("审阅租房合同", "审阅开发合同", "审阅保密协议"))
        ModuleType.ANTI_FRAUD -> ModuleSpec("反诈拦截", "识别危险链接、冒充身份与转账话术", R.drawable.ic_shield_rose, "粘贴收到的可疑短信…", listOf("您的快递异常，请点击链接", "客服称退款需提供验证码", "分析这条中奖短信"))
    }
}
