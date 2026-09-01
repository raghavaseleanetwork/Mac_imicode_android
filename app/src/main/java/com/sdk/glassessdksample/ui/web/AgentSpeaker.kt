package com.sdk.glassessdksample.ui.web

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Speaks the agent's questions and results aloud.
 *
 * Uses Android's built-in [TextToSpeech] with the same locale and rate the
 * rest of the app uses (see `VisionChatActivity`), so the browser agent sounds
 * like the assistant the user already knows rather than a second voice.
 *
 * Speaking is a convenience, never a requirement: if TTS never becomes ready,
 * every call is a silent no-op and the on-screen text still carries the whole
 * message.
 */
class AgentSpeaker(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var ready = false

    /** User preference — when false, nothing is ever spoken. */
    var enabled: Boolean = true

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TTS init failed: $status")
            return
        }
        val result = tts?.setLanguage(Locale("en", "IN"))
        ready = result != TextToSpeech.LANG_MISSING_DATA &&
            result != TextToSpeech.LANG_NOT_SUPPORTED

        tts?.apply {
            setSpeechRate(0.95f)
            setPitch(1.0f)
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        }
    }

    /**
     * Speaks [text], cutting off anything already playing — the agent's newest
     * status is always the one worth hearing.
     */
    fun speak(text: String) {
        if (!enabled || !ready || text.isBlank()) return
        try {
            tts?.speak(
                text.take(MAX_CHARS),
                TextToSpeech.QUEUE_FLUSH,
                null,
                "web_agent_${System.currentTimeMillis()}"
            )
        } catch (e: Exception) {
            Log.w(TAG, "speak failed", e)
        }
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
    }

    fun release() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        ready = false
    }

    companion object {
        private const val TAG = "AgentSpeaker"

        /** Long summaries are read on screen; speech stays digestible. */
        private const val MAX_CHARS = 400
    }
}
