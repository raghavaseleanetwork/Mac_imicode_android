package com.sdk.glassessdksample.ui.web

import java.util.Locale

/**
 * The AI services the Web section knows how to open and read back.
 *
 * This exists so "catch me up on my Claude project" resolves to a real URL and
 * the right summary style, instead of the agent guessing its way there.
 *
 * **Important limitation, by design:** none of this reads the user's Chrome-on-PC
 * history — an Android app cannot get at another browser's cookies. What it
 * does is open the service *in this WebView*, where the user has logged in once
 * themselves. Because these services keep conversations server-side, the same
 * account shows the same history, which gets the user the same answer.
 */
enum class AiService(
    val displayName: String,
    val homeUrl: String,
    /** Words in a command that should select this service. */
    val keywords: List<String>
) {
    CLAUDE(
        displayName = "Claude",
        homeUrl = "https://claude.ai/recents",
        keywords = listOf("claude", "anthropic")
    ),
    CHATGPT(
        displayName = "ChatGPT",
        homeUrl = "https://chatgpt.com",
        keywords = listOf("chatgpt", "chat gpt", "gpt", "openai")
    ),
    GEMINI(
        displayName = "Gemini",
        homeUrl = "https://gemini.google.com/app",
        keywords = listOf("gemini", "bard")
    );

    companion object {
        /** Finds the service a command refers to, or null. */
        fun match(command: String): AiService? {
            val text = command.lowercase(Locale.ROOT)
            return entries.firstOrNull { service ->
                service.keywords.any { text.contains(it) }
            }
        }
    }
}

/**
 * Classifies a typed or spoken command into what the Web section should
 * actually do with it.
 *
 * Kept as plain keyword matching rather than an extra Gemini call: routing is
 * cheap and predictable this way, and a misroute is more annoying than a
 * slightly rigid rule. Anything unrecognised falls through to the agent, which
 * is the general case.
 */
object CommandRouter {

    sealed class Route {
        /** Summarise the page already on screen. */
        data class SummarizeCurrent(
            val style: PageSummarizer.Style,
            val question: String?
        ) : Route()

        /** Open an AI service, then summarise what's there. */
        data class CatchUpOnService(
            val service: AiService,
            val question: String?
        ) : Route()

        /** Hand the whole thing to the browser agent. */
        object Agent : Route()
    }

    private val SUMMARY_WORDS = listOf(
        "summarise", "summarize", "summary", "tldr", "tl;dr",
        "catch me up", "catch up", "brief me", "brief about", "recap",
        "what does this say", "what is this page", "what's this page",
        "read this", "explain this page"
    )

    private val PROJECT_WORDS = listOf(
        "project", "how far", "progress", "where am i", "where did i",
        "status", "what was i doing", "what am i doing", "left off"
    )

    private val CONVERSATION_WORDS = listOf(
        "conversation", "thread", "chat", "discussion", "messages"
    )

    fun route(command: String): Route {
        val text = command.lowercase(Locale.ROOT)

        val wantsSummary = SUMMARY_WORDS.any { text.contains(it) }
        val wantsProject = PROJECT_WORDS.any { text.contains(it) }
        val service = AiService.match(text)

        // "How far is my Claude project" — go to the service and read it back.
        if (service != null && (wantsSummary || wantsProject)) {
            return Route.CatchUpOnService(service, command)
        }

        if (wantsSummary || wantsProject) {
            val style = when {
                wantsProject -> PageSummarizer.Style.PROJECT_STATUS
                CONVERSATION_WORDS.any { text.contains(it) } ->
                    PageSummarizer.Style.CONVERSATION
                else -> PageSummarizer.Style.GENERAL
            }
            return Route.SummarizeCurrent(style, command)
        }

        return Route.Agent
    }
}
