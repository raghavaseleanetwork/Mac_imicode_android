package com.sdk.glassessdksample.ui.web

import android.content.Context
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView

/**
 * Owns the browser session for the in-app Web section.
 *
 * The whole point of this class is persistence: cookies and DOM storage are
 * kept across app restarts so the user logs into a site **once** and stays
 * logged in. Module 2's agent relies on that — it never handles credentials
 * itself, it just reuses the session the user established by hand.
 */
object WebSessionManager {

    /** Desktop UA — many sites expose a richer, easier-to-drive DOM to it. */
    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /** Applies the browser configuration this feature depends on. */
    fun configure(webView: WebView, desktopMode: Boolean = false) {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true

            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false

            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)

            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_DEFAULT

            userAgentString = if (desktopMode) DESKTOP_UA else userAgentString
        }

        // Third-party cookies are required by most SSO / login flows.
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            webView.settings.forceDark = WebSettings.FORCE_DARK_AUTO
        }
    }

    /** Flushes cookies to disk so the session survives process death. */
    fun persist() {
        CookieManager.getInstance().flush()
    }

    /**
     * Wipes every trace of the browsing session: cookies, DOM storage, cache
     * and history. Exposed to the user as "Clear browsing data".
     */
    fun clearSession(context: Context, webView: WebView?) {
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }
        WebStorage.getInstance().deleteAllData()
        webView?.apply {
            clearCache(true)
            clearFormData()
            clearHistory()
        }
        context.cacheDir.listFiles()
            ?.filter { it.name.startsWith("org.chromium") }
            ?.forEach { it.deleteRecursively() }
    }

    /**
     * Turns whatever the user typed in the address bar into a URL.
     * Anything that doesn't look like a host becomes a Google search.
     */
    fun toUrlOrSearch(input: String): String {
        val text = input.trim()
        if (text.isEmpty()) return "about:blank"

        if (text.startsWith("http://") || text.startsWith("https://")) return text
        if (text.startsWith("about:") || text.startsWith("file://")) return text

        val looksLikeDomain = !text.contains(' ') &&
            text.contains('.') &&
            text.substringAfterLast('.').isNotEmpty()

        return if (looksLikeDomain) {
            "https://$text"
        } else {
            "https://www.google.com/search?q=" + java.net.URLEncoder.encode(text, "UTF-8")
        }
    }

    /** Short host label for the address bar, e.g. "google.com". */
    fun displayHost(url: String?): String {
        if (url.isNullOrBlank() || url == "about:blank") return ""
        return try {
            java.net.URI(url).host?.removePrefix("www.") ?: url
        } catch (_: Exception) {
            url
        }
    }
}
