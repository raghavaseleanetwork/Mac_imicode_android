package com.sdk.glassessdksample.utils

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.sdk.glassessdksample.R
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The "Hey IMI" wake chime, played the same way from every wake path.
 *
 * ## Why this exists
 *
 * The chime was previously implemented three separate times (Mark 1, Mark 2 and
 * the background service), each with MediaPlayer and slightly different audio
 * attributes. On many phones it simply never sounded, while the logs looked
 * completely healthy. Two independent causes:
 *
 * 1. **Wrong route.** By the time a wake word fires, SCO is already up for the
 *    glasses mic, and A2DP is suspended for exactly as long as SCO is held. A
 *    MediaPlayer opening a fresh USAGE_MEDIA / STREAM_MUSIC stream against that
 *    MODE_IN_COMMUNICATION session gets accepted by the OS and then rendered to a
 *    path that is suspended or still being re-routed - audible on phones that
 *    tolerate the re-route, silent on the many that do not. GeminiLiveService
 *    already hit and documented this for its "thinking" cue and solved it by
 *    matching the session's attributes exactly; the wake chime never got the
 *    same treatment.
 *
 * 2. **Synchronous decode per wake.** MediaPlayer.create() decodes on the calling
 *    thread every single time. On slower devices that decode did not finish
 *    before the conversation start tore the player down, so nothing played.
 *
 * SoundPool fixes both: it decodes once into memory up front, and it is built
 * with VOICE_COMMUNICATION attributes so the chime shares the very stream the
 * glasses audio is already using instead of forcing a Bluetooth re-route.
 *
 * ## Usage
 *
 * Call [preload] once (from Activity/Service create) and [play] on wake. [play]
 * always invokes its callback exactly once - on completion, on failure, or via a
 * watchdog - so a silent chime can never swallow the conversation start.
 */
object WakeChimePlayer {

    private const val TAG = "WakeChimePlayer"

    /**
     * Length of res/raw/wake_chime.wav: 414,032 data bytes at 44.1 kHz stereo
     * 16-bit (176,400 B/s) = 2,347 ms, rounded up. Mirrors ListeningService's
     * constant; swapping the audio file means updating both.
     */
    const val CHIME_DURATION_MS = 2_350L

    private var soundPool: SoundPool? = null
    private var soundId: Int = 0
    private var loaded = false
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Decode the chime into memory. Safe to call repeatedly; only the first call
     * does work. Call well before the first wake word so playback is instant.
     */
    @Synchronized
    fun preload(context: Context) {
        if (soundPool != null) return
        try {
            // These attributes MUST match the ones the glasses audio session uses.
            // USAGE_VOICE_COMMUNICATION keeps the chime on the same stream that is
            // already routed to the glasses, so no re-route happens on play and the
            // sound is not dropped while SCO holds the route.
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val pool = SoundPool.Builder()
                .setMaxStreams(1)
                .setAudioAttributes(attrs)
                .build()

            pool.setOnLoadCompleteListener { _, _, status ->
                loaded = status == 0
                if (!loaded) Log.w(TAG, "Wake chime failed to load (status=$status)")
            }

            soundId = pool.load(context.applicationContext, R.raw.wake_chime, 1)
            soundPool = pool
        } catch (e: Exception) {
            Log.w(TAG, "Could not preload wake chime: ${e.message}")
            soundPool = null
        }
    }

    /**
     * Play the chime, then invoke [then] exactly once.
     *
     * [then] fires as soon as playback has started rather than when it finishes:
     * the socket and SCO setup do not need the speaker, so waiting for the full
     * chime would just add dead air before IMI can listen. The watchdog covers the
     * case where the chime cannot play at all.
     *
     * @param waitForChime when true, [then] is delayed until the chime has
     *        finished. Used by the background service, where starting the live mic
     *        while the chime is still sounding would feed the chime into it.
     */
    fun play(context: Context, waitForChime: Boolean = false, then: () -> Unit = {}) {
        val fired = AtomicBoolean(false)
        val once = { if (fired.compareAndSet(false, true)) then() }

        // Backstop: whatever happens to the audio, the conversation still starts.
        val watchdog = if (waitForChime) CHIME_DURATION_MS + 250 else 800L
        handler.postDelayed({ once() }, watchdog)

        try {
            preload(context)
            val pool = soundPool
            if (pool == null || !loaded) {
                // Preload may still be decoding on a cold first wake. Rather than
                // dropping the chime, let the watchdog start the conversation and
                // play whatever is ready.
                Log.w(TAG, "Wake chime not ready yet (loaded=$loaded)")
                if (pool != null && soundId != 0) pool.play(soundId, 1f, 1f, 1, 0, 1f)
                if (!waitForChime) once()
                return
            }

            // Raise the voice-call stream if it is muted or near-silent, otherwise
            // the chime plays correctly but inaudibly - indistinguishable from the
            // routing failure this class exists to fix.
            ensureAudibleVolume(context)

            val streamId = pool.play(soundId, 1f, 1f, 1, 0, 1f)
            if (streamId == 0) {
                Log.w(TAG, "SoundPool refused to play wake chime")
                once()
                return
            }

            if (!waitForChime) once()
        } catch (e: Exception) {
            Log.w(TAG, "Wake chime failed: ${e.message}")
            once()
        }
    }

    /**
     * Nudge the voice-call stream up if it is sitting at or near zero.
     *
     * Only raises to 60% of max and never lowers, so a user who has deliberately
     * turned the volume down keeps their setting; this exists purely so a stream
     * left at 0 by an earlier call or SCO handover does not silence the chime.
     */
    private fun ensureAudibleVolume(context: Context) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val stream = AudioManager.STREAM_VOICE_CALL
            val max = am.getStreamMaxVolume(stream)
            val current = am.getStreamVolume(stream)
            if (current <= max / 5) {
                am.setStreamVolume(stream, (max * 0.6f).toInt().coerceAtLeast(1), 0)
                Log.d(TAG, "Raised voice-call volume for wake chime ($current -> ${am.getStreamVolume(stream)})")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not check wake chime volume: ${e.message}")
        }
    }

    /** Release the decoded sample. Call from onDestroy of the last owner. */
    @Synchronized
    fun release() {
        try {
            soundPool?.release()
        } catch (_: Exception) {
        }
        soundPool = null
        loaded = false
        soundId = 0
    }
}
