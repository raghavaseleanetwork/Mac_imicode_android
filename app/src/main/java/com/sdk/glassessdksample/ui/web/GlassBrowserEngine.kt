package com.sdk.glassessdksample.ui.web

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * A browser the glasses can drive without anyone looking at the phone.
 *
 * This is the same WebView machinery as [WebBrowserActivity], but off-screen
 * and process-wide, so a voice session can browse while the app is in the
 * background or the phone is in a pocket.
 *
 * Two things make that safe rather than reckless:
 * - It shares its cookie jar with the visible browser, so a site the user
 *   logged into on screen is already logged in here. Credentials are never
 *   handled by the agent, only reused.
 * - When it reaches something only a person should do — a login, a CAPTCHA —
 *   it stops and says so, rather than trying to get past it. The user opens
 *   the Web screen, does that step, and tells the glasses to carry on.
 *
 * Held as a singleton because the browsing session is conceptually one thing:
 * the user's, not any one screen's.
 */
object GlassBrowserEngine {

    private const val TAG = "GlassBrowserEngine"

    /** Off-screen WebView. Created on the main thread, on first use. */
    private var webView: WebView? = null

    private val main = Handler(Looper.getMainLooper())

    /** True once the user has been told to do a manual step and hasn't finished. */
    @Volatile
    var awaitingUser: Boolean = false
        private set

    /** Why the engine is waiting, in words the glasses can speak. */
    @Volatile
    var pendingReason: String? = null
        private set

    /** The goal that was interrupted, resumed when the user says to continue. */
    @Volatile
    private var interruptedGoal: String? = null

    /** Guards against two voice turns driving the browser at once. */
    @Volatile
    var isBusy: Boolean = false
        private set

    // ------------------------------------------------------------------ setup

    /** Creates the off-screen WebView if it doesn't exist yet. Main thread only. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(context: Context): WebView {
        webView?.let { return it }

        val view = WebView(context.applicationContext)
        WebSessionManager.configure(view, desktopMode = true)
        view.webViewClient = WebViewClient()
        // Same OAuth-popup fix as WebBrowserActivity (see PopupWindowRouter):
        // without this, "Continue with Google" during a voice-driven sign-in
        // would leave this WebView waiting on a popup that never opens.
        view.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onCreateWindow(
                webView: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean = PopupWindowRouter.routeInto(view, resultMsg)
        }
        // Never attached to a window: this browser has no viewer. Give it a
        // real size anyway, or layout-dependent scripts and visibility checks
        // see a 0x0 page and report nothing.
        view.layout(0, 0, VIRTUAL_WIDTH, VIRTUAL_HEIGHT)

        webView = view
        Log.d(TAG, "Off-screen browser created")
        return view
    }

    /** Runs [block] on the main thread, where every WebView call must happen. */
    private suspend fun <T> onMain(block: (WebView) -> T): T =
        withContext(Dispatchers.Main) {
            block(ensureWebView(appContext!!))
        }

    /** Set once from the Application/Service so tools don't need a Context. */
    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    // ------------------------------------------------------------- state gates

    /**
     * Marks the engine as needing the user. The glasses speak [reason] and the
     * browser stays put until [resume] or [cancel].
     */
    fun requireUser(reason: String, goal: String?) {
        awaitingUser = true
        pendingReason = reason
        interruptedGoal = goal
        Log.d(TAG, "Waiting on user: $reason")
    }

    /** The user says they've done their part. Returns the goal to resume. */
    fun resume(): String? {
        awaitingUser = false
        pendingReason = null
        val goal = interruptedGoal
        interruptedGoal = null
        return goal
    }

    fun cancel() {
        awaitingUser = false
        pendingReason = null
        interruptedGoal = null
        isBusy = false
    }

    fun markBusy(busy: Boolean) {
        isBusy = busy
    }

    // -------------------------------------------------------------- browsing

    /** The URL currently loaded, or null when nothing has been opened. */
    suspend fun currentUrl(): String? = onMain { it.url?.takeIf { u -> u != "about:blank" } }

    /** Navigates and waits for the page to settle. */
    suspend fun open(url: String): Boolean = onMain { view ->
        view.loadUrl(url)
        true
    }.also { settle() }

    /** Runs a web search. */
    suspend fun search(query: String, engine: String = "google"): Boolean {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val url = when (engine.lowercase()) {
            "youtube" -> "https://m.youtube.com/results?search_query=$q"
            "bing" -> "https://www.bing.com/search?q=$q"
            else -> "https://www.google.com/search?q=$q"
        }
        return open(url)
    }

    /** Reads the page's interactive summary, for the agent loop. */
    suspend fun readPage(): PageReader.PageSnapshot =
        withContext(Dispatchers.Main) {
            PageReader.read(ensureWebView(appContext!!))
        }

    /** Reads the page's prose, for summarising. */
    suspend fun readContent(): PageContentExtractor.Content =
        withContext(Dispatchers.Main) {
            PageContentExtractor.extract(ensureWebView(appContext!!))
        }

    /** An executor bound to the off-screen WebView. */
    suspend fun executor(): ActionExecutor =
        withContext(Dispatchers.Main) { ActionExecutor(ensureWebView(appContext!!)) }

    /**
     * Gives a freshly loaded page time to render before it is read.
     *
     * A fixed pause rather than an onPageFinished hook: most navigation on the
     * sites this is used with is client-side and never fires it.
     */
    private suspend fun settle() {
        kotlinx.coroutines.delay(SETTLE_MS)
    }

    /** Releases the WebView. Called when the app tears the voice session down. */
    fun release() {
        main.post {
            try {
                webView?.apply {
                    stopLoading()
                    destroy()
                }
            } catch (e: Exception) {
                Log.w(TAG, "release failed", e)
            }
            webView = null
            cancel()
        }
    }

    private const val VIRTUAL_WIDTH = 1280
    private const val VIRTUAL_HEIGHT = 2000
    private const val SETTLE_MS = 2500L
}
