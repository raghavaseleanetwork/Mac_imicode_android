package com.sdk.glassessdksample.ui.web

import android.webkit.WebView
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Turns the live page into a compact JSON summary the planner can reason about.
 *
 * The whole loop's cost and reliability hinge on this. Sending raw HTML would
 * blow the token budget and bury the useful parts, so the injected script keeps
 * only what an agent needs to decide its next move: visible interactive
 * elements, each with a **stable selector** we can hand straight back to
 * [ActionExecutor], plus a trimmed slice of the visible text.
 */
object PageReader {

    /** Marks fields the agent must never touch. Enforced again in the executor. */
    const val SENSITIVE_FLAG = "sensitive"

    private const val MAX_ELEMENTS = 40
    private const val MAX_TEXT_CHARS = 2500

    /**
     * Injected script. Assigns every candidate element a `data-imi-ref`
     * attribute, so the selector we report back stays valid even on pages
     * where classes are hashed and regenerated between renders.
     */
    private val SCRIPT = """
    (function() {
      try {
        var MAX_ELEMENTS = $MAX_ELEMENTS;
        var MAX_TEXT = $MAX_TEXT_CHARS;

        function visible(el) {
          var r = el.getBoundingClientRect();
          if (r.width < 4 || r.height < 4) return false;
          var s = window.getComputedStyle(el);
          if (s.visibility === 'hidden' || s.display === 'none') return false;
          if (parseFloat(s.opacity || '1') < 0.05) return false;
          // On-screen or just below the fold — the agent can scroll to it.
          return r.top < window.innerHeight * 2 && r.bottom > -window.innerHeight;
        }

        function label(el) {
          var t = (el.getAttribute('aria-label') || el.getAttribute('placeholder') ||
                   el.getAttribute('title') || el.getAttribute('name') ||
                   el.value || el.innerText || el.textContent || '').trim();
          return t.replace(/\s+/g, ' ').slice(0, 80);
        }

        // A field is sensitive if it is a password input, or if anything about
        // it mentions a password/OTP/card. Reported so the planner sees it and
        // enforced independently in Kotlin.
        function sensitive(el) {
          var type = (el.getAttribute('type') || '').toLowerCase();
          if (type === 'password') return true;
          var hay = ((el.getAttribute('name') || '') + ' ' +
                     (el.getAttribute('id') || '') + ' ' +
                     (el.getAttribute('autocomplete') || '') + ' ' +
                     (el.getAttribute('aria-label') || '') + ' ' +
                     (el.getAttribute('placeholder') || '')).toLowerCase();
          return /pass|pwd|otp|cvv|cvc|card|credit|secret|pin\b/.test(hay);
        }

        var refCounter = 0;
        function selectorFor(el) {
          if (el.id && /^[A-Za-z][\w-]*$/.test(el.id)) return '#' + el.id;
          var existing = el.getAttribute('data-imi-ref');
          if (existing) return '[data-imi-ref="' + existing + '"]';
          var ref = 'r' + (++refCounter) + '_' + Date.now().toString(36);
          el.setAttribute('data-imi-ref', ref);
          return '[data-imi-ref="' + ref + '"]';
        }

        var inputs = [], buttons = [], links = [];

        var inputEls = document.querySelectorAll(
          'input, textarea, select, [contenteditable="true"]');
        for (var i = 0; i < inputEls.length && inputs.length < MAX_ELEMENTS; i++) {
          var el = inputEls[i];
          var type = (el.getAttribute('type') || '').toLowerCase();
          if (type === 'hidden') continue;
          if (!visible(el)) continue;
          inputs.push({
            selector: selectorFor(el),
            label: label(el),
            type: type || el.tagName.toLowerCase(),
            value: (el.value || '').slice(0, 40),
            $SENSITIVE_FLAG: sensitive(el)
          });
        }

        var btnEls = document.querySelectorAll(
          'button, [role="button"], input[type="submit"], input[type="button"]');
        for (var j = 0; j < btnEls.length && buttons.length < MAX_ELEMENTS; j++) {
          var b = btnEls[j];
          if (!visible(b)) continue;
          var bl = label(b);
          if (!bl) continue;
          buttons.push({ selector: selectorFor(b), label: bl });
        }

        var linkEls = document.querySelectorAll('a[href]');
        for (var k = 0; k < linkEls.length && links.length < MAX_ELEMENTS; k++) {
          var a = linkEls[k];
          if (!visible(a)) continue;
          var al = label(a);
          if (!al) continue;
          links.push({
            selector: selectorFor(a),
            label: al,
            href: (a.href || '').slice(0, 200)
          });
        }

        var text = (document.body ? (document.body.innerText || '') : '')
          .replace(/\s+/g, ' ').trim().slice(0, MAX_TEXT);

        // Heuristics that tell the agent to stop and hand control to the user.
        var html = document.documentElement.innerHTML;
        var hasCaptcha = /recaptcha|hcaptcha|captcha|cf-challenge|turnstile/i.test(html);
        var hasPassword = document.querySelectorAll('input[type="password"]').length > 0;

        return JSON.stringify({
          ok: true,
          url: location.href,
          title: document.title || '',
          scrollY: Math.round(window.scrollY),
          pageHeight: Math.round(document.body ? document.body.scrollHeight : 0),
          viewportHeight: Math.round(window.innerHeight),
          atBottom: (window.innerHeight + window.scrollY) >=
                    ((document.body ? document.body.scrollHeight : 0) - 40),
          hasCaptcha: hasCaptcha,
          hasPasswordField: hasPassword,
          inputs: inputs,
          buttons: buttons,
          links: links,
          text: text
        });
      } catch (e) {
        return JSON.stringify({ ok: false, error: String(e) });
      }
    })();
    """.trimIndent()

    /** A parsed page snapshot. */
    data class PageSnapshot(
        val ok: Boolean,
        val url: String,
        val title: String,
        val hasCaptcha: Boolean,
        val hasPasswordField: Boolean,
        val atBottom: Boolean,
        val raw: JSONObject
    ) {
        /**
         * The page rendered for the prompt. Kept terse on purpose — this is
         * sent on every planning turn, so every character is paid for.
         */
        fun toPromptText(): String {
            val sb = StringBuilder()
            sb.append("URL: ").append(url).append('\n')
            sb.append("TITLE: ").append(title).append('\n')
            if (hasCaptcha) sb.append("WARNING: a CAPTCHA is present on this page.\n")
            if (hasPasswordField) sb.append("WARNING: a password field is present.\n")
            sb.append("AT_BOTTOM: ").append(atBottom).append('\n')

            appendList(sb, "INPUTS", "inputs") { o ->
                val flag = if (o.optBoolean(SENSITIVE_FLAG)) " [SENSITIVE - DO NOT TYPE]" else ""
                val value = o.optString("value").takeIf { it.isNotBlank() }
                    ?.let { " current=\"$it\"" }.orEmpty()
                "${o.optString("selector")} | ${o.optString("type")} | " +
                    "\"${o.optString("label")}\"$value$flag"
            }
            appendList(sb, "BUTTONS", "buttons") { o ->
                "${o.optString("selector")} | \"${o.optString("label")}\""
            }
            appendList(sb, "LINKS", "links") { o ->
                "${o.optString("selector")} | \"${o.optString("label")}\""
            }

            val text = raw.optString("text")
            if (text.isNotBlank()) {
                sb.append("\nVISIBLE TEXT:\n").append(text).append('\n')
            }
            return sb.toString()
        }

        private fun appendList(
            sb: StringBuilder,
            heading: String,
            key: String,
            render: (JSONObject) -> String
        ) {
            val arr = raw.optJSONArray(key) ?: return
            if (arr.length() == 0) return
            sb.append('\n').append(heading).append(":\n")
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                sb.append("- ").append(render(o)).append('\n')
            }
        }

        /** Whether a selector was actually reported by this snapshot. */
        fun hasSelector(selector: String): Boolean =
            listOf("inputs", "buttons", "links").any { key ->
                val arr = raw.optJSONArray(key) ?: return@any false
                (0 until arr.length()).any {
                    arr.optJSONObject(it)?.optString("selector") == selector
                }
            }

        /** Whether the field behind [selector] was flagged sensitive. */
        fun isSensitive(selector: String): Boolean {
            val arr = raw.optJSONArray("inputs") ?: return false
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("selector") == selector) {
                    return o.optBoolean(SENSITIVE_FLAG)
                }
            }
            return false
        }
    }

    /** Reads the current page. Must be called on the UI thread. */
    suspend fun read(webView: WebView): PageSnapshot = suspendCoroutine { cont ->
        webView.evaluateJavascript(SCRIPT) { result ->
            cont.resume(parse(result))
        }
    }

    private fun parse(evaluateResult: String?): PageSnapshot {
        val fallback = PageSnapshot(
            ok = false,
            url = "",
            title = "",
            hasCaptcha = false,
            hasPasswordField = false,
            atBottom = false,
            raw = JSONObject()
        )
        if (evaluateResult.isNullOrBlank() || evaluateResult == "null") return fallback

        return try {
            // evaluateJavascript hands back a JSON-encoded *string*, so the
            // payload is double-encoded and has to be unwrapped first.
            val unwrapped = if (evaluateResult.startsWith("\"")) {
                JSONObject("{\"v\":$evaluateResult}").getString("v")
            } else {
                evaluateResult
            }
            val json = JSONObject(unwrapped)
            if (!json.optBoolean("ok")) return fallback

            PageSnapshot(
                ok = true,
                url = json.optString("url"),
                title = json.optString("title"),
                hasCaptcha = json.optBoolean("hasCaptcha"),
                hasPasswordField = json.optBoolean("hasPasswordField"),
                atBottom = json.optBoolean("atBottom"),
                raw = json
            )
        } catch (_: Exception) {
            fallback
        }
    }
}
