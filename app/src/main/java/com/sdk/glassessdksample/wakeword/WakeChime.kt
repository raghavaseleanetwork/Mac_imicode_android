package com.sdk.glassessdksample.wakeword

import android.content.Context
import android.util.Log
import com.sdk.glassessdksample.utils.WakeChimePlayer

/**
 * Shared wake-acknowledgment chime used by every wake-word engine.
 *
 * Each detector used to carry its own copy of this logic, all of them pointing
 * at a "chime" raw resource and assets/sounds/chime.mp3 that do not exist in
 * this project. Every detection therefore fell through to a synthesized
 * ToneGenerator beep. Loading lives here now so the engines cannot drift apart.
 *
 * Playback delegates to [WakeChimePlayer], the single implementation shared with
 * MainActivity, Mark1MainActivity and ListeningService.
 *
 * This used to hand each detector its own MediaPlayer. A detector fires while SCO
 * is already held for the glasses mic (A2DP suspended for the life of that link),
 * and MediaPlayer's default media-stream output against that MODE_IN_COMMUNICATION
 * session was accepted by the OS but rendered to a suspended or re-routing path —
 * silent on many phones, with nothing in the logs. MediaPlayer.create() also
 * decoded synchronously on the detector's audio thread, which both stalled
 * detection and lost the chime entirely when the decode outlived the player.
 */
object WakeChime {

    private const val TAG = "WakeChime"

    /**
     * Warm the shared chime so the first detection plays instantly.
     *
     * Kept for call-site compatibility with the detectors, which used to own a
     * MediaPlayer each. There is no per-caller player any more, so the return
     * value is only a "did it load" hint and nothing needs releasing.
     */
    fun createPlayer(context: Context): Context? {
        return try {
            WakeChimePlayer.preload(context)
            context
        } catch (e: Exception) {
            Log.w(TAG, "Chime preload failed: ${e.message}")
            null
        }
    }

    /**
     * Play the chime. Safe to call from a detector's audio thread.
     *
     * Deliberately silent when [context] is null: the previous ToneGenerator
     * fallback produced the electronic beep this was meant to replace.
     */
    fun play(context: Context?, tag: String = TAG) {
        try {
            context?.let { WakeChimePlayer.play(it) }
        } catch (e: Exception) {
            Log.w(tag, "Chime play failed: ${e.message}")
        }
    }
}
