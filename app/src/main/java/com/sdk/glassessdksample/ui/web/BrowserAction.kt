package com.sdk.glassessdksample.ui.web

import org.json.JSONObject

/**
 * The complete set of things the AI is allowed to do to the browser.
 *
 * This is deliberately a closed list. The planner never returns code or raw
 * JavaScript — it returns one of these, which [ActionValidator] checks and
 * [ActionExecutor] performs. Anything the model asks for that doesn't map onto
 * a member here is rejected rather than guessed at.
 */
sealed class BrowserAction {

    /** Navigate to a URL. */
    data class Open(val url: String) : BrowserAction()

    /** Run a web search. Cheaper and more robust than driving a search box. */
    data class Search(val query: String, val engine: String = "google") : BrowserAction()

    /** Click the element matching [selector]. [label] is for the status strip. */
    data class Click(val selector: String, val label: String) : BrowserAction()

    /** Type [text] into the field matching [selector]. Never a password field. */
    data class Type(
        val selector: String,
        val text: String,
        val submit: Boolean = false
    ) : BrowserAction()

    /** Scroll the page. [amount] is in viewport-heights, negative scrolls up. */
    data class Scroll(val amount: Double) : BrowserAction()

    object Back : BrowserAction()
    object Forward : BrowserAction()
    object Reload : BrowserAction()

    /** Wait for the page to settle, e.g. after a client-side navigation. */
    data class Wait(val reason: String) : BrowserAction()

    /** The agent needs information only the user has ("which address?"). */
    data class AskUser(val question: String) : BrowserAction()

    /**
     * The agent has hit something it must not do itself — a login form, a
     * CAPTCHA, a payment step. The user takes over the WebView and taps
     * Continue when they're ready for the agent to resume.
     */
    data class HandoffToUser(val reason: String) : BrowserAction()

    /** The goal is complete. [summary] is read back to the user. */
    data class Done(val summary: String) : BrowserAction()

    /** The agent cannot proceed and is giving up. */
    data class Failed(val reason: String) : BrowserAction()

    /** Short human-readable form, shown in the agent status strip. */
    fun describe(): String = when (this) {
        is Open -> "Opening ${WebSessionManager.displayHost(url)}"
        is Search -> "Searching for \"$query\""
        is Click -> "Tapping $label"
        is Type -> "Typing into the page"
        is Scroll -> if (amount >= 0) "Scrolling down" else "Scrolling up"
        Back -> "Going back"
        Forward -> "Going forward"
        Reload -> "Reloading"
        is Wait -> "Waiting: $reason"
        is AskUser -> question
        is HandoffToUser -> reason
        is Done -> summary
        is Failed -> reason
    }

    companion object {
        private const val TAG = "BrowserAction"

        /**
         * Parses one planner response. Returns null when the JSON doesn't
         * describe a known action — the caller treats that as a planning
         * failure rather than attempting a best-effort interpretation.
         */
        fun fromJson(json: JSONObject): BrowserAction? {
            return when (json.optString("action").lowercase().trim()) {
                "open" -> json.optString("url")
                    .takeIf { it.isNotBlank() }
                    ?.let { Open(it) }

                "search" -> json.optString("query")
                    .takeIf { it.isNotBlank() }
                    ?.let {
                        Search(
                            query = it,
                            engine = json.optString("engine").ifBlank { "google" }
                        )
                    }

                "click" -> json.optString("selector")
                    .takeIf { it.isNotBlank() }
                    ?.let {
                        Click(
                            selector = it,
                            label = json.optString("label").ifBlank { "the element" }
                        )
                    }

                "type" -> {
                    val selector = json.optString("selector")
                    val text = json.optString("text")
                    if (selector.isBlank()) null
                    else Type(selector, text, json.optBoolean("submit", false))
                }

                "scroll" -> Scroll(json.optDouble("amount", 0.8))
                "back" -> Back
                "forward" -> Forward
                "reload" -> Reload
                "wait" -> Wait(json.optString("reason").ifBlank { "page is loading" })

                "ask_user" -> json.optString("question")
                    .takeIf { it.isNotBlank() }
                    ?.let { AskUser(it) }

                "handoff" -> HandoffToUser(
                    json.optString("reason").ifBlank {
                        "This step needs you — please take over."
                    }
                )

                "done" -> Done(
                    json.optString("summary").ifBlank { "Finished." }
                )

                "failed" -> Failed(
                    json.optString("reason").ifBlank { "I couldn't complete that." }
                )

                else -> null
            }
        }
    }
}

/** The outcome of running one action, fed back into the next planning turn. */
data class ActionResult(
    val action: BrowserAction,
    val success: Boolean,
    val detail: String
)
