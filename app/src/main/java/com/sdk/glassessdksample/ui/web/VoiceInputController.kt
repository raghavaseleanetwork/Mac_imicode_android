package com.sdk.glassessdksample.ui.web

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Speech-to-text for the Web section's command bar.
 *
 * Follows the same recognizer setup the app already uses in `ChatActivity`,
 * including its most important lesson: the inline recognizer fails often and
 * for boring reasons (busy, no match, client error), so recoverable failures
 * fall back to the system speech dialog rather than showing the user an error.
 */
class VoiceInputController(
    private val activity: Activity,
    private val listener: Listener
) {

    interface Listener {
        /** Mic opened / closed — drives the listening UI. */
        fun onListeningChanged(listening: Boolean)

        /** Live transcription while the user is still speaking. */
        fun onPartial(text: String)

        /** Final transcription. */
        fun onResult(text: String)

        /** Recognition failed in a way the user should hear about. */
        fun onError(message: String)

        /** The inline recognizer gave up; open the system dialog instead. */
        fun onFallbackToSystemDialog(intent: Intent)
    }

    private var recognizer: SpeechRecognizer? = null

    var isListening: Boolean = false
        private set

    /**
     * Starts listening. Returns false when it couldn't start — because the mic
     * permission is being requested, or the device has no recognizer.
     */
    fun start(): Boolean {
        if (isListening) return true

        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQ_AUDIO
            )
            return false
        }

        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            listener.onError("Voice input isn't available on this device.")
            return false
        }

        setListening(true)

        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(activity).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    Log.w(TAG, "Speech error $error")
                    activity.runOnUiThread {
                        setListening(false)
                        // These are the everyday failures — retrying through the
                        // system dialog works far more often than reporting them.
                        val recoverable = error == SpeechRecognizer.ERROR_NO_MATCH ||
                            error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                            error == SpeechRecognizer.ERROR_CLIENT ||
                            error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                        if (recoverable) {
                            listener.onFallbackToSystemDialog(buildIntent(partial = false))
                        } else {
                            listener.onError(errorMessage(error))
                        }
                    }
                }

                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    activity.runOnUiThread {
                        setListening(false)
                        if (text.isNotBlank()) listener.onResult(text)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val text = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (!text.isNullOrBlank()) {
                        activity.runOnUiThread { listener.onPartial(text) }
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        return try {
            recognizer?.startListening(buildIntent(partial = true))
            true
        } catch (e: Exception) {
            Log.e(TAG, "startListening failed", e)
            setListening(false)
            listener.onError("Couldn't start voice input.")
            false
        }
    }

    /** Stops listening without discarding what has been heard so far. */
    fun stop() {
        if (!isListening) return
        try {
            recognizer?.stopListening()
        } catch (_: Exception) {
        }
        setListening(false)
    }

    /** Releases the recognizer. Call from the Activity's onDestroy. */
    fun release() {
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
        isListening = false
    }

    private fun setListening(value: Boolean) {
        if (isListening == value) return
        isListening = value
        listener.onListeningChanged(value)
    }

    private fun buildIntent(partial: Boolean) =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, partial)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500)
            // Several OEM recognizers refuse to start without the caller named.
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, activity.packageName)
            if (!partial) putExtra(RecognizerIntent.EXTRA_PROMPT, "Say your command")
        }

    private fun errorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Microphone problem."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is off."
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Voice input needs a network connection."
        SpeechRecognizer.ERROR_SERVER -> "The speech service had a problem."
        else -> "Couldn't hear that."
    }

    companion object {
        private const val TAG = "VoiceInputController"

        /** Permission request code, also used by the Activity's callback. */
        const val REQ_AUDIO = 9701

        /** Request code for the system speech dialog fallback. */
        const val REQ_SYSTEM_SPEECH = 9702
    }
}
