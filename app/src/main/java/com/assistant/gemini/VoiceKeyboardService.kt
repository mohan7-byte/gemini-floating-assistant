package com.assistant.gemini

import android.content.Context
import android.content.ClipboardManager
import android.content.ClipDescription
import android.net.Uri
import android.content.pm.PackageManager
import android.graphics.Color
import android.content.res.ColorStateList
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
import java.util.Locale
import android.widget.EditText
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.ScrollView
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/** Minimal live-dictation IME with a usable offline English/Hindi keyboard. */
class VoiceKeyboardService : InputMethodService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var recording = false
    @Volatile private var intentionalStop = false
    private var statusView: TextView? = null
    private var dictateButton: TextView? = null
    private var keyboardContainer: LinearLayout? = null
    private var clipboardContainer: LinearLayout? = null
    private var clipboardScroll: ScrollView? = null
    private var settingsScroll: ScrollView? = null
    private var settingsContainer: LinearLayout? = null
    private var settingsActions: LinearLayout? = null
    private var settingsMode = false
    private var settingsThemeGroup: RadioGroup? = null
    private var settingsNumberRow: CheckBox? = null
    private var settingsSuggestions: CheckBox? = null
    private var settingsAutoLearn: CheckBox? = null
    private var settingsAlternateHints: CheckBox? = null
    private var settingsClearLearnedButton: TextView? = null
    private var settingsHeightSeek: SeekBar? = null
    private var settingsCustomKeyChecks: LinkedHashMap<String, CheckBox> = linkedMapOf()
    private var settingsHeightLabel: TextView? = null
    private var clipboardMode = false
    private var sensitiveInput = true
    private var toolbarExpanded = false
    private var numberOnlyPage = false
    @Volatile private var geminiConnected = false
    @Volatile private var liveGeneration = 0
    private var micPulseAnimator: ObjectAnimator? = null
    private var micPulseTarget: View? = null
    private var clipboardManager: ClipboardManager? = null
    private var clipboardListenerRegistered = false
    private var learnedEnglishWordsCache: LinkedHashMap<String, Int>? = null

    private data class ClipboardEntry(
        val id: String,
        val text: String? = null,
        val uri: String? = null,
        val mimeType: String? = null,
        val pinned: Boolean = false,
        val sourceUri: String? = null
    )

    private val keyboardPrefs get() = SecurePrefs.get(this)
    private fun suggestionsEnabled() = keyboardPrefs.getBoolean("keyboard_suggestions_enabled", true)
    private fun numberRowEnabled() = keyboardPrefs.getBoolean("keyboard_number_row", true)
    private fun autoLearnEnglishEnabled() = keyboardPrefs.getBoolean("keyboard_autolearn_english", false)
    private fun alternateHintsEnabled() = keyboardPrefs.getBoolean("keyboard_alternate_hints", true)
    private fun keyboardScale() = keyboardPrefs.getInt("keyboard_height_percent", 100).coerceIn(80, 125) / 100f
    private fun darkKeyboard(): Boolean {
        return when (keyboardPrefs.getString("keyboard_theme", "system")) {
            "dark" -> true
            "light" -> false
            else -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
    }
    private var quickKeysContainer: LinearLayout? = null
    private var voicePanel: View? = null
    private var offlinePanel: View? = null
    private var languageButton: TextView? = null
    private var inputRoot: View? = null
    private var suggestionViews: List<TextView> = emptyList()
    private var spellCheckerSession: SpellCheckerSession? = null
    private val pendingSuggestionWords = ConcurrentHashMap<Int, String>()
    @Volatile private var latestSuggestionRequest = 0

    // Small on-device vocabulary: immediate suggestions work even without a configured Android spell checker.
    private val offlineEnglishWords = """
        a about above after again against all also always am an and any are around as at away back
        be because been before being below between both bring build but by call came can change check
        child children choose clear close come common could day did different do does done down during
        each early easy end enough even every example face fact family far fast feel few find first
        follow for form found from full get give go good great group grow had has have he head help her
        here high him his home house how however i idea if in include inside into is it its just keep
        kind know large last late later learn left let life light like line list little live long look
        made make many may me mean more most move much must my name near need never new next night no
        not now number of off often old on once one only open or other our out over own page part people
        place plan play point pretty problem put question quick quite read real really right run same
        say school see seem set she should show side simple since small so some something sound still
        such take talk tell than that the their them then there these they thing think this those though
        through time to today together too try turn two under until up use used user very voice want was
        way we well went were what when where which while who why will with without word work world would
        write year yes you your hello thanks thank please okay ok great good morning afternoon evening
        tomorrow yesterday now today keyboard typing suggestion suggestions system theme settings clipboard
        image picture delete remove pin pinned save cancel light dark english hindi language sentence
        android app open close start stop copy paste text number symbol space enter backspace shift
        faster reliable lightweight personal assistant because need want help make feel looks
        design modern nice beautiful better best test build failed fixed working works supported
        accept accepted access account across action active actually add address after ahead allow almost
        alone along already although always among amount answer appear apple area around arrive ask asked
        asking asleep attention audio available avoid baby bad balance bank basic beach become began begin
        beginning believe best better between bill bit block body book both break bring broken brother
        budget business buy camera care careful carry case catch cause center certain chance change chat
        cheap check city class clean clear click clock cloud code color colour comfortable command comment
        complete computer connect connection continue control correct cost country create credit customer
        data date decide deep describe device dictionary dinner direction display document download drive
        earlier earth education email emotion employee energy engine enjoy enter entire error evening event
        exact excellent explain extra eye face fall father feature feel feeling file fill final finance finish
        fit food foot forget form free friend front fruit fun future game garden general generate get gift
        goal gold gone grand great green guess half hand happen happy hard hate health hear heart heavy hello
        history holiday hope hour human hundred hungry idea important improve industry information input
        install instant internet interview introduce issue item job join journey jump key keyboard know
        language laptop last launch lawyer lead leave lesson letter level library listen little local loved
        machine main maintain manager manner market matter maximum maybe meal measure meeting message middle
        minute mobile model moment month morning mother mountain music myself nation natural necessary network
        nice normal notice offer office offline okay online option order original output owner package paper
        parent park password pattern payment percent phone photo piece place picture pilot pink plan platform
        player please plenty pocket policy position possible power practice prefer prepare present price primary
        private process product program project protect provide public purpose push quality question queue quick
        quiet quite radio rain range rate reach ready reason receive recent record reduce reference refresh region
        reliable remember repeat reply report request research resource respect rest result review reward right
        role room rule safe sample save screen search season second secret section select self send service session
        share sheet short show signal similar skill sleep slow smart smile social software solid solution someone
        source special speed spend spring stand start state station stay step still story strategy street strong
        study subject success summer support sure switch table take task team technology temperature thank thing
        ticket time title touch traffic train travel treat trend true trust try type understand update useful usual
        value video view visit wait wake walk wallet watch water weather welcome window winter woman women wonder
        word work worker write wrong yesterday young yourself you're can't don't won't it's i've we're they're
    """.trimIndent().split(Regex("\\s+")).distinct()

    private val offlineHindiWords = """
        मैं हम आप तुम वह यह ये वे मेरा मेरी मेरे अपना अपने अपने आप आपका आपकी आपके है हैं हूँ हो था थी थे
        और या लेकिन क्योंकि नहीं हाँ जी क्या कैसे कहाँ कब क्यों कौन कितना कितनी कितने जब तब अब अभी आज कल
        कल सुबह शाम रात दिन समय घर काम बात पानी खाना अच्छा अच्छी अच्छे बहुत थोड़ा थोड़ी ज्यादा कम ठीक
        सही गलत नया नई नए पुराना पुरानी भारत हिंदी अंग्रेज़ी भाषा शब्द लिखना पढ़ना बोलना सुनना देखना
        करना करना है चाहिए सकता सकती सकते होगा होगी होंगे दिया लिया गया गई गए आए आया आई गए चलो धन्यवाद
        नमस्ते कृपया मदद दोस्त परिवार फोन मोबाइल कंप्यूटर किताब दुनिया लोग चीज चीज़ जगह रास्ता नाम नंबर
        जल्दी आसान सुंदर बढ़िया बेहतर अच्छा काम करता करते करती मदद चाहिए सेटिंग कुंजीपटल कीबोर्ड
    """.trimIndent().split(Regex("\\s+")).distinct()
    @Volatile private var activeSuggestionWord = ""
    private val spellCheckerListener = object : SpellCheckerSession.SpellCheckerSessionListener {
        override fun onGetSuggestions(results: Array<out SuggestionsInfo>) {
            val info = results.firstOrNull() ?: return
            val sequence = info.sequence
            val word = pendingSuggestionWords.remove(sequence) ?: return
            if (sequence != latestSuggestionRequest) return
            val candidates = mutableListOf<String>()
            for (index in 0 until minOf(info.suggestionsCount, 3)) {
                info.getSuggestionAt(index)?.takeIf { it.isNotBlank() }?.let(candidates::add)
            }
            val learnedFirst = if (offlineLanguage == "EN") {
                loadLearnedEnglishWords().entries
                    .filter { it.key.startsWith(word, ignoreCase = true) && !it.key.equals(word, ignoreCase = true) }
                    .sortedByDescending { it.value }
                    .take(3)
                    .map { matchCase(it.key, word, true) }
            } else emptyList()
            val merged = (learnedFirst + candidates + offlineSuggestions(word)).distinct().take(3)
            mainHandler.post {
                if (isOfflineKeyboard && activeSuggestionWord == word && !numberPage && !sensitiveInput && !clipboardMode && !settingsMode) {
                    displaySuggestions(merged)
                }
            }
        }
        override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>) = Unit
    }
    private var isOfflineKeyboard = false
    private var offlineLanguage = "EN"
    private var shifted = false
    private var numberPage = false
    @Volatile private var liveSession: GeminiLiveSession? = null
    @Volatile private var lastTranscriptChunk = ""
    @Volatile private var composingPrefix = ""
    private val deleteRepeater = object : Runnable {
        override fun run() {
            if (!deleteHeld) return
            deleteWord()
            mainHandler.postDelayed(this, 85L)
        }
    }
    private var deleteHeld = false
    private var deleteDownAt = 0L
    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private var gestureHandled = false

    override fun onCreateInputView(): View {
        val root = layoutInflater.inflate(R.layout.ime_voice_keyboard, null)
        toolbarExpanded = keyboardPrefs.getBoolean("keyboard_toolbar_expanded", false)
        inputRoot = root
        statusView = root.findViewById(R.id.imeStatus)
        dictateButton = root.findViewById(R.id.btnDictate)
        voicePanel = root.findViewById(R.id.voicePanel)
        offlinePanel = root.findViewById(R.id.offlinePanel)
        keyboardContainer = root.findViewById(R.id.keyboardContainer)
        clipboardContainer = root.findViewById(R.id.clipboardContainer)
        clipboardScroll = root.findViewById(R.id.clipboardScroll)
        settingsScroll = root.findViewById(R.id.settingsScroll)
        settingsContainer = root.findViewById(R.id.settingsContainer)
        settingsActions = root.findViewById(R.id.settingsActions)
        quickKeysContainer = root.findViewById(R.id.quickKeysContainer)
        languageButton = root.findViewById(R.id.btnLanguage)
        suggestionViews = listOf(root.findViewById(R.id.suggestion1), root.findViewById(R.id.suggestion2), root.findViewById(R.id.suggestion3))
        suggestionViews.forEach { candidate ->
            candidate.setOnClickListener {
                (it as? TextView)?.text?.toString()?.takeIf { text -> text.isNotBlank() }?.let(::acceptSuggestion)
            }
        }
        dictateButton?.setOnClickListener { if (recording) stopDictation() else startDictation() }
        root.findViewById<View>(R.id.btnMic).setOnClickListener { if (recording) stopDictation() else startDictation() }
        root.findViewById<View>(R.id.btnMicCompact).setOnClickListener { if (recording) stopDictation() else startDictation() }
        root.findViewById<View>(R.id.btnHideKeyboard).setOnClickListener { stopDictation(); requestHideSelf(0) }
        root.findViewById<View>(R.id.btnOfflineKeyboard).setOnClickListener {
            if (clipboardMode) showOfflineKeyboard(true) else showOfflineKeyboard(!isOfflineKeyboard)
        }
        root.findViewById<View>(R.id.btnClipboard).setOnClickListener {
            if (clipboardMode) showOfflineKeyboard(true) else showClipboardPanel()
        }
        root.findViewById<View>(R.id.btnSettings).setOnClickListener { showKeyboardSettings() }
        root.findViewById<View>(R.id.btnToggleToolbar).setOnClickListener { setToolbarExpanded(!toolbarExpanded) }
        root.findViewById<View>(R.id.btnTheme).setOnClickListener { cycleKeyboardTheme() }
        root.findViewById<View>(R.id.btnSaveKeyboardSettings).setOnClickListener { saveKeyboardSettings() }
        root.findViewById<View>(R.id.btnCancelKeyboardSettings).setOnClickListener { showOfflineKeyboard(true) }
        root.findViewById<View>(R.id.btnBackToVoice).setOnClickListener { showOfflineKeyboard(false) }
        languageButton?.setOnClickListener { switchLanguage() }
        root.findViewById<View>(R.id.btnCustomizeKeys).setOnClickListener { showKeyboardSettings() }
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (!clipboardListenerRegistered) {
            clipboardManager?.addPrimaryClipChangedListener(clipboardChangedListener)
            clipboardListenerRegistered = clipboardManager != null
        }
        // Swipe down on the header/status area hides the IME; it never steals ordinary key taps.
        listOf(root.findViewById<View>(R.id.imeHeader), root.findViewById<View>(R.id.imeStatus))
            .forEach { installHideGesture(it) }
        renderQuickKeys()
        renderOfflineKeys()
        applyKeyboardPalette(root)
        rememberClipboardItems()
        // The normal QWERTY keyboard is the default; Gemini Live remains one tap away in the toolbar.
        showOfflineKeyboard(true)
        return KeyboardSwipeDismissLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(root, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            canSwipeToHide = { !clipboardMode && !settingsMode }
            onSwipeDown = {
                stopDictation()
                requestHideSelf(0)
            }
        }
    }

    private fun startDictation() {
        if (recording) return
        val key = SecurePrefs.get(this).getString("api_key", "").orEmpty()
        if (key.isBlank()) { showStatus("Add your API key in app settings first."); return }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            showStatus("Enable microphone permission in Android settings."); return
        }
        if (currentInputConnection == null) { showStatus("Tap a text field first."); return }
        intentionalStop = false
        val sessionGeneration = ++liveGeneration
        recording = true
        geminiConnected = false
        lastTranscriptChunk = ""
        dictateButton?.text = "■  Stop dictation"
        applyToolbarIcons()
        showStatus("Connecting to live transcription…")
        val session = GeminiLiveSession(this, key, "gemini-3.5-transcribe-live",
            object : GeminiLiveSession.Callbacks {
                override fun onStatus(message: String) { if (recording && sessionGeneration == liveGeneration) showStatus(message) }
                override fun onReady() {
            mainHandler.post {
                if (!recording || sessionGeneration != liveGeneration) return@post
                geminiConnected = true
                applyToolbarIcons()
                showStatus("Gemini connected · Listening")
            }
        }
                override fun onTranscript(message: String, isAssistant: Boolean, isInterim: Boolean) {
                    if (isAssistant || !recording || sessionGeneration != liveGeneration || message.isBlank()) return
                    mainHandler.post {
                        if (!recording || sessionGeneration != liveGeneration) return@post
                        val ic = currentInputConnection ?: return@post
                        val normalized = message.trim()
                        if (normalized.isBlank()) return@post
                        if (isInterim) {
                            if (lastTranscriptChunk.isBlank()) composingPrefix = spacingPrefix(ic)
                            lastTranscriptChunk = normalized
                            ic.setComposingText(composingPrefix + normalized, 1)
                            showStatus("Listening · $normalized")
                        } else {
                            ic.commitText((if (lastTranscriptChunk.isNotBlank()) composingPrefix else spacingPrefix(ic)) + normalized, 1)
                            lastTranscriptChunk = ""
                            composingPrefix = ""
                            showStatus("Inserted · $normalized")
                        }
                    }
                }
                override fun onEnded(message: String) {
                    mainHandler.post {
                        if (sessionGeneration != liveGeneration) return@post
                        val userStopped = intentionalStop
                        currentInputConnection?.finishComposingText()
                        recording = false
                        geminiConnected = false
                        stopMicPulse()
                        liveSession = null
                        lastTranscriptChunk = ""
                        composingPrefix = ""
                        dictateButton?.text = "●  Start live dictation"
                        applyToolbarIcons()
                        showStatus(if (userStopped) "Dictation stopped" else message)
                    }
                }
            }, dictationOnly = true)
        liveSession = session
        session.connect()
    }

    private fun spacingPrefix(ic: InputConnection): String {
        val before = ic.getTextBeforeCursor(1, 0)?.lastOrNull()
        return if (before != null && !before.isWhitespace() && before !in "([{\n\r") " " else ""
    }

    private fun stopDictation() {
        if (!recording && liveSession == null) return
        intentionalStop = true
        liveGeneration += 1
        recording = false
        geminiConnected = false
        stopMicPulse()
        currentInputConnection?.finishComposingText()
        val session = liveSession
        liveSession = null
        session?.close()
        dictateButton?.text = "●  Start live dictation"
        applyToolbarIcons()
        showStatus("Disconnected from Gemini · microphone stopped")
        lastTranscriptChunk = ""
        composingPrefix = ""
    }

    private fun showOfflineKeyboard(show: Boolean) {
        isOfflineKeyboard = show
        clipboardMode = false
        settingsMode = false
        toolbarExpanded = if (show) keyboardPrefs.getBoolean("keyboard_toolbar_expanded", false) else true
        voicePanel?.visibility = if (show) View.GONE else View.VISIBLE
        offlinePanel?.visibility = if (show) View.VISIBLE else View.GONE
        keyboardContainer?.visibility = if (show) View.VISIBLE else View.GONE
        clipboardContainer?.visibility = View.GONE
        clipboardScroll?.visibility = View.GONE
        settingsScroll?.visibility = View.GONE
        settingsActions?.visibility = View.GONE
        inputRoot?.findViewById<View>(R.id.suggestionContainer)?.visibility = if (show) View.VISIBLE else View.GONE
        val toggle = inputRoot?.findViewById<TextView>(R.id.btnOfflineKeyboard)
        toggle?.contentDescription = if (show) "Switch to voice controls" else "Switch to keyboard"
        languageButton?.contentDescription = "Current language: $offlineLanguage. Tap to switch."
        if (show) {
            stopDictation()
            openSpellChecker()
            showStatus("$offlineLanguage keyboard")
            renderOfflineKeys()
            refreshSuggestions()
        } else {
            activeSuggestionWord = ""
            displaySuggestions(emptyList())
            showStatus(if (recording) "Listening · live text" else "Gemini Live voice typing")
        }
        applyToolbarExpansion()
        updateSuggestionStripVisibility()
        applyKeyboardPalette(inputRoot)
    }

    private fun showClipboardPanel() {
        if (sensitiveInput) {
            showStatus("Clipboard is unavailable in secure fields")
            return
        }
        rememberClipboardItems()
        stopDictation()
        isOfflineKeyboard = true
        clipboardMode = true
        settingsMode = false
        toolbarExpanded = true
        applyToolbarExpansion()
        voicePanel?.visibility = View.GONE
        offlinePanel?.visibility = View.VISIBLE
        keyboardContainer?.visibility = View.GONE
        inputRoot?.findViewById<View>(R.id.suggestionContainer)?.visibility = View.GONE
        settingsScroll?.visibility = View.GONE
        settingsActions?.visibility = View.GONE
        clipboardContainer?.visibility = View.VISIBLE
        clipboardScroll?.visibility = View.VISIBLE
        renderClipboardItems()
        showStatus("Clipboard")
        applyKeyboardPalette(inputRoot)
    }

    private fun showKeyboardSettings() {
        stopDictation()
        isOfflineKeyboard = true
        clipboardMode = false
        settingsMode = true
        toolbarExpanded = true
        applyToolbarExpansion()
        voicePanel?.visibility = View.GONE
        offlinePanel?.visibility = View.VISIBLE
        keyboardContainer?.visibility = View.GONE
        inputRoot?.findViewById<View>(R.id.suggestionContainer)?.visibility = View.GONE
        clipboardContainer?.visibility = View.GONE
        clipboardScroll?.visibility = View.GONE
        settingsScroll?.visibility = View.VISIBLE
        settingsActions?.visibility = View.VISIBLE
        renderSettingsPanel()
        showStatus("Keyboard settings")
        applyKeyboardPalette(inputRoot)
    }

    private fun renderSettingsPanel() {
        val container = settingsContainer ?: return
        container.removeAllViews()
        val dark = darkKeyboard()
        val foreground = if (dark) 0xFFF5F6F8.toInt() else 0xFF20232B.toInt()
        val secondary = if (dark) 0xFFB4BAC6.toInt() else 0xFF555C6A.toInt()
        val surface = if (dark) 0xFF292D36.toInt() else 0xFFFFFFFF.toInt()

        fun label(text: String, primary: Boolean = false): TextView = TextView(this).apply {
            this.text = text
            textSize = if (primary) 15f else 12f
            setTextColor(if (primary) foreground else secondary)
            if (primary) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(if (primary) 10 else 3), 0, dp(5))
        }

        container.addView(label("Appearance", true))
        val themeGroup = RadioGroup(this).apply { orientation = LinearLayout.HORIZONTAL }
        val selectedTheme = keyboardPrefs.getString("keyboard_theme", "system") ?: "system"
        listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEach { (value, caption) ->
            val radio = RadioButton(this).apply {
                id = View.generateViewId()
                tag = value
                text = caption
                textSize = 13f
                setTextColor(foreground)
                isChecked = value == selectedTheme
                layoutParams = RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            themeGroup.addView(radio)
        }
        settingsThemeGroup = themeGroup
        container.addView(themeGroup)

        val numberRow = CheckBox(this).apply {
            text = "Show number row"
            textSize = 13f
            setTextColor(foreground)
            isChecked = numberRowEnabled()
        }
        settingsNumberRow = numberRow
        container.addView(numberRow)

        val suggestions = CheckBox(this).apply {
            text = "Word suggestions"
            textSize = 13f
            setTextColor(foreground)
            isChecked = suggestionsEnabled()
        }
        settingsSuggestions = suggestions
        container.addView(suggestions)
        container.addView(label("Instant offline completions with system spelling help when available.", false))
        val autoLearn = CheckBox(this).apply {
            text = "Learn English words as I type"
            textSize = 13f
            setTextColor(foreground)
            isChecked = autoLearnEnglishEnabled()
        }
        settingsAutoLearn = autoLearn
        container.addView(autoLearn)
        val alternateHints = CheckBox(this).apply {
            text = "Show alternate symbols on keys"
            textSize = 13f
            setTextColor(foreground)
            isChecked = alternateHintsEnabled()
        }
        settingsAlternateHints = alternateHints
        container.addView(alternateHints)
        val clearLearned = TextView(this).apply {
            text = "Clear learned English words"
            textSize = 13f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setTextColor(if (dark) 0xFFFFB4AB.toInt() else 0xFF9D2933.toInt())
            background = GradientDrawable().apply {
                setColor(surface)
                cornerRadius = dp(9).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply {
                topMargin = dp(4)
                bottomMargin = dp(4)
            }
            setOnClickListener {
                clearLearnedEnglishWords()
                text = "Learned English words cleared"
            }
        }
        settingsClearLearnedButton = clearLearned
        container.addView(clearLearned)

        container.addView(label("Keyboard height", true))
        val heightLabel = label("Height: ${keyboardPrefs.getInt("keyboard_height_percent", 100).coerceIn(80, 125)}%", false)
        settingsHeightLabel = heightLabel
        container.addView(heightLabel)
        val heightSeek = SeekBar(this).apply {
            max = 45
            progress = keyboardPrefs.getInt("keyboard_height_percent", 100).coerceIn(80, 125) - 80
            progressTintList = ColorStateList.valueOf(if (dark) 0xFFA7F3D0.toInt() else 0xFF6D87C5.toInt())
            thumbTintList = progressTintList
        }
        heightSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                heightLabel.text = "Height: ${progress + 80}%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        settingsHeightSeek = heightSeek
        container.addView(heightSeek)

        container.addView(label("Quick key shortcuts", true))
        container.addView(label("Tap up to 6 symbols to add them beside Space and Enter.", false))
        settingsCustomKeyChecks = linkedMapOf()
        val existing = keyboardPrefs.getString("voice_custom_keys", "").orEmpty()
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val options = (listOf("@", "#", "₹", "&", "_", "+", "-", "=", ":", ";", "?", "!", "/", "\\", "%", "*") + existing)
            .distinct().take(22)
        options.chunked(6).forEach { optionRow ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38))
            }
            optionRow.forEach { symbol ->
                val check = CheckBox(this).apply {
                    text = symbol
                    textSize = 14f
                    setTextColor(foreground)
                    isChecked = symbol in existing
                    buttonTintList = ColorStateList.valueOf(if (dark) 0xFFA7F3D0.toInt() else 0xFF536EA3.toInt())
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    setOnCheckedChangeListener { _, checked ->
                        if (checked && settingsCustomKeyChecks.values.count { it.isChecked } > 6) {
                            isChecked = false
                            showStatus("You can select up to 6 quick keys")
                        }
                    }
                }
                settingsCustomKeyChecks[symbol] = check
                row.addView(check)
            }
            container.addView(row)
        }
    }

    private fun saveKeyboardSettings() {
        val theme = settingsThemeGroup
            ?.findViewById<RadioButton>(settingsThemeGroup?.checkedRadioButtonId ?: View.NO_ID)
            ?.tag as? String ?: "system"
        val customKeys = settingsCustomKeyChecks.filterValues { it.isChecked }.keys.take(6)
        keyboardPrefs.edit()
            .putString("keyboard_theme", theme)
            .putBoolean("keyboard_number_row", settingsNumberRow?.isChecked ?: true)
            .putBoolean("keyboard_suggestions_enabled", settingsSuggestions?.isChecked ?: true)
            .putBoolean("keyboard_autolearn_english", settingsAutoLearn?.isChecked ?: false)
            .putBoolean("keyboard_alternate_hints", settingsAlternateHints?.isChecked ?: true)
            .putInt("keyboard_height_percent", (settingsHeightSeek?.progress ?: 20) + 80)
            .putString("voice_custom_keys", customKeys.joinToString(","))
            .apply()
        if (suggestionsEnabled()) openSpellChecker() else {
            spellCheckerSession?.close()
            spellCheckerSession = null
            displaySuggestions(emptyList())
        }
        applyKeyboardPalette(inputRoot)
        renderQuickKeys()
        renderOfflineKeys()
        showOfflineKeyboard(true)
    }

    private fun cycleKeyboardTheme() {
        val current = keyboardPrefs.getString("keyboard_theme", "system") ?: "system"
        val next = when (current) {
            "system" -> "light"
            "light" -> "dark"
            else -> "system"
        }
        keyboardPrefs.edit().putString("keyboard_theme", next).apply()
        showStatus("Theme: ${next.replaceFirstChar { it.uppercase() }}")
        applyKeyboardPalette(inputRoot)
        renderQuickKeys()
        renderOfflineKeys()
        if (settingsMode) renderSettingsPanel()
        inputRoot?.findViewById<TextView>(R.id.btnTheme)?.contentDescription = "Keyboard theme: $next. Tap to cycle."
    }

    private fun switchLanguage() {
        offlineLanguage = if (offlineLanguage == "EN") "HI" else "EN"
        shifted = false
        numberPage = false
        numberOnlyPage = false
        languageButton?.contentDescription = "Current language: $offlineLanguage. Tap to switch."
        renderOfflineKeys()
        if (isOfflineKeyboard) {
            openSpellChecker()
            refreshSuggestions()
        }
    }

    private fun renderQuickKeys() {
        val container = quickKeysContainer ?: return
        container.removeAllViews()
        val custom = SecurePrefs.get(this).getString("voice_custom_keys", "").orEmpty()
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }.take(6)
        val keys = listOf("⌫", "Space", "↵", ".", ",") + custom
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; layoutParams = matchWrap() }
        keys.forEach { key ->
            val label = when (key) { "Space" -> "Space"; "↵" -> "Enter"; else -> key }
            val view = keyView(label, if (key == "Space") 1.6f else 1f, primary = key == "Space" || key == "↵") {
                when (key) {
                    "⌫", "Backspace" -> deleteOne()
                    "Space" -> commit(" ")
                    "↵", "Enter" -> enterKey()
                    else -> commit(key)
                }
            }
            if (key == "⌫" || key == "Backspace") installDeleteGesture(view)
            row.addView(view)
        }
        container.addView(row)
    }

    private fun renderOfflineKeys() {
        val container = keyboardContainer ?: return
        container.removeAllViews()
        val baseRows: List<List<String>> = when {
            numberPage && numberOnlyPage -> listOf(
                listOf("1","2","3"),
                listOf("4","5","6"),
                listOf("7","8","9"),
                listOf("#+=","0",".","⌫","↵")
            )
            numberPage -> listOf(
                listOf("1","2","3","4","5","6","7","8","9","0"),
                listOf("@","#","\$","%","&","-","+","(",")"),
                listOf("*","\"","'",";",":","!","?","⌫"),
                listOf("ABC","123","\\","Space",".",",","↵")
            )
            offlineLanguage == "EN" -> listOf(
                listOf("q","w","e","r","t","y","u","i","o","p"),
                listOf("a","s","d","f","g","h","j","k","l"),
                listOf("⇧","z","x","c","v","b","n","m","⌫"),
                listOf("?123",",","Space",".","↵")
            )
            !shifted -> listOf(
                listOf("अ","आ","इ","ई","उ","ऊ","ए","ऐ","ओ","औ"),
                listOf("क","ख","ग","घ","ङ","च","छ","ज","झ","ञ"),
                listOf("ट","ठ","ड","ढ","ण","त","थ","द","ध","न"),
                listOf("⇧","प","फ","ब","भ","म","य","र","ल","⌫"),
                listOf("?123","ं","Space","्","↵")
            )
            else -> listOf(
                listOf("ऋ","अं","अः","क्ष","त्र","ज्ञ","श्र","ड़","ढ़","फ़"),
                listOf("व","श","ष","स","ह","ळ","क़","ख़","ग़","ज़"),
                listOf("ा","ि","ी","ु","ू","ृ","े","ै","ो","ौ"),
                listOf("⇧","ं","ः","्","ँ","ॉ","ौ","ॄ","ऽ","⌫"),
                listOf("?123","।","Space","॰","↵")
            )
        }
        val rows = if (numberRowEnabled() && !numberPage) listOf(listOf("1","2","3","4","5","6","7","8","9","0")) + baseRows else baseRows
        rows.forEach { rowKeys ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(((if (numberPage) 44 else if (offlineLanguage == "HI") 39 else 41) * keyboardScale()).toInt())
                ).apply {
                    bottomMargin = dp((3 * keyboardScale()).toInt())
                    val sideInset = when {
                        numberOnlyPage || numberPage -> 0
                        rowKeys.firstOrNull() == "a" && offlineLanguage == "EN" -> dp(8)
                        rowKeys.firstOrNull() == "⇧" && offlineLanguage == "EN" -> dp(2)
                        else -> 0
                    }
                    marginStart = sideInset
                    marginEnd = sideInset
                }
            }
            rowKeys.forEach { key ->
                val weight = when (key) {
                    "Space" -> 2.65f
                    "?123","ABC","123","#+=","↵","⇧","⌫" -> 1.3f
                    else -> 1f
                }
                val label = when {
                    key == "Space" -> "space"
                    key == "⇧" && shifted && offlineLanguage == "EN" -> "⬆"
                    key == "⇧" -> "⇧"
                    key.length == 1 && offlineLanguage == "EN" && shifted && key[0].isLetter() -> key.uppercase()
                    else -> key
                }
                val button = keyView(label, weight, primary = key == "Space" || key == "↵" || (key == "⇧" && shifted)) {
                    when (key) {
                        "⌫" -> deleteOne()
                        "Space" -> commit(" ")
                        "↵" -> enterKey()
                        "⇧" -> { shifted = !shifted; renderOfflineKeys() }
                        "?123" -> { numberPage = true; numberOnlyPage = false; renderOfflineKeys() }
                        "ABC" -> { numberPage = false; numberOnlyPage = false; shifted = false; renderOfflineKeys() }
                        "123" -> { numberOnlyPage = true; renderOfflineKeys() }
                        "#+=" -> { numberOnlyPage = false; renderOfflineKeys() }
                        else -> {
                            commit(if (offlineLanguage == "EN" && shifted && key.length == 1 && key[0].isLetter()) key.uppercase() else key)
                            if (offlineLanguage == "EN" && shifted) { shifted = false; renderOfflineKeys() }
                        }
                    }
                }
                if (!numberPage && offlineLanguage == "EN" && key.length == 1 && key[0].isLetter()) {
                    alternateSymbol(key)?.let { alternate ->
                        if (alternateHintsEnabled()) {
                            val decorated = android.text.SpannableString("$label$alternate")
                            decorated.setSpan(android.text.style.RelativeSizeSpan(0.55f), label.length, label.length + alternate.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            decorated.setSpan(android.text.style.SuperscriptSpan(), label.length, label.length + alternate.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            button.text = decorated
                        }
                        button.setOnLongClickListener {
                            commit(alternate)
                            if (shifted) { shifted = false; renderOfflineKeys() }
                            true
                        }
                    }
                }
                if (key == "⌫") installDeleteGesture(button)
                else if (key == "Space") installSpaceCursorGesture(button)
                row.addView(button)
            }
            container.addView(row)
        }
        updateSuggestionStripVisibility()
        if (numberPage || !suggestionsEnabled() || sensitiveInput || clipboardMode || settingsMode) displaySuggestions(emptyList())
        else refreshSuggestions()
    }

    private fun updateSuggestionStripVisibility() {
        val strip = inputRoot?.findViewById<View>(R.id.suggestionContainer) ?: return
        val visible = isOfflineKeyboard && !clipboardMode && !settingsMode
        strip.visibility = if (visible) View.VISIBLE else View.GONE
        inputRoot?.findViewById<View>(R.id.btnToggleToolbar)?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun applyToolbarExpansion() {
        inputRoot?.findViewById<View>(R.id.imeHeader)?.visibility =
            if (toolbarExpanded || clipboardMode || settingsMode) View.VISIBLE else View.GONE
        inputRoot?.findViewById<TextView>(R.id.btnToggleToolbar)?.rotation = if (toolbarExpanded) 180f else 0f
        inputRoot?.findViewById<View>(R.id.btnMicCompact)?.visibility = if (toolbarExpanded) View.GONE else View.VISIBLE
        inputRoot?.findViewById<TextView>(R.id.btnToggleToolbar)?.contentDescription =
            if (toolbarExpanded) "Collapse keyboard toolbar" else "Show keyboard toolbar"
        applyToolbarIcons()
    }

    private fun setToolbarExpanded(expanded: Boolean, persist: Boolean = true) {
        toolbarExpanded = expanded
        if (persist) keyboardPrefs.edit().putBoolean("keyboard_toolbar_expanded", expanded).apply()
        applyToolbarExpansion()
    }

    private fun openSpellChecker() {
        if (!suggestionsEnabled() || sensitiveInput) {
            spellCheckerSession?.close()
            spellCheckerSession = null
            return
        }
        runCatching {
            spellCheckerSession?.close()
            val manager = getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE) as? TextServicesManager
            val locale = if (offlineLanguage == "HI") Locale("hi", "IN") else Locale.US
            spellCheckerSession = manager?.newSpellCheckerSession(null, locale, spellCheckerListener, true)
        }.onFailure { spellCheckerSession = null }
    }

    private fun isWordCharacter(character: Char): Boolean {
        val type = Character.getType(character)
        return character.isLetterOrDigit() ||
            character.code in 0x0900..0x097F ||
            type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt()
    }

    private fun currentWord(): String {
        val before = currentInputConnection?.getTextBeforeCursor(96, 0)?.toString().orEmpty()
        return before.takeLastWhile(::isWordCharacter)
    }

    private fun loadLearnedEnglishWords(): LinkedHashMap<String, Int> {
        learnedEnglishWordsCache?.let { return it }
        val words = linkedMapOf<String, Int>()
        val raw = keyboardPrefs.getString("keyboard_learned_english_words", "{}").orEmpty()
        runCatching { JSONObject(raw) }.getOrNull()?.let { objectValue ->
            val iterator = objectValue.keys()
            while (iterator.hasNext()) {
                val word = iterator.next().lowercase(Locale.ROOT)
                val frequency = objectValue.optInt(word, 0)
                if (word.matches(Regex("[a-z][a-z'-]{1,31}")) && frequency > 0) words[word] = frequency
            }
        }
        learnedEnglishWordsCache = words
        return words
    }

    private fun persistLearnedEnglishWords(words: Map<String, Int>) {
        val json = JSONObject()
        words.forEach { (word, frequency) -> json.put(word, frequency) }
        keyboardPrefs.edit().putString("keyboard_learned_english_words", json.toString()).apply()
        learnedEnglishWordsCache = LinkedHashMap(words)
    }

    private fun clearLearnedEnglishWords() {
        keyboardPrefs.edit().remove("keyboard_learned_english_words").apply()
        learnedEnglishWordsCache = linkedMapOf()
        refreshSuggestions()
        showStatus("Learned English words cleared")
    }

    private fun storeLearnedEnglishWord(rawWord: String) {
        if (!autoLearnEnglishEnabled() || offlineLanguage != "EN" || sensitiveInput) return
        val word = rawWord.trim().lowercase(Locale.ROOT)
        if (word.length !in 2..32 || !word.matches(Regex("[a-z][a-z'-]*"))) return
        val words = loadLearnedEnglishWords()
        words[word] = (words[word] ?: 0) + 1
        if (words.size > 500) {
            words.entries.sortedBy { it.value }.take(words.size - 500).forEach { words.remove(it.key) }
        }
        persistLearnedEnglishWords(words)
    }

    private fun learnCompletedEnglishWordIfEnabled(separator: String) {
        if (separator.none { it.isWhitespace() || it in ".,!?;:" }) return
        val before = currentInputConnection?.getTextBeforeCursor(48, 0)?.toString().orEmpty()
        val word = before.takeLastWhile { it.isLetter() || it == '-' || it.code == 39 }
        storeLearnedEnglishWord(word)
    }

    private fun oneEditAway(a: String, b: String): Boolean {
        if (kotlin.math.abs(a.length - b.length) > 1 || a == b) return false
        if (a.length == b.length) {
            val mismatch = a.indices.filter { a[it] != b[it] }
            if (mismatch.size == 1) return true
            return mismatch.size == 2 &&
                mismatch[1] == mismatch[0] + 1 &&
                a[mismatch[0]] == b[mismatch[1]] &&
                a[mismatch[1]] == b[mismatch[0]]
        }
        val shorter = if (a.length < b.length) a else b
        val longer = if (a.length < b.length) b else a
        var i = 0
        var j = 0
        var edits = 0
        while (i < shorter.length && j < longer.length) {
            if (shorter[i] == longer[j]) {
                i++
                j++
            } else {
                edits++
                j++
                if (edits > 1) return false
            }
        }
        return true
    }

    private fun offlineSuggestions(word: String): List<String> {
        if (word.isBlank()) return emptyList()
        val english = offlineLanguage == "EN"
        val normalized = if (english) word.lowercase(Locale.ROOT) else word
        val vocabulary = if (english) offlineEnglishWords else offlineHindiWords
        val learned: Map<String, Int> = if (english) loadLearnedEnglishWords() else emptyMap()
        val ranked = linkedMapOf<String, Int>()

        fun add(candidate: String, score: Int) {
            if (candidate.isBlank() || candidate.equals(word, ignoreCase = english)) return
            val currentScore = ranked[candidate]
            if (currentScore == null || score < currentScore) ranked[candidate] = score
        }

        learned.entries.asSequence()
            .filter { it.key.startsWith(normalized, ignoreCase = true) && it.key != normalized }
            .sortedByDescending { it.value }
            .take(12)
            .forEach { entry -> add(matchCase(entry.key, word, english), -1000 - entry.value) }

        vocabulary.firstOrNull { it.equals(normalized, ignoreCase = english) }?.let {
            add(matchCase(it, word, english), -500)
        }

        vocabulary.asSequence()
            .filter { if (english) it.startsWith(normalized, ignoreCase = true) else it.startsWith(normalized) }
            .take(30)
            .forEachIndexed { index, candidate ->
                val learnedCount = learned[candidate.lowercase(Locale.ROOT)] ?: 0
                add(matchCase(candidate, word, english), index - learnedCount)
            }

        // A bounded one-edit check improves likely typos without a heavyweight model or network call.
        if (english && normalized.length in 3..18) {
            vocabulary.asSequence()
                .filter { it.length in 3..18 && oneEditAway(normalized, it.lowercase(Locale.ROOT)) }
                .take(24)
                .forEach { candidate ->
                    add(matchCase(candidate, word, true), 100 - (learned[candidate.lowercase(Locale.ROOT)] ?: 0))
                }
            learned.entries.asSequence()
                .filter { it.key.length in 3..32 && oneEditAway(normalized, it.key) }
                .take(16)
                .forEach { add(matchCase(it.key, word, true), 80 - it.value) }
        }
        val result = ranked.entries.sortedBy { it.value }.map { it.key }.distinct().take(3)
        return if (result.isEmpty()) listOf(word) else result
    }

    private fun matchCase(candidate: String, typed: String, english: Boolean): String {
        return if (english && typed.firstOrNull()?.isUpperCase() == true) {
            candidate.replaceFirstChar { it.titlecase(Locale.ROOT) }
        } else candidate
    }

    private fun refreshSuggestions() {
        if (!isOfflineKeyboard || numberPage || !suggestionsEnabled() || sensitiveInput || clipboardMode || settingsMode) {
            displaySuggestions(emptyList())
            return
        }
        val word = currentWord()
        activeSuggestionWord = word
        latestSuggestionRequest += 1
        pendingSuggestionWords.clear()
        if (word.isBlank()) {
            displaySuggestions(emptyList())
            return
        }
        // Show offline completions immediately, then merge system spell-check results if available.
        displaySuggestions(offlineSuggestions(word))
        if (word.length < 2) return
        val checker = spellCheckerSession ?: return
        val sequence = latestSuggestionRequest
        pendingSuggestionWords[sequence] = word
        runCatching { checker.getSuggestions(arrayOf(TextInfo(word, 0, sequence)), 3, false) }
            .onFailure { pendingSuggestionWords.remove(sequence) }
    }

    private fun displaySuggestions(words: List<String>) {
        suggestionViews.forEachIndexed { index, candidate ->
            val suggestion = words.getOrNull(index).orEmpty()
            candidate.text = suggestion
            candidate.alpha = if (suggestion.isBlank()) 0.35f else 1f
            candidate.isEnabled = suggestion.isNotBlank()
            candidate.contentDescription = if (suggestion.isBlank()) "No suggestion" else "Use suggestion $suggestion"
        }
    }

    private fun acceptSuggestion(suggestion: String) {
        val ic = currentInputConnection ?: return
        val word = currentWord()
        if (word.isBlank()) return
        ic.deleteSurroundingText(word.length, 0)
        storeLearnedEnglishWord(suggestion)
        ic.commitText("$suggestion ", 1)
        refreshSuggestions()
    }

    private fun installHideGesture(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    gestureStartX = event.rawX; gestureStartY = event.rawY; gestureHandled = false; false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!gestureHandled && event.rawY - gestureStartY > dp(70) &&
                        abs(event.rawY - gestureStartY) > abs(event.rawX - gestureStartX)) {
                        gestureHandled = true; stopDictation(); requestHideSelf(0); true
                    } else false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { gestureHandled = false; false }
                else -> false
            }
        }
    }

    private fun installDeleteGesture(view: View) {
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    deleteHeld = false
                    gestureStartX = event.rawX; gestureStartY = event.rawY
                    deleteDownAt = android.os.SystemClock.uptimeMillis()
                    mainHandler.postDelayed({
                        if (deleteDownAt != 0L) { deleteHeld = true; deleteWord(); mainHandler.postDelayed(deleteRepeater, 140L) }
                    }, ViewConfiguration.getLongPressTimeout().toLong())
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (deleteDownAt != 0L && (abs(event.rawX - gestureStartX) > dp(36) || abs(event.rawY - gestureStartY) > dp(36))) {
                        if (!deleteHeld) { mainHandler.removeCallbacks(deleteRepeater); deleteHeld = true }
                        deleteWord()
                        true
                    } else true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val wasLong = deleteHeld
                    deleteDownAt = 0L; deleteHeld = false; mainHandler.removeCallbacks(deleteRepeater)
                    if (!wasLong && event.actionMasked == MotionEvent.ACTION_UP) deleteOne()
                    true
                }
                else -> true
            }
        }
    }

    private fun applyKeyboardPalette(view: View?) {
        val dark = darkKeyboard()
        val backgroundColor = if (dark) 0xFF202124.toInt() else 0xFFEFF1F8.toInt()
        val primaryText = if (dark) 0xFFF1F3F4.toInt() else 0xFF202124.toInt()
        val secondaryText = if (dark) 0xFFB8BBC2.toInt() else 0xFF555963.toInt()
        view?.setBackgroundColor(backgroundColor)
        statusView?.visibility = View.GONE
        statusView?.setTextColor(secondaryText)
        languageButton?.setTextColor(primaryText)
        suggestionViews.forEach { it.setTextColor(primaryText) }

        // Toolbar buttons share the keyboard surface: no separate dark slab or chunky tiles.
        listOf(
            R.id.btnLanguage, R.id.btnSettings, R.id.btnOfflineKeyboard,
            R.id.btnTheme, R.id.btnClipboard, R.id.btnMic, R.id.btnHideKeyboard
        ).forEach { id ->
            inputRoot?.findViewById<TextView>(id)?.apply {
                setTextColor(primaryText)
                val shape = GradientDrawable().apply {
                    setColor(backgroundColor)
                    cornerRadius = dp(18).toFloat()
                }
                val mask = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = dp(18).toFloat()
                }
                background = android.graphics.drawable.RippleDrawable(
                    ColorStateList.valueOf(if (dark) 0x337E8A9E.toInt() else 0x22000000),
                    shape,
                    mask
                )
                elevation = 0f
                isClickable = true
                isFocusable = true
            }
        }
        dictateButton?.apply {
            setTextColor(if (dark) 0xFFA7F3D0.toInt() else 0xFF263F69.toInt())
            background = GradientDrawable().apply {
                setColor(if (dark) 0xFF164E42.toInt() else 0xFFDCE6FF.toInt())
                cornerRadius = dp(12).toFloat()
            }
        }
        inputRoot?.findViewById<TextView>(R.id.btnCancelKeyboardSettings)?.apply {
            setTextColor(primaryText)
            background = GradientDrawable().apply { setColor(backgroundColor) }
        }
        inputRoot?.findViewById<TextView>(R.id.btnSaveKeyboardSettings)?.apply {
            setTextColor(if (dark) 0xFFA7F3D0.toInt() else 0xFF263F69.toInt())
            background = GradientDrawable().apply {
                setColor(if (dark) 0xFF164E42.toInt() else 0xFFDCE6FF.toInt())
                cornerRadius = dp(10).toFloat()
            }
        }
        applyToolbarIcons()
    }

    private fun applyToolbarIcons() {
        val tint = if (darkKeyboard()) 0xFFF1F3F4.toInt() else 0xFF343741.toInt()
        val micTint = when {
            geminiConnected -> if (darkKeyboard()) 0xFFA7F3D0.toInt() else 0xFF16834A.toInt()
            recording -> if (darkKeyboard()) 0xFFFFD180.toInt() else 0xFF9A6500.toInt()
            else -> tint
        }
        fun setIcon(id: Int, drawableId: Int, description: String, iconTint: Int = tint) {
            val button = inputRoot?.findViewById<TextView>(id) ?: return
            val drawable = androidx.core.content.ContextCompat.getDrawable(this, drawableId)?.mutate() ?: return
            androidx.core.graphics.drawable.DrawableCompat.setTint(drawable, iconTint)
            button.text = ""
            button.setCompoundDrawablesWithIntrinsicBounds(null, drawable, null, null)
            button.compoundDrawablePadding = 0
            button.gravity = Gravity.CENTER
            button.contentDescription = description
        }
        setIcon(R.id.btnLanguage, R.drawable.ic_language, "Switch language. Current language: $offlineLanguage")
        setIcon(R.id.btnSettings, R.drawable.ic_keyboard_settings, "Keyboard settings")
        setIcon(
            R.id.btnOfflineKeyboard,
            if (isOfflineKeyboard) R.drawable.ic_waveform else R.drawable.ic_keyboard,
            if (isOfflineKeyboard) "Switch to voice controls" else "Switch to keyboard"
        )
        setIcon(R.id.btnTheme, R.drawable.ic_palette, "Cycle keyboard theme")
        setIcon(R.id.btnClipboard, R.drawable.ic_clipboard, "Open clipboard history")
        setIcon(R.id.btnMic, if (recording) R.drawable.ic_stop else R.drawable.ic_mic,
            if (recording) "Stop Gemini Live dictation" else "Start Gemini Live dictation", micTint)
        setIcon(R.id.btnMicCompact, if (recording) R.drawable.ic_stop else R.drawable.ic_mic,
            if (recording) "Stop Gemini Live dictation" else "Start Gemini Live dictation", micTint)
        setIcon(R.id.btnHideKeyboard, R.drawable.ic_chevron_down, "Hide keyboard")
        setIcon(R.id.btnToggleToolbar, R.drawable.ic_chevron_down,
            if (toolbarExpanded) "Collapse keyboard toolbar" else "Show keyboard toolbar")
        inputRoot?.findViewById<View>(R.id.btnToggleToolbar)?.rotation = if (toolbarExpanded) 180f else 0f
        refreshMicIndicator()
    }

    private fun refreshMicIndicator() {
        val micViews = listOfNotNull(
            inputRoot?.findViewById<TextView>(R.id.btnMic),
            inputRoot?.findViewById<TextView>(R.id.btnMicCompact)
        )
        if (recording && geminiConnected) {
            micViews.forEach { mic ->
                mic.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (darkKeyboard()) 0xFF164E42.toInt() else 0xFFD7F4E3.toInt())
                }
            }
            val target = inputRoot?.findViewById<View>(
                if (toolbarExpanded) R.id.btnMic else R.id.btnMicCompact
            )
            if (target != null && (micPulseTarget !== target || micPulseAnimator?.isRunning != true)) {
                stopMicPulse()
                micPulseTarget = target
                micPulseAnimator = ObjectAnimator.ofFloat(target, View.ALPHA, 0.55f, 1f).apply {
                    duration = 720L
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.REVERSE
                    start()
                }
            }
        } else {
            stopMicPulse()
            micViews.forEach { mic ->
                mic.alpha = 1f
                mic.background = GradientDrawable().apply {
                    setColor(if (darkKeyboard()) 0xFF202124.toInt() else 0xFFEFF1F8.toInt())
                    cornerRadius = dp(18).toFloat()
                }
            }
        }
    }

    private fun stopMicPulse() {
        micPulseAnimator?.cancel()
        micPulseAnimator = null
        micPulseTarget = null
        inputRoot?.findViewById<View>(R.id.btnMic)?.alpha = 1f
        inputRoot?.findViewById<View>(R.id.btnMicCompact)?.alpha = 1f
    }

    private fun loadClipboardEntries(): MutableList<ClipboardEntry> {
        val raw = keyboardPrefs.getString("keyboard_clipboard_items", "[]").orEmpty()
        val array = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        val result = mutableListOf<ClipboardEntry>()
        for (index in 0 until array.length()) {
            val item = runCatching { array.getJSONObject(index) }.getOrNull() ?: continue
            result.add(
                ClipboardEntry(
                    id = item.optString("id"),
                    text = item.optString("text").takeIf { it.isNotEmpty() },
                    uri = item.optString("uri").takeIf { it.isNotEmpty() },
                    mimeType = item.optString("mimeType").takeIf { it.isNotEmpty() },
                    pinned = item.optBoolean("pinned", false),
                    sourceUri = item.optString("sourceUri").takeIf { it.isNotEmpty() }
                )
            )
        }
        return result
    }

    private fun saveClipboardEntries(entries: List<ClipboardEntry>) {
        val array = JSONArray()
        entries.forEach { item ->
            array.put(JSONObject()
                .put("id", item.id)
                .put("text", item.text ?: "")
                .put("uri", item.uri ?: "")
                .put("mimeType", item.mimeType ?: "")
                .put("pinned", item.pinned)
                .put("sourceUri", item.sourceUri ?: ""))
        }
        keyboardPrefs.edit().putString("keyboard_clipboard_items", array.toString()).apply()
        val directory = File(filesDir, "keyboard_clips")
        if (directory.exists()) {
            val retained = entries.mapNotNull { entry ->
                val uri = entry.uri?.let { Uri.parse(it) } ?: return@mapNotNull null
                if (uri.authority == "$packageName.keyboardfiles") uri.lastPathSegment else null
            }.toSet()
            directory.listFiles()?.forEach { file -> if (file.name !in retained) file.delete() }
        }
    }

    private val clipboardChangedListener = ClipboardManager.OnPrimaryClipChangedListener {
        mainHandler.post {
            if (!sensitiveInput) {
                rememberClipboardItems()
                if (clipboardMode) renderClipboardItems()
            }
        }
    }

    private fun rememberClipboardItems() {
        if (sensitiveInput) return
        val clip = runCatching { (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip }.getOrNull() ?: return
        val entries = loadClipboardEntries()
        val added = mutableListOf<ClipboardEntry>()
        val mimeTypes = (0 until clip.description.mimeTypeCount).map { clip.description.getMimeType(it) }
        val imageMime = mimeTypes.firstOrNull { it.startsWith("image/") }
        for (i in 0 until minOf(clip.itemCount, 5)) {
            val item = clip.getItemAt(i)
            // Some image clips also contain a filename/label as text; prioritize the image URI.
            val source = item.uri
            val mime = if (source != null) {
                runCatching { contentResolver.getType(source) }.getOrNull() ?: imageMime
            } else null
            if (source != null && mime?.startsWith("image/") == true) {
                val sourceString = source.toString()
                val previous = entries.firstOrNull { it.sourceUri == sourceString }
                val savedUri = previous?.uri ?: cacheClipboardImage(source, mime)?.toString() ?: sourceString
                added.add(ClipboardEntry(
                    id = "image:" + sourceString.hashCode().toString(),
                    uri = savedUri,
                    mimeType = mime,
                    pinned = previous?.pinned ?: false,
                    sourceUri = sourceString
                ))
                continue
            }
            val text = runCatching { item.text?.toString() }.getOrNull()?.takeIf { it.isNotBlank() }
            if (text != null) {
                val id = "text:" + text.hashCode().toString()
                val previous = entries.firstOrNull { it.id == id }
                added.add(ClipboardEntry(id = id, text = text.take(12000), pinned = previous?.pinned ?: false))
            }
        }
        if (added.isEmpty()) return
        val combined = (added + entries.filterNot { old -> added.any { it.id == old.id } }).distinctBy { it.id }
        val pinned = combined.filter { it.pinned }
        val recent = combined.filterNot { it.pinned }.take(20)
        saveClipboardEntries((pinned + recent).take(40))
    }

    private fun cacheClipboardImage(source: Uri, mimeType: String): Uri? {
        return runCatching {
            // Keep stored clipboard images across app restarts; removing a clip deletes its file.
            val directory = File(filesDir, "keyboard_clips").apply { mkdirs() }
            val extension = when (mimeType.substringAfter("/", "")) {
                "jpeg" -> ".jpg"
                "png" -> ".png"
                "webp" -> ".webp"
                "gif" -> ".gif"
                else -> ".img"
            }
            val file = File(directory, "clip_${System.currentTimeMillis()}$extension")
            contentResolver.openInputStream(source)?.use { input ->
                FileOutputStream(file).use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count <= 0) break
                        total += count
                        if (total > 12 * 1024 * 1024) throw IllegalStateException("Clipboard image is larger than 12 MB")
                        output.write(buffer, 0, count)
                    }
                }
            } ?: return null
            FileProvider.getUriForFile(this, "$packageName.keyboardfiles", file)
        }.getOrNull()
    }

    private fun renderClipboardItems() {
        val host = clipboardContainer ?: return
        host.removeAllViews()
        val entries = loadClipboardEntries()
        if (entries.isEmpty()) {
            val empty = TextView(this).apply {
                text = "Copy text or an image, then open Clipboard."
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(if (darkKeyboard()) 0xFFB4BAC6.toInt() else 0xFF555C6A.toInt())
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(100))
            }
            host.addView(empty)
            return
        }
        entries.forEach { entry ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(4), dp(4), dp(4))
                background = GradientDrawable().apply {
                    setColor(if (darkKeyboard()) 0xFF292D36.toInt() else 0xFFFFFFFF.toInt())
                    cornerRadius = dp(10).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply {
                    bottomMargin = dp(5)
                }
            }
            if (entry.uri != null) {
                val thumbnail = android.widget.ImageView(this).apply {
                    contentDescription = "Clipboard image preview"
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                        marginEnd = dp(8)
                    }
                    runCatching { setImageURI(Uri.parse(entry.uri)) }
                    setOnClickListener { pasteClipboardEntry(entry) }
                }
                row.addView(thumbnail)
            }
            val label = TextView(this).apply {
                text = entry.text?.replace("\n", " ") ?: "Image · tap to paste"
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                textSize = 13f
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(if (darkKeyboard()) 0xFFF5F6F8.toInt() else 0xFF20232B.toInt())
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                setOnClickListener { pasteClipboardEntry(entry) }
            }
            val pin = TextView(this).apply {
                text = if (entry.pinned) "★" else "☆"
                textSize = 19f
                gravity = Gravity.CENTER
                setTextColor(if (darkKeyboard()) 0xFFA7F3D0.toInt() else 0xFF425A8A.toInt())
                layoutParams = LinearLayout.LayoutParams(dp(36), ViewGroup.LayoutParams.MATCH_PARENT)
                contentDescription = if (entry.pinned) "Unpin clipboard item" else "Pin clipboard item"
                setOnClickListener {
                    val current = loadClipboardEntries()
                    saveClipboardEntries(current.map { if (it.id == entry.id) it.copy(pinned = !it.pinned) else it })
                    renderClipboardItems()
                }
            }
            val remove = TextView(this).apply {
                text = "×"
                textSize = 21f
                gravity = Gravity.CENTER
                setTextColor(if (darkKeyboard()) 0xFFFFB4AB.toInt() else 0xFF9D2933.toInt())
                layoutParams = LinearLayout.LayoutParams(dp(32), ViewGroup.LayoutParams.MATCH_PARENT)
                contentDescription = "Remove clipboard item"
                setOnClickListener {
                    saveClipboardEntries(loadClipboardEntries().filterNot { it.id == entry.id })
                    renderClipboardItems()
                }
            }
            row.addView(label)
            row.addView(pin)
            row.addView(remove)
            host.addView(row)
        }
    }

    private fun pasteClipboardEntry(entry: ClipboardEntry) {
        val connection = currentInputConnection ?: return
        if (entry.text != null) {
            connection.commitText(entry.text, 1)
            showStatus("Pasted text")
            showOfflineKeyboard(true)
            return
        }
        val rawUri = entry.uri ?: return
        val uri = Uri.parse(rawUri)
        val mime = entry.mimeType ?: runCatching { contentResolver.getType(uri) }.getOrNull() ?: "image/*"
        val content = InputContentInfo(uri, ClipDescription("Clipboard image", arrayOf(mime)), null)
        val accepted = runCatching {
            connection.commitContent(content, InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)
        }.getOrDefault(false)
        if (accepted) {
            showStatus("Pasted image")
            showOfflineKeyboard(true)
        } else showStatus("This text field cannot accept images")
    }

    private fun alternateSymbol(key: String): String? = when (key.lowercase(Locale.ROOT)) {
        "q" -> "%"
        "w" -> "\\"
        "e" -> "|"
        "r" -> "="
        "t" -> "["
        "y" -> "]"
        "u" -> "<"
        "i" -> ">"
        "o" -> "{"
        "p" -> "}"
        "a" -> "@"
        "s" -> "#"
        "d" -> "\$"
        "f" -> "-"
        "g" -> "&"
        "h" -> "_"
        "j" -> "+"
        "k" -> "("
        "l" -> ")"
        "z" -> "*"
        "x" -> "\""
        "c" -> "'"
        "v" -> ":"
        "b" -> ";"
        "n" -> "!"
        "m" -> "?"
        else -> null
    }

    private fun installSpaceCursorGesture(view: View) {
        var startX = 0f
        var startY = 0f
        var moved = false
        var lastSteps = 0
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    moved = false
                    lastSteps = 0
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    if (abs(dx) > dp(18) && abs(dx) > abs(event.rawY - startY)) {
                        moved = true
                        val steps = (dx / dp(28).coerceAtLeast(1)).toInt()
                        val delta = steps - lastSteps
                        if (delta != 0) {
                            val keyCode = if (delta < 0) android.view.KeyEvent.KEYCODE_DPAD_LEFT else android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                            repeat(abs(delta).coerceAtMost(8)) { sendDownUpKeyEvents(keyCode) }
                            lastSteps = steps
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) commit(" ")
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> true
            }
        }
    }

    private fun customizeKeys() = showKeyboardSettings()

    private fun keyView(label: String, weight: Float = 1f, primary: Boolean = false, action: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = when {
                label.length > 5 -> 11f
                label.length > 2 -> 13f
                isOfflineKeyboard && label.length == 1 -> 23f
                isOfflineKeyboard -> 19f
                else -> 17f
            }
            val dark = darkKeyboard()
            val fill = when {
                primary && dark -> 0xFFBDEDDD.toInt()
                primary -> 0xFFDCE6FF.toInt()
                dark -> 0xFF303641.toInt()
                else -> 0xFFFFFFFF.toInt()
            }
            val foreground = when {
                primary && dark -> 0xFF202B34.toInt()
                primary -> 0xFF26344D.toInt()
                dark -> Color.WHITE
                else -> 0xFF20232B.toInt()
            }
            setTextColor(foreground)
            val radius = dp(if (dark) 7 else 8).toFloat()
            val keyShape = GradientDrawable().apply {
                setColor(fill)
                cornerRadius = radius
                setStroke(dp(1), when {
                    primary && dark -> 0xFFBDEDDD.toInt()
                    primary -> 0xFFDCE6FF.toInt()
                    dark -> 0xFF454D58.toInt()
                    else -> 0xFFE4E7EF.toInt()
                })
            }
            val rippleMask = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = radius
            }
            background = android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(if (dark) 0x337E8A9E.toInt() else 0x22000000),
                keyShape,
                rippleMask
            )
            elevation = dp(1).toFloat()
            isClickable = true; isFocusable = true
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> view.animate().cancel().scaleX(0.965f).scaleY(0.965f).setDuration(45L).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(90L).start()
                }
                false
            }
            setOnClickListener { action() }
            val keyHeight = when {
                !isOfflineKeyboard -> 36
                numberPage -> 42
                offlineLanguage == "HI" -> 36
                else -> 38
            }
            layoutParams = LinearLayout.LayoutParams(0, dp((keyHeight * keyboardScale()).toInt()), weight).apply { marginStart = dp(1); marginEnd = dp(1) }
        }

    private fun commit(text: String) {
        learnCompletedEnglishWordIfEnabled(text)
        currentInputConnection?.commitText(text, 1)
        refreshSuggestions()
    }
    private fun deleteOne() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) ic.commitText("", 1)
        else {
            val before = ic.getTextBeforeCursor(2, 0)
            if (!before.isNullOrEmpty() && before.length >= 2 && Character.isLowSurrogate(before.last()) && Character.isHighSurrogate(before[before.length - 2])) ic.deleteSurroundingText(2, 0)
            else ic.deleteSurroundingText(1, 0)
        }
        refreshSuggestions()
    }
    private fun deleteWord() {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(80, 0)?.toString().orEmpty()
        if (before.isEmpty()) { deleteOne(); return }
        val trimmed = before.dropLastWhile { it.isWhitespace() }
        val word = trimmed.takeLastWhile { !it.isWhitespace() }
        val count = (word.length + (before.length - trimmed.length)).coerceAtLeast(1)
        ic.deleteSurroundingText(count, 0)
        refreshSuggestions()
    }
    private fun enterKey() {
        learnCompletedEnglishWordIfEnabled("\n")
        if (!sendDefaultEditorAction(true)) sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_ENTER)
    }
    private fun installDummy() = Unit
    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun showStatus(message: String) {
        mainHandler.post {
            statusView?.text = message
            statusView?.contentDescription = message
            inputRoot?.findViewById<TextView>(R.id.btnMic)?.let { mic ->
                if (!recording) mic.contentDescription = if (isOfflineKeyboard) "Start Gemini Live dictation" else "Start Gemini Live dictation from keyboard"
            }
        }
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val inputType = info?.inputType ?: 0
        val inputClass = inputType and android.text.InputType.TYPE_MASK_CLASS
        val variation = inputType and android.text.InputType.TYPE_MASK_VARIATION
        sensitiveInput =
            (inputClass == android.text.InputType.TYPE_CLASS_TEXT &&
                variation in setOf(
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,
                    android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                    android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                )) ||
            (inputClass == android.text.InputType.TYPE_CLASS_NUMBER &&
                variation == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD) ||
            ((info?.imeOptions ?: 0) and android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        if (sensitiveInput && clipboardMode) showOfflineKeyboard(true)
        val shouldShowNumbers = inputClass == android.text.InputType.TYPE_CLASS_NUMBER ||
            inputClass == android.text.InputType.TYPE_CLASS_PHONE
        if (numberPage != shouldShowNumbers) {
            numberPage = shouldShowNumbers
            numberOnlyPage = shouldShowNumbers
            if (isOfflineKeyboard && !clipboardMode && !settingsMode) renderOfflineKeys()
        }
        inputRoot?.findViewById<View>(R.id.suggestionContainer)?.visibility =
            if (isOfflineKeyboard && suggestionsEnabled() && !numberPage && !sensitiveInput && !clipboardMode) View.VISIBLE else View.GONE
        if (!sensitiveInput) rememberClipboardItems()
        if (isOfflineKeyboard) {
            openSpellChecker()
            refreshSuggestions()
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        stopDictation()
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        deleteDownAt = 0L
        deleteHeld = false
        mainHandler.removeCallbacks(deleteRepeater)
        stopDictation()
        stopMicPulse()
        clipboardManager?.let { manager ->
            if (clipboardListenerRegistered) manager.removePrimaryClipChangedListener(clipboardChangedListener)
        }
        clipboardListenerRegistered = false
        clipboardManager = null
        spellCheckerSession?.close()
        spellCheckerSession = null
        super.onDestroy()
    }
}


/**
 * Intercepts a deliberate downward swipe anywhere on the keyboard, while cancelling the child
 * touch first so long-press delete and spacebar gestures do not remain stuck.
 */
private class KeyboardSwipeDismissLayout(context: Context) : LinearLayout(context) {
    var canSwipeToHide: (() -> Boolean)? = null
    var onSwipeDown: (() -> Unit)? = null
    private var downX = 0f
    private var downY = 0f
    private var dismissing = false
    private val swipeThreshold = (70 * resources.displayMetrics.density).toInt()

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (dismissing) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                dismissing = false
            }
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaY = event.rawY - downY
                val deltaX = abs(event.rawX - downX)
                if ((canSwipeToHide?.invoke() ?: true) &&
                    deltaY > swipeThreshold && deltaY > deltaX * 1.15f) {
                    val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                    dismissing = true
                    onSwipeDown?.invoke()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                downX = 0f
                downY = 0f
            }
        }
        return super.dispatchTouchEvent(event)
    }
}
