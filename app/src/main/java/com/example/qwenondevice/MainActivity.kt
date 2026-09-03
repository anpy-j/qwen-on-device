package com.example.qwenondevice

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
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

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"

        // ===== 本地 LLM 模型（Qwen2.5 0.5B q8，约 523 MB）=====
        private const val ASSET_MODEL_FILENAME = "qwen.task"
        private const val LOCAL_MODEL_FILENAME = "qwen.task"
        private const val MODEL_SIZE_BYTES = 546_660_344L

        private const val DICT_AI_SYSTEM = """你是英语词典。针对用户给出的英语单词，只输出一行 JSON（不要 markdown、不要其他文字），字段：
{"word":"...","ipa":"IPA音标","cn":"中文释义（简短）","example_en":"一个 B1 水平简单例句","example_cn":"例句中文翻译"}"""

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

    private lateinit var pageDict: LinearLayout
    private lateinit var dictContainer: LinearLayout
    private lateinit var scrollDict: ScrollView
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

    private var llmInference: LlmInference? = null
    private var llmReady = false

    private var realtimeVoiceManager: RealtimeVoiceManager? = null

    private lateinit var tts: TtsManager
    private lateinit var db: AppDatabaseHelper

    private val mainHandler = Handler(Looper.getMainLooper())
    private val chatHistory = ArrayList<Pair<String, String>>()
    private var currentShadowSentence: String = ""

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
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_card_white)
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
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_chip_pill)
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
        setContentView(R.layout.activity_main)

        inputText = findViewById(R.id.inputText)
        actionChipContainer = findViewById(R.id.actionChipContainer)
        navContainer = findViewById(R.id.navContainer)

        heroStatus = findViewById(R.id.heroStatus)
        heroSubtitle = findViewById(R.id.heroSubtitle)

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
            if (ok) Log.i(TAG, "TTS engine ready") else Log.e(TAG, "TTS engine failed")
        }
        Dictionary.load(this, { })

        setTab(NavTab.DICT)
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
                setImageDrawable(ContextCompat.getDrawable(this@MainActivity, item.iconRes))
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
                dictContainer.addView(hintCard(
                    "输入或点下方示例查询单词，也可以直接点麦克风用语音查。" +
                        "内置 9733 个高频词；查不到的词会由本地 AI 现场解释。"
                ))
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
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_chip_pill)
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
        if (!Dictionary.isLoaded()) {
            toast("词典还在加载中，请稍等几秒")
            return
        }
        val entry = Dictionary.lookup(word)
        dictContainer.removeAllViews()
        if (entry != null) {
            dictContainer.addView(buildDictCard(entry))
            scrollDict.smoothScrollTo(0, 0)
        } else {
            val card = card()
            dictContainer.addView(card)
            card.addView(textView(word.lowercase(), 20, INK, bold = true))
            card.addView(textView("本地词库未收录，AI 正在现场解释…", 12, FAINT, topMargin = 6))
            llmAsk(DICT_AI_SYSTEM, word.lowercase()) { result ->
                val parsed = parseDictJson(result)
                mainHandler.post {
                    if (isFinishing || isDestroyed) return@post
                    if (parsed == null || parsed.cn.isEmpty()) {
                        toast("AI 解释失败，请换词再试")
                        return@post
                    }
                    dictContainer.removeAllViews()
                    dictContainer.addView(buildDictCard(
                        Dictionary.Entry(
                            word = parsed.word.ifEmpty { word.lowercase() },
                            phonetic = parsed.ipa,
                            definition = "",
                            translation = parsed.cn,
                            inflection = "",
                            tag = "AI 现场解释",
                            source = "ai"
                        )
                    ))
                }
            }
        }
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

    private fun cacheExample(word: String, section: LinearLayout) {
        val cached = db.getDictCache(word)
        if (cached != null && cached.first.isNotEmpty()) {
            fillExampleSection(section, cached.first, cached.second)
            return
        }
        section.addView(textView("AI 正在生成例句…", 12, FAINT))
        llmAsk(DICT_AI_SYSTEM, word) { result ->
            val parsed = parseDictJson(result)
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                if (parsed != null && parsed.exampleEn.isNotEmpty()) {
                    db.putDictCache(word, parsed.exampleEn, parsed.exampleCn)
                    section.removeAllViews()
                    fillExampleSection(section, parsed.exampleEn, parsed.exampleCn)
                } else {
                    section.removeAllViews()
                    section.addView(textView("例句生成失败", 12, FAINT))
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
        val exampleEn: String,
        val exampleCn: String
    )

    private fun parseDictJson(result: String?): ParsedDict? {
        if (result.isNullOrBlank()) return null
        val start = result.indexOf('{')
        val end = result.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            val obj = org.json.JSONObject(result.substring(start, end + 1))
            ParsedDict(
                word = obj.optString("word").trim(),
                ipa = obj.optString("ipa").trim().trimStart('/').trimEnd('/'),
                cn = obj.optString("cn").trim(),
                exampleEn = obj.optString("example_en").trim(),
                exampleCn = obj.optString("example_cn").trim()
            )
        } catch (e: Exception) {
            null
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
            append(CHAT_SYSTEM).append("\n\n")
            for ((role, text) in chatHistory) {
                append(if (role == "user") "User: " else "Mia: ").append(text).append('\n')
            }
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
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_chip_pill)
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
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_btn_primary)
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
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_chip_pill)
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
    private fun doAddWord(raw: String) {
        val word = raw.trim()
        if (!isSingleWord(word)) {
            toast("请输入单个单词")
            return
        }
        val entry = if (Dictionary.isLoaded()) Dictionary.lookup(word) else null
        if (entry != null) {
            db.upsertWord(entry.word, entry.phonetic, firstLine(entry.translation))
            toast("已加入单词本：${entry.word}")
            renderWordsView()
        } else {
            toast("本地词库未收录，AI 正在解释并加入…")
            llmAsk(DICT_AI_SYSTEM, word.lowercase()) { result ->
                val parsed = parseDictJson(result)
                mainHandler.post {
                    if (isFinishing || isDestroyed) return@post
                    if (parsed == null || parsed.cn.isEmpty()) {
                        toast("加入失败，请再试一次")
                    } else {
                        db.upsertWord(parsed.word.ifEmpty { word }, parsed.ipa, parsed.cn)
                        toast("已加入单词本：${parsed.word.ifEmpty { word }}")
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
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_chip_pill)
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
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_card_white)
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

    private fun ttsSpeak(text: String, speed: Float = 1f) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        if (tts.isReady) {
            tts.speak(clean, speed)
        } else {
            tts.prepareAsync { ok ->
                if (ok) tts.speak(clean, speed) else toast("英文发音引擎未就绪，无法朗读")
            }
        }
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
        row.addView(smallPill("🐢 慢速", { ttsSpeak(clean, 0.75f) }))
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
            toast("请先授予麦克风权限，才能使用语音")
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
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_btn_primary)
            setPadding(dp(24), dp(10), dp(24), dp(10))
            isEnabled = false
            alpha = 0.6f
        }
        val stopButton = Button(this).apply {
            text = "停止"
            setTextColor(Color.WHITE)
            textSize = 14f
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_btn_primary)
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
        val prompt = "$system\n\n$user"
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
