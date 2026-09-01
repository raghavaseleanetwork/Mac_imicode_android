package com.sdk.glassessdksample.ui.web

import android.webkit.WebView
import kotlinx.coroutines.delay
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Performs a validated [BrowserAction] against the WebView.
 *
 * Nothing reaches this class without passing [ActionValidator], but the
 * password rule is re-checked here anyway — an executor that can be tricked
 * into typing a secret is a bug no matter which caller does the tricking.
 *
 * All methods must be called on the UI thread.
 */
class ActionExecutor(private val webView: WebView) {

    suspend fun execute(action: BrowserAction): ActionResult = when (action) {
        is BrowserAction.Open -> {
            webView.loadUrl(action.url)
            awaitPageSettle()
            ActionResult(action, true, "Opened ${action.url}")
        }

        is BrowserAction.Search -> {
            val url = searchUrl(action.query, action.engine)
            webView.loadUrl(url)
            awaitPageSettle()
            ActionResult(action, true, "Searched for \"${action.query}\"")
        }

        is BrowserAction.Click -> runClick(action)
        is BrowserAction.Type -> runType(action)
        is BrowserAction.Scroll -> runScroll(action)

        BrowserAction.Back -> {
            if (webView.canGoBack()) {
                webView.goBack()
                awaitPageSettle()
                ActionResult(action, true, "Went back")
            } else {
                ActionResult(action, false, "There is no page to go back to")
            }
        }

        BrowserAction.Forward -> {
            if (webView.canGoForward()) {
                webView.goForward()
                awaitPageSettle()
                ActionResult(action, true, "Went forward")
            } else {
                ActionResult(action, false, "There is no page to go forward to")
            }
        }

        BrowserAction.Reload -> {
            webView.reload()
            awaitPageSettle()
            ActionResult(action, true, "Reloaded")
        }

        is BrowserAction.Wait -> {
            delay(PAGE_SETTLE_MS)
            ActionResult(action, true, "Waited")
        }

        // These are conversational, not mechanical — the session handles them.
        is BrowserAction.AskUser,
        is BrowserAction.HandoffToUser,
        is BrowserAction.Done,
        is BrowserAction.Failed -> ActionResult(action, true, action.describe())
    }

    // ------------------------------------------------------------------ click

    private suspend fun runClick(action: BrowserAction.Click): ActionResult {
        val js = """
        (function() {
          try {
            var el = document.querySelector(${action.selector.toJsString()});
            if (!el) return JSON.stringify({ok:false, error:'element not found'});
            el.scrollIntoView({block:'center'});
            // Fire a full pointer sequence: frameworks often listen for these
            // rather than for click alone.
            ['pointerdown','mousedown','pointerup','mouseup','click'].forEach(function(t) {
              var Ctor = t.indexOf('pointer') === 0 ? (window.PointerEvent || MouseEvent) : MouseEvent;
              el.dispatchEvent(new Ctor(t, {bubbles:true, cancelable:true, view:window}));
            });
            return JSON.stringify({ok:true});
          } catch (e) {
            return JSON.stringify({ok:false, error:String(e)});
          }
        })();
        """.trimIndent()

        val res = evaluate(js)
        awaitPageSettle()
        return if (res.optBoolean("ok")) {
            ActionResult(action, true, "Tapped ${action.label}")
        } else {
            ActionResult(action, false, "Couldn't tap ${action.label}: ${res.optString("error")}")
        }
    }

    // ------------------------------------------------------------------- type

    private suspend fun runType(action: BrowserAction.Type): ActionResult {
        val js = """
        (function() {
          try {
            var el = document.querySelector(${action.selector.toJsString()});
            if (!el) return JSON.stringify({ok:false, error:'field not found'});

            // Independent refusal: this executor never fills a password field,
            // whatever it was asked to do.
            var type = (el.getAttribute('type') || '').toLowerCase();
            if (type === 'password') {
              return JSON.stringify({ok:false, error:'refused: password field'});
            }

            el.scrollIntoView({block:'center'});
            el.focus();

            var value = ${action.text.toJsString()};

            if (el.isContentEditable) {
              el.textContent = value;
            } else {
              // Assign through the native setter so React/Vue see the change;
              // writing .value directly is swallowed by their value tracker.
              var proto = el.tagName === 'TEXTAREA'
                ? window.HTMLTextAreaElement.prototype
                : window.HTMLInputElement.prototype;
              var setter = Object.getOwnPropertyDescriptor(proto, 'value');
              if (setter && setter.set) { setter.set.call(el, value); }
              else { el.value = value; }
            }

            el.dispatchEvent(new Event('input', {bubbles:true}));
            el.dispatchEvent(new Event('change', {bubbles:true}));

            if (${action.submit}) {
              var form = el.form || el.closest('form');
              el.dispatchEvent(new KeyboardEvent('keydown',
                {bubbles:true, cancelable:true, key:'Enter', keyCode:13, which:13}));
              el.dispatchEvent(new KeyboardEvent('keyup',
                {bubbles:true, cancelable:true, key:'Enter', keyCode:13, which:13}));
              if (form && typeof form.requestSubmit === 'function') {
                form.requestSubmit();
              } else if (form) {
                form.submit();
              }
            }
            return JSON.stringify({ok:true});
          } catch (e) {
            return JSON.stringify({ok:false, error:String(e)});
          }
        })();
        """.trimIndent()

        val res = evaluate(js)
        if (action.submit) awaitPageSettle() else delay(SHORT_SETTLE_MS)

        return if (res.optBoolean("ok")) {
            ActionResult(action, true, "Filled the field")
        } else {
            ActionResult(action, false, "Couldn't fill the field: ${res.optString("error")}")
        }
    }

    // ----------------------------------------------------------------- scroll

    private suspend fun runScroll(action: BrowserAction.Scroll): ActionResult {
        val js = """
        (function() {
          window.scrollBy({top: window.innerHeight * ${action.amount}, behavior: 'instant'});
          return JSON.stringify({ok:true});
        })();
        """.trimIndent()
        evaluate(js)
        delay(SHORT_SETTLE_MS)
        return ActionResult(action, true, action.describe())
    }

    // ---------------------------------------------------------------- helpers

    private suspend fun evaluate(js: String): JSONObject = suspendCoroutine { cont ->
        webView.evaluateJavascript(js) { raw ->
            cont.resume(parseJsResult(raw))
        }
    }

    private fun parseJsResult(raw: String?): JSONObject {
        if (raw.isNullOrBlank() || raw == "null") {
            return JSONObject().put("ok", false).put("error", "no result")
        }
        return try {
            val unwrapped = if (raw.startsWith("\"")) {
                JSONObject("{\"v\":$raw}").getString("v")
            } else {
                raw
            }
            JSONObject(unwrapped)
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: "bad result")
        }
    }

    /**
     * Waits for the page to look settled. A fixed delay rather than an
     * onPageFinished hook, because most modern navigation is client-side and
     * never fires it; the readyState poll catches full loads early.
     */
    private suspend fun awaitPageSettle() {
        delay(SHORT_SETTLE_MS)
        var waited = SHORT_SETTLE_MS
        while (waited < PAGE_SETTLE_MS) {
            val state = evaluate(
                "(function(){return JSON.stringify({ok:true, state:document.readyState});})();"
            )
            if (state.optString("state") == "complete") break
            delay(POLL_MS)
            waited += POLL_MS
        }
        // Let client-side rendering paint before the next page read.
        delay(RENDER_MS)
    }

    private fun searchUrl(query: String, engine: String): String {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        return when (engine.lowercase()) {
            "youtube" -> "https://m.youtube.com/results?search_query=$q"
            "bing" -> "https://www.bing.com/search?q=$q"
            "duckduckgo" -> "https://duckduckgo.com/?q=$q"
            else -> "https://www.google.com/search?q=$q"
        }
    }

    companion object {
        private const val SHORT_SETTLE_MS = 400L
        private const val PAGE_SETTLE_MS = 6000L
        private const val POLL_MS = 300L
        private const val RENDER_MS = 700L

        /** Safely embeds a Kotlin string as a JS string literal. */
        private fun String.toJsString(): String =
            JSONObject.quote(this)
    }
}
