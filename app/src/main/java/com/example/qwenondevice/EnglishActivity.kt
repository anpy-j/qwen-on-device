package com.example.qwenondevice

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.ColorInt
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.MoreExecutors
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import com.google.mediapipe.tasks.genai.llminference.ProgressListener
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class EnglishActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "EnglishActivity"
        const val EXTRA_QUERY = "extra_query"
        const val EXTRA_OPEN_CHAT = "extra_open_chat"
        const val EXTRA_OPEN_VOICE = "extra_open_voice"
        private const val PREFS_NAME = "english_app"
        private const val KEY_DICT_USE_MODEL = "dict_use_model"
        private const val KEY_TTS_SPEED = "tts_speed"

        // ===== 本地 LLM 模型（Qwen2.5 0.5B q8，约 523 MB）=====
        private const val ASSET_MODEL_FILENAME = "qwen.task"
        private const val LOCAL_MODEL_FILENAME = "qwen.task"
        private const val MODEL_SIZE_BYTES = 546_660_344L

        private const val DICT_AI_SYSTEM = """你是专业英汉词典。用户输入一个英文单词，请准确给出该单词的音标、中文释义、简短英文释义、词形变化和例句。
必须仅返回单个合法的 JSON 对象（严禁包含 markdown 代码块、严禁输出任何多余文字）：
{"word":"apple","ipa":"/ˈæpl/","cn":"苹果","def":"a round fruit with red or green skin","inf":"复数 apples","example_en":"I ate a red apple today.","example_cn":"我今天吃了一个红苹果。"}
规则：
1. 必须严格解释用户输入的单词，严禁解释示例词或替换为其他单词。
2. word 字段必须严格等于用户输入的单词。
3. 释义必须准确对应用户输入的单词本身。"""

        private const val DICT_EXAMPLE_SYSTEM = """你是英语老师。为一个英文单词生成一句简单的英文例句，只返回一行 JSON（不要 markdown、不要多余文字）：
{"example_en":"...","example_cn":"..."}
example_en 是包含该单词的 B1 简单英文例句，example_cn 是对应的中文翻译。"""

        private const val CHAT_SYSTEM = """你是 Mia，一位耐心友好的英语会话伙伴，正在陪用户练习英语口语（B1 水平）。
规则：
1. 回复保持简短，2-3 句简单英文即可。
2. 如果用户的表达有语法或用词错误，在英文回复之后另起一行输出：[纠错] 一句中文说明 + 正确写法。没有错误就不要输出这一行。
3. 每次回复最后提一个简单的跟进问题，把话题继续下去。
4. 用户说中文也没关系，用简单英文回应。
5. 不要提及自己是 AI 或语言模型。"""

        private const val FIX_SYSTEM = """你是英语纠错教练。用户会输入一个英文句子，或一句想让你改成地道英文的中文。
严格按以下格式输出，不要输出任何其他内容：
✅ 正确表达：<地道英文句子>
❌ 逐条讲解：<每条问题单独一行，中文简短说明；如果用户输入的是中文，第一行写（由中文意向改写）>
🌐 中文意思：<整句的中文意思>
💡 备选写法：<一个更地道的同义改写（可选，没有就省略整行）>"""

        private const val SHADOW_GENERATE_SYSTEM =
            "请给一个 B1 水平日常生活英文句子，10 到 14 个词。只输出句子本身，不要引号、编号或解释。"
    }

    private enum class NavTab { DICT, CHAT, FIX, WORDS, SHADOW }

    private data class NavItem(
        val tab: NavTab,
        val iconRes: Int,
        val label: String
    )

    private val navItems = listOf(
        NavItem(NavTab.DICT, R.drawable.ic_doc, "词典"),
        NavItem(NavTab.CHAT, R.drawable.ic_group, "口语陪练"),
        NavItem(NavTab.FIX, R.drawable.ic_code, "句子纠错"),
        NavItem(NavTab.WORDS, R.drawable.ic_book, "单词本"),
        NavItem(NavTab.SHADOW, R.drawable.ic_mic, "跟读听力")
    )

    private val INK = 0xFF0F172A.toInt()
    private val INK_SUB = 0xFF334155.toInt()
    private val MUTED = 0xFF64748B.toInt()
    private val FAINT = 0xFF94A3B8.toInt()
    private val BLUE = 0xFF2563EB.toInt()
    private val INDIGO = 0xFF6366F1.toInt()
    private val VIOLET = 0xFF8B5CF6.toInt()
    private val ROSE = 0xFFE11D48.toInt()
    private val AMBER = 0xFFF59E0B.toInt()
    private val GREEN = 0xFF10B981.toInt()

    private lateinit var inputText: EditText
    private lateinit var actionChipContainer: LinearLayout
    private lateinit var navContainer: LinearLayout

    private lateinit var heroStatus: TextView
    private lateinit var heroSubtitle: TextView
    private lateinit var ttsSpeedPill: TextView
    private var ttsSpeed = 1.0f

    private lateinit var pageDict: LinearLayout
    private lateinit var dictContainer: LinearLayout
    private lateinit var scrollDict: ScrollView
    private lateinit var tvDictBannerSub: TextView
    private lateinit var dictModeBundled: TextView
    private lateinit var dictModeModel: TextView
    private lateinit var pageChat: LinearLayout
    private lateinit var scrollChat: ScrollView
    private lateinit var chatContainer: LinearLayout
    private lateinit var pageFix: LinearLayout
    private lateinit var scrollFix: ScrollView
    private lateinit var logFix: TextView
    private lateinit var pageWords: LinearLayout
    private lateinit var tvWordsStat: TextView
    private lateinit var btnWordsClear: TextView
    private lateinit var wordsContainer: LinearLayout
    private lateinit var pageShadow: LinearLayout
    private lateinit var scrollShadow: ScrollView
    private lateinit var shadowContainer: LinearLayout

    private lateinit var btnVoice: ImageButton
    private var voiceDialog: AlertDialog? = null

    private var currentTab: NavTab = NavTab.DICT
    private var dictUseModel = false
    private var dictQuerySeq = 0

    private var llmInference: LlmInference? = null
    private var llmReady = false

    private var realtimeVoiceManager: RealtimeVoiceManager? = null

    private lateinit var tts: TtsManager
    private lateinit var db: AppDatabaseHelper

    private val mainHandler = Handler(Looper.getMainLooper())
    private val chatHistory = ArrayList<Pair<String, String>>()
    private var currentShadowSentence: String = ""
    private var pendingIncomingQuery: String? = null

    // =========================================================================
    // 视图工具
    // =========================================================================

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun sp(value: Int): Float = value * resources.displayMetrics.scaledDensity
    @ColorInt
    private fun color(id: Int): Int = ContextCompat.getColor(this, id)
    private fun isSingleWord(word: String): Boolean =
        Regex("^[a-zA-Z][a-zA-Z'-]*$").matches(word) && word.length in 1..40
    private fun firstLine(s: String): String = s.lineSequence().firstOrNull()?.trim().orEmpty()

    private fun card(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@EnglishActivity, R.drawable.bg_card_white)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun textView(
        text: String,
        sizeSp: Int,
        @ColorInt colorVal: Int,
        bold: Boolean = false,
        topMargin: Int = 0
    ): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = sizeSp.toFloat()
            setTextColor(colorVal)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { if (topMargin > 0) this.topMargin = dp(topMargin) }
        }
    }

    private fun sectionLabel(label: String, @ColorInt colorVal: Int): TextView =
        textView(label, 11, colorVal, bold = true, topMargin = 10).apply { letterSpacing = 0.05f }

    @SuppressLint("SetTextI18n")
    private fun smallPill(text: String, onClick: (() -> Unit)? = null): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(INK_SUB)
            textSize = 12f
            gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(this@EnglishActivity, R.drawable.bg_chip_pill)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(8)
            }
            if (onClick != null) setOnClickListener { onClick() }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun hintCard(text: String): LinearLayout {
        return card().apply {
            addView(textView(text, 13, FAINT).also {
                it.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                it.setLineSpacing(sp(4), 1.0f)
            })
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(inputText.windowToken, 0)
    }

    private fun showKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(inputText, InputMethodManager.SHOW_IMPLICIT)
        inputText.requestFocus()
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_english)
        val incoming = intent.getStringExtra(EXTRA_QUERY).orEmpty().trim()
        pendingIncomingQuery = incoming.takeIf { it.isNotEmpty() }

        findViewById<TextView>(R.id.btnBackHome).setOnClickListener { finish() }

        inputText = findViewById(R.id.inputText)
        actionChipContainer = findViewById(R.id.actionChipContainer)
        navContainer = findViewById(R.id.navContainer)

        heroStatus = findViewById(R.id.heroStatus)
        heroSubtitle = findViewById(R.id.heroSubtitle)
        ttsSpeedPill = findViewById(R.id.ttsSpeedPill)
        ttsSpeed = prefs.getFloat(KEY_TTS_SPEED, 1.0f).coerceIn(0.3f, 1.5f)
        refreshTtsSpeedPill()
        ttsSpeedPill.setOnClickListener { showTtsSpeedDialog() }

        pageDict = findViewById(R.id.pageDict)
        dictContainer = findViewById(R.id.dictContainer)
        scrollDict = findViewById(R.id.scrollDict)
        pageChat = findViewById(R.id.pageChat)
        scrollChat = findViewById(R.id.scrollChat)
        chatContainer = findViewById(R.id.chatContainer)
        pageFix = findViewById(R.id.pageFix)
        scrollFix = findViewById(R.id.scrollFix)
        logFix = findViewById(R.id.logFix)
        pageWords = findViewById(R.id.pageWords)
        tvWordsStat = findViewById(R.id.tvWordsStat)
        btnWordsClear = findViewById(R.id.btnWordsClear)
        wordsContainer = findViewById(R.id.wordsContainer)
        pageShadow = findViewById(R.id.pageShadow)
        tvDictBannerSub = findViewById(R.id.tvDictBannerSub)
        dictModeBundled = findViewById(R.id.dictModeBundled)
        dictModeModel = findViewById(R.id.dictModeModel)
        scrollShadow = findViewById(R.id.scrollShadow)
        shadowContainer = findViewById(R.id.shadowContainer)

        val btnSend = findViewById<Button>(R.id.btnSend)
        btnVoice = findViewById(R.id.btnVoice)

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)

        tts = TtsManager(this)
        db = AppDatabaseHelper(this)

        buildNavBar()

        btnSend.setOnClickListener { onTextInput(inputText.text.toString()) }
        btnVoice.setOnClickListener { startVoiceDialog() }
        btnVoice.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.ic_mic))
        btnVoice.setColorFilter(color(R.color.text_primary))

        inputText.setOnEditorActionListener { v, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                onTextInput(v.text.toString())
                true
            } else false
        }
        (inputText.parent as? LinearLayout)?.setOnClickListener { showKeyboard() }

        btnWordsClear.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("清空单词本")
                .setMessage("确定删除全部 ${db.countWords()} 个单词吗？")
                .setPositiveButton("删除") { _, _ ->
                    db.clearAllWords()
                    toast("已清空单词本")
                    renderWordsView()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        loadLlmModel()
        tts.prepareAsync { ok ->
            if (ok) {
                Log.i(TAG, "TTS engine ready")
            } else {
                Log.e(TAG, "TTS engine failed: ${tts.lastError}")
            }
        }

        // 词典查词方式：内置词典 / AI 模型（模型模式下不再读取 words.json）
        dictUseModel = prefs.getBoolean(KEY_DICT_USE_MODEL, false)
        dictModeBundled.setOnClickListener { setDictMode(false) }
        dictModeModel.setOnClickListener { setDictMode(true) }
        refreshDictModeUi()
        ensureDictionaryLoaded()

        val openChat = intent.getBooleanExtra(EXTRA_OPEN_CHAT, false)
        setTab(if (openChat) NavTab.CHAT else NavTab.DICT)
        if (incoming.isNotEmpty()) {
            inputText.setText(incoming)
            inputText.setSelection(incoming.length)
        } else {
            val savedDraft = prefs.getString("english_draft", "").orEmpty()
            inputText.setText(savedDraft)
            inputText.setSelection(savedDraft.length)
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_VOICE, false)) {
            mainHandler.postDelayed({ startVoiceDialog() }, 350L)
        }
    }

    override fun onPause() {
        prefs.edit().putString("english_draft", inputText.text.toString()).apply()
        super.onPause()
    }

    private val prefs: SharedPreferences by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    private fun setDictMode(useModel: Boolean) {
        if (dictUseModel == useModel) return
        dictUseModel = useModel
        prefs.edit().putBoolean(KEY_DICT_USE_MODEL, useModel).apply()
        refreshDictModeUi()
        ensureDictionaryLoaded()
        if (currentTab == NavTab.DICT) {
            // 切换后清空旧查询结果，给出对应模式的引导文案
            dictQuerySeq++
            dictContainer.removeAllViews()
            dictContainer.addView(hintCard(dictWelcomeText()))
        }
    }

    @SuppressLint("SetTextI18n")
    private fun refreshDictModeUi() {
        tvDictBannerSub.text = if (dictUseModel) {
            "端侧 AI 逐词解释 · 不依赖内置词库"
        } else {
            "9733 高频词已内置 · 点词即查，发音全程离线"
        }
        styleModePill(dictModeBundled, !dictUseModel)
        styleModePill(dictModeModel, dictUseModel)
    }

    private fun styleModePill(pill: TextView, selected: Boolean) {
        pill.background = ContextCompat.getDrawable(
            this,
            if (selected) R.drawable.bg_chip_pill_selected else R.drawable.bg_chip_pill
        )
        pill.setTextColor(
            if (selected) Color.WHITE else color(R.color.text_primary)
        )
    }

    private fun ensureDictionaryLoaded() {
        // 模型模式不加载 words.json，也不参与查询
        if (dictUseModel) return
        Dictionary.load(this, { })
    }

    override fun onDestroy() {
        super.onDestroy()
        tts.release()
        stopSpeechSession()
        releaseLlmInference()
    }

    // =========================================================================
    // Tab 导航 / 快捷操作
    // =========================================================================

    @SuppressLint("SetTextI18n")
    private fun buildNavBar() {
        navContainer.removeAllViews()
        for ((index, item) in navItems.withIndex()) {
            val tabView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            val lp = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply { if (index > 0) marginStart = dp(4) }
            tabView.layoutParams = lp

            tabView.addView(ImageView(this).apply {
                setImageDrawable(ContextCompat.getDrawable(this@EnglishActivity, item.iconRes))
                setColorFilter(color(R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
                contentDescription = null
            })
            tabView.addView(TextView(this).apply {
                text = item.label
                textSize = 10.5f
                setTextColor(Color.parseColor("#7B8CA6"))
                gravity = Gravity.CENTER
                maxLines = 2
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(3) }
            })
            tabView.setOnClickListener { setTab(item.tab) }
            navContainer.addView(tabView)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun setTab(tab: NavTab) {
        currentTab = tab
        tts.stopPlayback()
        hideKeyboard()

        navItems.forEachIndexed { index, item ->
            val child = navContainer.getChildAt(index) as? LinearLayout ?: return@forEachIndexed
            val icon = child.getChildAt(0) as ImageView
            val label = child.getChildAt(1) as TextView
            if (item.tab == tab) {
                child.setBackgroundResource(R.drawable.bg_chip_pill_on_dark)
                child.setPadding(dp(8), dp(8), dp(8), dp(8))
                icon.setColorFilter(Color.WHITE)
                label.setTextColor(Color.WHITE)
                label.setTypeface(null, Typeface.BOLD)
            } else {
                child.background = null
                child.setPadding(dp(8), dp(8), dp(8), dp(8))
                icon.setColorFilter(color(R.color.text_primary))
                label.setTextColor(Color.parseColor("#7B8CA6"))
                label.setTypeface(null, Typeface.NORMAL)
            }
        }

        val pages = listOf(pageDict, pageChat, pageFix, pageWords, pageShadow)
        for ((i, page) in pages.withIndex()) {
            page.visibility = if (tab.ordinal == i) View.VISIBLE else View.GONE
        }

        inputText.hint = when (tab) {
            NavTab.DICT -> "输入单词，如 apple"
            NavTab.CHAT -> "用英语说一句吧"
            NavTab.FIX -> "粘贴英文原句，或输入想表达的中文"
            NavTab.WORDS -> "输入单词加入单词本"
            NavTab.SHADOW -> "输入跟读句子，或点 AI 出题"
        }

        buildActionChipsForTab(tab)
        when (tab) {
            NavTab.DICT -> if (dictContainer.childCount == 0) {
                dictContainer.addView(hintCard(dictWelcomeText()))
            }
            NavTab.CHAT -> if (chatContainer.childCount == 0) {
                addAssistantBubble("Hi! I'm Mia, your English practice partner. What did you do last weekend?")
            }
            NavTab.FIX -> { }
            NavTab.WORDS -> renderWordsView()
            NavTab.SHADOW -> if (currentShadowSentence.isEmpty()) {
                setShadow("The weather is really nice today.")
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun buildActionChipsForTab(tab: NavTab) {
        actionChipContainer.removeAllViews()
        val items = when (tab) {
            NavTab.DICT -> listOf("apple", "actually", "run", "brilliant", "weather")
            NavTab.CHAT -> listOf(
                "Let's talk about my weekend",
                "What should I eat for dinner?",
                "Help me practice a job interview"
            )
            NavTab.FIX -> listOf(
                "Yesterday I go to the park and saw my friend.",
                "I have 20 years old.",
                "我想说：我昨天把钥匙忘在家里了"
            )
            NavTab.WORDS -> listOf("apple", "brilliant", "weather", "happiness")
            NavTab.SHADOW -> listOf(
                "The weather is really nice today.",
                "Could you speak a little more slowly, please?",
                "AI 为我出一句跟读"
            )
        }
        for (item in items) {
            actionChipContainer.addView(buildChipView(item) {
                if (tab == NavTab.SHADOW && (it.contains("AI") || it.contains("出题"))) {
                    askAiShadow()
                } else {
                    onTextInput(it)
                }
            })
        }
    }

    @SuppressLint("SetTextI18n")
    private fun buildChipView(text: String, onClick: (String) -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(color(R.color.text_primary))
            background = ContextCompat.getDrawable(this@EnglishActivity, R.drawable.bg_chip_pill)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            textSize = 12f
            isSingleLine = true
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
            setOnClickListener { onClick(text) }
        }
    }

    // =========================================================================
    // 输入分发
    // =========================================================================

    private fun onTextInput(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        inputText.setText("")
        hideKeyboard()
        when (currentTab) {
            NavTab.DICT -> doDictLookup(text)
            NavTab.CHAT -> doChatTurn(text)
            NavTab.FIX -> doFix(text)
            NavTab.WORDS -> doAddWord(text)
            NavTab.SHADOW -> if (text.contains("AI") || text.contains("出题")) askAiShadow() else setShadow(text)
        }
    }

    // =========================================================================
    // 词典
    // =========================================================================

    @SuppressLint("SetTextI18n")
    private fun doDictLookup(raw: String) {
        val word = raw.trim()
        if (!isSingleWord(word)) {
            toast("请输入单个单词")
            return
        }
        if (!dictUseModel && !Dictionary.isLoaded()) {
            toast("词典还在加载中，请稍等几秒")
            return
        }
        dictContainer.removeAllViews()
        if (dictUseModel) {
            // 模型模式：不读取 words.json，查词全部交给端侧 AI
            lookupByAi(word, notInDict = false, seq = ++dictQuerySeq)
            return
        }
        val entry = Dictionary.lookup(word)
        if (entry != null) {
            dictContainer.addView(buildDictCard(entry))
            scrollDict.smoothScrollTo(0, 0)
        } else {
            lookupByAi(word, notInDict = true, seq = ++dictQuerySeq)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun lookupByAi(word: String, notInDict: Boolean, seq: Int, attempt: Int = 0) {
        val card = card()
        dictContainer.addView(card)
        card.addView(textView(word.lowercase(), 20, INK, bold = true))
        card.addView(textView(
            if (notInDict) "本地词库未收录，AI 正在现场解释…" else "AI 正在现场解释…",
            12, FAINT, topMargin = 6
        ))
        llmAsk(DICT_AI_SYSTEM, word.lowercase()) { result ->
            val parsed = parseDictJson(result)
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                // 期间用户又查了新词或切换了模式，丢弃过期回包
                if (seq != dictQuerySeq) return@post
                val wordMismatch = parsed?.word?.isNotEmpty() == true &&
                    !parsed.word.equals(word, ignoreCase = true) &&
                    !word.startsWith(parsed.word, ignoreCase = true) &&
                    !parsed.word.startsWith(word, ignoreCase = true)
                if (parsed == null || parsed.cn.isEmpty() || wordMismatch) {
                    if (!llmReady) {
                        // requireLlm 已 toast 加载提示，这里把卡片占位改为等待提示
                        (card.getChildAt(1) as? TextView)?.text = "本地 AI 引擎加载中，请稍后重试…"
                        return@post
                    }
                    // 模型偶发输出格式不合法或词不对题：自动重试一次，仍失败才提示
                    if (attempt == 0) {
                        dictContainer.removeAllViews()
                        lookupByAi(word, notInDict, seq, attempt = 1)
                        return@post
                    }
                    toast("AI 解释失败，请换词再试")
                    return@post
                }
                dictContainer.removeAllViews()
                dictContainer.addView(buildDictCard(
                    Dictionary.Entry(
                        // 标题与例句缓存键一律用查询词，防止模型回填示例词
                        word = word.lowercase(),
                        phonetic = parsed.ipa,
                        definition = parsed.def,
                        translation = parsed.cn,
                        inflection = parsed.inf,
                        tag = "AI 现场解释",
                        source = "ai"
                    )
                ))
            }
        }
    }

    private fun dictWelcomeText(): String = if (dictUseModel) {
        "输入或点下方示例查询单词，也可以直接点麦克风用语音查。当前为 AI 模型模式，每个词都由本地 AI 逐词解释。"
    } else {
        "输入或点下方示例查询单词，也可以直接点麦克风用语音查。" +
            "内置 9733 个高频词；查不到的词会由本地 AI 现场解释。"
    }

    @SuppressLint("SetTextI18n")
    private fun buildDictCard(entry: Dictionary.Entry): LinearLayout {
        val card = card()

        val headRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headRow.addView(textView(entry.word, 20, INK, bold = true))
        if (entry.phonetic.isNotEmpty()) {
            headRow.addView(textView("/${entry.phonetic}/", 13, MUTED).apply {
                (layoutParams as LinearLayout.LayoutParams).marginStart = dp(8)
            })
        }
        headRow.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(1), 1f)
        })
        headRow.addView(smallPill("▶ 发音") { ttsSpeak(entry.word) })
        headRow.addView(smallPill("＋单词本") {
            db.upsertWord(entry.word, entry.phonetic, firstLine(entry.translation))
            toast("已加入单词本：${entry.word}")
        })
        card.addView(headRow)

        if (entry.tag.isNotEmpty()) {
            val tags = entry.tag.split(",").map { it.trim() }.filter { it.isNotEmpty() }.take(6)
            if (tags.isNotEmpty()) {
                card.addView(textView(tags.joinToString(" · "), 11, INDIGO, topMargin = 6))
            }
        }
        if (entry.translation.isNotEmpty()) {
            card.addView(sectionLabel("释义", BLUE))
            card.addView(textView(entry.translation, 13, INK).also { it.setLineSpacing(sp(3), 1.0f) })
        }
        if (entry.definition.isNotEmpty()) {
            card.addView(sectionLabel("英文释义", BLUE))
            card.addView(textView(entry.definition, 13, INK_SUB).also { it.setLineSpacing(sp(3), 1.0f) })
        }
        if (entry.inflection.isNotEmpty()) {
            card.addView(sectionLabel("词形变化", BLUE))
            card.addView(textView(entry.inflection, 12, INK_SUB))
        }

        card.addView(sectionLabel("例句", BLUE))
        val exampleSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(exampleSection)
        cacheExample(entry.word, exampleSection)
        return card
    }

    private fun cacheExample(word: String, section: LinearLayout, attempt: Int = 0) {
        val cached = db.getDictCache(word)
        if (cached != null && cached.first.isNotEmpty()) {
            fillExampleSection(section, cached.first, cached.second)
            return
        }
        section.addView(textView("AI 正在生成例句…", 12, FAINT))
        llmAsk(DICT_EXAMPLE_SYSTEM, word) { result ->
            val parsed = parseDictJson(result)
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                if (parsed != null && parsed.exampleEn.isNotEmpty()) {
                    db.putDictCache(word, parsed.exampleEn, parsed.exampleCn)
                    section.removeAllViews()
                    fillExampleSection(section, parsed.exampleEn, parsed.exampleCn)
                } else if (!llmReady) {
                    // requireLlm 已提示加载中，这里改为等待占位而非“生成失败”
                    section.removeAllViews()
                    section.addView(textView("本地 AI 加载中，例句稍后重新查词生成", 12, FAINT))
                } else if (attempt == 0) {
                    // 例句生成失败自动重试一次
                    section.removeAllViews()
                    section.addView(textView("AI 正在重新生成例句…", 12, FAINT))
                    cacheExample(word, section, attempt = 1)
                } else {
                    section.removeAllViews()
                    section.addView(textView("例句生成失败，可稍后重新查词", 12, FAINT))
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun fillExampleSection(section: LinearLayout, en: String, cn: String) {
        section.addView(textView(en, 13, INK, bold = true))
        if (cn.isNotEmpty()) {
            section.addView(textView(cn, 12, MUTED, topMargin = 2))
        }
        section.addView(smallPill("▶ 朗读例句") { ttsSpeak(en) }.also {
            (it.layoutParams as LinearLayout.LayoutParams).topMargin = dp(6)
        })
    }

    private data class ParsedDict(
        val word: String,
        val ipa: String,
        val cn: String,
        val def: String,
        val inf: String,
        val exampleEn: String,
        val exampleCn: String
    )

    private fun parseDictJson(result: String?): ParsedDict? {
        if (result.isNullOrBlank()) return null
        // 去掉可能的 markdown 代码块围栏（```json / ```）
        val text = result
            .replace(Regex("```(?:json)?\\s*", RegexOption.IGNORE_CASE), "")
            .replace("```", "")
            .trim()
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val jsonText = text.substring(start, end + 1)
        try {
            val obj = org.json.JSONObject(jsonText)
            return ParsedDict(
                word = obj.optString("word").trim(),
                ipa = obj.optString("ipa").trim().trimStart('/').trimEnd('/'),
                cn = obj.optString("cn").trim(),
                def = obj.optString("def").trim().ifEmpty { obj.optString("defEn").trim() },
                inf = obj.optString("inf").trim().ifEmpty { obj.optString("inflection").trim() },
                exampleEn = obj.optString("example_en").trim(),
                exampleCn = obj.optString("example_cn").trim()
            )
        } catch (e: Exception) {
            // JSONObject 解析失败（模型偶发输出尾逗号/缺引号等），退回逐字段正则提取
            fun grab(key: String): String {
                val m = Regex("\"$key\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").find(jsonText)
                    ?: return ""
                return m.groupValues[1].replace("\\\"", "\"").trim()
            }
            val word = grab("word")
            val cn = grab("cn")
            val exampleEn = grab("example_en")
            if (word.isEmpty() && cn.isEmpty() && exampleEn.isEmpty()) return null
            return ParsedDict(
                word = word,
                ipa = grab("ipa").trimStart('/').trimEnd('/'),
                cn = cn,
                def = grab("def").ifEmpty { grab("defEn") },
                inf = grab("inf").ifEmpty { grab("inflection") },
                exampleEn = exampleEn,
                exampleCn = grab("example_cn")
            )
        }
    }

    // =========================================================================
    // 口语陪练
    // =========================================================================

    private fun doChatTurn(userText: String) {
        addChatBubbleUser(userText)
        chatHistory.add("user" to userText)
        trimChatHistory()

        val llm = requireLlm() ?: return
        val conversation = buildString {
            append("<|im_start|>system\n").append(CHAT_SYSTEM).append("\n<|im_end|>\n")
            for ((role, text) in chatHistory) {
                append("<|im_start|>").append(role).append('\n').append(text).append("\n<|im_end|>\n")
            }
            append("<|im_start|>assistant\n")
        }
        val pending = addPendingAssistantBubble()
        val pendingIndex = chatContainer.indexOfChild(pending)

        val future = llm.generateResponseAsync(conversation, object : ProgressListener<String> {
            override fun run(output: String, isFinal: Boolean) {
                if (isFinal) return
                mainHandler.post {
                    val view = chatContainer.getChildAt(pendingIndex)
                    if (view === pending && !output.isNullOrEmpty()) {
                        pending.text = output
                        scrollChatToBottom()
                    }
                }
            }
        })
        Futures.addCallback(future, object : FutureCallback<String> {
            override fun onSuccess(result: String?) {
                mainHandler.post {
                    if (isFinishing || isDestroyed) return@post
                    val finalText = (result ?: "").trim()
                    if (chatContainer.getChildAt(pendingIndex) === pending) {
                        chatContainer.removeViewAt(pendingIndex)
                    }
                    if (finalText.isEmpty()) {
                        chatContainer.addView(textView("模型没有返回内容，请再试一次。", 12, FAINT))
                    } else {
                        chatHistory.removeAt(chatHistory.size - 1)
                        chatHistory.add("assistant" to finalText)
                        trimChatHistory()
                        addAssistantBubble(finalText)
                        scrollChatToBottom()
                        speakEnglishPart(finalText)
                    }
                }
            }

            override fun onFailure(t: Throwable) {
                Log.e(TAG, "Chat error: ${t.message}")
                mainHandler.post {
                    if (isFinishing || isDestroyed) return@post
                    if (chatContainer.getChildAt(pendingIndex) === pending) {
                        chatContainer.removeViewAt(pendingIndex)
                    }
                    chatContainer.addView(textView("对话出错了：${t.message}", 12, ROSE))
                    scrollChatToBottom()
                }
            }
        }, MoreExecutors.directExecutor())
    }

    private fun trimChatHistory() {
        while (chatHistory.size > 12) chatHistory.removeAt(0)
    }

    @SuppressLint("SetTextI18n")
    private fun addPendingAssistantBubble(): TextView {
        val view = TextView(this).apply {
            text = "Mia 正在输入…"
            setTextColor(FAINT)
            textSize = 14f
            background = ContextCompat.getDrawable(this@EnglishActivity, R.drawable.bg_chip_pill)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        chatContainer.addView(view)
        scrollChatToBottom()
        return view
    }

    @SuppressLint("SetTextI18n")
    private fun addChatBubbleUser(text: String) {
        val bubble = TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 14f
            background = ContextCompat.getDrawable(this@EnglishActivity, R.drawable.bg_btn_primary)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
                gravity = Gravity.END
            }
        }
        chatContainer.addView(bubble)
        scrollChatToBottom()
    }

    @SuppressLint("SetTextI18n")
    private fun addAssistantBubble(text: String) {
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@EnglishActivity, R.drawable.bg_chip_pill)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        val english = text.substringBefore("[").trim()
        if (english.isNotEmpty()) {
            bubble.addView(textView(english, 14, INK).also { it.setLineSpacing(sp(3), 1.0f) })
        }
        if (text.contains("[纠错]")) {
            val fix = text.substringAfter("[纠错]").trim()
            if (fix.isNotEmpty()) {
                bubble.addView(textView("✍ $fix", 12, ROSE, topMargin = 6).also {
                    it.setLineSpacing(sp(2), 1.0f)
                })
            }
        }
        if (english.isNotEmpty()) {
            bubble.addView(smallPill("▶ 发音") { ttsSpeak(english) }.also {
                (it.layoutParams as LinearLayout.LayoutParams).topMargin = dp(6)
                (it.layoutParams as LinearLayout.LayoutParams).marginStart = 0
            })
        }
        chatContainer.addView(bubble)
    }

    private fun scrollChatToBottom() {
        val last = chatContainer.getChildAt(chatContainer.childCount - 1)
        if (last != null) scrollChat.smoothScrollTo(0, last.top)
    }

    // =========================================================================
    // 句子纠错
    // =========================================================================

    @SuppressLint("SetTextI18n")
    private fun doFix(raw: String) {
        val oldHint = logFix.text.toString()
        val base = if (oldHint.startsWith("在这里粘贴")) "" else oldTextWithSeparators(oldHint)
        logFix.text = base + "📨 $raw\n（AI 正在逐条改写…）\n"
        scrollFix.fullScroll(View.FOCUS_DOWN)

        llmAsk(FIX_SYSTEM, raw) { result ->
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                val text = result?.trim().orEmpty()
                val current = logFix.text.toString()
                val stripped = current.removeSuffix("（AI 正在逐条改写…）\n")
                val block = if (text.isEmpty()) {
                    "⚠️ 模型没有返回内容，请再试一次。\n"
                } else {
                    text + "\n\n"
                }
                logFix.text = stripped + block
                val corrected = text.lines().firstOrNull { it.startsWith("✅") }
                    ?.substringAfter("：")?.substringAfter(":")?.trim().orEmpty()
                if (corrected.isNotEmpty()) ttsSpeak(corrected)
                scrollFix.post { scrollFix.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun oldTextWithSeparators(text: String): String =
        if (text.endsWith("\n")) text else "$text\n\n"

    // =========================================================================
    // 单词本（SRS 间隔重复）
    // =========================================================================

    @SuppressLint("SetTextI18n")
    private fun doAddWord(raw: String, attempt: Int = 0) {
        val word = raw.trim()
        if (!isSingleWord(word)) {
            toast("请输入单个单词")
            return
        }
        // 模型模式下不查内置词库，加词同样交给端侧 AI 解释
        val entry = if (!dictUseModel && Dictionary.isLoaded()) Dictionary.lookup(word) else null
        if (entry != null) {
            db.upsertWord(entry.word, entry.phonetic, firstLine(entry.translation))
            toast("已加入单词本：${entry.word}")
            renderWordsView()
        } else {
            if (attempt == 0) {
                toast(if (dictUseModel) "AI 正在解释并加入…" else "本地词库未收录，AI 正在解释并加入…")
            }
            llmAsk(DICT_AI_SYSTEM, word.lowercase()) { result ->
                val parsed = parseDictJson(result)
                mainHandler.post {
                    if (isFinishing || isDestroyed) return@post
                    if (parsed == null || parsed.cn.isEmpty()) {
                        if (!llmReady) {
                            // requireLlm 已提示加载中，避免误报“加入失败”
                            return@post
                        }
                        // 模型偶发输出不合法：自动重试一次
                        if (attempt == 0) {
                            doAddWord(word, attempt = 1)
                            return@post
                        }
                        toast("加入失败，请再试一次")
                    } else {
                        db.upsertWord(word, parsed.ipa, parsed.cn)
                        toast("已加入单词本：$word")
                        renderWordsView()
                    }
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun renderWordsView() {
        val total = db.countWords()
        val due = db.countDue()
        tvWordsStat.text = "共 $total 个单词 · $due 个待复习"
        wordsContainer.removeAllViews()

        val pending = db.dueWords()
        if (pending.isNotEmpty()) {
            wordsContainer.addView(reviewCard(pending[0]))
            if (pending.size > 1) {
                wordsContainer.addView(TextView(this).apply {
                    text = "还有 ${pending.size - 1} 个待复习，完成当前词继续下一个"
                    setTextColor(FAINT)
                    textSize = 11f
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(10) }
                })
            }
        }

        val all = db.latestWords(30)
        if (all.isEmpty()) {
            wordsContainer.addView(hintCard("单词本还是空的。\n去「词典」查词后点『＋单词本』，或直接在这里输入单词发送。"))
        } else {
            wordsContainer.addView(sectionLabel("最近加入", VIOLET))
            for (w in all) {
                wordsContainer.addView(wordRow(w))
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun reviewCard(w: WordRow): LinearLayout {
        val card = card()
        card.addView(sectionLabel("复习单词", VIOLET))

        val headRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headRow.addView(textView(w.word, 18, INK, bold = true))
        if (w.phonetic.isNotEmpty()) {
            headRow.addView(textView("/${w.phonetic}/", 12, MUTED).apply {
                (layoutParams as LinearLayout.LayoutParams).marginStart = dp(8)
            })
        }
        headRow.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(1), 1f)
        })
        headRow.addView(smallPill("▶", { ttsSpeak(w.word) }))
        card.addView(headRow)

        val answerBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        card.addView(answerBox)
        answerBox.addView(TextView(this).apply {
            text = "先回忆一下意思，再点显示答案"
            setTextColor(FAINT)
            textSize = 12f
        })
        answerBox.addView(smallPill("显示答案") {
            answerBox.removeAllViews()
            if (w.translation.isNotEmpty()) {
                answerBox.addView(textView(w.translation, 13, INK).also { it.setLineSpacing(sp(3), 1.0f) })
            }
            answerBox.addView(buildRatingRow(w.word).also {
                (it.layoutParams as LinearLayout.LayoutParams).topMargin = dp(10)
            })
        }.also {
            (it.layoutParams as LinearLayout.LayoutParams).topMargin = dp(8)
        })
        return card
    }

    @SuppressLint("SetTextI18n")
    private fun buildRatingRow(word: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val ratings = listOf(
            Triple("忘记", ROSE, 0),
            Triple("模糊", AMBER, 1),
            Triple("认识", GREEN, 2)
        )
        for ((label, colorVal, value) in ratings) {
            row.addView(TextView(this).apply {
                text = label
                setTextColor(colorVal)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = ContextCompat.getDrawable(this@EnglishActivity, R.drawable.bg_chip_pill)
                setPadding(dp(14), dp(9), dp(14), dp(9))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (value > 0) marginStart = dp(8)
                }
                setOnClickListener {
                    db.reviewWord(word, value)
                    val msg = when (value) {
                        0 -> "已重置，2 分钟后再次出现"
                        1 -> "10 分钟后再次出现"
                        else -> "间隔已延长，恭喜巩固 +1"
                    }
                    toast("「$word」$msg")
                    renderWordsView()
                }
            })
        }
        return row
    }

    @SuppressLint("SetTextI18n")
    private fun wordRow(w: WordRow): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(this@EnglishActivity, R.drawable.bg_card_white)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val titleText = if (w.phonetic.isNotEmpty()) "${w.word}  /${w.phonetic}/" else w.word
        left.addView(textView(titleText, 13, INK, bold = true))
        val stageText = if (w.stage == 0) "新词 · 待复习" else "第 ${w.stage} 级 · ${w.intervalDays} 天后到期"
        left.addView(textView(stageText, 11, FAINT))
        row.addView(left)
        row.addView(smallPill("▶", { ttsSpeak(w.word) }))
        row.addView(smallPill("✕", {
            db.deleteWord(w.word)
            toast("已删除「${w.word}」")
            renderWordsView()
        }))
        return row
    }

    // =========================================================================
    // TTS 封装
    // =========================================================================

    private fun ttsSpeak(text: String, speed: Float? = null) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val effSpeed = speed ?: ttsSpeed
        if (tts.isReady) {
            tts.speak(clean, effSpeed)
        } else {
            tts.prepareAsync { ok ->
                if (ok) tts.speak(clean, effSpeed)
                else toast(tts.lastError ?: "英文发音引擎未就绪，无法朗读")
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun refreshTtsSpeedPill() {
        ttsSpeedPill.text = "语速 ${ttsSpeed}×"
    }

    private fun showTtsSpeedDialog() {
        val options = arrayOf("0.3x 极慢", "0.5x 较慢", "0.7x 慢速", "0.9x", "1.0x 标准", "1.1x", "1.3x 快速")
        val values = floatArrayOf(0.3f, 0.5f, 0.7f, 0.9f, 1.0f, 1.1f, 1.3f)
        val current = values.indexOfFirst { kotlin.math.abs(it - ttsSpeed) < 0.05f }.let { if (it < 0) 4 else it }
        AlertDialog.Builder(this)
            .setTitle("发音语速")
            .setSingleChoiceItems(options, current) { dialog, which ->
                ttsSpeed = values[which]
                prefs.edit().putFloat(KEY_TTS_SPEED, ttsSpeed).apply()
                refreshTtsSpeedPill()
                toast("发音语速：${options[which]}")
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun speakEnglishPart(text: String) {
        val head = text.substringBefore("[").trim()
            .lines()
            .filter { line ->
                val latin = line.count { it in 'a'..'z' || it in 'A'..'Z' }
                val cjk = line.count { it in '\u4e00'..'\u9fa5' }
                latin > 0 && cjk * 4 < line.length
            }
            .joinToString(" ")
            .trim()
        ttsSpeak(head)
    }

    // =========================================================================
    // 跟读听力
    // =========================================================================

    @SuppressLint("SetTextI18n")
    private fun setShadow(sentence: String) {
        val clean = sentence.trim()
            .replace(Regex("^[\"'“”‘’\\s]+|[\"]+$"), "")
            .trim()
        if (clean.isEmpty()) return
        currentShadowSentence = clean
        shadowContainer.removeAllViews()

        val card = card()
        shadowContainer.addView(card)
        card.addView(sectionLabel("跟读句子", ROSE))
        card.addView(textView(clean, 17, INK, bold = true).also { it.setLineSpacing(sp(4), 1.0f) })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        row.addView(smallPill("▶ 播放", { ttsSpeak(clean) }).also {
            (it.layoutParams as LinearLayout.LayoutParams).marginStart = 0
        })
        row.addView(smallPill("🐢 慢速", { ttsSpeak(clean, (ttsSpeed * 0.75f).coerceIn(0.3f, 0.75f)) }))
        card.addView(row)

        shadowContainer.addView(hintCard(
            "点底部 🎙 按钮开始跟读，识别后逐词高亮差距。" +
                "换句子：点上方示例、直接输入，或让 AI 出题。"
        ))
        scrollShadow.smoothScrollTo(0, 0)
    }

    @SuppressLint("SetTextI18n")
    private fun askAiShadow() {
        shadowContainer.removeAllViews()
        shadowContainer.addView(hintCard("AI 正在出题…"))
        llmAsk(SHADOW_GENERATE_SYSTEM, "出一句") { result ->
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                val sentence = result?.trim()
                    ?.lineSequence()?.firstOrNull().orEmpty()
                    .replace(Regex("^[\"'“”‘’\\s]+|[\"'“”‘’\\s]+$"), "")
                    .trim()
                if (sentence.isEmpty()) {
                    shadowContainer.removeAllViews()
                    shadowContainer.addView(hintCard("出题失败，请再试一次。"))
                } else {
                    setShadow(sentence)
                }
            }
        }
    }

    private fun onShadowFollow(spoken: String) {
        val target = currentShadowSentence
        if (target.isEmpty()) {
            setShadow("The weather is really nice today.")
            toast("先选一句跟读句子")
            return
        }
        val targetTokens = tokenize(target)
        val spokenTokens = tokenize(spoken)
        val matched = lcsMatch(targetTokens, spokenTokens)
        val matchCount = matched.count { it }
        val rate = if (targetTokens.isEmpty()) 0 else matchCount * 100 / targetTokens.size

        shadowContainer.removeAllViews()

        val sentenceCard = card()
        shadowContainer.addView(sentenceCard)
        sentenceCard.addView(sectionLabel("跟读句子", ROSE))
        sentenceCard.addView(textView(target, 17, INK, bold = true).also { it.setLineSpacing(sp(4), 1.0f) })
        sentenceCard.addView(smallPill("▶ 再听一遍") { ttsSpeak(target) }.also {
            (it.layoutParams as LinearLayout.LayoutParams).topMargin = dp(10)
            (it.layoutParams as LinearLayout.LayoutParams).marginStart = 0
        })

        val resultCard = card()
        shadowContainer.addView(resultCard)
        resultCard.addView(sectionLabel("你的跟读", ROSE))
        val display = targetTokens.joinToString(" ")
        val sb = SpannableStringBuilder(display)
        var pos = 0
        for ((i, token) in targetTokens.withIndex()) {
            val end = pos + token.length
            if (matched[i]) {
                sb.setSpan(ForegroundColorSpan(GREEN), pos, end, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(StyleSpan(Typeface.BOLD), pos, end, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                sb.setSpan(ForegroundColorSpan(ROSE), pos, end, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(StrikethroughSpan(), pos, end, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            pos = end + 1
        }
        resultCard.addView(TextView(this).apply {
            text = sb
            textSize = 15f
            typeface = Typeface.DEFAULT
            setLineSpacing(sp(4), 1.0f)
        })
        resultCard.addView(textView(
            "匹配度 ${rate}%（$matchCount / ${targetTokens.size} 个词）", 13, INDIGO, bold = true, topMargin = 8
        ))
        val comment = when {
            rate >= 80 -> "不错，整体到位了，继续保持！"
            rate >= 50 -> "大致意思到了，对照标红的词再跟一遍。"
            else -> "差距还有点大，先点 ▶ 多听两遍示范再跟读。"
        }
        resultCard.addView(textView(comment, 12, MUTED, topMargin = 4))
        if (spoken.isNotBlank()) {
            resultCard.addView(textView("识别到：$spoken", 11, FAINT, topMargin = 6))
        }

        shadowContainer.addView(hintCard("换一句？点上方示例、直接输入，或让 AI 出题。"))
        scrollShadow.fullScroll(View.FOCUS_DOWN)
    }

    private fun tokenize(text: String): List<String> =
        text.lowercase().split(Regex("[^a-z']+")).filter { it.isNotEmpty() }

    private fun lcsMatch(target: List<String>, spoken: List<String>): BooleanArray {
        if (target.isEmpty()) return BooleanArray(0)
        val n = target.size
        val m = spoken.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (target[i] == spoken[j]) {
                    dp[i + 1][j + 1] + 1
                } else {
                    maxOf(dp[i + 1][j], dp[i][j + 1])
                }
            }
        }
        val matched = BooleanArray(n)
        var i = 0
        var j = 0
        while (i < n && j < m) {
            if (target[i] == spoken[j]) {
                matched[i] = true
                i++
                j++
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                i++
            } else {
                j++
            }
        }
        return matched
    }

    // =========================================================================
    // 语音输入（离线 sherpa-onnx）
    // =========================================================================

    private var voiceProgressView: TextView? = null
    private var voiceRecognizedView: TextView? = null
    private var voiceStartButton: Button? = null
    private var voiceStopButton: Button? = null

    @SuppressLint("SetTextI18n")
    private fun startVoiceDialog() {
        if (packageManager?.checkPermission(android.Manifest.permission.RECORD_AUDIO, packageName)
                != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 2001)
            toast("请授予麦克风权限后再次点击语音按钮")
            return
        }
        val existing = voiceDialog
        if (existing?.isShowing == true) return

        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(28), dp(24), dp(20))
        }
        val hintView = TextView(this).apply {
            text = "点击「开始说话」对着麦克风朗读\n停顿约 1.8 秒自动判停并发送"
            setTextColor(INK_SUB)
            textSize = 12.5f
            gravity = Gravity.CENTER
            setLineSpacing(sp(5), 1.0f)
        }
        val progressView = TextView(this).apply {
            text = "正在加载语音识别模型…"
            setTextColor(INK_SUB)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        }
        val recognizedView = TextView(this).apply {
            setTextColor(INK)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setLineSpacing(sp(6), 1.0f)
            visibility = View.GONE
            setPadding(0, dp(12), 0, 0)
        }
        val startButton = Button(this).apply {
            text = "开始说话"
            setTextColor(Color.WHITE)
            textSize = 14f
            background = ContextCompat.getDrawable(this@EnglishActivity, R.drawable.bg_btn_primary)
            setPadding(dp(24), dp(10), dp(24), dp(10))
            isEnabled = false
            alpha = 0.6f
        }
        val stopButton = Button(this).apply {
            text = "停止"
            setTextColor(Color.WHITE)
            textSize = 14f
            background = ContextCompat.getDrawable(this@EnglishActivity, R.drawable.bg_btn_primary)
            setPadding(dp(24), dp(10), dp(24), dp(10))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        dialogView.addView(hintView)
        dialogView.addView(progressView)
        dialogView.addView(recognizedView)
        dialogView.addView(startButton)
        dialogView.addView(stopButton)

        voiceProgressView = progressView
        voiceRecognizedView = recognizedView
        voiceStartButton = startButton
        voiceStopButton = stopButton

        startButton.setOnClickListener { beginRecording() }
        stopButton.setOnClickListener {
            progressView.text = "正在结束…"
            progressView.setTextColor(INK_SUB)
            stopButton.isEnabled = false
            realtimeVoiceManager?.stopListening(null)
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        voiceDialog = dialog
        dialog.setOnDismissListener {
            stopSpeechSession()
        }
        dialog.show()

        if (VoiceModelManager.isModelReady(this)) {
            initSpeechRecognizer()
        } else {
            VoiceModelManager.prepareModel(this, object : VoiceModelManager.ModelLoadCallback {
                override fun onProgress(percentage: Int, message: String) {
                    progressView.text = message
                    progressView.setTextColor(INK_SUB)
                }

                override fun onSuccess(modelDir: File) {
                    initSpeechRecognizer()
                }

                override fun onError(errorMsg: String) {
                    if (!dialog.isShowing) return
                    progressView.text = "语音模型加载失败：$errorMsg"
                    progressView.setTextColor(ROSE)
                    dialog.dismiss()
                }
            })
        }
    }

    private fun initSpeechRecognizer() {
        val manager = RealtimeVoiceManager(this)
        manager.initRecognizerAsync(VoiceModelManager.getModelDir(this)) { ok ->
            if (!ok) {
                voiceProgressView?.let {
                    it.text = "语音引擎初始化失败，请重新打开"
                    it.setTextColor(ROSE)
                }
                return@initRecognizerAsync
            }
            realtimeVoiceManager = manager
            voiceProgressView?.let {
                it.text = "就绪，点击「开始说话」"
                it.setTextColor(GREEN)
            }
            voiceStartButton?.let {
                it.isEnabled = true
                it.alpha = 1f
            }
            voiceStopButton?.visibility = View.VISIBLE
        }
    }

    @SuppressLint("SetTextI18n")
    private fun beginRecording() {
        val manager = realtimeVoiceManager
        if (manager == null || !manager.isInitialized()) {
            toast("语音引擎未就绪")
            return
        }
        voiceRecognizedView?.text = ""
        voiceRecognizedView?.visibility = View.VISIBLE
        voiceProgressView?.apply {
            text = "正在录音，请说话…"
            setTextColor(INK_SUB)
        }
        voiceStartButton?.isEnabled = false
        voiceStopButton?.isEnabled = true
        manager.startListening(object : RealtimeVoiceManager.VoiceCallback {
            override fun onReady() { }

            override fun onPartialResult(partialText: String) {
                voiceRecognizedView?.text = partialText
            }

            override fun onRmsChanged(rmsdB: Float) { }

            override fun onFinished(finalText: String) {
                finishSpeech(finalText)
            }

            override fun onError(errorMsg: String) {
                voiceProgressView?.apply {
                    text = "出错：$errorMsg"
                    setTextColor(ROSE)
                }
                voiceStartButton?.isEnabled = true
                voiceStopButton?.visibility = View.GONE
            }
        })
    }

    private fun finishSpeech(text: String) {
        val clean = text.trim()
        voiceDialog?.dismiss()
        if (clean.isEmpty()) {
            toast("未识别到内容，请重试")
            return
        }
        onSpeechResult(clean)
    }

    private fun onSpeechResult(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        when (currentTab) {
            NavTab.SHADOW -> onShadowFollow(text)
            else -> onTextInput(text)
        }
    }

    private fun stopSpeechSession() {
        val manager = realtimeVoiceManager
        if (manager != null) {
            try {
                manager.cancel()
                manager.release()
            } catch (_: Exception) {
            }
        }
        realtimeVoiceManager = null
        voiceDialog = null
    }

    // =========================================================================
    // 本地 LLM（MediaPipe LLM Inference + Qwen2.5 0.5B q8）
    // =========================================================================

    private fun requireLlm(): LlmInference? {
        val llm = llmInference
        if (llm == null || !llmReady) {
            toast("本地 AI 还在加载中，首次约需 30-60 秒，请稍后再试")
            return null
        }
        return llm
    }

    private fun llmAsk(system: String, user: String, onResult: (String?) -> Unit) {
        val llm = requireLlm() ?: run {
            onResult(null)
            return
        }
        val prompt = "<|im_start|>system\n$system\n<|im_end|>\n<|im_start|>user\n$user\n<|im_end|>\n<|im_start|>assistant\n"
        val future = llm.generateResponseAsync(prompt)
        Futures.addCallback(future, object : FutureCallback<String> {
            override fun onSuccess(result: String?) {
                onResult(result?.trim())
            }

            override fun onFailure(t: Throwable) {
                Log.e(TAG, "LLM error: ${t.message}")
                onResult(null)
            }
        }, MoreExecutors.directExecutor())
    }

    private fun loadLlmModel() {
        heroStatus.text = "模型加载中"
        heroStatus.setTextColor(AMBER)

        val localFile = File(filesDir, LOCAL_MODEL_FILENAME)
        if (localFile.exists() && localFile.length() == MODEL_SIZE_BYTES) {
            heroStatus.text = "模型就绪"
            heroStatus.setTextColor(GREEN)
            initializeLlmInference(localFile)
            return
        }

        heroSubtitle.text = "首次运行：正在解包模型 Qwen 0.5B（约 523 MB）…"
        Thread {
            var errorMessage: String? = null
            try {
                if (!localFile.exists() || localFile.length() != MODEL_SIZE_BYTES) {
                    extractAssetModel(localFile)
                }
            } catch (e: Exception) {
                Log.e(TAG, "模型准备失败", e)
                errorMessage = "模型准备失败：${e.message}"
            }
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                if (errorMessage == null) {
                    heroStatus.text = "模型就绪"
                    heroStatus.setTextColor(GREEN)
                    initializeLlmInference(localFile)
                } else {
                    heroStatus.text = "加载失败"
                    heroStatus.setTextColor(ROSE)
                    heroSubtitle.text = errorMessage
                    toast(errorMessage)
                }
            }
        }.start()
    }

    private fun extractAssetModel(target: File) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            assets.open(ASSET_MODEL_FILENAME).use { input ->
                FileOutputStream(tmp).use { output ->
                    input.copyTo(output, 128 * 1024)
                }
            }
            if (tmp.length() != MODEL_SIZE_BYTES) {
                throw IOException("模型文件校验失败：${tmp.length()} 字节，期望 $MODEL_SIZE_BYTES")
            }
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) {
                    throw IOException("模型文件移动到目标目录失败")
                }
            }
        } finally {
            if (!target.exists() && tmp.exists()) tmp.delete()
        }
    }

    private fun initializeLlmInference(modelFile: File) {
        heroStatus.text = "引擎初始化中"
        heroStatus.setTextColor(AMBER)
        Thread {
            try {
                try {
                    llmInference?.close()
                } catch (_: Exception) { }
                val options = LlmInferenceOptions.builder()
                    .setModelPath(modelFile.absolutePath)
                    .setMaxTokens(2048)
                    .build()
                llmInference = LlmInference.createFromOptions(this, options)
                llmReady = true
                mainHandler.post {
                    if (isFinishing || isDestroyed) return@post
                    heroStatus.text = "端侧 AI 就绪"
                    heroStatus.setTextColor(GREEN)
                    heroSubtitle.text = "Qwen 0.5B q8 · 全程离线推理"
                    toast("本地 AI 引擎就绪，可以开始使用了")
                    pendingIncomingQuery?.let { query ->
                        pendingIncomingQuery = null
                        onTextInput(query)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "LLM 初始化失败", e)
                llmReady = false
                val msg = "LLM 初始化失败：${e.message}"
                mainHandler.post {
                    if (isFinishing || isDestroyed) return@post
                    heroStatus.text = "初始化失败"
                    heroStatus.setTextColor(ROSE)
                    heroSubtitle.text = msg
                    toast(msg)
                }
            }
        }.start()
    }

    private fun releaseLlmInference() {
        try {
            llmInference?.close()
        } catch (e: Exception) {
            Log.w(TAG, "释放 LLM 资源时出错（忽略）: ${e.message}")
        } finally {
            llmInference = null
            llmReady = false
        }
    }
}
