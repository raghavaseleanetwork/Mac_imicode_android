package com.sdk.glassessdksample.wakeword

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import com.sdk.glassessdksample.R

/**
 * Shared wake-acknowledgment chime used by every wake-word engine.
 *
 * Each detector used to carry its own copy of this logic, all of them pointing
 * at a "chime" raw resource and assets/sounds/chime.mp3 that do not exist in
 * this project. Every detection therefore fell through to a synthesized
 * ToneGenerator beep. Loading lives here now so the engines cannot drift apart.
 *
 * Sourced from R.raw.wake_chime — the same resource MainActivity, Mark1MainActivity
 * and ListeningService play, so there is exactly one wake sound in the app.
 */
object WakeChime {

    private const val TAG = "WakeChime"

    /**
     * Build a MediaPlayer for the wake chime, or null if it cannot be loaded.
     * Callers own the returned player and must release() it.
     */
    fun createPlayer(context: Context): MediaPlayer? {
        return try {
            MediaPlayer.create(context, R.raw.wake_chime)?.apply { setVolume(1f, 1f) }
        } catch (e: Exception) {
            Log.w(TAG, "Chime preload failed: ${e.message}")
            null
        }
    }

    /**
     * Play the chime, restarting it if it is already sounding.
     *
     * Deliberately silent when [player] is null: the previous ToneGenerator
     * fallback produced the electronic beep this was meant to replace.
     */
    fun play(player: MediaPlayer?, tag: String = TAG) {
        try {
            player?.let { p ->
                if (p.isPlaying) p.seekTo(0) else p.start()
            }
        } catch (e: Exception) {
            Log.w(tag, "Chime play failed: ${e.message}")
        }
    }
}
