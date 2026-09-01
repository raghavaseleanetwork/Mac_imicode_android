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
        "read_current_page",
        "catch_up_on_ai"
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
                "Control a real web browser to carry out a task on a website. Use this " +
                "when the user wants something DONE on a site rather than just answered: " +
                "'open my email and check', 'find flights to Delhi on this site', " +
                "'search Amazon for headphones and tell me the price', 'go to the " +
                "cricket site and tell me the score'. The browser stays signed in to " +
                "sites the user has logged into before. Describe the whole task in the " +
                "'goal' parameter, in the user's own words.",
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
                "read_current_page" -> readPage(context, args["question"]?.toString())
                "catch_up_on_ai" -> catchUp(
                    context,
                    args["service"]?.toString().orEmpty(),
                    args["question"]?.toString()
                )
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
