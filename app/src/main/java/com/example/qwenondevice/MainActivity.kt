package com.example.qwenondevice

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.MoreExecutors
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

enum class NavTab {
    BOOKKEEPING, SCHEDULE, DOCUMENT, MEETING, DIARY, ANTI_FRAUD
}

class MainActivity : Activity() {

    companion object {
        private const val REQUEST_CODE_PERMISSION_ALL = 1001
    }

    private val modelUrl = "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"
    private val modelFile = "qwen.task"

    // 导航与页面状态
    private var currentTab = NavTab.BOOKKEEPING
    private lateinit var tabButtons: Map<NavTab, Button>
    private lateinit var pageContainers: Map<NavTab, View>

    // 顶部与公共控件
    private lateinit var tvModelStatus: TextView
    private lateinit var btnClearCurrent: Button
    private lateinit var dynamicChipsContainer: LinearLayout
    private lateinit var input: EditText
    private lateinit var sendBtn: Button
    private lateinit var btnVoice: Button

    // 页面 1：记账控件
    private lateinit var tvExpense: TextView
    private lateinit var tvIncome: TextView
    private lateinit var tvBalance: TextView
    private lateinit var logBookkeeping: TextView
    private lateinit var scrollBookkeeping: ScrollView

    // 页面 2：日程闹钟控件
    private lateinit var logSchedule: TextView
    private lateinit var scrollSchedule: ScrollView

    // 页面 3：文档阅读控件
    private lateinit var logDocument: TextView
    private lateinit var scrollDocument: ScrollView
    private lateinit var btnDocRent: Button
    private lateinit var btnDocDev: Button
    private lateinit var btnDocNda: Button
    private var currentSelectedDoc = 0 // 0: 租房, 1: 开发, 2: 保密

    // 页面 4：会议纪要控件
    private lateinit var logMeeting: TextView
    private lateinit var scrollMeeting: ScrollView

    // 页面 5：秘密日记控件
    private lateinit var logDiary: TextView
    private lateinit var scrollDiary: ScrollView
    private lateinit var tvCurrentMood: TextView
    private var currentMood = "🌟 积极充实"

    // 页面 6：反诈拦截控件
    private lateinit var logAntiFraud: TextView
    private lateinit var scrollAntiFraud: ScrollView

    // 数据库
    private lateinit var dbHelper: AppDatabaseHelper

    // AI 推理与语音
    private val ui = Handler(Looper.getMainLooper())
    private var llm: LlmInference? = null
    private var generating = false
    private val currentResponse = StringBuilder()
    private var replyFinished = false

    private lateinit var voiceManager: RealtimeVoiceManager
    private var voiceDialog: AlertDialog? = null
    private var tvVoiceStatus: TextView? = null
    private var tvLivePartialText: TextView? = null
    private var vSoundWaveIndicator: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        dbHelper = AppDatabaseHelper(this)
        voiceManager = RealtimeVoiceManager(this)

        initViews()
        initTabNavigation()
        initDocSampleButtons()
        initInitialLogs()
        switchTab(NavTab.BOOKKEEPING)

        checkAndLoadModel()
        initVoiceModelAsync()
    }

    private fun initVoiceModelAsync() {
        VoiceModelManager.prepareModel(this, object : VoiceModelManager.ModelLoadCallback {
            override fun onProgress(percentage: Int, message: String) {}
            override fun onSuccess(modelDir: File) {
                // 回调在主线程，但加载模型必须放后台线程，否则会 ANR
                voiceManager.initRecognizerAsync(modelDir) {}
            }
            override fun onError(errorMsg: String) {}
        })
    }

    private fun checkAndLoadModel() {
        val model = File(filesDir, modelFile)
        if (model.exists()) {
            loadModel(model.absolutePath)
        } else if (hasAssetModel()) {
            tvModelStatus.text = "📦 检测到 APK 内置离线大模型，正在解压部署(约1~2秒)..."
            setInputsEnabled(false)
            Thread { extractAssetModel(model) }.start()
        } else {
            tvModelStatus.text = "⚠️ 首次使用需下载 521MB 离线模型(一次性)..."
            setInputsEnabled(false)
            Thread { download(model) }.start()
        }
    }

    private fun hasAssetModel(): Boolean {
        return try {
            assets.open(modelFile).close()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun extractAssetModel(targetModel: File) {
        try {
            assets.open(modelFile).use { input ->
                FileOutputStream(targetModel).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                    }
                }
            }
            ui.post {
                tvModelStatus.text = "🟢 模型释放完成，正在启动离线推理引擎..."
                loadModel(targetModel.absolutePath)
            }
        } catch (e: Exception) {
            ui.post {
                tvModelStatus.text = "❌ 释放失败，尝试联网下载..."
                download(targetModel)
            }
        }
    }

    private fun loadModel(path: String) {
        setInputsEnabled(false)
        tvModelStatus.text = "⚙️ 正在加载 Qwen 离线模型到内存(约10秒)..."
        Thread {
            try {
                val options = LlmInferenceOptions.builder().setModelPath(path).build()
                llm = LlmInference.createFromOptions(this, options)
                ui.post {
                    tvModelStatus.text = "🟢 0.5B 本地离线引擎就绪 · 0流量消耗 · 100% 隐私安全"
                    setInputsEnabled(true)
                }
            } catch (e: Exception) {
                ui.post {
                    tvModelStatus.text = "❌ 模型加载失败: ${e.message}"
                    setInputsEnabled(true)
                }
            }
        }.start()
    }

    private fun download(model: File) {
        try {
            val conn = URL(modelUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 30000
            conn.readTimeout = 60000
            conn.connect()
            val total = conn.contentLengthLong
            conn.inputStream.use { ins ->
                FileOutputStream(model).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var done = 0L
                    var lastPct = -1
                    while (ins.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read)
                        done += read
                        val pct = if (total > 0) ((done * 100) / total).toInt() else -1
                        if (pct != lastPct && (pct == 100 || pct % 10 == 0)) {
                            lastPct = pct
                            ui.post {
                                tvModelStatus.text = "⬇️ 下载模型中: $pct% (${done / 1024 / 1024}MB / ${total / 1024 / 1024}MB)"
                            }
                        }
                    }
                }
            }
            ui.post { loadModel(model.absolutePath) }
        } catch (e: Exception) {
            ui.post {
                tvModelStatus.text = "❌ 下载失败: ${e.message}"
                setInputsEnabled(true)
            }
        }
    }

    private fun initViews() {
        tvModelStatus = findViewById(R.id.tvModelStatus)
        btnClearCurrent = findViewById(R.id.btnClearCurrent)
        dynamicChipsContainer = findViewById(R.id.dynamicChipsContainer)
        input = findViewById(R.id.input)
        sendBtn = findViewById(R.id.send)
        btnVoice = findViewById(R.id.btnVoice)

        // 页面 1
        tvExpense = findViewById(R.id.tvExpense)
        tvIncome = findViewById(R.id.tvIncome)
        tvBalance = findViewById(R.id.tvBalance)
        logBookkeeping = findViewById(R.id.logBookkeeping)
        scrollBookkeeping = findViewById(R.id.scrollBookkeeping)

        // 页面 2
        logSchedule = findViewById(R.id.logSchedule)
        scrollSchedule = findViewById(R.id.scrollSchedule)

        // 页面 3
        logDocument = findViewById(R.id.logDocument)
        scrollDocument = findViewById(R.id.scrollDocument)
        btnDocRent = findViewById(R.id.btnDocRent)
        btnDocDev = findViewById(R.id.btnDocDev)
        btnDocNda = findViewById(R.id.btnDocNda)

        // 页面 4
        logMeeting = findViewById(R.id.logMeeting)
        scrollMeeting = findViewById(R.id.scrollMeeting)

        // 页面 5
        logDiary = findViewById(R.id.logDiary)
        scrollDiary = findViewById(R.id.scrollDiary)
        tvCurrentMood = findViewById(R.id.tvCurrentMood)

        // 页面 6
        logAntiFraud = findViewById(R.id.logAntiFraud)
        scrollAntiFraud = findViewById(R.id.scrollAntiFraud)

        sendBtn.setOnClickListener { handleUserAction() }
        btnVoice.setOnClickListener { startVoiceRecognition() }

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                handleUserAction()
                true
            } else false
        }

        btnClearCurrent.setOnClickListener { showClearCurrentDialog() }
    }

    private fun setInputsEnabled(enabled: Boolean) {
        sendBtn.isEnabled = enabled
        btnVoice.isEnabled = enabled
    }

    private fun initTabNavigation() {
        tabButtons = mapOf(
            NavTab.BOOKKEEPING to findViewById(R.id.tabBookkeeping),
            NavTab.SCHEDULE to findViewById(R.id.tabSchedule),
            NavTab.DOCUMENT to findViewById(R.id.tabDocument),
            NavTab.MEETING to findViewById(R.id.tabMeeting),
            NavTab.DIARY to findViewById(R.id.tabDiary),
            NavTab.ANTI_FRAUD to findViewById(R.id.tabAntiFraud)
        )

        pageContainers = mapOf(
            NavTab.BOOKKEEPING to findViewById(R.id.pageBookkeeping),
            NavTab.SCHEDULE to findViewById(R.id.pageSchedule),
            NavTab.DOCUMENT to findViewById(R.id.pageDocument),
            NavTab.MEETING to findViewById(R.id.pageMeeting),
            NavTab.DIARY to findViewById(R.id.pageDiary),
            NavTab.ANTI_FRAUD to findViewById(R.id.pageAntiFraud)
        )

        tabButtons.forEach { (tab, button) ->
            button.setOnClickListener { switchTab(tab) }
        }
    }

    private fun switchTab(targetTab: NavTab) {
        currentTab = targetTab

        // 1. 更新 Tab 按钮的现代胶囊样式
        val onPrimary = getColor(R.color.on_primary)
        val onSurfaceVariant = getColor(R.color.on_surface_variant)
        tabButtons.forEach { (tab, button) ->
            if (tab == targetTab) {
                button.setBackgroundResource(R.drawable.bg_chip_pill_selected)
                button.setTextColor(onPrimary)
            } else {
                button.setBackgroundResource(R.drawable.bg_chip_pill)
                button.setTextColor(onSurfaceVariant)
            }
        }

        // 2. 更新页面容器可见性
        pageContainers.forEach { (tab, container) ->
            container.visibility = if (tab == targetTab) View.VISIBLE else View.GONE
        }

        // 3. 动态切换输入框提示语与快捷 Chips
        updateInputHintAndChips()
    }

    private fun updateInputHintAndChips() {
        dynamicChipsContainer.removeAllViews()

        val chips = when (currentTab) {
            NavTab.BOOKKEEPING -> {
                input.hint = "说出或输入记账（如：中午吃麦当劳38.5元微信付）..."
                listOf(
                    "中午吃麦当劳38.5元微信付",
                    "刚才打车花了26块",
                    "发工资入账8500元银行卡",
                    "超市买日用品128元支付宝"
                )
            }
            NavTab.SCHEDULE -> {
                input.hint = "说出闹钟或日程（如：明早7点半叫我起床）..."
                listOf(
                    "明天早上7点半叫我起床",
                    "明天下午3点在3号会议室开会",
                    "25分钟后提醒我关火",
                    "下周一上午10点项目终验评审"
                )
            }
            NavTab.DOCUMENT -> {
                input.hint = "针对选定合同提问或点击速读..."
                listOf(
                    "一键生成合同核心条款与风险摘要",
                    "这份合同的租金和押金怎么规定的？",
                    "违约责任和赔偿条款是什么？",
                    "双方的交付周期与验收要求"
                )
            }
            NavTab.MEETING -> {
                input.hint = "输入会议讨论发言或一键导入示例..."
                listOf(
                    "导入产品周会讨论并生成纪要",
                    "导入商务谈判记录并提取待办",
                    "提炼本次沟通的 Action Items"
                )
            }
            NavTab.DIARY -> {
                input.hint = "写下日记随笔，端侧为您改错与文采润色..."
                listOf(
                    "今天工作很累但学到了新技能，明天继续加油",
                    "感觉压力有点大，晚上散步心情好多了",
                    "今天项目顺利上线了，大家一起吃了顿大餐"
                )
            }
            NavTab.ANTI_FRAUD -> {
                input.hint = "输入短信内容，端侧反诈雷达深度研判..."
                listOf(
                    "【社保局】医保卡已停用，请点击链接验证",
                    "【顺丰】包裹已到菜鸟驿站，取件码5-2-301",
                    "【公安局】您涉嫌一起洗钱案，请转入安全账户",
                    "【工商银行】您尾号8888消费支出500元"
                )
            }
        }

        val chipHeight = resources.getDimensionPixelSize(R.dimen.height_chip)
        val chipPaddingH = (resources.getDimensionPixelSize(R.dimen.space_lg) + resources.getDimensionPixelSize(R.dimen.space_sm))
        val chipGap = resources.getDimensionPixelSize(R.dimen.space_sm)
        val chipTextColor = getColor(R.color.on_surface_variant)
        chips.forEach { text ->
            val btn = Button(this).apply {
                this.text = text
                textSize = 12f
                minHeight = 0
                minimumHeight = 0
                height = chipHeight
                setBackgroundResource(R.drawable.bg_chip_pill)
                setTextColor(chipTextColor)
                setPadding(chipPaddingH, 0, chipPaddingH, 0)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = chipGap
                }
                layoutParams = params
                setOnClickListener {
                    input.setText(text)
                    handleUserAction()
                }
            }
            dynamicChipsContainer.addView(btn)
        }
    }

    private fun initDocSampleButtons() {
        val docButtons = listOf(btnDocRent, btnDocDev, btnDocNda)
        fun selectDoc(index: Int) {
            currentSelectedDoc = index
            val onPrimary = getColor(R.color.on_primary)
            val onSurfaceVariant = getColor(R.color.on_surface_variant)
            docButtons.forEachIndexed { idx, btn ->
                if (idx == index) {
                    btn.setBackgroundResource(R.drawable.bg_chip_pill_selected)
                    btn.setTextColor(onPrimary)
                } else {
                    btn.setBackgroundResource(R.drawable.bg_chip_pill)
                    btn.setTextColor(onSurfaceVariant)
                }
            }
            showCurrentDocPreview()
        }

        btnDocRent.setOnClickListener { selectDoc(0) }
        btnDocDev.setOnClickListener { selectDoc(1) }
        btnDocNda.setOnClickListener { selectDoc(2) }
    }

    private fun getDocumentText(index: Int): Pair<String, String> {
        return when (index) {
            0 -> "《房屋租赁合同精简版》" to """
                【房屋租赁合同】
                出租方(甲方)：张伟，承租方(乙方)：李明。
                1. 房屋位置：北京市朝阳区望京SOHO塔2。
                2. 租期与租金：租期自2026年9月1日至2027年8月31日止。月租金为人民币6500元整，按季度支付(押一付三)。
                3. 押金条款：乙方应于签约当日支付押金人民币6500元整。合同期满且无损坏违约后全额无息退还。
                4. 违约责任：任何一方提前解约需提前30天书面通知，并赔偿对方一个月租金作为违约金。逾期支付租金每日按欠款金额的1%加收滞纳金。
            """.trimIndent()
            1 -> "《软件定制开发协议》" to """
                【软件定制开发协议】
                委托方(甲方)：未来智能科技，开发方(乙方)：星辰网络工作室。
                1. 开发标的：端侧 AI 离线助手 Android 客户端。
                2. 项目金额与付款：项目总开发费用为人民币 80,000 元。分期支付：签约支付 30%，Demo交付支付 40%，终验上线支付 30%。
                3. 交付周期：乙方须在 45 个工作日内完成全部功能开发并提交源码。
                4. 验收与保修：甲方在收到交付物后 7 个工作日内完成验收。乙方提供为期 1 年的免费 Bug 维护与技术支持。
                5. 违约责任：乙方逾期交付每日按总合同金额的 0.5% 支付违约金，逾期超过 15 天甲方有权单方解除合同并全额退款。
            """.trimIndent()
            else -> "《商业技术保密协议 (NDA)》" to """
                【商业技术保密协议】
                披露方：北京奇点智算科技有限公司，接收方：合作技术顾问团队。
                1. 保密信息范围：包括但不限于端侧模型量化算法、离线知识库引擎架构及未公开产品原型设计。
                2. 保密期限：自本协议签署之日起 3 年内有效，不因双方合作终止而失效。
                3. 违约责任：如接收方未经书面许可向任何第三方泄露保密信息，应向披露方支付违约金人民币 500,000 元整，并赔偿由此造成的直接与间接经济损失。
            """.trimIndent()
        }
    }

    private fun showCurrentDocPreview() {
        val (title, content) = getDocumentText(currentSelectedDoc)
        val sb = StringBuilder()
            .append("当前合同：$title\n\n")
            .append(content)
            .append("\n\n提示：点击下方快捷指令一键生成核心条款与风险摘要，或直接针对条款提问（如：违约金是多少？押金怎么退？）。Qwen 本地模型将精准定位并结构化解答。\n")
            .append("─────────────────────────\n")
        logDocument.text = sb.toString()
    }

    private fun initInitialLogs() {
        // 1. 记账
        refreshBookkeepingStats()
        showBookkeepingHistory()

        // 2. 日程
        showScheduleHistory()

        // 3. 文档
        showCurrentDocPreview()

        // 4. 会议
        logMeeting.text = "会议纪要智能提炼工作区\n\n导入会议转写稿或输入一段讨论记录，端侧 Qwen 将毫秒级提取核心共识、待办清单与风险关注点。\n─────────────────────────\n"

        // 5. 日记
        logDiary.text = "私密日记本 · 100% 本地安全\n\n数据永远锁在手机沙箱内。支持错别字检查、语法润色、文采提升与每日情绪晴雨表。\n─────────────────────────\n"

        // 6. 反诈
        logAntiFraud.text = "端侧离线反诈雷达 · 国家安全标准\n\n遇到可疑短信或陌生通知，输入即可毫秒级深度拆解诈骗套路并标红高危风险，短信数据不出手机。\n─────────────────────────\n"
    }

    private fun refreshBookkeepingStats() {
        val stats = dbHelper.getBillStatistics()
        tvExpense.text = String.format(Locale.getDefault(), "¥%.2f", stats.totalExpense)
        tvIncome.text = String.format(Locale.getDefault(), "¥%.2f", stats.totalIncome)
        tvBalance.text = String.format(Locale.getDefault(), "¥%.2f (%d笔)", stats.balance, stats.count)
    }

    private fun showBookkeepingHistory() {
        val bills = dbHelper.getAllBills()
        val sb = StringBuilder("本地 SQLite 账单流水\n")
        if (bills.isNotEmpty()) {
            bills.take(5).forEach { bill ->
                sb.append("${bill.categoryIcon} ${bill.formattedTime} · ${bill.type} ${bill.category} · ${bill.displayAmount}（${bill.payMethod.ifEmpty { "其他" }}）· ${bill.note}\n")
            }
        } else {
            sb.append("暂无流水记录，点击下方快捷指令即可一键体验智能记账。\n")
        }
        sb.append("─────────────────────────\n")
        logBookkeeping.text = sb.toString()
    }

    private fun showScheduleHistory() {
        val schedules = dbHelper.getAllSchedules()
        val sb = StringBuilder("本地日程与闹钟备忘\n")
        if (schedules.isNotEmpty()) {
            schedules.take(5).forEach { item ->
                sb.append("[${item.type}] ${item.title} · ${item.timeStr} · 地点：${item.location.ifEmpty { "无" }}\n")
            }
        } else {
            sb.append("暂无日程备忘，说出“明天早上7点半叫我起床”即可创建。\n")
        }
        sb.append("─────────────────────────\n")
        logSchedule.text = sb.toString()
    }

    // ================= 核心业务分发与 AI 推理 =================

    private fun handleUserAction() {
        val text = input.text.toString().trim()
        if (text.isEmpty() || generating) return
        input.setText("")
        generating = true
        replyFinished = false
        setInputsEnabled(false)
        currentResponse.clear()

        val activeLogView = getActiveLogView()
        val activeScrollView = getActiveScrollView()

        activeLogView.append("\n已收到指令：\"$text\"\n")
        activeScrollView.post { activeScrollView.fullScroll(View.FOCUS_DOWN) }

        val prompt = buildPromptForCurrentTab(text)

        try {
            val future = llm!!.generateResponseAsync(prompt) { partial, done ->
                ui.post {
                    if (partial.isNotEmpty()) {
                        val filtered = partial.replace("<|im_end|>", "").replace("<|endoftext|>", "")
                        if (filtered.isNotEmpty()) {
                            currentResponse.append(filtered)
                            activeLogView.append(filtered)
                            activeScrollView.post { activeScrollView.fullScroll(View.FOCUS_DOWN) }
                        }
                    }
                    if (done) {
                        finishCurrentTabAction(text)
                    }
                }
            }

            Futures.addCallback(future, object : FutureCallback<String> {
                override fun onSuccess(result: String?) {}
                override fun onFailure(t: Throwable) {
                    ui.post {
                        if (!replyFinished) {
                            activeLogView.append("\n[错误] ${t.message}")
                            activeScrollView.post { activeScrollView.fullScroll(View.FOCUS_DOWN) }
                            finishCurrentTabAction(text)
                        }
                    }
                }
            }, MoreExecutors.directExecutor())
        } catch (e: Exception) {
            activeLogView.append("\n[错误] ${e.message}")
            activeScrollView.post { activeScrollView.fullScroll(View.FOCUS_DOWN) }
            finishCurrentTabAction(text)
        }
    }

    private fun buildPromptForCurrentTab(userInput: String): String {
        return when (currentTab) {
            NavTab.BOOKKEEPING -> """
                <|im_start|>system
                你是一个记账助手。请从用户的一句话中提取记账要素，并严格只输出标准 JSON 格式：
                {"type":"支出"|"收入","category":"餐饮"|"交通"|"购物"|"娱乐"|"工资"|"居住"|"医疗"|"其他","amount":数字金额,"pay_method":"微信"|"支付宝"|"现金"|"银行卡"|"其他","note":"备注"}
                示例：中午吃麦当劳38.5元微信付 -> {"type":"支出","category":"餐饮","amount":38.5,"pay_method":"微信","note":"吃麦当劳"}
                <|im_end|>
                <|im_start|>user
                $userInput
                <|im_end|>
                <|im_start|>assistant
            """.trimIndent()

            NavTab.SCHEDULE -> {
                val nowStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
                """
                <|im_start|>system
                你是一个智能日程与闹钟管家。当前时间：$nowStr。
                请从用户输入中提取意图，严格只输出 JSON 格式：
                {
                  "type": "闹钟" 或 "日历",
                  "title": "事件标题",
                  "hour": 小时数字(0~23),
                  "minute": 分钟数字(0~59),
                  "date_str": "yyyy-MM-dd",
                  "time_str": "HH:mm",
                  "location": "地点(若有)",
                  "note": "备注"
                }
                示例1：明天早上7点半叫我起床 -> {"type":"闹钟","title":"起床闹钟","hour":7,"minute":30,"date_str":"","time_str":"07:30","location":"","note":"叫我起床"}
                示例2：明天下午3点在3号会议室开会 -> {"type":"日历","title":"在3号会议室开会","hour":15,"minute":0,"date_str":"明天","time_str":"15:00","location":"3号会议室","note":"开会"}
                <|im_end|>
                <|im_start|>user
                $userInput
                <|im_end|>
                <|im_start|>assistant
                """.trimIndent()
            }

            NavTab.DOCUMENT -> {
                val (docTitle, docContent) = getDocumentText(currentSelectedDoc)
                """
                <|im_start|>system
                你是一个专业的合同与文档阅读助手。请根据下方提供的合同原文，准确回答用户的问题或生成结构化摘要。
                【当前文档】：$docTitle
                $docContent
                <|im_end|>
                <|im_start|>user
                $userInput
                <|im_end|>
                <|im_start|>assistant
                """.trimIndent()
            }

            NavTab.MEETING -> {
                val sampleMeeting = if (userInput.contains("导入")) {
                    """
                    参会人：张总(产品)、李工(技术)、王经理(运营)。
                    张总：下周版本必须上线端侧AI离线记账和会议纪要功能，李工负责技术落地，周五前给测试包。
                    李工：好的，目前模型已跑通，需要王经理提供20条测试反诈短信样本。
                    王经理：没问题，我明天上午整理好样本发给李工。另外周四前确定UI终稿。
                    """.trimIndent()
                } else userInput

                """
                <|im_start|>system
                你是一个专业的会议纪要秘书。请从以下会议发言中提取结构化纪要，输出格式如下：
                🎯【核心议题与共识】：简要总结
                ✅【Action Items 待办清单】：
                1. 任务名 (责任人: xx，截止时间: xx)
                💡【风险与后续关注】：关注点
                <|im_end|>
                <|im_start|>user
                会议内容：$sampleMeeting
                <|im_end|>
                <|im_start|>assistant
                """.trimIndent()
            }

            NavTab.DIARY -> """
                <|im_start|>system
                你是一个私密日记与文字润色助手。请对用户的日记草稿进行三项处理：
                1. ✏️【错别字与语病纠正】：指出错别字或语法问题（若无则写无）
                2. ✨【文采润色优美版】：在保持原意下用更优美的文笔重写
                3. 🌈【今日情绪诊断】：开心 / 平静 / 充实 / 疲惫 / 焦虑 (给出简要暖心评语)
                <|im_end|>
                <|im_start|>user
                日记草稿：$userInput
                <|im_end|>
                <|im_start|>assistant
            """.trimIndent()

            NavTab.ANTI_FRAUD -> """
                <|im_start|>system
                你是一个专业的国家反诈中心离线研判专家。请对用户收到的短信进行深度反诈安全分析，输出格式如下：
                🚨【风险评级】：🔴高危诈骗 / 🟡疑似推销广告 / 🟢安全政务快递
                📦【短信分类】：快递物流 / 银行交易 / 交通出行 / 涉诈风险
                🔍【套路拆解】：详细分析骗子话术与心理诱导陷阱
                🛡️【防骗处置建议】：具体应对措施(如绝不转账、核实官方电话等)
                <|im_end|>
                <|im_start|>user
                短信内容：$userInput
                <|im_end|>
                <|im_start|>assistant
            """.trimIndent()
        }
    }

    private fun finishCurrentTabAction(rawInput: String) {
        if (replyFinished) return
        replyFinished = true

        val rawOutput = currentResponse.toString().trim()
        val activeLogView = getActiveLogView()
        val activeScrollView = getActiveScrollView()

        when (currentTab) {
            NavTab.BOOKKEEPING -> {
                val bill = parseBillFromJson(rawOutput, rawInput)
                if (bill != null) {
                    val id = dbHelper.insertBill(bill)
                    refreshBookkeepingStats()
                    val card = StringBuilder("\n\n")
                        .append("已自动落库 #")
                        .append(id)
                        .append("\n")
                        .append("类别：${bill.categoryIcon} ${bill.type} · ${bill.category}　金额：${bill.displayAmount}\n")
                        .append("支付：${bill.payMethod.ifEmpty { "未指定" }}　备注：${bill.note.ifEmpty { "无" }}　时间：${bill.formattedTime}\n")
                    activeLogView.append(card.toString())
                }
            }

            NavTab.SCHEDULE -> {
                handleScheduleResult(rawOutput, rawInput, activeLogView)
            }

            NavTab.DOCUMENT -> {
                // 文档问答已在流式输出中展示完毕
            }

            NavTab.MEETING -> {
                val item = MeetingSummaryItem(
                    title = "会议纪要-${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())}",
                    rawContent = rawInput,
                    consensus = rawOutput,
                    actionItems = "",
                    risks = ""
                )
                dbHelper.insertMeeting(item)
                activeLogView.append("\n\n会议纪要已自动归档到本地数据库。\n")
            }

            NavTab.DIARY -> {
                val diary = DiaryItem(
                    title = "日记-${SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())}",
                    content = rawInput,
                    polishedContent = rawOutput,
                    corrections = "",
                    mood = currentMood
                )
                dbHelper.insertDiary(diary)
                activeLogView.append("\n\n私密日记已加密存入本地。\n")
            }

            NavTab.ANTI_FRAUD -> {
                val isHighRisk = rawOutput.contains("高危") || rawOutput.contains("诈骗")
                val item = SmsRecordItem(
                    sender = "短信分析",
                    content = rawInput,
                    category = if (isHighRisk) "可疑" else "普通",
                    riskLevel = if (isHighRisk) "高危" else "安全",
                    analysis = rawOutput,
                    advice = ""
                )
                dbHelper.insertSmsRecord(item)
                if (isHighRisk) {
                    activeLogView.append("\n\n反诈雷达预警：已拦截并标记为高危涉诈短信。\n")
                }
            }
        }

        activeLogView.append("─────────────────────────\n")
        activeScrollView.post { activeScrollView.fullScroll(View.FOCUS_DOWN) }
        generating = false
        setInputsEnabled(true)
    }

    private fun handleScheduleResult(rawOutput: String, rawInput: String, logView: TextView) {
        try {
            val matcher = Pattern.compile("\\{.*?\\}", Pattern.DOTALL).matcher(rawOutput)
            if (matcher.find()) {
                val json = JSONObject(matcher.group())
                val type = json.optString("type", "日历")
                val title = json.optString("title", rawInput)
                val hour = json.optInt("hour", 8)
                val minute = json.optInt("minute", 0)
                val timeStr = json.optString("time_str", String.format(Locale.getDefault(), "%02d:%02d", hour, minute))
                val location = json.optString("location", "")

                val scheduleItem = ScheduleItem(
                    type = type,
                    title = title,
                    timeStr = timeStr,
                    timestamp = System.currentTimeMillis(),
                    location = location,
                    note = rawInput
                )
                val id = dbHelper.insertSchedule(scheduleItem)

                if (type == "闹钟") {
                    try {
                        val alarmIntent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                            putExtra(AlarmClock.EXTRA_HOUR, hour)
                            putExtra(AlarmClock.EXTRA_MINUTES, minute)
                            putExtra(AlarmClock.EXTRA_MESSAGE, title)
                            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                        }
                        if (alarmIntent.resolveActivity(packageManager) != null) {
                            startActivity(alarmIntent)
                            logView.append("\n\n已调起系统闹钟：" + String.format(Locale.getDefault(), "%d点%d分", hour, minute) + "（$title）")
                        } else {
                            logView.append("\n\n闹钟已存入本地备忘 #$id：${hour}点${minute}分（$title）")
                        }
                    } catch (e: Exception) {
                        logView.append("\n\n闹钟已存入本地备忘 #$id：${hour}点${minute}分")
                    }
                } else {
                    try {
                        val cal = Calendar.getInstance().apply {
                            add(Calendar.DAY_OF_MONTH, 1)
                            set(Calendar.HOUR_OF_DAY, hour)
                            set(Calendar.MINUTE, minute)
                        }
                        val calIntent = Intent(Intent.ACTION_INSERT).apply {
                            data = CalendarContract.Events.CONTENT_URI
                            putExtra(CalendarContract.Events.TITLE, title)
                            putExtra(CalendarContract.Events.EVENT_LOCATION, location)
                            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, cal.timeInMillis)
                            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, cal.timeInMillis + 3600000)
                        }
                        if (calIntent.resolveActivity(packageManager) != null) {
                            startActivity(calIntent)
                            logView.append("\n\n已生成系统日历事件：$title（$timeStr）")
                        } else {
                            logView.append("\n\n日程已存入本地备忘 #$id：$title（$timeStr）")
                        }
                    } catch (e: Exception) {
                        logView.append("\n\n日程已存入本地备忘 #$id：$title（$timeStr）")
                    }
                }
            }
        } catch (e: Exception) {
            logView.append("\n⚠️ 未能提取到标准时间参数，已记录为通用备忘。")
        }
    }

    private fun parseBillFromJson(rawOutput: String, rawInput: String): Bill? {
        return try {
            val matcher = Pattern.compile("\\{.*?\\}", Pattern.DOTALL).matcher(rawOutput)
            if (!matcher.find()) return null
            val obj = JSONObject(matcher.group())
            val amount = obj.optDouble("amount", 0.0)
            if (amount <= 0) return null

            Bill(
                type = obj.optString("type", "支出"),
                category = obj.optString("category", "其他"),
                amount = amount,
                payMethod = obj.optString("pay_method", "其他"),
                note = obj.optString("note", rawInput),
                rawText = rawInput
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun getActiveLogView(): TextView {
        return when (currentTab) {
            NavTab.BOOKKEEPING -> logBookkeeping
            NavTab.SCHEDULE -> logSchedule
            NavTab.DOCUMENT -> logDocument
            NavTab.MEETING -> logMeeting
            NavTab.DIARY -> logDiary
            NavTab.ANTI_FRAUD -> logAntiFraud
        }
    }

    private fun getActiveScrollView(): ScrollView {
        return when (currentTab) {
            NavTab.BOOKKEEPING -> scrollBookkeeping
            NavTab.SCHEDULE -> scrollSchedule
            NavTab.DOCUMENT -> scrollDocument
            NavTab.MEETING -> scrollMeeting
            NavTab.DIARY -> scrollDiary
            NavTab.ANTI_FRAUD -> scrollAntiFraud
        }
    }

    private fun showClearCurrentDialog() {
        val tabName = when (currentTab) {
            NavTab.BOOKKEEPING -> "记账流水"
            NavTab.SCHEDULE -> "日程与闹钟"
            NavTab.DOCUMENT -> "文档问答"
            NavTab.MEETING -> "会议纪要"
            NavTab.DIARY -> "私密日记"
            NavTab.ANTI_FRAUD -> "反诈记录"
        }

        AlertDialog.Builder(this)
            .setTitle("确认清空【$tabName】")
            .setMessage("确定清空当前模块在本地 SQLite 数据库中的所有记录吗？")
            .setPositiveButton("清空") { _, _ ->
                when (currentTab) {
                    NavTab.BOOKKEEPING -> {
                        dbHelper.clearAllBills()
                        refreshBookkeepingStats()
                        logBookkeeping.text = "本地账单数据已清空。\n─────────────────────────\n"
                    }
                    NavTab.SCHEDULE -> {
                        dbHelper.clearAllSchedules()
                        logSchedule.text = "本地日程备忘已清空。\n─────────────────────────\n"
                    }
                    NavTab.DOCUMENT -> {
                        showCurrentDocPreview()
                    }
                    NavTab.MEETING -> {
                        dbHelper.clearAllMeetings()
                        logMeeting.text = "会议纪要已清空。\n─────────────────────────\n"
                    }
                    NavTab.DIARY -> {
                        dbHelper.clearAllDiaries()
                        logDiary.text = "私密日记已清空。\n─────────────────────────\n"
                    }
                    NavTab.ANTI_FRAUD -> {
                        dbHelper.clearAllSmsRecords()
                        logAntiFraud.text = "反诈记录已清空。\n─────────────────────────\n"
                    }
                }
                Toast.makeText(this, "已清空 $tabName", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ================= 纯端侧离线实时流式语音识别 (Sherpa-ONNX) =================

    private fun startVoiceRecognition() {
        if (generating) {
            Toast.makeText(this, "正在处理上一条任务，请稍候...", Toast.LENGTH_SHORT).show()
            return
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_CODE_PERMISSION_ALL)
            return
        }

        // 1. 语音识别回调
        val voiceCb = object : RealtimeVoiceManager.VoiceCallback {
            override fun onReady() {
                tvVoiceStatus?.text = "🎙️ 正在实时聆听，请说话..."
                tvLivePartialText?.text = "（正在实时转文字...）"
            }

            override fun onPartialResult(partialText: String) {
                tvVoiceStatus?.text = "🔊 正在实时转文字 (0流量·离线)..."
                tvLivePartialText?.text = partialText
                input.setText(partialText)
                input.setSelection(partialText.length)
            }

            override fun onRmsChanged(rmsdB: Float) {
                // 标准 dBFS：0 满刻度，语音约 -45 ~ -15，噪声底 < -60；映射 -60 ~ -10 到 0 ~ 1
                val factor = ((rmsdB + 60f).coerceIn(0f, 50f) / 50f)
                val widthPx = ((60 + factor * 180) * resources.displayMetrics.density).toInt()
                vSoundWaveIndicator?.layoutParams?.width = widthPx
                vSoundWaveIndicator?.requestLayout()
            }

            override fun onFinished(finalText: String) {
                if (voiceDialog == null) return // 已取消或已处理
                dismissListeningDialog()
                if (finalText.isNotEmpty()) {
                    input.setText(finalText)
                    input.setSelection(finalText.length)
                    Toast.makeText(this@MainActivity, "✅ 语音识别完成，正在提交 Qwen 离线大模型...", Toast.LENGTH_SHORT).show()
                    handleUserAction()
                } else {
                    Toast.makeText(this@MainActivity, "未检测到有效语音", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onError(errorMsg: String) {
                dismissListeningDialog()
                Toast.makeText(this@MainActivity, "语音识别提示: $errorMsg", Toast.LENGTH_SHORT).show()
            }
        }

        // 2. 检查端侧语音识别模型是否就绪；未就绪则先弹框、后台异步加载引擎（避免主线程阻塞 ANR）
        if (!voiceManager.isInitialized()) {
            if (VoiceModelManager.isModelReady(this)) {
                showListeningDialog(voiceCb)
                tvVoiceStatus?.text = "⏳ 正在加载离线语音引擎(约几秒)..."
                voiceManager.initRecognizerAsync(VoiceModelManager.getModelDir(this)) { ok ->
                    if (voiceDialog == null) return@initRecognizerAsync // 用户已取消
                    if (ok) {
                        voiceManager.startListening(voiceCb)
                    } else {
                        dismissListeningDialog()
                        Toast.makeText(this, "语音引擎初始化失败，请重试", Toast.LENGTH_SHORT).show()
                    }
                }
                return
            } else {
                showDownloadVoiceModelDialog()
                return
            }
        }

        // 3. 启动端侧流式录音识别
        showListeningDialog(voiceCb)
        voiceManager.startListening(voiceCb)
    }

    private fun showDownloadVoiceModelDialog() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("📦 初始化端侧离线语音模型")
            .setMessage("首次使用需加载 38MB 端侧离线语音流式识别模型，之后将 100% 离线可用（0流量、保护隐私）。")
            .setCancelable(false)
            .setPositiveButton("立即加载", null)
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            val btn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            btn.setOnClickListener {
                dialog.setMessage("⏳ 正在准备离线语音模型组件，请稍候...")
                btn.isEnabled = false

                VoiceModelManager.prepareModel(this, object : VoiceModelManager.ModelLoadCallback {
                    override fun onProgress(percentage: Int, message: String) {
                        dialog.setMessage("$message ($percentage%)")
                    }

                    override fun onSuccess(modelDir: File) {
                        dialog.dismiss()
                        voiceManager.initRecognizerAsync(modelDir) { ok ->
                            if (ok) {
                                Toast.makeText(this@MainActivity, "🟢 离线语音引擎初始化完成！", Toast.LENGTH_SHORT).show()
                                startVoiceRecognition()
                            } else {
                                Toast.makeText(this@MainActivity, "语音引擎初始化失败，请重试", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }

                    override fun onError(errorMsg: String) {
                        dialog.dismiss()
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("下载失败")
                            .setMessage("离线语音模型下载失败: $errorMsg\n\n提示：您也可以手动推入模型至私有目录。")
                            .setPositiveButton("确定", null)
                            .show()
                    }
                })
            }
        }
        dialog.show()
    }

    private fun showListeningDialog(voiceCb: RealtimeVoiceManager.VoiceCallback) {
        val density = resources.displayMetrics.density
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (18 * density).toInt(), (24 * density).toInt(), (14 * density).toInt())
            gravity = Gravity.CENTER_HORIZONTAL
        }

        tvVoiceStatus = TextView(this).apply {
            text = "🎙️ 正在启动离线录音..."
            textSize = 15f
            setTextColor(getColor(R.color.on_surface))
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setPadding(0, 0, 0, (10 * density).toInt())
        }
        layout.addView(tvVoiceStatus)

        // 声波跳动条 (Sound Wave Indicator)
        vSoundWaveIndicator = View(this).apply {
            val h = (6 * density).toInt()
            val w = (60 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(w, h).apply {
                bottomMargin = (14 * density).toInt()
            }
            setBackgroundColor(getColor(R.color.primary))
        }
        layout.addView(vSoundWaveIndicator)

        // 实时文字展示框
        tvLivePartialText = TextView(this).apply {
            text = "（正在倾听...）"
            textSize = 14f
            setTextColor(getColor(R.color.on_surface_variant))
            setBackgroundColor(getColor(R.color.surface_variant))
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
            minLines = 2
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (12 * density).toInt()
            }
        }
        layout.addView(tvLivePartialText)

        val tvHint = TextView(this).apply {
            text = "💡 边说边实时出字 · 停顿 1.8 秒将自动提交"
            textSize = 11f
            setTextColor(getColor(R.color.on_surface_faint))
            textAlignment = View.TEXT_ALIGNMENT_CENTER
        }
        layout.addView(tvHint)

        voiceDialog = AlertDialog.Builder(this)
            .setTitle("端侧离线实时语音输入")
            .setView(layout)
            .setPositiveButton("完成说话") { _, _ ->
                // 必须带上回调，否则最终文本不会回填、对话框也不会关闭
                voiceManager.stopListening(voiceCb)
            }
            .setNegativeButton("取消") { _, _ ->
                voiceManager.cancel()
                dismissListeningDialog()
            }
            .setCancelable(false)
            .show()
    }

    private fun dismissListeningDialog() {
        voiceDialog?.dismiss()
        voiceDialog = null
        tvVoiceStatus = null
        tvLivePartialText = null
        vSoundWaveIndicator = null
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceManager.release()
        llm?.close()
        dbHelper.close()
    }
}
