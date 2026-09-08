package com.sdk.glassessdksample.ui

import com.sdk.glassessdksample.RemoteConfigManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.sdk.glassessdksample.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GeminiAIClient(
    private val context: Context? = null,
    private val chatModelName: String = DEFAULT_CHAT_MODEL,
    private val fallbackChatModelName: String? = null
) {

    companion object {
        private const val TAG = "GeminiAIClient"
        // Cheapest/lowest Gemini chat model. If the key can't access it (404),
        // the client falls back to CHAT_MODEL_FALLBACK.
        const val CHAT_MODEL_FLASH_LITE = "gemini-2.0-flash-lite"
        // Known-good model for this key (vision uses gemini-2.5-flash).
        const val CHAT_MODEL_FALLBACK = "gemini-2.0-flash"
        private const val DEFAULT_CHAT_MODEL = "gemini-2.5-flash"
        private const val MAX_GALLERY_CONTEXT_CHARS = 900
        private const val MAX_MEMORY_CONTEXT_CHARS = 900
        private const val MAX_NOTES_CONTEXT_CHARS = 1000
        private const val MAX_MEETINGS_CONTEXT_CHARS = 1500
        private const val MAX_CHAT_PROMPT_CHARS = 1800
        private const val MAX_HISTORY_ENTRIES = 6
    }

    // User memory manager for personalization
    private val memoryManager: UserMemoryManager? by lazy {
        context?.let { UserMemoryManager(it) }
    }

    // Quick Notes + Meeting Minutes, so the typed chat can answer questions
    // about the user's saved notes and meetings just like the voice assistant.
    private val notesManager: QuickNotesManager? by lazy {
        context?.let { QuickNotesManager(it) }
    }
    private val meetingsManager: MeetingMinutesManager? by lazy {
        context?.let { MeetingMinutesManager(it) }
    }

    private fun buildNotesContext(): String {
        val notes = notesManager?.getNotesContextForAI()?.trim().orEmpty()
        // The manager returns a "no notes" placeholder when empty — skip it.
        if (notes.isBlank() || notes.startsWith("No quick notes")) return ""
        return trimForPrompt(notes, MAX_NOTES_CONTEXT_CHARS)
    }

    private fun buildMeetingsContext(): String {
        val meetings = meetingsManager?.getMeetingsContextForAI()?.trim().orEmpty()
        if (meetings.isBlank()) return ""
        return trimForPrompt(meetings, MAX_MEETINGS_CONTEXT_CHARS)
    }

    // Compact system prompt for lower token usage in chat.
    //
    // Answer length and tone are deliberately NOT stated here: they come from the
    // user's AI Preferences and are appended by [baseSystemPrompt] below. This
    // block used to say "Keep answers short and practical (usually 1-2 sentences)",
    // which silently overrode the Settings choice - picking Balanced or Detailed
    // changed nothing, because this line always won.
    private val baseSystemPromptTemplate = """
        You are Imi Glass, an AI assistant for smart glasses by IMI Wearables.
        Reply in the same language as the user (Hindi or English).
        Give direct answers first; no long introductions.
        You can see the user's saved Quick Notes, Meeting Minutes (summaries and
        transcripts), captured photos, and learned profile. When the user asks
        about their notes, meetings, or anything they saved, answer from that
        data. If the requested item isn't in the provided data, say so plainly.
        If asked who made you, say you were built by Ajay Mehta at IMI Wearables.
        If uncertain, say so clearly instead of guessing.
    """.trimIndent()

    /**
     * Base prompt with the user's chosen answer length and tone applied.
     *
     * A getter rather than a val: it is read on every request, so changing the
     * preference in Settings takes effect on the next message instead of needing
     * the app restarted. Falls back to the bare template when no context is
     * available (this client is constructible without one).
     */
    private val baseSystemPrompt: String
        get() {
            val style = context?.let {
                runCatching { AiResponsePrefs.buildResponseStyleInstruction(it) }.getOrNull()
            }
            return if (style.isNullOrBlank()) baseSystemPromptTemplate
            else "$baseSystemPromptTemplate\n$style"
        }

    private fun trimForPrompt(text: String, maxChars: Int): String {
        val normalized = text
            .replace("\\r", "")
            .trim()
        if (normalized.length <= maxChars) return normalized
        return normalized.take(maxChars)
    }
    
    /**
     * Get the complete system prompt with user memory context.
     *
     * Memory is built automatically from past conversations – the user never
     * needs to enter anything manually.  Every piece of information is learned
     * from what the user says in chat.
     */
    /**
     * Reads all AI-generated image notes and returns them as context text.
     *
     * Primary source : ImageDescriptionStore vault JSON (always up-to-date)
     * Fallback source: legacy .desc.txt sidecar files in the live_gallery dir
     *
     * This gives the chat AI full knowledge of every captured photo, including
     * which one was captured LAST (used when the user asks "describe my last photo").
     */
    private fun buildGalleryContext(): String {
        val ctx = context ?: return ""

        val vaultContext = com.sdk.glassessdksample.ui.gallery.ImageDescriptionStore
            .buildAIContext(ctx)
        if (vaultContext.isNotBlank()) {
            Log.d(TAG, "Injecting compact vault image context")
            return trimForPrompt(vaultContext, MAX_GALLERY_CONTEXT_CHARS)
        }

        val galleryDir = ctx.filesDir?.let { java.io.File(it, "live_gallery") } ?: return ""
        if (!galleryDir.exists()) {
            Log.d(TAG, "Gallery dir does not exist yet")
            return ""
        }
        val descFiles = galleryDir.listFiles { f -> f.name.endsWith(".desc.txt") }
            ?.sortedByDescending { it.lastModified() }
            ?.take(5)
            ?: return ""
        if (descFiles.isEmpty()) {
            Log.d(TAG, "No .desc.txt files found in gallery")
            return ""
        }
        Log.d(TAG, "Injecting ${descFiles.size} compact gallery image notes")
        val fmt = java.text.SimpleDateFormat("MMM dd HH:mm", java.util.Locale.getDefault())
        val entries = descFiles.mapIndexed { idx, f ->
            val time = fmt.format(java.util.Date(f.lastModified()))
            val description = trimForPrompt(f.readText().trim(), 120)
            "Image ${idx + 1} ($time): $description"
        }
        return trimForPrompt(
            """
            Photo notes (newest first):
            ${entries.joinToString("\n")}
            Use these notes when the user asks about previous images.
            """.trimIndent(),
            MAX_GALLERY_CONTEXT_CHARS
        )
    }

    private fun getSystemPromptWithMemory(): String {
        val userMemory = memoryManager?.getUserMemory()
        val galleryContext = buildGalleryContext()
        val notesContext = buildNotesContext()
        val meetingsContext = buildMeetingsContext()

        // Headroom for the largest possible system prompt across all sources.
        val maxLen = MAX_MEMORY_CONTEXT_CHARS + MAX_GALLERY_CONTEXT_CHARS +
            MAX_NOTES_CONTEXT_CHARS + MAX_MEETINGS_CONTEXT_CHARS + 700

        return if (userMemory != null && !userMemory.isEmpty()) {
            val memoryContext = trimForPrompt(userMemory.toPromptContext(), MAX_MEMORY_CONTEXT_CHARS)
            val relationshipNote = when {
                userMemory.totalMessages > 100 ->
                    "Long-term user relationship."
                userMemory.totalMessages > 20  ->
                    "Returning user. Use known preferences briefly."
                userMemory.totalMessages > 0   ->
                    "Early user profile available."
                else -> ""
            }
            trimForPrompt("""
            $baseSystemPrompt
            ${if (galleryContext.isNotBlank()) "\n$galleryContext" else ""}
            ${if (notesContext.isNotBlank()) "\n$notesContext" else ""}
            ${if (meetingsContext.isNotBlank()) "\n$meetingsContext" else ""}

            $memoryContext
            ${if (relationshipNote.isNotEmpty()) "\n$relationshipNote" else ""}

            Personalization:
            - Use known preferences naturally.
            - If name is known (${userMemory.userName.ifBlank { "user" }}), use it occasionally.
            """.trimIndent(), maxLen)
        } else {
            trimForPrompt("""
            $baseSystemPrompt
            ${if (galleryContext.isNotBlank()) "\n$galleryContext" else ""}
            ${if (notesContext.isNotBlank()) "\n$notesContext" else ""}
            ${if (meetingsContext.isNotBlank()) "\n$meetingsContext" else ""}
            """.trimIndent(), maxLen)
        }
    }

    // Recreate model per request so latest memory can be applied.
    private fun createGenerativeModel(modelName: String): GenerativeModel {
        return GenerativeModel(
            modelName = modelName,
            apiKey = RemoteConfigManager.geminiApiKey,
            systemInstruction = com.google.ai.client.generativeai.type.content { 
                text(getSystemPromptWithMemory()) 
            }
        )
    }

    private fun isModelNotFoundError(error: Exception): Boolean {
        val message = error.message ?: ""
        val stack = error.stackTraceToString()
        return message.contains("404") || message.contains("Not Found", ignoreCase = true) || stack.contains("404")
    }

    suspend fun chat(
        prompt: String,
        mode: TokenUsageTracker.Mode = TokenUsageTracker.Mode.AI_CHAT
    ): String {
        if (!UsageLimitManager.tryConsume(context, mode)) {
            UsageLimitManager.promptUpgradeIfPossible(context, mode)
            return UsageLimitManager.limitReachedMessage(mode)
        }

        return try {
            val compactPrompt = trimForPrompt(prompt, MAX_CHAT_PROMPT_CHARS)
            // Ordered list of models to try. We start with the cheapest and, on a
            // "model not found" (404), walk down to models the key can access.
            val candidateModels = listOfNotNull(
                chatModelName,
                fallbackChatModelName,
                DEFAULT_CHAT_MODEL
            ).distinct()
            val response = withContext(Dispatchers.IO) {
                var lastError: Exception? = null
                var result: com.google.ai.client.generativeai.type.GenerateContentResponse? = null
                for (modelName in candidateModels) {
                    try {
                        result = createGenerativeModel(modelName).generateContent(compactPrompt)
                        if (modelName != chatModelName) {
                            Log.w(TAG, "Used fallback chat model: $modelName")
                        }
                        break
                    } catch (e: Exception) {
                        lastError = e
                        if (isModelNotFoundError(e)) {
                            Log.w(TAG, "Model $modelName not available, trying next")
                            continue
                        }
                        throw e
                    }
                }
                result ?: throw (lastError ?: IllegalStateException("No chat model available"))
            }
            TokenUsageTracker.track(context, mode, response.usageMetadata)

            val text = response.text
            if (text.isNullOrBlank()) {
                Log.w(TAG, "Empty response.text from Gemini")
                "No response from Gemini."
            } else {
                text
            }
        } catch (e: Exception) {
            // Log full stacktrace for debugging
            Log.e(TAG, "Error calling Gemini API", e)
            val msg = e.message ?: "An error occurred calling Gemini."
            val stackTrace = e.stackTraceToString()
            
            // Handle specific error codes
            return when {
                msg.contains("429") || msg.contains("RESOURCE_EXHAUSTED") || stackTrace.contains("RESOURCE_EXHAUSTED") -> {
                    Log.e(TAG, "Quota exhausted - rate limit hit")
                    "API quota exceeded. Please wait a moment and try again."
                }
                msg.contains("404") || msg.contains("Not Found", true) -> {
                    "Model not available for your key."
                }
                msg.contains("MissingFieldException") || stackTrace.contains("MissingFieldException") -> {
                    Log.e(TAG, "JSON parsing error - likely an API error response")
                    "Service temporarily unavailable. Please try again."
                }
                msg.contains("401") || msg.contains("Unauthorized") -> {
                    "Invalid API key. Please check your configuration."
                }
                msg.contains("403") || msg.contains("Forbidden") -> {
                    "Access forbidden. Check API key permissions."
                }
                else -> {
                    "AI service error. Please try again later."
                }
            }
        }
    }

    // Overloaded chat function to handle history (optional)
    suspend fun chat(
        prompt: String,
        history: List<Pair<String, String>>,
        mode: TokenUsageTracker.Mode = TokenUsageTracker.Mode.AI_CHAT
    ): String {
        val compactHistory = history
            .takeLast(MAX_HISTORY_ENTRIES)
            .joinToString("\n") { "${it.first}: ${trimForPrompt(it.second, 180)}" }
        val fullPrompt = if (compactHistory.isBlank()) {
            trimForPrompt(prompt, MAX_CHAT_PROMPT_CHARS)
        } else {
            trimForPrompt("$compactHistory\nuser: ${trimForPrompt(prompt, 400)}", MAX_CHAT_PROMPT_CHARS)
        }
        return chat(fullPrompt, mode)
    }
    
    // Vision API - Analyze image and detect objects
    suspend fun analyzeImage(imageBytes: ByteArray, userPrompt: String = """Look at this image and describe EXACTLY what you see in detail. 
        |Be specific - mention brands, colors, text visible, object models/types. 
        |Don't give vague or generic answers. 
        |Describe as if explaining to someone who cannot see the image.""".trimMargin()): String {
        if (!UsageLimitManager.tryConsume(context, TokenUsageTracker.Mode.SEEING)) {
            UsageLimitManager.promptUpgradeIfPossible(context, TokenUsageTracker.Mode.SEEING)
            return UsageLimitManager.limitReachedMessage(TokenUsageTracker.Mode.SEEING)
        }

        return try {
            val response = withContext(Dispatchers.IO) {
                // Convert ByteArray to Bitmap for Gemini Vision API
                val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                    ?: return@withContext null
                
                val imagePart = com.google.ai.client.generativeai.type.content {
                    image(bitmap)
                }
                val promptPart = com.google.ai.client.generativeai.type.content {
                    text(userPrompt)
                }
                
                // Use vision model for image analysis
                val visionModel = GenerativeModel(
                    modelName = "gemini-2.5-flash",
                    apiKey = RemoteConfigManager.geminiApiKey
                )
                
                visionModel.generateContent(imagePart, promptPart)
            }
            
            response?.usageMetadata?.let {
                TokenUsageTracker.track(context, TokenUsageTracker.Mode.SEEING, it)
            }

            val text = response?.text
            if (text.isNullOrBlank()) {
                Log.w("GeminiAIClient", "Empty vision response from Gemini")
                "I couldn't analyze the image."
            } else {
                text
            }
        } catch (e: Exception) {
            Log.e("GeminiAIClient", "Error calling Gemini Vision API", e)
            val msg = e.message ?: "Vision analysis error"
            
            when {
                msg.contains("429") || msg.contains("RESOURCE_EXHAUSTED") -> {
                    "Vision API quota exceeded. Please wait a moment."
                }
                msg.contains("404") || msg.contains("Not Found", true) -> {
                    "Vision model not available."
                }
                else -> {
                    "I couldn't analyze the image. Please try again."
                }
            }
        }
    }
    

}
