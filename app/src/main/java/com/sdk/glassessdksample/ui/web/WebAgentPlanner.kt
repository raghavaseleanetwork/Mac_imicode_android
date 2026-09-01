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
import org.json.JSONObject

/**
 * Decides the agent's next single action, using the Gemini key this app
 * already ships with.
 *
 * Design notes:
 * - **One action per call.** The model never plans a whole sequence up front;
 *   it sees the page as it actually is and picks the next move. Web pages
 *   change under you, so a plan made three steps ago is usually wrong.
 * - **JSON response mode.** `responseMimeType = application/json` removes the
 *   markdown-fence parsing that otherwise breaks these loops.
 * - The model returns an action *name*, never code. Executing it is
 *   [ActionExecutor]'s job and permitting it is [ActionValidator]'s.
 */
class WebAgentPlanner(private val context: Context?) {

    /** Reuses the AI_CHAT budget rather than adding a metering mode. */
    private val usageMode = TokenUsageTracker.Mode.AI_CHAT

    private val systemPrompt = """
        You are the browser agent inside the Imi Glass app. You control a real
        Android WebView on the user's phone to accomplish the user's goal.

        You are given the user's goal, what you have done so far, and a summary
        of the CURRENT page. Reply with exactly ONE next action as JSON.

        Allowed actions (JSON shapes):
        {"action":"open","url":"https://..."}
        {"action":"search","query":"...","engine":"google|youtube|bing|duckduckgo"}
        {"action":"click","selector":"<selector from the page summary>","label":"what it is"}
        {"action":"type","selector":"<selector>","text":"...","submit":true|false}
        {"action":"scroll","amount":0.8}
        {"action":"back"} {"action":"forward"} {"action":"reload"}
        {"action":"wait","reason":"..."}
        {"action":"ask_user","question":"..."}
        {"action":"handoff","reason":"..."}
        {"action":"done","summary":"..."}
        {"action":"failed","reason":"..."}

        Hard rules:
        1. Use ONLY selectors that appear verbatim in the page summary. Never
           invent a selector.
        2. NEVER type into a field marked [SENSITIVE]. Never type passwords,
           OTPs, card numbers or PINs anywhere. For those, use "handoff".
        3. If the page shows a login screen or a CAPTCHA, use "handoff" and
           explain what the user should do.
        4. If you need information only the user has (an address, a date, a
           choice between options), use "ask_user" with one clear question.
        5. Prefer "search" over hunting for a site's own search box.
        6. When the goal is met, use "done" with a short summary that answers
           the user's actual question. If you have read what the user asked
           for, put the answer in the summary itself.
        7. If you are stuck or the site blocks automation, use "failed" with a
           plain explanation. Do not loop.
        8. Do not repeat an action that has just failed. Try something else.

        Reply with JSON only.
    """.trimIndent()

    /**
     * Plans one step. Returns null when the model or the key is unavailable,
     * or when the reply couldn't be read as a known action.
     */
    suspend fun planNext(
        goal: String,
        page: PageReader.PageSnapshot?,
        history: List<ActionResult>,
        userAnswers: List<Pair<String, String>>
    ): BrowserAction? {
        if (!UsageLimitManager.tryConsume(context, usageMode)) {
            UsageLimitManager.promptUpgradeIfPossible(context, usageMode)
            return BrowserAction.Failed(UsageLimitManager.limitReachedMessage(usageMode))
        }

        val prompt = buildPrompt(goal, page, history, userAnswers)

        return try {
            withContext(Dispatchers.IO) {
                var lastError: Exception? = null
                for (modelName in CANDIDATE_MODELS) {
                    try {
                        val response = model(modelName).generateContent(prompt)
                        TokenUsageTracker.track(context, usageMode, response.usageMetadata)

                        val text = response.text?.trim().orEmpty()
                        if (text.isBlank()) {
                            lastError = IllegalStateException("empty response")
                            continue
                        }
                        return@withContext parseAction(text)
                    } catch (e: Exception) {
                        lastError = e
                        // Same fallback ladder GeminiAIClient uses: walk down
                        // to a model this key can actually reach.
                        if (isModelNotFound(e)) {
                            Log.w(TAG, "Model $modelName unavailable, trying next")
                            continue
                        }
                        throw e
                    }
                }
                Log.e(TAG, "Planning failed", lastError)
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Planner error: ${e.message}", e)
            null
        }
    }

    private fun model(modelName: String) = GenerativeModel(
        modelName = modelName,
        apiKey = RemoteConfigManager.geminiApiKey,
        systemInstruction = com.google.ai.client.generativeai.type.content {
            text(systemPrompt)
        },
        generationConfig = generationConfig {
            temperature = 0.1f          // planning wants determinism, not flair
            responseMimeType = "application/json"
            maxOutputTokens = 400
        }
    )

    private fun buildPrompt(
        goal: String,
        page: PageReader.PageSnapshot?,
        history: List<ActionResult>,
        userAnswers: List<Pair<String, String>>
    ): String {
        val sb = StringBuilder()
        sb.append("GOAL: ").append(goal).append("\n\n")

        if (userAnswers.isNotEmpty()) {
            sb.append("ANSWERS THE USER GAVE YOU:\n")
            userAnswers.takeLast(MAX_ANSWERS).forEach { (q, a) ->
                sb.append("- Q: ").append(q).append("\n  A: ").append(a).append('\n')
            }
            sb.append('\n')
        }

        if (history.isNotEmpty()) {
            sb.append("STEPS SO FAR (oldest first):\n")
            history.takeLast(MAX_HISTORY).forEach { r ->
                val mark = if (r.success) "ok" else "FAILED"
                sb.append("- ").append(r.action.describe())
                    .append(" -> ").append(mark).append(": ").append(r.detail).append('\n')
            }
            sb.append('\n')
        }

        if (page != null && page.ok) {
            sb.append("CURRENT PAGE:\n").append(trim(page.toPromptText()))
        } else {
            sb.append("CURRENT PAGE: blank — nothing is loaded yet.\n")
        }

        sb.append("\nWhat is your next single action? JSON only.")
        return sb.toString()
    }

    private fun parseAction(text: String): BrowserAction? {
        // JSON mode makes fences rare but not impossible; strip them if present.
        val cleaned = text
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()

        // Take the outermost JSON object, ignoring any prose around it.
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) {
            Log.w(TAG, "No JSON object in plan: $text")
            return null
        }
        val jsonText = cleaned.substring(start, end + 1)

        return try {
            BrowserAction.fromJson(JSONObject(jsonText))
        } catch (e: Exception) {
            Log.w(TAG, "Unparseable plan: $text")
            null
        }
    }

    private fun trim(text: String): String =
        if (text.length <= MAX_PAGE_CHARS) text else text.take(MAX_PAGE_CHARS)

    private fun isModelNotFound(e: Exception): Boolean {
        val message = e.message.orEmpty()
        return message.contains("404") ||
            message.contains("Not Found", ignoreCase = true) ||
            e.stackTraceToString().contains("404")
    }

    companion object {
        private const val TAG = "WebAgentPlanner"

        private val CANDIDATE_MODELS = listOf(
            "gemini-2.5-flash",
            "gemini-2.0-flash",
            "gemini-2.0-flash-lite"
        )

        private const val MAX_PAGE_CHARS = 6000
        private const val MAX_HISTORY = 8
        private const val MAX_ANSWERS = 5
    }
}
