package com.sdk.glassessdksample.ui.web

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sdk.glassessdksample.databinding.ActivityWebBrowserBinding
import com.sdk.glassessdksample.ui.QuickNote
import com.sdk.glassessdksample.ui.QuickNotesManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.sdk.glassessdksample.utils.SystemBarsInsets

/**
 * The **Web** section — a full browser that lives inside the app, which the
 * user can drive by hand, by typed command, or by voice.
 *
 * The session is persistent (see [WebSessionManager]), so a site the user logs
 * into stays logged in across app restarts. That is what makes the rest work:
 * the agent never handles credentials, it just reuses a session the user
 * established themselves.
 *
 * A command entered here is routed by [CommandRouter] to one of three things:
 * summarise the current page, open an AI service and read the user's work back
 * to them, or hand the goal to [WebAgentSession] to act on.
 */
class WebBrowserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWebBrowserBinding

    /** True while a page load is in flight — flips the reload button to stop. */
    private var isLoading = false

    /** Desktop UA gives a richer DOM, which the agent prefers. */
    private var desktopMode = false

    /** Created lazily on the first AI command. */
    private var agent: WebAgentSession? = null

    /** True while the command box is collecting an answer to an agent question. */
    private var awaitingAnswer = false

    /** True while the agent is parked waiting for the user's manual step. */
    private var awaitingContinue = false

    /** Speech-to-text for the command bar. Created on first mic tap. */
    private var voice: VoiceInputController? = null

    /** Text-to-speech for the agent's questions and results. */
    private var speaker: AgentSpeaker? = null

    /** Summarises pages and AI-service history. */
    private val summarizer by lazy { PageSummarizer(this) }

    /** Tracks the in-flight summary so a new request replaces it. */
    private var summarizeJob: Job? = null

    /** The most recent summary, kept so it can be re-saved or re-read. */
    private var lastSummary: PageSummarizer.Summary? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBarsInsets.apply(this)

        setupWebView()
        setupAddressBar()
        setupControls()
        setupCommandBar()
        setupBackHandling()

        speaker = AgentSpeaker(this).apply { enabled = speakRepliesEnabled() }

        // Restore the last page across launches so the section feels continuous.
        val startUrl = intent.getStringExtra(EXTRA_URL) ?: lastUrl()
        if (!startUrl.isNullOrBlank()) {
            loadUrl(startUrl)
        }
    }

    // ---------------------------------------------------------------- WebView

    private fun setupWebView() {
        WebSessionManager.configure(binding.webView, desktopMode)

        binding.webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                // Non-http schemes (mailto:, intent:, upi:) would crash the
                // WebView. Keep them out; the browser only handles web pages.
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    Toast.makeText(
                        this@WebBrowserActivity,
                        "This link opens outside the browser and was blocked.",
                        Toast.LENGTH_SHORT
                    ).show()
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                isLoading = true
                binding.layoutStartScreen.visibility = View.GONE
                binding.progressWeb.visibility = View.VISIBLE
                binding.btnReloadOrStop.setImageResource(
                    com.sdk.glassessdksample.R.drawable.ic_close
                )
                setAddressText(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                isLoading = false
                binding.progressWeb.visibility = View.INVISIBLE
                binding.btnReloadOrStop.setImageResource(
                    com.sdk.glassessdksample.R.drawable.ic_refresh
                )
                setAddressText(url)
                binding.tvPageTitle.text = view?.title?.takeIf { it.isNotBlank() }
                    ?: WebSessionManager.displayHost(url)
                updateNavButtons()

                // Persist both the cookies and the current page.
                WebSessionManager.persist()
                if (!url.isNullOrBlank() && url != "about:blank") saveLastUrl(url)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                // Only surface failures of the main document; a failed tracker
                // pixel is not something the user needs to hear about.
                if (request?.isForMainFrame == true) {
                    Toast.makeText(
                        this@WebBrowserActivity,
                        "Couldn't load this page. Check your connection.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.progressWeb.progress = newProgress
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!title.isNullOrBlank()) binding.tvPageTitle.text = title
            }
        }

        binding.webView.setDownloadListener { url, _, _, _, _ ->
            // Downloads are out of scope for now — tell the user plainly
            // rather than silently doing nothing.
            Toast.makeText(this, "Downloads aren't supported yet.", Toast.LENGTH_SHORT).show()
            android.util.Log.d(TAG, "Download request ignored: $url")
        }
    }

    // ------------------------------------------------------------ address bar

    private fun setupAddressBar() {
        binding.etAddress.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                submitAddress()
                true
            } else {
                false
            }
        }

        binding.btnReloadOrStop.setOnClickListener {
            if (isLoading) binding.webView.stopLoading() else binding.webView.reload()
        }
    }

    private fun submitAddress() {
        val input = binding.etAddress.text?.toString().orEmpty()
        if (input.isBlank()) return
        hideKeyboard()
        binding.etAddress.clearFocus()
        loadUrl(WebSessionManager.toUrlOrSearch(input))
    }

    /** Shows the friendly host while idle, the full URL while editing. */
    private fun setAddressText(url: String?) {
        if (binding.etAddress.hasFocus()) return
        binding.etAddress.setText(url.orEmpty())
    }

    // --------------------------------------------------------------- controls

    private fun setupControls() {
        binding.btnCloseBrowser.setOnClickListener { finish() }

        binding.btnBack.setOnClickListener {
            if (binding.webView.canGoBack()) binding.webView.goBack()
        }
        binding.btnForward.setOnClickListener {
            if (binding.webView.canGoForward()) binding.webView.goForward()
        }
        binding.btnHome.setOnClickListener { showStartScreen() }

        binding.btnCommand.setOnClickListener { toggleCommandBar() }

        binding.chipGoogle.setOnClickListener { loadUrl("https://www.google.com") }
        binding.chipYoutube.setOnClickListener { loadUrl("https://m.youtube.com") }
        binding.chipGemini.setOnClickListener { loadUrl(AiService.GEMINI.homeUrl) }
        binding.chipClaude.setOnClickListener { loadUrl(AiService.CLAUDE.homeUrl) }
        binding.chipChatGpt.setOnClickListener { loadUrl(AiService.CHATGPT.homeUrl) }

        binding.btnBrowserMenu.setOnClickListener { showMenu() }

        updateNavButtons()
    }

    private fun showMenu() {
        val menu = PopupMenu(this, binding.btnBrowserMenu)
        menu.menu.add(0, MENU_SUMMARIZE, 0, "Summarise this page")
        if (lastSummary != null) {
            menu.menu.add(0, MENU_LAST_SUMMARY, 0, "Show last summary")
        }
        menu.menu.add(0, MENU_DESKTOP, 0, if (desktopMode) "Mobile site" else "Desktop site")
        menu.menu.add(0, MENU_SPEAK, 1, if (speakRepliesEnabled()) "Mute replies" else "Speak replies")
        menu.menu.add(0, MENU_AUTO_RUN, 2, if (autoRunVoice()) "Review voice commands" else "Run voice commands instantly")
        menu.menu.add(0, MENU_CLEAR, 3, "Clear browsing data")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_DESKTOP -> {
                    desktopMode = !desktopMode
                    WebSessionManager.configure(binding.webView, desktopMode)
                    binding.webView.reload()
                    true
                }
                MENU_SUMMARIZE -> {
                    summarizeCurrentPage(PageSummarizer.Style.GENERAL, null)
                    true
                }

                MENU_LAST_SUMMARY -> {
                    lastSummary?.let { showSummaryDialog(it) }
                    true
                }

                MENU_SPEAK -> {
                    val next = !speakRepliesEnabled()
                    prefs().edit().putBoolean(KEY_SPEAK, next).apply()
                    speaker?.enabled = next
                    if (!next) speaker?.stop()
                    Toast.makeText(
                        this,
                        if (next) "I'll read replies aloud." else "Replies muted.",
                        Toast.LENGTH_SHORT
                    ).show()
                    true
                }

                MENU_AUTO_RUN -> {
                    val next = !autoRunVoice()
                    prefs().edit().putBoolean(KEY_AUTO_RUN, next).apply()
                    Toast.makeText(
                        this,
                        if (next) "Spoken commands will run straight away."
                        else "Spoken commands wait for you to tap send.",
                        Toast.LENGTH_SHORT
                    ).show()
                    true
                }

                MENU_CLEAR -> {
                    confirmClearSession()
                    true
                }
                else -> false
            }
        }
        menu.show()
    }

    /**
     * Clearing wipes saved logins, so it needs a confirmation — losing a
     * session the user set up by hand is exactly the kind of thing this
     * feature promises not to do by accident.
     */
    private fun confirmClearSession() {
        AlertDialog.Builder(this)
            .setTitle("Clear browsing data?")
            .setMessage("This signs you out of every site you logged into here and clears cookies, storage and history.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ ->
                WebSessionManager.clearSession(this, binding.webView)
                saveLastUrl(null)
                showStartScreen()
                Toast.makeText(this, "Browsing data cleared.", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showStartScreen() {
        binding.webView.loadUrl("about:blank")
        binding.layoutStartScreen.visibility = View.VISIBLE
        binding.tvPageTitle.text = "Web"
        binding.etAddress.setText("")
        updateNavButtons()
    }

    private fun updateNavButtons() {
        binding.btnBack.alpha = if (binding.webView.canGoBack()) 1f else 0.35f
        binding.btnForward.alpha = if (binding.webView.canGoForward()) 1f else 0.35f
    }

    private fun loadUrl(url: String) {
        binding.layoutStartScreen.visibility = View.GONE
        binding.webView.loadUrl(url)
    }

    // ------------------------------------------------------------ AI commands

    private fun setupCommandBar() {
        binding.etCommand.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitCommand()
                true
            } else {
                false
            }
        }
        binding.btnSendCommand.setOnClickListener { submitCommand() }
        binding.btnAgentStop.setOnClickListener { agent?.stop() }
        binding.btnAgentContinue.setOnClickListener { onContinueTapped() }
        binding.btnMic.setOnClickListener { toggleVoiceInput() }
        binding.btnHistory.setOnClickListener { showCommandHistory() }
        binding.btnMic.alpha = 0.75f
    }

    // ---------------------------------------------------------------- voice

    private fun toggleVoiceInput() {
        val controller = voice ?: VoiceInputController(this, voiceListener).also { voice = it }
        if (controller.isListening) controller.stop() else controller.start()
    }

    private val voiceListener = object : VoiceInputController.Listener {

        override fun onListeningChanged(listening: Boolean) {
            // The mic dims while idle and goes full accent while hearing you.
            binding.btnMic.alpha = if (listening) 1f else 0.75f
            binding.etCommand.hint = when {
                listening -> "Listening…"
                awaitingAnswer -> "Type your answer"
                else -> "Tell me what to do on this page"
            }
            if (listening) {
                // Never listen and talk at once — the mic would hear the TTS.
                speaker?.stop()
            }
        }

        override fun onPartial(text: String) {
            binding.etCommand.setText(text)
            binding.etCommand.setSelection(binding.etCommand.text.length)
        }

        override fun onResult(text: String) {
            binding.etCommand.setText(text)
            binding.etCommand.setSelection(binding.etCommand.text.length)

            // An answer to the agent's own question is unambiguous, so it goes
            // straight through. A new goal is left for the user to check first
            // unless they've opted into hands-free — mis-heard speech should
            // not set an agent loose on a live page.
            if (awaitingAnswer || autoRunVoice()) {
                submitCommand()
            } else {
                Toast.makeText(
                    this@WebBrowserActivity,
                    "Check the command, then tap send.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        override fun onError(message: String) {
            Toast.makeText(this@WebBrowserActivity, message, Toast.LENGTH_SHORT).show()
        }

        override fun onFallbackToSystemDialog(intent: android.content.Intent) {
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(intent, VoiceInputController.REQ_SYSTEM_SPEECH)
            } catch (_: Exception) {
                Toast.makeText(
                    this@WebBrowserActivity,
                    "Voice input isn't available on this device.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    @Deprecated("Matches the app's existing speech-dialog pattern")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != VoiceInputController.REQ_SYSTEM_SPEECH) return
        if (resultCode != RESULT_OK) return

        val text = data
            ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            .orEmpty()
        if (text.isBlank()) return

        binding.etCommand.setText(text)
        binding.etCommand.setSelection(binding.etCommand.text.length)
        if (awaitingAnswer || autoRunVoice()) submitCommand()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != VoiceInputController.REQ_AUDIO) return

        val granted = grantResults.firstOrNull() ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) {
            voice?.start()
        } else {
            Toast.makeText(
                this,
                "Voice commands need microphone access. You can still type.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // -------------------------------------------------------- command history

    private fun showCommandHistory() {
        val history = WebCommandHistory.recent(this)
        if (history.isEmpty()) {
            Toast.makeText(this, "No commands yet.", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Recent commands")
            .setItems(history.toTypedArray()) { _, which ->
                binding.etCommand.setText(history[which])
                binding.etCommand.setSelection(binding.etCommand.text.length)
            }
            .setNeutralButton("Clear") { _, _ -> WebCommandHistory.clear(this) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun toggleCommandBar() {
        val showing = binding.layoutCommandBar.visibility == View.VISIBLE
        binding.layoutCommandBar.visibility = if (showing) View.GONE else View.VISIBLE
        if (!showing) {
            binding.etCommand.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(binding.etCommand, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun submitCommand() {
        val command = binding.etCommand.text?.toString()?.trim().orEmpty()
        if (command.isEmpty()) return

        // A question the agent asked takes priority: this input is the answer,
        // not a new goal.
        val session = agent
        if (session != null && awaitingAnswer) {
            awaitingAnswer = false
            binding.etCommand.setText("")
            hideCommandKeyboard()
            session.provideAnswer(command)
            return
        }

        // Reading a page back to the user doesn't touch it, so it skips the
        // agent consent gate — that gate is about acting on the user's behalf.
        when (val route = CommandRouter.route(command)) {
            is CommandRouter.Route.SummarizeCurrent -> {
                binding.etCommand.setText("")
                hideCommandKeyboard()
                WebCommandHistory.add(this, command)
                summarizeCurrentPage(route.style, route.question)
                return
            }

            is CommandRouter.Route.CatchUpOnService -> {
                binding.etCommand.setText("")
                hideCommandKeyboard()
                WebCommandHistory.add(this, command)
                catchUpOnService(route.service, route.question)
                return
            }

            CommandRouter.Route.Agent -> Unit
        }

        if (!consentGiven()) {
            showConsentDialog { startAgent(command) }
            return
        }
        startAgent(command)
    }

    // ----------------------------------------------------------- summarising

    /** Reads the page currently on screen and speaks/shows a summary. */
    private fun summarizeCurrentPage(
        style: PageSummarizer.Style,
        question: String?
    ) {
        if (binding.layoutStartScreen.visibility == View.VISIBLE) {
            Toast.makeText(this, "Open a page first.", Toast.LENGTH_SHORT).show()
            return
        }

        summarizeJob?.cancel()
        summarizeJob = lifecycleScope.launch {
            showAgentStrip("Reading this page…", showStop = false, showContinue = false)

            val content = PageContentExtractor.extract(binding.webView)
            if (!content.ok || content.isEmpty) {
                finishSummary(null, "There's no readable text on this page.")
                return@launch
            }

            showAgentStrip("Summarising…", showStop = false, showContinue = false)
            val summary = summarizer.summarize(content, style, question)

            if (summary == null) {
                finishSummary(null, "I couldn't summarise this page.")
            } else {
                finishSummary(summary, summary.body)
            }
        }
    }

    /**
     * Opens an AI service and summarises what's there.
     *
     * The user logs into the service once inside this browser; from then on the
     * same account shows the same server-side history their computer does.
     */
    private fun catchUpOnService(service: AiService, question: String?) {
        summarizeJob?.cancel()
        summarizeJob = lifecycleScope.launch {
            showAgentStrip("Opening ${service.displayName}…", showStop = false, showContinue = false)

            binding.layoutStartScreen.visibility = View.GONE
            binding.webView.loadUrl(service.homeUrl)
            delay(SERVICE_LOAD_MS)

            val content = PageContentExtractor.extract(binding.webView)

            // A login wall reads as a near-empty page — say what's actually
            // wrong instead of summarising a sign-in screen.
            if (!content.ok || content.text.length < MIN_CONTENT_CHARS) {
                finishSummary(
                    null,
                    "I need you signed in to ${service.displayName}. " +
                        "Log in here, then ask me again — you'll only do this once."
                )
                return@launch
            }

            showAgentStrip("Reading your ${service.displayName} history…", showStop = false, showContinue = false)
            val summary = summarizer.summarize(
                content,
                PageSummarizer.Style.PROJECT_STATUS,
                question
            )

            if (summary == null) {
                finishSummary(null, "I couldn't read your ${service.displayName} history.")
            } else {
                finishSummary(summary, summary.body)
            }
        }
    }

    /** Shows the outcome, speaks it, and offers to save it when it's real. */
    private fun finishSummary(summary: PageSummarizer.Summary?, message: String) {
        showAgentStrip(message, showStop = false, showContinue = false, busy = false)
        speaker?.speak(message)

        if (summary == null) {
            binding.layoutAgentStatus.postDelayed({
                if (agent?.isRunning != true) {
                    binding.layoutAgentStatus.visibility = View.GONE
                }
            }, ERROR_LINGER_MS)
            return
        }

        lastSummary = summary
        showSummaryDialog(summary)
    }

    private fun showSummaryDialog(summary: PageSummarizer.Summary) {
        AlertDialog.Builder(this)
            .setTitle(summary.title.take(60))
            .setMessage(summary.body)
            .setNegativeButton("Close", null)
            .setNeutralButton("Read aloud") { _, _ -> speaker?.speak(summary.body) }
            .setPositiveButton("Save to Notes") { _, _ -> saveSummaryToNotes(summary) }
            .show()
    }

    /** Files the summary in Quick Notes, where the rest of the app can see it. */
    private fun saveSummaryToNotes(summary: PageSummarizer.Summary) {
        try {
            QuickNotesManager(this).createNote(
                title = summary.title.take(80).ifBlank { "Web summary" },
                content = buildString {
                    append(summary.body)
                    append("\n\nSource: ").append(summary.sourceUrl)
                },
                createdBy = QuickNote.CreatedBy.AI
            )
            Toast.makeText(this, "Saved to Quick Notes.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Saving summary note failed", e)
            Toast.makeText(this, "Couldn't save the note.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startAgent(command: String) {
        binding.etCommand.setText("")
        hideCommandKeyboard()
        WebCommandHistory.add(this, command)

        val session = agent ?: WebAgentSession(
            context = this,
            webView = binding.webView,
            scope = lifecycleScope,
            listener = agentListener
        ).also { agent = it }

        showAgentStrip("Starting…", showStop = true, showContinue = false)
        session.start(command)
    }

    private val agentListener = object : WebAgentSession.Listener {

        override fun onStatus(message: String) {
            showAgentStrip(message, showStop = true, showContinue = false)
        }

        override fun onQuestion(question: String) {
            awaitingAnswer = true
            showAgentStrip(question, showStop = true, showContinue = false, busy = false)
            // Spoken, because the whole point of a voice flow is not having to
            // watch the screen to know the agent needs something.
            speaker?.speak(question)
            // The command box doubles as the answer box, so open it and say so.
            binding.layoutCommandBar.visibility = View.VISIBLE
            binding.etCommand.hint = "Type your answer"
            binding.etCommand.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(binding.etCommand, InputMethodManager.SHOW_IMPLICIT)
        }

        override fun onHandoff(reason: String) {
            awaitingContinue = true
            showAgentStrip(reason, showStop = true, showContinue = true, busy = false)
            speaker?.speak(reason)
        }

        override fun onConfirmationNeeded(prompt: String) {
            AlertDialog.Builder(this@WebBrowserActivity)
                .setTitle("Confirm this step")
                .setMessage(prompt)
                .setCancelable(false)
                .setNegativeButton("Cancel") { _, _ -> agent?.provideConfirmation(false) }
                .setPositiveButton("Yes, do it") { _, _ -> agent?.provideConfirmation(true) }
                .show()
        }

        override fun onFinished(success: Boolean, summary: String) {
            awaitingAnswer = false
            awaitingContinue = false
            binding.etCommand.hint = "Tell me what to do on this page"
            showAgentStrip(summary, showStop = false, showContinue = false, busy = false)

            // "Stopped." is the user's own doing — reading it back is noise.
            if (summary != "Stopped.") speaker?.speak(summary)

            // Leave the outcome on screen briefly, then clear the strip.
            binding.layoutAgentStatus.postDelayed({
                if (agent?.isRunning != true) {
                    binding.layoutAgentStatus.visibility = View.GONE
                }
            }, if (success) RESULT_LINGER_MS else ERROR_LINGER_MS)
        }
    }

    private fun onContinueTapped() {
        if (!awaitingContinue) return
        awaitingContinue = false
        showAgentStrip("Continuing…", showStop = true, showContinue = false)
        agent?.resumeAfterHandoff()
    }

    private fun showAgentStrip(
        message: String,
        showStop: Boolean,
        showContinue: Boolean,
        busy: Boolean = true
    ) {
        binding.layoutAgentStatus.visibility = View.VISIBLE
        binding.tvAgentStatus.text = message
        binding.progressAgent.visibility = if (busy) View.VISIBLE else View.GONE
        binding.btnAgentStop.visibility = if (showStop) View.VISIBLE else View.GONE
        binding.btnAgentContinue.visibility = if (showContinue) View.VISIBLE else View.GONE
    }

    private fun hideCommandKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etCommand.windowToken, 0)
    }

    /**
     * Shown once before the first agent run. The user is agreeing to let the
     * app act on pages on their behalf, which is worth stating plainly rather
     * than burying — including the parts the agent deliberately won't do.
     */
    private fun showConsentDialog(onAccepted: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Let AI control this browser?")
            .setMessage(
                "The assistant can open pages, search, scroll, tap and fill in " +
                    "ordinary fields to carry out what you ask.\n\n" +
                    "It will never type your passwords, OTPs or card details, and " +
                    "it will never answer a CAPTCHA. When a step needs those, it " +
                    "stops and hands the browser to you.\n\n" +
                    "It asks before anything that costs money or can't be undone. " +
                    "Watch what it does — you can tap Stop at any time.\n\n" +
                    "Some sites block automation and simply won't work."
            )
            .setNegativeButton("Not now", null)
            .setPositiveButton("I understand") { _, _ ->
                prefs().edit().putBoolean(KEY_CONSENT, true).apply()
                onAccepted()
            }
            .show()
    }

    private fun consentGiven(): Boolean = prefs().getBoolean(KEY_CONSENT, false)

    /** Speaking replies is on by default; the menu can silence it. */
    private fun speakRepliesEnabled(): Boolean = prefs().getBoolean(KEY_SPEAK, true)

    /**
     * Hands-free mode. Off by default: a mis-heard sentence shouldn't send the
     * agent off across live pages, so the user reviews a spoken goal before it
     * runs. Answers to the agent's own questions bypass this either way.
     */
    private fun autoRunVoice(): Boolean = prefs().getBoolean(KEY_AUTO_RUN, false)

    // ----------------------------------------------------------- back / state

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    // Back is the natural "cancel" while the agent is running.
                    agent?.isRunning == true -> agent?.stop()
                    binding.layoutCommandBar.visibility == View.VISIBLE ->
                        binding.layoutCommandBar.visibility = View.GONE
                    binding.webView.canGoBack() -> binding.webView.goBack()
                    binding.layoutStartScreen.visibility != View.VISIBLE -> showStartScreen()
                    else -> finish()
                }
            }
        })
    }

    override fun onPause() {
        super.onPause()
        binding.webView.onPause()
        WebSessionManager.persist()

        // Don't keep the mic open or keep talking once the user has left.
        voice?.stop()
        speaker?.stop()
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
        showGlassHandoffIfWaiting()
    }

    /**
     * The glasses tell the user to open this screen when they hit a login or a
     * security check. Say so here too, so arriving on the phone explains itself
     * instead of showing a bare browser.
     */
    private fun showGlassHandoffIfWaiting() {
        val reason = GlassBrowserEngine.pendingReason ?: return
        showAgentStrip(
            "$reason Finish it here, then tell your glasses to continue.",
            showStop = false,
            showContinue = false,
            busy = false
        )
    }

    override fun onDestroy() {
        // The agent drives the WebView, so it must stop before the WebView goes.
        agent?.stop(notify = false)
        agent = null

        summarizeJob?.cancel()
        summarizeJob = null

        voice?.release()
        voice = null
        speaker?.release()
        speaker = null

        // Detach before destroying, otherwise the WebView leaks the Activity.
        binding.webView.apply {
            stopLoading()
            webChromeClient = null
            (parent as? android.view.ViewGroup)?.removeView(this)
            destroy()
        }
        super.onDestroy()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etAddress.windowToken, 0)
    }

    // --------------------------------------------------------------- last URL

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun lastUrl(): String? = prefs().getString(KEY_LAST_URL, null)

    private fun saveLastUrl(url: String?) {
        prefs().edit().apply {
            if (url == null) remove(KEY_LAST_URL) else putString(KEY_LAST_URL, url)
        }.apply()
    }

    companion object {
        private const val TAG = "WebBrowserActivity"
        private const val PREFS = "web_browser_prefs"
        private const val KEY_LAST_URL = "last_url"
        private const val KEY_CONSENT = "agent_consent"
        private const val KEY_SPEAK = "speak_replies"
        private const val KEY_AUTO_RUN = "auto_run_voice"

        private const val RESULT_LINGER_MS = 9000L
        private const val ERROR_LINGER_MS = 6000L

        /** Give an AI service time to render its client-side history list. */
        private const val SERVICE_LOAD_MS = 4500L

        /** Below this, a page is a login wall or a shell, not content. */
        private const val MIN_CONTENT_CHARS = 400

        private const val MENU_DESKTOP = 1
        private const val MENU_CLEAR = 2
        private const val MENU_SPEAK = 3
        private const val MENU_AUTO_RUN = 4
        private const val MENU_SUMMARIZE = 5
        private const val MENU_LAST_SUMMARY = 6

        /** Optional start URL — used by Module 2 to open the agent on a site. */
        const val EXTRA_URL = "extra_url"
    }
}
