package com.sdk.glassessdksample.wakeword

import com.sdk.glassessdksample.RemoteConfigManager
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import ai.picovoice.porcupine.PorcupineException
import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.sdk.glassessdksample.BuildConfig
import java.io.File
import java.io.FileOutputStream

/**
 * PorcupineWakeWordDetector
 *
 * Uses Picovoice Porcupine v4 SDK (PorcupineManager) to detect "Hey IMI".
 * PorcupineManager manages its own AudioRecord — no manual mic thread needed.
 *
 * Drop-in replacement for HeyImiWakeWordDetector — same public API:
 *   initialize() / start() / stop() / cleanup() / isListening()
 *   playChimeSound() / setThreshold() / getThreshold() / processExternalAudio()
 */
class PorcupineWakeWordDetector(
    private val context: Context,
    private val onWakeWordDetected: (confidence: Float) -> Unit
) {
    companion object {
        private const val TAG = "PorcupineDetector"
        private const val PPN_ASSET = "hey-imi_en_android_v4_0_0.ppn"

        // Sensitivity [0.0 – 1.0]
        private const val DEFAULT_SENSITIVITY = 0.7f
        private const val DETECTION_COOLDOWN_MS = 2000L
        private const val FRAME_LENGTH = 512   // Porcupine fixed frame size
    }

    // ── State ────────────────────────────────────────────────────────────────
    private var porcupineManager: PorcupineManager? = null
    @Volatile private var isListening = false
    private var sensitivity = DEFAULT_SENSITIVITY
    private var lastDetectionTime = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    // Context handle for the shared WakeChimePlayer, not a per-detector player.
    // Each detector used to own a MediaPlayer here; playback now goes through the
    // one shared SoundPool so the chime survives the SCO route the detector runs
    // under, and there is nothing per-detector left to release.
    private var chimePlayer: Context? = null

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Initialise Porcupine. Must be called once before start().
     */
    @Throws(Exception::class)
    fun initialize() {
        val accessKey = RemoteConfigManager.picovoiceAccessKey
        if (accessKey.isBlank()) {
            throw IllegalStateException("PICOVOICE_ACCESS_KEY is not configured in Firebase Remote Config")
        }

        // Copy .ppn model from assets → internal storage so Porcupine can load it by path
        val ppnFile = copyAssetToFilesDir(PPN_ASSET)

        try {
            val callback = PorcupineManagerCallback { keywordIndex ->
                Log.i(TAG, "🔥 Porcupine keyword detected! index=$keywordIndex")
                triggerDetection()
            }

            porcupineManager = PorcupineManager.Builder()
                .setAccessKey(accessKey)
                .setKeywordPath(ppnFile.absolutePath)
                .setSensitivity(sensitivity)
                .build(context, callback)

            Log.i(TAG, "✅ Porcupine v4 initialized — model: $PPN_ASSET  sensitivity=$sensitivity")
        } catch (e: PorcupineException) {
            Log.e(TAG, "❌ Porcupine init failed: ${e.message}", e)
            throw e
        }

        preloadChime()
    }

    /** Set sensitivity [0.0 – 1.0]. Call before initialize(). */
    fun setThreshold(value: Float) {
        sensitivity = value.coerceIn(0.0f, 1.0f)
        Log.d(TAG, "Porcupine sensitivity set to: $sensitivity")
    }

    fun getThreshold(): Float = sensitivity

    /**
     * Start listening for "Hey IMI".
     * PorcupineManager opens the microphone internally — no AudioRecord needed.
     */
    fun start() {
        if (isListening) { Log.w(TAG, "Already listening"); return }
        if (porcupineManager == null) { Log.e(TAG, "Call initialize() first"); return }

        try {
            porcupineManager?.start()
            isListening = true
            Log.i(TAG, "🎤 Porcupine listening started — say 'Hey IMI'")
        } catch (e: PorcupineException) {
            Log.e(TAG, "❌ Failed to start Porcupine: ${e.message}", e)
        }
    }

    /** Stop listening. Mic is released by PorcupineManager automatically. */
    fun stop() {
        if (!isListening) return
        try {
            porcupineManager?.stop()
            isListening = false
            Log.i(TAG, "🛑 Porcupine listening stopped")
        } catch (e: PorcupineException) {
            Log.e(TAG, "Error stopping Porcupine: ${e.message}")
        }
    }

    /** Release all Porcupine resources. */
    fun cleanup() {
        stop()
        try {
            porcupineManager?.delete()
            porcupineManager = null
            chimePlayer = null
            Log.i(TAG, "✅ Porcupine resources released")
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup error: ${e.message}")
        }
    }

    fun isListening(): Boolean = isListening

    /** Play wake-acknowledgment chime. */
    fun playChimeSound() {
        WakeChime.play(chimePlayer, TAG)
    }

    /**
     * Feed PCM audio from external source (e.g. BLE Glass mic).
     * Note: PorcupineManager v4 manages its own microphone internally.
     * This method is a no-op for API compatibility.
     */
    fun processExternalAudio(pcmData: ByteArray) {
        // PorcupineManager doesn't expose frame-level processing
        // It handles the microphone automatically
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private fun triggerDetection() {
        val now = System.currentTimeMillis()
        if (now - lastDetectionTime < DETECTION_COOLDOWN_MS) return
        lastDetectionTime = now

        Log.i(TAG, "🔥 Hey IMI DETECTED by Porcupine!")
        isListening = false   // auto-stop; HotHelper will restart after Gemini finishes

        mainHandler.post {
            playChimeSound()
            onWakeWordDetected(1.0f)
        }
    }

    private fun copyAssetToFilesDir(assetName: String): File {
        val outFile = File(context.filesDir, assetName)
        // Always re-copy to ensure we have the latest .ppn file
        context.assets.open(assetName).use { input ->
            FileOutputStream(outFile).use { output -> input.copyTo(output) }
        }
        Log.d(TAG, "✅ Copied $assetName → ${outFile.absolutePath}  (${outFile.length()} bytes)")
        return outFile
    }

    private fun preloadChime() {
        chimePlayer = WakeChime.createPlayer(context)
    }
}
