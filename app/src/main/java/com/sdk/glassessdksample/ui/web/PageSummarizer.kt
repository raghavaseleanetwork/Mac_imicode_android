package com.sdk.glassessdksample.ui.web

import android.content.Context
import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.generationConfig
import com.sdk.glassessdksample.RemoteConfigManager
import com.sdk.glassessdksample.ui.TokenUsageTracker
import com.sdk.glassessdksample.ui.UsageLimitManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Summarises whatever page the user is on, using the app's existing Gemini key.
 *
 * This is what turns the browser from something the agent *drives* into
 * something it can *read back to you* — "where is my project up to", "what does
 * this article say", "catch me up on this thread".
 */
class PageSummarizer(private val context: Context?) {

    /** Reuses the AI_CHAT budget, same as [WebAgentPlanner]. */
    private val usageMode = TokenUsageTracker.Mode.AI_CHAT

    /** What the user wants out of the page. */
    enum class Style {
        /** Neutral "what is this page" summary. */
        GENERAL,

        /** A work-in-progress read: state, decisions, what's next. */
        PROJECT_STATUS,

        /** A back-and-forth (chat thread, comments) condensed. */
        CONVERSATION
    }

    data class Summary(
        val title: String,
        val body: String,
        val sourceUrl: String
    )

    private fun systemPrompt(style: Style) = when (style) {
        Style.GENERAL -> """
            You summarise web pages for a smart-glasses assistant.
            Write for someone who will hear this read aloud.
            Lead with the single most useful sentence, then 3-6 short bullets.
            Be concrete: names, numbers, dates. No preamble, no "this article".
            If the page is mostly navigation or an error, say so plainly.
        """
        Style.PROJECT_STATUS -> """
            You summarise a work-in-progress from a page the user was working on.
            Answer, in this order and only where the page supports it:
            1. What the project is, in one sentence.
            2. Where it has got to right now.
            3. What was decided or completed most recently.
            4. What is still open or next.
            Be concrete and brief. If the page doesn't show progress, say that
            instead of guessing.
        """
        Style.CONVERSATION -> """
            You condense a conversation thread.
            Give: what it is about, the key points each side made, anything
            decided, and any open question. Short bullets. Attribute points to
            speakers where the page makes that clear. Never invent turns.
        """
    }.trimIndent()

    /**
     * Summarises [content]. [question] optionally narrows it to what the user
     * actually asked. Returns null when the model is unreachable.
     */
    suspend fun summarize(
        content: PageContentExtractor.Content,
        style: Style = Style.GENERAL,
        question: String? = null
    ): Summary? {
        if (!content.ok || content.isEmpty) return null

        if (!UsageLimitManager.tryConsume(context, usageMode)) {
            UsageLimitManager.promptUpgradeIfPossible(context, usageMode)
            return Summary(
                title = content.title.ifBlank { "Summary" },
                body = UsageLimitManager.limitReachedMessage(usageMode),
                sourceUrl = content.url
            )
        }

        val prompt = buildString {
            if (!question.isNullOrBlank()) {
                append("The user asked: ").append(question).append("\n\n")
                append("Answer it from the page below. ")
                append("If the page doesn't contain the answer, say so.\n\n")
            }
            append("PAGE TITLE: ").append(content.title).append('\n')
            append("PAGE URL: ").append(content.url).append("\n\n")
            append("PAGE CONTENT:\n").append(content.text)
            if (content.truncated) {
                append("\n\n[Content was truncated — summarise what is here.]")
            }
        }

        return try {
            withContext(Dispatchers.IO) {
                var lastError: Exception? = null
                for (modelName in CANDIDATE_MODELS) {
                    try {
                        val response = model(modelName, style).generateContent(prompt)
                        TokenUsageTracker.track(context, usageMode, response.usageMetadata)

                        val text = response.text?.trim().orEmpty()
                        if (text.isBlank()) {
                            lastError = IllegalStateException("empty summary")
                            continue
                        }
                        return@withContext Summary(
                            title = content.title.ifBlank { "Summary" },
                            body = text,
                            sourceUrl = content.url
                        )
                    } catch (e: Exception) {
                        lastError = e
                        if (isModelNotFound(e)) {
                            Log.w(TAG, "Model $modelName unavailable, trying next")
                            continue
                        }
                        throw e
                    }
                }
                Log.e(TAG, "Summarize failed", lastError)
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Summarizer error: ${e.message}", e)
            null
        }
    }

    private fun model(modelName: String, style: Style) = GenerativeModel(
        modelName = modelName,
        apiKey = RemoteConfigManager.geminiApiKey,
        systemInstruction = com.google.ai.client.generativeai.type.content {
            text(systemPrompt(style))
        },
        generationConfig = generationConfig {
            temperature = 0.3f
            maxOutputTokens = 800
        }
    )

    private fun isModelNotFound(e: Exception): Boolean {
        val message = e.message.orEmpty()
        return message.contains("404") ||
            message.contains("Not Found", ignoreCase = true) ||
            e.stackTraceToString().contains("404")
    }

    companion object {
        private const val TAG = "PageSummarizer"

        private val CANDIDATE_MODELS = listOf(
            "gemini-2.5-flash",
            "gemini-2.0-flash",
            "gemini-2.0-flash-lite"
        )
    }
}
