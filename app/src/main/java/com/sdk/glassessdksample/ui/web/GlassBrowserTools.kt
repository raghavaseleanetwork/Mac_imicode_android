package com.sdk.glassessdksample.ui.web

import android.content.Context
import android.util.Log
import com.sdk.glassessdksample.ui.QuickNote
import com.sdk.glassessdksample.ui.QuickNotesManager

/**
 * The browser, exposed to the glasses voice session as tools.
 *
 * The user speaks; the live model decides a tool is needed; these functions run
 * and return a **spoken-style sentence** that the model reads back. So the
 * whole browser is operated by talking, with the phone in a pocket.
 *
 * Every string returned here is going to be said out loud, so they are written
 * as speech — short, no markup, no URLs read character by character.
 */
object GlassBrowserTools {

    private const val TAG = "GlassBrowserTools"

    /** Tool names this object handles, for the dispatcher to check against. */
    val TOOL_NAMES = setOf(
        "browse_web",
        "browser_continue",
        "browser_cancel",
        "read_current_page",
        "catch_up_on_ai",
        "browser_scroll",
        "browser_click",
        "browser_type",
        "browser_back",
        "browser_forward"
    )

    /**
     * Declarations in the same shape as the other tools in `GeminiLiveService`.
     * The descriptions carry their weight: they are what makes the model pick
     * the browser instead of answering from memory.
     */
    fun declarations(): List<Map<String, Any>> = listOf(
        mapOf(
            "type" to "function",
            "name" to "browse_web",
            "description" to
                "Control a real web browser to look something up on the live web or " +
                "carry out a task on a website. Use this whenever the answer depends on " +
                "CURRENT information you cannot know from memory - flights, prices, " +
                "availability, timings, scores, news, stock, opening hours - as well as " +
                "for doing things on a site: 'find me flights to Delhi', 'how much is " +
                "this on Amazon', 'what's the score', 'open my email and check', " +
                "'book a table on this site'. Never answer these by telling the user to " +
                "go check a website themselves; open it here and report what you found. " +
                "The browser stays signed in to sites the user has logged into before. " +
                "Describe the whole task in the 'goal' parameter, in the user's own words.",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "goal" to mapOf(
                        "type" to "string",
                        "description" to "The complete task to carry out in the browser"
                    )
                ),
                "required" to listOf("goal")
            )
        ),
        mapOf(
            "type" to "function",
            "name" to "browser_continue",
            "description" to
                "Resume a browsing task that stopped because the user had to do " +
                "something themselves, like signing in or solving a security check. " +
                "Call this when the user says they are done — 'I've logged in', " +
                "'done', 'carry on', 'continue', 'ho gaya'.",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to emptyMap<String, Any>()
            )
        ),
        mapOf(
            "type" to "function",
            "name" to "browser_cancel",
            "description" to
                "Give up on the current browsing task instead of continuing it. Use " +
                "when the user says 'never mind', 'cancel that', 'forget it', 'stop', " +
                "or 'give up' about something the browser was doing or waiting on.",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to emptyMap<String, Any>()
            )
        ),
        mapOf(
            "type" to "function",
            "name" to "browser_scroll",
            "description" to
                "Scroll the page currently open in the browser, without re-planning " +
                "the whole task. Use for 'scroll down', 'scroll up', 'go down more', " +
                "'page down', 'scroll to the top'.",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "direction" to mapOf(
                        "type" to "string",
                        "description" to "'down' or 'up'. Defaults to down."
                    ),
                    "amount" to mapOf(
                        "type" to "string",
                        "description" to
                            "How far: 'a bit', 'a lot'/'page', or 'top'/'bottom' to jump " +
                            "to the very start or end of the page. Defaults to 'a bit'."
                    )
                )
            )
        ),
        mapOf(
            "type" to "function",
            "name" to "browser_click",
            "description" to
                "Tap a button or link on the page currently open in the browser, by " +
                "what it says, without re-planning the whole task. Use for 'click " +
                "sign up', 'tap the second result', 'open the first link', 'press " +
                "search'. Won't tap a sign-in/login control — that still needs the " +
                "user's own tap.",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "description" to mapOf(
                        "type" to "string",
                        "description" to "What the button or link says or looks like, in the user's words"
                    )
                ),
                "required" to listOf("description")
            )
        ),
        mapOf(
            "type" to "function",
            "name" to "browser_type",
            "description" to
                "Type text into a field on the page currently open in the browser, by " +
                "what the field is for, without re-planning the whole task. Use for " +
                "'type headphones in the search box', 'put my name in the name field'. " +
                "Never used for passwords, OTPs or card numbers — those are always the " +
                "user's own step.",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "field" to mapOf(
                        "type" to "string",
                        "description" to "Which field, by its label or placeholder, in the user's words"
                    ),
                    "text" to mapOf(
                        "type" to "string",
                        "description" to "The text to type"
                    ),
                    "submit" to mapOf(
                        "type" to "boolean",
                        "description" to "True if this should also submit the field (press Enter)"
                    )
                ),
                "required" to listOf("field", "text")
            )
        ),
        mapOf(
            "type" to "function",
            "name" to "browser_back",
            "description" to
                "Go back to the previous page in the browser. Use for 'go back', " +
                "'previous page'.",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to emptyMap<String, Any>()
            )
        ),
        mapOf(
            "type" to "function",
            "name" to "browser_forward",
            "description" to
                "Go forward to the next page in the browser, after having gone back. " +
                "Use for 'go forward'.",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to emptyMap<String, Any>()
            )
        ),
        mapOf(
            "type" to "function",
            "name" to "read_current_page",
            "description" to
                "Read back what is on the page the browser is currently showing. Use " +
                "when the user asks 'what does it say', 'read that', 'summarise this " +
                "page', or asks a question about the page just opened.",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "question" to mapOf(
                        "type" to "string",
                        "description" to "What the user wants to know, if they asked something specific"
                    )
                )
            )
        ),
        mapOf(
            "type" to "function",
            "name" to "catch_up_on_ai",
            "description" to
                "Tell the user where their work with an AI assistant has got to, by " +
                "opening that service in the browser and reading their recent " +
                "conversations. Use for 'how far is my Claude project', 'what was I " +
                "doing in ChatGPT', 'catch me up on my Gemini chats'.",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "service" to mapOf(
                        "type" to "string",
                        "description" to "Which assistant: claude, chatgpt or gemini"
                    ),
                    "question" to mapOf(
                        "type" to "string",
                        "description" to "What the user actually asked"
                    )
                ),
                "required" to listOf("service")
            )
        )
    )

    /**
     * Blocking entry point for the `onToolCall` dispatchers, which are plain
     * functions.
     *
     * The browser does its WebView work on the main thread, so blocking the
     * main thread here would deadlock. `onToolCall` is normally reached from
     * `Dispatchers.IO`, but the Activity implementations can be reached from
     * the main thread too — so this refuses to block there and says so, rather
     * than hanging the UI.
     */
    @JvmStatic
    fun handleBlocking(
        context: Context,
        toolName: String,
        args: Map<String, Any>
    ): String {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            Log.w(TAG, "Browser tool $toolName called on the main thread")
            return "I can't run the browser right now. Try again in a moment."
        }
        return kotlinx.coroutines.runBlocking { handle(context, toolName, args) }
    }

    /**
     * Runs one browser tool. Returns the sentence the glasses should say.
     *
     * Never throws: a voice turn that dies silently is worse than one that
     * says it went wrong.
     */
    suspend fun handle(
        context: Context,
        toolName: String,
        args: Map<String, Any>
    ): String {
        GlassBrowserEngine.init(context)

        return try {
            when (toolName) {
                "browse_web" -> browse(context, args["goal"]?.toString().orEmpty())
                "browser_continue" -> resume(context)
                "browser_cancel" -> cancel()
                "read_current_page" -> readPage(context, args["question"]?.toString())
                "catch_up_on_ai" -> catchUp(
                    context,
                    args["service"]?.toString().orEmpty(),
                    args["question"]?.toString()
                )
                "browser_scroll" -> scroll(
                    args["direction"]?.toString(),
                    args["amount"]?.toString()
                )
                "browser_click" -> click(args["description"]?.toString().orEmpty())
                "browser_type" -> type(
                    args["field"]?.toString().orEmpty(),
                    args["text"]?.toString().orEmpty(),
                    args["submit"]?.toString()?.toBooleanStrictOrNull() ?: false
                )
                "browser_back" -> backOrForward(forward = false)
                "browser_forward" -> backOrForward(forward = true)
                else -> "I don't know how to do that in the browser."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Tool $toolName failed", e)
            GlassBrowserEngine.markBusy(false)
            "Something went wrong in the browser."
        }
    }

    // ------------------------------------------------------------------ tools

    private suspend fun browse(context: Context, goal: String): String {
        if (goal.isBlank()) return "Tell me what you'd like me to do on the web."

        if (GlassBrowserEngine.isBusy) {
            return "I'm still working on the last thing. Give me a moment."
        }

        // A pending manual step blocks everything: doing a new task would leave
        // the old one silently abandoned.
        GlassBrowserEngine.pendingReason?.let { reason ->
            return "$reason Say continue when you're done."
        }

        GlassBrowserEngine.markBusy(true)
        return try {
            val runner = HeadlessAgentRunner(context)
            val outcome = runner.run(goal)
            outcome.spokenResult
        } finally {
            GlassBrowserEngine.markBusy(false)
        }
    }

    private suspend fun resume(context: Context): String {
        val goal = GlassBrowserEngine.resume()
            ?: return "There's nothing waiting to continue."

        GlassBrowserEngine.markBusy(true)
        return try {
            val runner = HeadlessAgentRunner(context)
            runner.run(goal, resuming = true).spokenResult
        } finally {
            GlassBrowserEngine.markBusy(false)
        }
    }

    private fun cancel(): String {
        if (!GlassBrowserEngine.awaitingUser && !GlassBrowserEngine.isBusy) {
            return "There's nothing to cancel."
        }
        GlassBrowserEngine.cancel()
        return "Okay, I've dropped that."
    }

    /**
     * A quick, direct action against whatever page is already open — no LLM
     * planning turn, no page snapshot round-trip through a planner prompt.
     * These exist so ordinary mid-browsing commands ("scroll down", "click
     * sign up") answer immediately instead of re-running the whole [browse]
     * goal loop, which was the only way to act on the page before.
     */
    private suspend fun scroll(direction: String?, amount: String?): String {
        if (GlassBrowserEngine.isBusy) return "Hang on, I'm still doing the last thing."
        if (GlassBrowserEngine.currentUrl() == null) return "There's no page open yet."

        val amountText = amount?.lowercase().orEmpty()
        val dir = if (direction?.lowercase()?.contains("up") == true) -1.0 else 1.0
        val magnitude = when {
            // No absolute "jump to edge" primitive exists in BrowserAction (by
            // design, the executor only performs the closed action set below),
            // so "top"/"bottom" is approximated with a large relative scroll —
            // comfortably more than any single page's height.
            "top" in amountText -> return runDirectAction(BrowserAction.Scroll(-25.0))
            "bottom" in amountText -> return runDirectAction(BrowserAction.Scroll(25.0))
            "lot" in amountText || "page" in amountText -> 1.6
            "bit" in amountText || "little" in amountText -> 0.4
            else -> 0.9
        }

        val action = BrowserAction.Scroll(dir * magnitude)
        return runDirectAction(action)
    }

    private suspend fun click(description: String): String {
        if (description.isBlank()) return "What should I click?"
        if (GlassBrowserEngine.isBusy) return "Hang on, I'm still doing the last thing."
        if (GlassBrowserEngine.currentUrl() == null) return "There's no page open yet."

        val page = GlassBrowserEngine.readPage()
        val target = findByLabel(page, description, includeInputs = false)
            ?: return "I can't find \"$description\" on this page."

        val action = BrowserAction.Click(target.selector, target.label)
        return runDirectAction(action, page)
    }

    private suspend fun type(field: String, text: String, submit: Boolean): String {
        if (field.isBlank() || text.isBlank()) return "What should I type, and into which field?"
        if (GlassBrowserEngine.isBusy) return "Hang on, I'm still doing the last thing."
        if (GlassBrowserEngine.currentUrl() == null) return "There's no page open yet."

        val page = GlassBrowserEngine.readPage()
        val target = findByLabel(page, field, includeInputs = true)
            ?: return "I can't find a \"$field\" field on this page."

        val action = BrowserAction.Type(target.selector, text, submit)
        return runDirectAction(action, page)
    }

    private suspend fun backOrForward(forward: Boolean): String {
        if (GlassBrowserEngine.isBusy) return "Hang on, I'm still doing the last thing."
        val action = if (forward) BrowserAction.Forward else BrowserAction.Back
        return runDirectAction(action)
    }

    /** One labelled, clickable/typable element found on the page. */
    private data class LabelledTarget(val selector: String, val label: String)

    /**
     * Best-effort match of a spoken description against the page's buttons,
     * links, and (optionally) input labels — same data [WebAgentPlanner] would
     * reason over, but matched directly instead of via an LLM call, so this
     * stays fast. Exact label match wins; otherwise the element whose label
     * contains the most words from the description wins.
     */
    private fun findByLabel(
        page: PageReader.PageSnapshot,
        description: String,
        includeInputs: Boolean
    ): LabelledTarget? {
        val query = description.lowercase().trim()
        val candidates = mutableListOf<LabelledTarget>()

        fun collect(key: String) {
            val arr = page.raw.optJSONArray(key) ?: return
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val label = o.optString("label")
                if (label.isBlank()) continue
                if (includeInputs && key == "inputs" && o.optBoolean(PageReader.SENSITIVE_FLAG)) continue
                candidates.add(LabelledTarget(o.optString("selector"), label))
            }
        }
        collect("buttons")
        collect("links")
        if (includeInputs) collect("inputs")

        if (candidates.isEmpty()) return null

        candidates.firstOrNull { it.label.equals(query, ignoreCase = true) }?.let { return it }
        candidates.firstOrNull { it.label.lowercase().contains(query) }?.let { return it }
        candidates.firstOrNull { query.contains(it.label.lowercase()) }?.let { return it }

        val queryWords = query.split(" ").filter { it.length > 2 }
        if (queryWords.isEmpty()) return null
        return candidates
            .map { it to queryWords.count { w -> it.label.lowercase().contains(w) } }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first
    }

    /** Validates then executes one action directly, outside the planner loop. */
    private suspend fun runDirectAction(
        action: BrowserAction,
        page: PageReader.PageSnapshot? = null
    ): String {
        val snapshot = page ?: GlassBrowserEngine.readPage()
        when (val verdict = ActionValidator.validate(action, snapshot)) {
            is ActionValidator.Verdict.Handoff -> {
                GlassBrowserEngine.requireUser(verdict.reason, null)
                return "${verdict.reason} Say continue when you're done."
            }
            is ActionValidator.Verdict.NeedsConfirmation -> {
                // A spoken "yes" is too weak a gate for anything the planner
                // itself refuses to do without an on-screen tap — send the
                // user to the Web screen the same way the full agent loop does.
                return "${verdict.prompt} Please do that step on the Web screen."
            }
            is ActionValidator.Verdict.Reject -> return "I couldn't do that: ${verdict.reason}"
            ActionValidator.Verdict.Allow -> Unit
        }

        GlassBrowserEngine.markBusy(true)
        return try {
            val executor = GlassBrowserEngine.executor()
            val result = withContextMain { executor.execute(action) }
            result.detail
        } finally {
            GlassBrowserEngine.markBusy(false)
        }
    }

    private suspend fun <T> withContextMain(block: suspend () -> T): T =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { block() }

    private suspend fun readPage(context: Context, question: String?): String {
        val url = GlassBrowserEngine.currentUrl()
            ?: return "There's no page open yet. Tell me what to look up."

        val content = GlassBrowserEngine.readContent()
        if (!content.ok || content.isEmpty) {
            return "There's nothing readable on that page."
        }

        val summary = PageSummarizer(context).summarize(
            content,
            PageSummarizer.Style.GENERAL,
            question
        ) ?: return "I couldn't read that page."

        Log.d(TAG, "Summarised $url")
        return summary.body
    }

    private suspend fun catchUp(
        context: Context,
        serviceName: String,
        question: String?
    ): String {
        val service = AiService.match(serviceName)
            ?: return "I can check Claude, ChatGPT or Gemini. Which one?"

        GlassBrowserEngine.markBusy(true)
        return try {
            GlassBrowserEngine.open(service.homeUrl)
            val content = GlassBrowserEngine.readContent()

            // A sign-in wall reads as an almost-empty page. Say what's actually
            // needed rather than summarising a login screen.
            if (!content.ok || content.text.length < MIN_CONTENT_CHARS) {
                GlassBrowserEngine.requireUser(
                    "I need you signed in to ${service.displayName}. " +
                        "Open the Web screen on your phone and log in.",
                    null
                )
                return "I need you signed in to ${service.displayName}. " +
                    "Open the Web section on your phone and log in — you only have to do it once. " +
                    "Then say continue."
            }

            val summary = PageSummarizer(context).summarize(
                content,
                PageSummarizer.Style.PROJECT_STATUS,
                question
            ) ?: return "I couldn't read your ${service.displayName} history."

            saveNote(context, "${service.displayName} catch-up", summary)
            summary.body
        } finally {
            GlassBrowserEngine.markBusy(false)
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Files a summary in Quick Notes so it survives the conversation — the user
     * heard it once, in a pocket, and will want it later.
     */
    private fun saveNote(
        context: Context,
        title: String,
        summary: PageSummarizer.Summary
    ) {
        try {
            QuickNotesManager(context).createNote(
                title = title,
                content = buildString {
                    append(summary.body)
                    append("\n\nSource: ").append(summary.sourceUrl)
                },
                createdBy = QuickNote.CreatedBy.AI
            )
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't save catch-up note", e)
        }
    }

    private const val MIN_CONTENT_CHARS = 400
}
