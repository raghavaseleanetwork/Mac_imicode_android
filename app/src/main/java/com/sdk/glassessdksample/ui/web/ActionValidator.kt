package com.sdk.glassessdksample.ui.web

import java.util.Locale

/**
 * The security boundary between the planner and the browser.
 *
 * Every action crosses this before it runs. The rules here are code, not prompt
 * text, because a prompt is a request and this needs to be a guarantee: a model
 * that is confused, or a page that tries to talk the model into something,
 * still cannot get a password typed or a CAPTCHA answered.
 */
object ActionValidator {

    sealed class Verdict {
        object Allow : Verdict()

        /** Not permitted, but the user can do it themselves. */
        data class Handoff(val reason: String) : Verdict()

        /** Malformed or nonsensical — the planner is told and retries. */
        data class Reject(val reason: String) : Verdict()

        /** Permitted only after the user explicitly confirms. */
        data class NeedsConfirmation(val prompt: String) : Verdict()
    }

    /** Words that mean "this click spends money or is otherwise final". */
    private val CONFIRM_KEYWORDS = listOf(
        "pay", "payment", "checkout", "place order", "buy now", "confirm booking",
        "book now", "purchase", "subscribe", "delete", "remove account",
        "send money", "transfer", "authorize", "authorise"
    )

    /** Words that mean "this is the login step the user must do". */
    private val LOGIN_KEYWORDS = listOf(
        "sign in", "signin", "log in", "login", "continue with google",
        "continue with apple", "sign in with", "verify otp", "verify code"
    )

    fun validate(action: BrowserAction, page: PageReader.PageSnapshot?): Verdict {
        // A CAPTCHA stops everything except explicitly handing over.
        if (page?.hasCaptcha == true && action !is BrowserAction.HandoffToUser &&
            action !is BrowserAction.Done && action !is BrowserAction.Failed
        ) {
            return Verdict.Handoff(
                "This page is asking for a CAPTCHA. Please solve it, then tap Continue."
            )
        }

        return when (action) {
            is BrowserAction.Type -> validateType(action, page)
            is BrowserAction.Click -> validateClick(action, page)
            is BrowserAction.Open -> validateOpen(action)
            is BrowserAction.Search ->
                if (action.query.isBlank()) Verdict.Reject("Empty search query.")
                else Verdict.Allow

            is BrowserAction.Scroll ->
                if (action.amount == 0.0) Verdict.Reject("Scroll amount was zero.")
                else Verdict.Allow

            else -> Verdict.Allow
        }
    }

    private fun validateType(
        action: BrowserAction.Type,
        page: PageReader.PageSnapshot?
    ): Verdict {
        // Rule 1: never type into a credential field. Checked against what the
        // page actually reported, not against what the model claims.
        if (page?.isSensitive(action.selector) == true) {
            return Verdict.Handoff(
                "That field is a password or security code. Please type it yourself, then tap Continue."
            )
        }

        // Rule 2: never type text that looks like a credential, wherever it
        // is going — this catches a model trying to route around rule 1.
        if (looksLikeCredential(action.text)) {
            return Verdict.Handoff(
                "This step needs your own login details. Please enter them, then tap Continue."
            )
        }

        if (action.selector.isBlank()) return Verdict.Reject("No field selector given.")

        if (page != null && !page.hasSelector(action.selector)) {
            return Verdict.Reject(
                "Selector ${action.selector} is not on this page. Pick one from the list."
            )
        }
        return Verdict.Allow
    }

    private fun validateClick(
        action: BrowserAction.Click,
        page: PageReader.PageSnapshot?
    ): Verdict {
        if (action.selector.isBlank()) return Verdict.Reject("No selector given.")

        if (page != null && !page.hasSelector(action.selector)) {
            return Verdict.Reject(
                "Selector ${action.selector} is not on this page. Pick one from the list."
            )
        }

        val label = action.label.lowercase(Locale.ROOT)

        // Login buttons go to the user: the agent gets them to the door, the
        // user opens it. That keeps credentials out of this app entirely.
        if (LOGIN_KEYWORDS.any { label.contains(it) }) {
            return Verdict.Handoff(
                "Signing in is your step. Please log in, then tap Continue and I'll carry on."
            )
        }

        // Anything that spends money or is irreversible needs an explicit tap.
        if (CONFIRM_KEYWORDS.any { label.contains(it) }) {
            return Verdict.NeedsConfirmation(
                "I'm about to tap \"${action.label}\". This may be final or cost money. Continue?"
            )
        }

        return Verdict.Allow
    }

    private fun validateOpen(action: BrowserAction.Open): Verdict {
        val url = action.url.trim()
        // Only real web pages. javascript: and data: URLs are how a page would
        // try to get arbitrary code executed through the agent.
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            return Verdict.Reject("Only http and https URLs can be opened.")
        }
        return Verdict.Allow
    }

    /**
     * Heuristic for text that shouldn't be typed by an automation. Errs toward
     * handing off: a false positive costs the user one manual entry, a false
     * negative means the app typed a secret into a page.
     */
    private fun looksLikeCredential(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        // A bare 4-8 digit number in a form is almost always an OTP or PIN.
        if (t.length in 4..8 && t.all { it.isDigit() }) return true
        // Long card-like digit runs.
        if (t.filter { it.isDigit() }.length >= 12 && t.none { it.isLetter() }) return true
        val lower = t.lowercase(Locale.ROOT)
        return lower.contains("password") || lower.contains("otp code")
    }
}
