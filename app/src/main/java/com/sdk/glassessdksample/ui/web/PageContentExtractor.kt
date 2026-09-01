package com.sdk.glassessdksample.ui.web

import android.webkit.WebView
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Pulls the *readable content* out of the current page.
 *
 * This is a different job from [PageReader], which produces a short summary of
 * interactive elements so the agent can decide what to click. Here we want the
 * prose itself — an article, a conversation, a docs page — long enough to
 * summarise properly.
 *
 * The extraction is a small readability pass: drop the chrome (nav, header,
 * footer, script, style), prefer a main content container when the page
 * declares one, and fall back to the body.
 */
object PageContentExtractor {

    /** Roughly 12k characters keeps a long article inside a single Gemini call. */
    private const val MAX_CHARS = 12000

    private val SCRIPT = """
    (function() {
      try {
        var MAX = $MAX_CHARS;

        function clean(root) {
          if (!root) return '';
          var clone = root.cloneNode(true);
          var junk = clone.querySelectorAll(
            'script,style,noscript,svg,iframe,nav,header,footer,aside,' +
            'form,button,[role="navigation"],[role="banner"],' +
            '[role="contentinfo"],[aria-hidden="true"]');
          for (var i = 0; i < junk.length; i++) {
            if (junk[i].parentNode) junk[i].parentNode.removeChild(junk[i]);
          }
          return (clone.innerText || clone.textContent || '')
            .replace(/[ \t]+/g, ' ')
            .replace(/\n{3,}/g, '\n\n')
            .trim();
        }

        // Prefer whatever the page itself calls its main content. Falling
        // straight to <body> drags in menus and cookie banners.
        var candidates = ['main', 'article', '[role="main"]', '#main', '#content',
                          '.main-content', '.article-body', '.post-content'];
        var best = '';
        for (var c = 0; c < candidates.length; c++) {
          var el = document.querySelector(candidates[c]);
          if (!el) continue;
          var t = clean(el);
          if (t.length > best.length) best = t;
        }

        var bodyText = clean(document.body);
        // A "main" that captured far less than the body usually means the page
        // mislabels its structure — trust whichever is richer.
        var text = (best.length > bodyText.length * 0.4) ? best : bodyText;

        var truncated = text.length > MAX;
        if (truncated) text = text.slice(0, MAX);

        return JSON.stringify({
          ok: true,
          url: location.href,
          title: document.title || '',
          text: text,
          truncated: truncated,
          totalChars: (document.body ? (document.body.innerText || '').length : 0)
        });
      } catch (e) {
        return JSON.stringify({ ok: false, error: String(e) });
      }
    })();
    """.trimIndent()

    data class Content(
        val ok: Boolean,
        val url: String,
        val title: String,
        val text: String,
        val truncated: Boolean
    ) {
        val isEmpty: Boolean get() = text.isBlank()
    }

    /** Reads the page. Must be called on the UI thread. */
    suspend fun extract(webView: WebView): Content = suspendCoroutine { cont ->
        webView.evaluateJavascript(SCRIPT) { raw ->
            cont.resume(parse(raw))
        }
    }

    private fun parse(raw: String?): Content {
        val empty = Content(false, "", "", "", false)
        if (raw.isNullOrBlank() || raw == "null") return empty

        return try {
            // evaluateJavascript returns a JSON-encoded string, so the payload
            // is double-encoded and needs unwrapping first.
            val unwrapped = if (raw.startsWith("\"")) {
                JSONObject("{\"v\":$raw}").getString("v")
            } else {
                raw
            }
            val json = JSONObject(unwrapped)
            if (!json.optBoolean("ok")) return empty

            Content(
                ok = true,
                url = json.optString("url"),
                title = json.optString("title"),
                text = json.optString("text"),
                truncated = json.optBoolean("truncated")
            )
        } catch (_: Exception) {
            empty
        }
    }
}
