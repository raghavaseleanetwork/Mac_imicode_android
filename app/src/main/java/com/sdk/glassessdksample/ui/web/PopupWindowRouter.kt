package com.sdk.glassessdksample.ui.web

import android.os.Message
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Handles [android.webkit.WebChromeClient.onCreateWindow] for both browser
 * WebViews (the visible one in [WebBrowserActivity] and the off-screen one in
 * [GlassBrowserEngine]).
 *
 * OAuth buttons like "Continue with Google" open their flow in a JS popup
 * (`window.open(...)`), not a normal navigation. A WebView with multi-window
 * support off - or on but with no `onCreateWindow` override - simply drops
 * that request: the popup never opens, and the page is left waiting for it
 * forever, which looks exactly like the browser being stuck.
 *
 * There is only ever one WebView the user can actually see per screen, so
 * rather than spawning a second, real popup WebView (extra lifecycle to
 * manage, and still invisible on the off-screen engine), this creates a
 * throwaway WebView just to catch the popup's first navigation target, then
 * loads that URL into the real WebView and discards the throwaway one.
 */
object PopupWindowRouter {

    /**
     * Call from `onCreateWindow`. Returns `true` (as the override must) once
     * the throwaway WebView is wired up to redirect into [target].
     */
    fun routeInto(target: WebView, resultMsg: Message?): Boolean {
        val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false

        val popup = WebView(target.context.applicationContext)
        popup.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: android.webkit.WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                target.loadUrl(url)
                // The throwaway view has done its one job; let it go.
                popup.stopLoading()
                popup.destroy()
                return true
            }
        }
        transport.webView = popup
        resultMsg.sendToTarget()
        return true
    }
}
