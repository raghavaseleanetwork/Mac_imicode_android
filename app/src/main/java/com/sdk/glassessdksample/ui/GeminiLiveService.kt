package com.sdk.glassessdksample.ui

import com.sdk.glassessdksample.RemoteConfigManager
import android.media.AudioFormat
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.SoundPool
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.media.audiofx.AutomaticGainControl
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import java.util.concurrent.CountDownLatch
import android.util.Log
import com.google.gson.Gson
import com.sdk.glassessdksample.BuildConfig
import com.sdk.glassessdksample.R
import kotlinx.coroutines.*
import okhttp3.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import com.sdk.glassessdksample.ui.ScoConnectionHelper


/**
 * GeminiLiveService - Android implementation of real-time bidirectional audio streaming
 * Supports BOTH OpenAI GPT Realtime API and Google Gemini Live API.
 * User can switch between models from the home screen.
 * 
 * This service handles:
 * - Real-time audio input capture (PCM16LE)
 * - Real-time audio output playback (PCM16LE)
 * - WebSocket-based bidirectional communication
 * - Live transcription (input and output)
 * - Turn-based conversation management
 */

/** Which AI provider to use for realtime voice */
enum class ModelProvider {
    GPT_REALTIME,      // OpenAI gpt-4o-mini-realtime-preview
    GEMINI_LIVE        // Google gemini-2.5-flash-native-audio
}

class GeminiLiveService(
    private val context: Context,
    private val callbacks: GeminiLiveCallbacks
) {
    companion object {
        private const val TAG = "GeminiLiveService"
        
        /**
         * Camera/vision tools. Only Mark 2 has a handler for these
         * (MainActivity.handleGeminiToolCall → VisionChatActivity), so they are
         * filtered out of the declared tool list on Mark 1.
         */
        private val VISION_TOOL_NAMES = setOf("analyze_view", "capture_new_frame")

        // SharedPreferences key for model selection
        const val PREF_NAME = "imi_model_prefs"
        const val PREF_KEY_MODEL = "selected_model" // "gpt" or "gemini"
        
        // 🆕 Singleton instance for cross-activity access
        @Volatile
        private var instance: GeminiLiveService? = null
        
        /**
         * Get the current active instance (if any)
         */
        fun getInstance(): GeminiLiveService? = instance
        
        /**
         * Check if Gemini Live is currently active
         */
        fun isActive(): Boolean = instance?.webSocket != null
        
        /**
         * Read saved model preference
         */
        fun getSavedModelProvider(context: Context): ModelProvider {
            // Gemini is the only user-facing provider. The GPT Realtime code path
            // is kept for internal use (e.g. the Whisper interview) but is never
            // selectable, so the app always defaults to Gemini Live here.
            return ModelProvider.GEMINI_LIVE
        }
        
        /**
         * Save model preference
         */
        fun saveModelProvider(context: Context, provider: ModelProvider) {
            // No-op: provider selection is locked to Gemini Live (see
            // getSavedModelProvider). Kept so existing call sites compile.
        }
        
        // Audio configuration constants
        // Both APIs use PCM16LE; sample rates differ
        private val CHANNEL_CONFIG_IN = android.media.AudioFormat.CHANNEL_IN_MONO
        private val CHANNEL_CONFIG_OUT = android.media.AudioFormat.CHANNEL_OUT_MONO
        private val AUDIO_FORMAT = android.media.AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_MULTIPLIER = 8
        
        // Pre-buffering: Wait for this many audio chunks before starting playback
        private const val PRE_BUFFER_COUNT = 3
        
        // Audio timeout: How long to wait for more audio before declaring end of speech
        private const val AUDIO_END_TIMEOUT_MS = 700L
        // How long the first queued chunk of a turn may wait for the pre-buffer to
        // fill before we play it anyway. Gemini streams chunks milliseconds apart, so
        // a real multi-chunk reply always fills well inside this; only a reply that is
        // genuinely shorter than PRE_BUFFER_COUNT hits the timeout.
        private const val SHORT_REPLY_FLUSH_MS = 250L

        // Upper bound on waiting for AudioTrack to play out its buffer, so a stalled
        // track cannot wedge the playback loop.
        private const val MAX_DRAIN_WAIT_MS = 3000L
        // Extra settle time for A2DP's downstream buffering (headsets typically hold
        // 100-200 ms) before we suspend the profile by taking SCO back.
        private const val A2DP_TAIL_DRAIN_MS = 250L
        // How long a Bluetooth SCO→A2DP handover takes before the headset actually
        // emits sound. Playing the cue before this elapses means it is inaudible.
        private const val ROUTE_SETTLE_MS = 350L
        // Volume of the thinking cue. It plays alone (never under speech), so it can
        // sit well above the old 0.35, which was inaudible on the glasses.
        private const val THINKING_CUE_VOLUME = 0.7f
        // Length of res/raw/processing_chime.wav (1071 ms), rounded up.
        private const val CHIME_DURATION_MS = 1100L
        // Hard cap on the looping cue. It exists only for the turn where no reply
        // ever arrives — normally stopThinkingSound() ends the cue long before this.
        // Sized past the slowest observed grounded turn (a Google Search reply took
        // 3.81s from speech-end) with margin, but short enough that a stuck cue is a
        // brief annoyance rather than an endless loop.
        private const val MAX_CUE_MS = 8000L
        
        // Loudness settings
        private const val SOFTWARE_GAIN = 1.0f
        
        // ---- OpenAI GPT Realtime ----
        private const val GPT_MODEL = "gpt-4o-mini-realtime-preview"
        private const val GPT_VOICE = "shimmer" // alloy, echo, fable, onyx, nova, shimmer
        private const val GPT_INPUT_SAMPLE_RATE = 24000
        private const val GPT_OUTPUT_SAMPLE_RATE = 24000
        
        // ---- Google Gemini Live ----
        // Gemini Live native-audio model. The dated "-12-2025" preview returned
        // 404 ("model not available for your key"); this is the stable native-audio
        // preview ID. If the key lacks native-audio access, fall back to
        // GEMINI_MODEL_FALLBACK (half-cascade Live), which is more widely enabled.
        private const val GEMINI_MODEL = "gemini-2.5-flash-native-audio-preview-09-2025"
        // "gemini-live-2.5-flash-preview" is rejected by v1beta bidiGenerateContent
        // with 1008 ("not found for API version v1beta"), so the fallback used to
        // fail too and the session died with nothing spoken. This half-cascade Live
        // model is served on v1beta.
        private const val GEMINI_MODEL_FALLBACK = "gemini-2.0-flash-live-001"
        private const val GEMINI_VOICE = "Kore"
        private const val GEMINI_INPUT_SAMPLE_RATE = 16000
        private const val GEMINI_OUTPUT_SAMPLE_RATE = 24000

        /**
         * SPLIT BLUETOOTH AUDIO — mic over SCO, playback over A2DP.
         *
         * Bluetooth only exposes a microphone in SCO/HFP mode, which is mono
         * 8–16 kHz "phone call" quality. Routing playback through SCO as well made
         * the glasses sound low/muffled. With this enabled we keep capturing the mic
         * over SCO but pin the AI's voice to the A2DP device, which carries the full
         * 24 kHz output, and use MODE_IN_COMMUNICATION so media isn't forced into the
         * telephony path.
         *
         * Set to false to restore the previous SCO-only behaviour if a particular
         * headset stutters or drops out with split routing.
         *
         * Kept TRUE for full-quality 24 kHz playback, but note that SCO and A2DP
         * cannot run on the same device at the same time: holding SCO open suspends
         * A2DP, the OS tears down the A2DP output track mid-stream, and the reply
         * audio written to it is discarded. Observed in logcat during a reply:
         *     AudioTrackShared  Track invalidated
         *     AudioTrack        restoreTrack_l: dead IAudioTrack ... creating a new one
         *     AudioTrack        releaseBuffer is no-op due to IAudioTrack sequence mismatch
         * which presented as "the AI replies but I hear nothing" on the glasses while
         * the phone speaker worked fine.
         *
         * The fix WAS time-division: SCO held only while listening, released while
         * the AI speaks so A2DP has the device to itself. See
         * releaseScoForPlayback()/reacquireScoForListening().
         *
         * NOW FALSE. Time-division does not survive on these glasses. Once the
         * headset is connected for call audio (Bluetooth settings → "Phone calls"
         * enabled — which is what finally gave us the glasses MIC), every turn has
         * to tear down SCO, wait for A2DP to resume, play, then re-handshake SCO.
         * That handover is slower than a conversational turn, so the reply lands on
         * a route that is still switching and is discarded:
         *     AudioTrack  releaseBuffer() ... disabled due to previous underrun
         * i.e. the mic works but the user hears NOTHING back.
         *
         * SCO-only keeps one stable route for the whole session. The reply is
         * narrowband call quality rather than 24 kHz, which is a real downgrade —
         * but audible beats inaudible, and it removes a per-turn route switch that
         * was also adding latency. Flip back to true only if a headset is verified
         * to handle rapid SCO↔A2DP handover cleanly.
         */
        private const val HIGH_QUALITY_PLAYBACK = false
        
        // Echo cancellation and noise suppression
        private const val ENERGY_THRESHOLD = 200.0

        /**
         * Quiet time that marks the end of a user turn, for the processing chime.
         *
         * Deliberately short — this is a debounce, not an artificial delay. It only
         * has to outlast the natural pauses inside a sentence so the cue does not
         * fire mid-question; anything longer would put dead air back into exactly
         * the gap this feature exists to fill. It runs in PARALLEL with Gemini's own
         * turn detection and never gates it: audio keeps streaming throughout, so
         * the model's processing is not delayed by a single millisecond.
         */
        private const val VAD_SILENCE_MS = 450L

        // How many times to silently re-establish a dropped/failed connection
        // before surfacing the error to the user (covers transient Gemini hiccups
        // that previously required the user to manually "quick start" again).
        private const val MAX_AUTO_RECONNECTS = 2

        /**
         * How long to wait for the Bluetooth SCO route to actually come up after
         * startBluetoothSco(). The call is asynchronous and the handshake commonly
         * takes 500-2000ms; anything shorter races the OS and leaves audio on the
         * phone. Bounded so a headset without HFP cannot stall session start.
         *
         * This is dead time the user experiences as lag: the mic does not open
         * until the wait resolves, so on a device whose glasses never emit the
         * CONNECTED broadcast the FULL timeout is burned on every wake-up before
         * IMI can hear anything. 1200ms still covers a normal handshake while
         * cutting ~1.8s of silence off the wake→listening path.
         *
         * Note the wait no longer GATES the SCO request — the caller asserts
         * isBluetoothScoOn regardless of the result — so a timeout here costs
         * latency only, not routing.
         */
        private const val SCO_ROUTE_TIMEOUT_MS = 1200L
    }
    
    // Active model provider (read from prefs at start)
    private var activeProvider: ModelProvider = getSavedModelProvider(context)

    /**
     * True when this session is running on Mark 2 glasses.
     *
     * This service is shared with Mark 1, whose SCO timing is already working, so
     * the asynchronous-SCO wait added for Mark 2 is scoped to Mark 2 only and
     * Mark 1 keeps its previous behaviour exactly.
     */
    private val isMark2: Boolean
        get() = DevicePreferenceManager.getDeviceType(context) == DeviceType.MARK2

    /**
     * Whether a Bluetooth A2DP output actually exists right now.
     *
     * Split routing (mic on SCO, reply on A2DP) only works if the headset exposes
     * A2DP at all. These glasses connect over HFP for call audio; if no A2DP
     * endpoint is present, pinning playback to a media route sends the reply
     * nowhere and the user hears silence.
     */
    private fun hasA2dpOutput(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
            audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                ?.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP } == true
        } catch (e: Exception) {
            Log.w(TAG, "Could not query A2DP availability: ${e.message}")
            false
        }
    }

    // Gemini Live model actually used for the current connection. Starts at the
    // primary model and switches to the fallback if the primary returns 404.
    private var activeGeminiModel: String = GEMINI_MODEL
    private var triedGeminiFallback: Boolean = false
    private var lastSystemInstruction: String = ""
    private var lastApiKey: String = ""

    // When true, the AI proactively greets the user the moment the session is
    // ready, then keeps listening. Set per-session by startLiveConversation and
    // preserved across auto-reconnects.
    private var greetOnConnect: Boolean = false
    // Counts silent reconnect attempts for the current session (reset on a fresh
    // start and once a session reaches setupComplete).
    private var autoReconnects: Int = 0
    
    // Dynamic sample rates based on provider
    private val inputSampleRate: Int get() = if (activeProvider == ModelProvider.GPT_REALTIME) GPT_INPUT_SAMPLE_RATE else GEMINI_INPUT_SAMPLE_RATE
    private val outputSampleRate: Int get() = if (activeProvider == ModelProvider.GPT_REALTIME) GPT_OUTPUT_SAMPLE_RATE else GEMINI_OUTPUT_SAMPLE_RATE

    // Callbacks interface for communication with UI
    interface GeminiLiveCallbacks {
        fun onTranscriptionUpdate(input: String, output: String, isFinal: Boolean)
        fun onTurnComplete(fullInput: String, fullOutput: String)
        
        // Tool call callback - called when Gemini wants to execute a function
        fun onToolCall(toolName: String, args: Map<String, Any>): String
        fun onAudioPlaybackStart()
        fun onAudioPlaybackEnd()
        fun onError(error: String)
        fun onConnectionStatusChanged(isConnected: Boolean)
    }
    
    /**
     * 🆕 Secondary listener for VisionChatActivity
     * VisionChat can register to receive user transcription and intercept vision commands
     */
    interface VisionTranscriptionListener {
        fun onUserTranscription(text: String, isFinal: Boolean)
    }
    
    // 🆕 Secondary vision listener (VisionChatActivity)
    @Volatile
    private var visionTranscriptionListener: VisionTranscriptionListener? = null
    
    /**
     * 🆕 Register a secondary vision listener (for VisionChatActivity)
     * This allows VisionChat to intercept voice commands while Gemini Live is active
     */
    fun setVisionTranscriptionListener(listener: VisionTranscriptionListener?) {
        visionTranscriptionListener = listener
        Log.d(TAG, "👁️ Vision transcription listener ${if (listener != null) "registered" else "removed"}")
    }

    private val gson = com.google.gson.Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // WebSocket components
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .pingInterval(20, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    // Audio components
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var audioManager: AudioManager? = null
    private var scoHelper: ScoConnectionHelper? = null
    private val isRecording = AtomicBoolean(false)
    private val isPlaying = AtomicBoolean(false)
    private val isSetupComplete = AtomicBoolean(false)

    // Text handed to speakText() before the session finished setup (or while it was
    // reconnecting onto the fallback model). Flushed from setupComplete.
    private val pendingSpeakLock = Any()
    private var pendingSpeakText: Pair<String, Boolean>? = null


    // Echo cancellation and noise suppression
    private val isAIPlaying = AtomicBoolean(false) // Half-duplex flag: true when AI is speaking
    private var acousticEchoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var automaticGainControl: AutomaticGainControl? = null
    
    // 🎵 Rolling mic buffer for Shazam-style song identification.
    // Allocated when audio capture starts, released on cleanup. Null when no
    // live session is running, which is how identify_song knows it has no audio.
    @Volatile
    private var songIdBuffer: SongIdentifier.PcmRingBuffer? = null

    // Transcription state
    private var currentInputTranscription = StringBuilder()
    private var currentOutputTranscription = StringBuilder()
    private var receivedAudioInCurrentTurn = false
    private var hasTranscriptionForCurrentTurn = false

    // Set when a tool runs during this turn. Gemini sometimes ends a turn straight
    // after a tool response without speaking - the tool data comes back fine, the
    // model just never narrates it, so the user is left waiting for a reply that
    // never arrives. Tracked so an empty turn can be retried once (see
    // recoverFromSilentTurn); reset with the other per-turn flags.
    private var toolCallInCurrentTurn = false
    private var lastToolResultSummary: String? = null
    // Guards the retry so a model that stays silent cannot loop forever.
    private var silentTurnRetried = false
    
    // Audio playback queue
    private val audioQueue = mutableListOf<ByteArray>()
    private val audioQueueLock = Any()
    private var isPreBuffering = true // Wait for buffer to fill before playing
    
    // 🆕 Mute functionality for vision chat integration
    private val isMuted = AtomicBoolean(false) // When true, blocks audio output (but keeps listening)
    
    // 🎵 Thinking sound - plays during delay between user question and AI reply.
    //
    // Uses SoundPool, NOT MediaPlayer. MediaPlayer opens its own output stream with
    // default USAGE_MEDIA attributes; against our MODE_IN_COMMUNICATION session with
    // AudioTrack pinned to A2DP that forces the OS to re-route Bluetooth on every
    // start and stop. That re-route is what delayed the cue and left the AI reply
    // silent — AudioTrack writes landed on a path that was still being torn down.
    // SoundPool is built with the SAME AudioAttributes as the reply AudioTrack, so
    // both share one stream and no re-routing happens. It also decodes into memory
    // once up front, instead of MediaPlayer.create()'s synchronous main-thread
    // decode on every single turn.
    private var thinkingSoundPool: SoundPool? = null
    private var thinkingSoundId: Int = 0
    private var thinkingStreamId: Int = 0
    private val isThinkingSoundLoaded = AtomicBoolean(false)
    private val isThinkingSoundPlaying = AtomicBoolean(false)

    /**
     * Guards publication of [thinkingStreamId] against a concurrent stop.
     *
     * The cue's play() is deferred by ROUTE_SETTLE_MS, so a reply can arrive while
     * the start is still in flight. Without this, stop() could run between the
     * "still wanted?" check and play() returning, leaving a loop=-1 stream running
     * under the reply with no id recorded to stop it.
     */
    private val thinkingSoundLock = Any()

    /** Token for the deferred cue start, so stopThinkingSound() can cancel it. */
    private val thinkingCueToken = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    
    /**
     * 🎵 Load the thinking sound into memory once per session, so that starting it
     * later is just a play() call with no decode.
     */
    private fun initThinkingSound() {
        if (thinkingSoundPool != null) return
        try {
            // Must use the SAME usage as the reply AudioTrack. A different usage lands
            // on a different route and forces the Bluetooth stack to switch mid-turn,
            // which is what made the cue delay and swallow the reply.
            thinkingSoundPool = SoundPool.Builder()
                .setMaxStreams(1)
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .build()
                .apply {
                    setOnLoadCompleteListener { _, _, status ->
                        if (status == 0) {
                            isThinkingSoundLoaded.set(true)
                            Log.d(TAG, "🎵 Thinking sound loaded and ready")
                        } else {
                            Log.e(TAG, "🎵 Thinking sound failed to load, status=$status")
                        }
                    }
                }
            thinkingSoundId = thinkingSoundPool?.load(context, R.raw.processing_chime, 1) ?: 0
        } catch (e: Exception) {
            Log.e(TAG, "🎵 Error initialising thinking SoundPool: ${e.message}", e)
        }
    }

    private fun startThinkingSound() {
        // Claim the slot atomically. Gemini streams inputTranscription in many partial
        // fragments and each one calls in here, so a check-then-set would let several
        // through before the first took effect.
        if (!isThinkingSoundPlaying.compareAndSet(false, true)) {
            // Guards edge case 1/7: one cue per user turn. Speech-end can be
            // signalled more than once for a single turn (VAD flutter, or a
            // transcript fragment arriving after the local VAD already fired).
            Log.d(TAG, "🔊 Processing chime SKIPPED — already played for this turn")
            return
        }
        val pool = thinkingSoundPool
        if (pool == null || !isThinkingSoundLoaded.get()) {
            // Not ready yet (very fast first turn) — skip this turn rather than block.
            isThinkingSoundPlaying.set(false)
            Log.w(TAG, "🔊 Processing chime SKIPPED — sample not decoded yet (first turn)")
            return
        }
        Log.d(TAG, "🔊 PROCESSING CHIME TRIGGERED")
        // The user has stopped speaking, so the mic is no longer needed. Hand the
        // device to A2DP now: the cue and the reply that follows then share one
        // stable route, with no switch in between to swallow the reply's start.
        releaseScoForPlayback()
        // A Bluetooth SCO→A2DP handover takes ~100-300 ms, during which the headset
        // emits nothing. Playing immediately meant a short cue could finish inside
        // that window and never be heard at all. Wait for the route to settle first.
        mainHandler.postDelayed({
            // The reply may have arrived while we were waiting; if the cue was
            // stopped in the meantime, don't start it late over the top of speech.
            if (!isThinkingSoundPlaying.get()) return@postDelayed
            try {
                // loop=-1 → repeat until stopped, so the cue BRIDGES the whole gap
                // instead of ending 1.07s in and leaving silence until the reply.
                // On a grounded turn (Google Search) that gap runs 3-6s, which the
                // one-shot version covered less than a third of.
                //
                // A loop that outlives its stop() would play underneath the reply,
                // which is far worse than a short silence — so it is bounded twice:
                //   1. stopThinkingSound() kills it on the reply's first audio byte
                //      (five call sites: both providers' first-chunk handlers,
                //      turnComplete/interrupted, response.done, barge-in, cleanup).
                //   2. The MAX_CUE_MS watchdog below stops it unconditionally even
                //      if every one of those is missed.
                // No decode here: the sample is already in memory, so this returns
                // immediately and never blocks the caller.
                val streamId = pool.play(
                    thinkingSoundId, THINKING_CUE_VOLUME, THINKING_CUE_VOLUME, 1, -1, 1.0f
                )
                // Re-check UNDER the lock after play(). stopThinkingSound() may have
                // run between the check above and here — it would have found
                // thinkingStreamId still 0 and stopped nothing, leaving this stream
                // playing underneath the reply. Publishing the id and testing the
                // flag together closes that window.
                var started = false
                synchronized(thinkingSoundLock) {
                    if (isThinkingSoundPlaying.get()) {
                        thinkingStreamId = streamId
                        started = true
                    } else {
                        pool.stop(streamId)
                    }
                }
                if (!started) {
                    Log.d(TAG, "🔊 Processing chime stopped before it was audible — reply already arriving")
                    return@postDelayed
                }
                Log.d(TAG, "🔊 PROCESSING CHIME PLAYING (stream=$streamId)")
                // Watchdog. The cue now LOOPS, so unlike the one-shot version it will
                // not end by itself — something must always stop it. Normally that is
                // stopThinkingSound() on the reply; this is the backstop for the turn
                // where no reply ever arrives (dropped socket, tool call that never
                // returns) so the user is never left with a cue looping forever.
                // Note this reuses thinkingCueToken, so stopThinkingSound() cancels
                // it — that is correct, because stop() already clears the flag and
                // stops the stream itself.
                mainHandler.postDelayed({
                    if (isThinkingSoundPlaying.compareAndSet(true, false)) {
                        synchronized(thinkingSoundLock) {
                            if (thinkingStreamId != 0) {
                                try { pool.stop(thinkingStreamId) } catch (_: Exception) {}
                                thinkingStreamId = 0
                            }
                        }
                        Log.d(TAG, "🔊 PROCESSING CHIME STOPPED (max duration reached, no reply)")
                    }
                }, thinkingCueToken, MAX_CUE_MS)
            } catch (e: Exception) {
                isThinkingSoundPlaying.set(false)
                Log.e(TAG, "🎵 Error starting thinking sound: ${e.message}", e)
            }
        }, thinkingCueToken, ROUTE_SETTLE_MS)
    }

    /**
     * 🎵 Stop the thinking/processing sound (called when AI reply audio arrives)
     */
    private fun stopThinkingSound() {
        // Clearing the flag first also cancels any start still waiting out the
        // route-settle delay (it re-checks the flag before playing).
        val wasPlaying = isThinkingSoundPlaying.getAndSet(false)
        // Cancel a queued start that has not run yet, so it cannot fire after this.
        mainHandler.removeCallbacksAndMessages(thinkingCueToken)
        synchronized(thinkingSoundLock) {
            if (!wasPlaying && thinkingStreamId == 0) return
            try {
                if (thinkingStreamId != 0) {
                    thinkingSoundPool?.stop(thinkingStreamId)
                    thinkingStreamId = 0
                    Log.d(TAG, "🔊 PROCESSING CHIME STOPPED (reply arriving)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "🎵 Error stopping thinking sound: ${e.message}")
                thinkingStreamId = 0
            }
        }
    }
    
    // ---- Local speech-end VAD state (capture thread only, no locking needed) ----
    /** True once the user's voice has been heard in the current turn. */
    private var speechActive = false
    /** uptimeMillis of the last frame whose energy was above the speech threshold. */
    private var lastVoiceFrameMs = 0L

    /**
     * Detect the user's speech ENDING, and fire the processing chime when it does.
     *
     * Called once per ~30 ms mic frame from the existing capture loop. It reuses
     * [ENERGY_THRESHOLD] — the level this service already treats as "voice" — so
     * this is not a second VAD with its own tuning, just an edge detector on the
     * signal the capture loop is already producing.
     *
     * A turn ends when we have heard speech and then [VAD_SILENCE_MS] of quiet
     * follows. The debounce exists because natural speech is full of short gaps
     * (between words, before a subordinate clause); firing on the first quiet
     * frame would chime in the middle of the user's sentence.
     */
    private fun detectSpeechEdge(frame: ShortArray, size: Int) {
        // Mean absolute amplitude — same cheap energy measure as elsewhere here,
        // and enough to separate speech from room noise.
        var sum = 0L
        for (i in 0 until size) sum += kotlin.math.abs(frame[i].toInt())
        val energy = sum.toDouble() / size
        val now = android.os.SystemClock.uptimeMillis()

        if (energy > ENERGY_THRESHOLD) {
            if (!speechActive) Log.d(TAG, "🎤 USER SPEECH STARTED")
            speechActive = true
            lastVoiceFrameMs = now
            return
        }

        // Below threshold: this frame is silence.
        if (speechActive && lastVoiceFrameMs != 0L && now - lastVoiceFrameMs >= VAD_SILENCE_MS) {
            speechActive = false
            lastVoiceFrameMs = 0L
            Log.d(TAG, "🎤 USER SPEECH ENDED (${VAD_SILENCE_MS}ms silence)")
            // Fire and forget. startThinkingSound() only flips an atomic and posts
            // to the main handler, so the capture loop is never blocked on audio
            // and the websocket keeps streaming underneath the cue.
            startThinkingSound()
        }
    }

    /**
     * Start the live conversation session
     */
    fun startLiveConversation(
        systemInstruction: String = "You are a helpful AI assistant.",
        greetOnStart: Boolean = false
    ) {
        if (webSocket != null) {
            Log.w(TAG, "Live conversation already started")
            callbacks.onError("Conversation already in progress")
            return
        }

        // 🆕 Set singleton instance
        instance = this
        Log.d(TAG, "🌐 GeminiLiveService instance set for cross-activity access")

        // Always start a new session unmuted — isMuted may be left true from a
        // previous session that called muteOutput() for vision chat and then ended
        // before unmuteOutput() was called (e.g. user said goodbye mid-vision).
        isMuted.set(false)
        Log.d(TAG, "🔊 Output unmuted for new session")

        // Re-read model preference at start
        activeProvider = getSavedModelProvider(context)
        // Reset Gemini Live model to the primary; fallback re-arms per session.
        activeGeminiModel = GEMINI_MODEL
        triedGeminiFallback = false
        // Arm the proactive greeting and reset the reconnect budget for this session.
        greetOnConnect = greetOnStart
        autoReconnects = 0
        // Never carry text queued for a previous session into this one.
        synchronized(pendingSpeakLock) { pendingSpeakText = null }
        Log.d(TAG, "🔄 Starting with model provider: $activeProvider")
        
        val apiKey = if (activeProvider == ModelProvider.GPT_REALTIME) {
            RemoteConfigManager.openAiApiKey
        } else {
            RemoteConfigManager.geminiApiKey
        }
        
        val providerName = if (activeProvider == ModelProvider.GPT_REALTIME) "OpenAI" else "Gemini"
        
        if (apiKey.isEmpty() || apiKey == "YOUR_GEMINI_API_KEY_HERE" || apiKey == "YOUR_OPENAI_API_KEY_HERE") {
            val errorMsg = "$providerName API Key is not configured properly"
            Log.e(TAG, "❌ $errorMsg")
            callbacks.onError(errorMsg)
            return
        }
        
        Log.d(TAG, "✅ $providerName API Key validated successfully")

        scope.launch {
            try {
                // Initialize audio components
                initializeAudioComponents()
                
                // Connect to WebSocket
                connectWebSocket(apiKey, systemInstruction)
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start live conversation", e)
                callbacks.onError("Failed to start: ${e.message}")
                cleanup()
            }
        }
    }

    /**
     * Stop the live conversation session
     */
    fun stopLiveConversation() {
        scope.launch {
            cleanup()
            callbacks.onConnectionStatusChanged(false)
        }
    }
    
    /**
     * If this session was started with greetOnStart, nudge the model to open the
     * conversation with a short spoken greeting and then keep listening. Fires
     * exactly once per session (cleared immediately so an auto-reconnect that
     * re-runs setup won't greet twice).
     */
    private fun maybeSendGreeting() {
        if (!greetOnConnect) return
        greetOnConnect = false

        val ws = webSocket ?: return
        val greetingInstruction = AiResponsePrefs.buildGreetingInstruction(context)

        try {
            // Give audio playback/capture a beat to come up before the model speaks.
            scope.launch {
                delay(250)
                if (activeProvider == ModelProvider.GPT_REALTIME) {
                    val responseCreate = mapOf(
                        "type" to "response.create",
                        "response" to mapOf("instructions" to greetingInstruction)
                    )
                    ws.send(gson.toJson(responseCreate))
                } else {
                    val clientContent = mapOf(
                        "client_content" to mapOf(
                            "turns" to listOf(
                                mapOf("role" to "user", "parts" to listOf(mapOf("text" to greetingInstruction)))
                            ),
                            "turn_complete" to true
                        )
                    )
                    ws.send(gson.toJson(clientContent))
                }
                Log.d(TAG, "👋 Sent initial greeting trigger")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to send greeting trigger: ${e.message}", e)
        }
    }

    /**
     * 🆕 Inject text for Gemini to speak naturally
     * This sends a user message asking Gemini to speak the provided text
     * Gemini will respond with its natural streaming voice
     * 
     * @param textToSpeak The text content for Gemini to speak
     * @param speakDirectly If true, instructs Gemini to speak this text directly
     */
    fun speakText(textToSpeak: String, speakDirectly: Boolean = true) {
        val ws = webSocket
        if (ws == null) {
            Log.e(TAG, "❌ Cannot speak text - WebSocket not connected")
            callbacks.onError("AI not connected")
            return
        }

        // The socket can be open while the server is still processing our setup
        // message. Anything sent in that window is refused with 1007 and takes the
        // whole session down, so hold the text and send it from setupComplete.
        if (!isSetupComplete.get()) {
            synchronized(pendingSpeakLock) {
                pendingSpeakText = textToSpeak to speakDirectly
            }
            Log.d(TAG, "⏳ Queued text until session setup completes: ${textToSpeak.take(60)}...")
            return
        }

        try {
            val promptText = if (speakDirectly) {
                "Read this COMPLETELY in one go, do not pause or stop in the middle: $textToSpeak"
            } else {
                textToSpeak
            }
            
            if (activeProvider == ModelProvider.GPT_REALTIME) {
                // OpenAI Realtime: send conversation.item.create + response.create
                val itemCreate = mapOf(
                    "type" to "conversation.item.create",
                    "item" to mapOf(
                        "type" to "message",
                        "role" to "user",
                        "content" to listOf(
                            mapOf("type" to "input_text", "text" to promptText)
                        )
                    )
                )
                ws.send(gson.toJson(itemCreate))
                ws.send(gson.toJson(mapOf("type" to "response.create")))
            } else {
                // Gemini Live: client_content
                val clientContent = mapOf(
                    "client_content" to mapOf(
                        "turns" to listOf(
                            mapOf("role" to "user", "parts" to listOf(mapOf("text" to promptText)))
                        ),
                        "turn_complete" to true
                    )
                )
                ws.send(gson.toJson(clientContent))
            }
            
            Log.d(TAG, "🔊 Injected text to speak: ${textToSpeak.take(100)}...")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to inject speak text: ${e.message}", e)
            callbacks.onError("Failed to speak: ${e.message}")
        }
    }

    /**
     * Send any text that speakText() had to hold back because the session setup
     * had not been acknowledged yet. Called from setupComplete / session.created.
     *
     * The queued entry survives a reconnect (model fallback, transient drop), so a
     * vision result asked for on a dead session is still spoken on the new one
     * instead of being silently dropped.
     */
    private fun flushPendingSpeakText() {
        val pending = synchronized(pendingSpeakLock) {
            val p = pendingSpeakText
            pendingSpeakText = null
            p
        } ?: return

        Log.d(TAG, "▶️ Flushing queued text now that setup is complete")
        speakText(pending.first, pending.second)
    }


    /**
     * 🆕 Inject vision context into Gemini Live's conversation memory
     * This adds the vision analysis to the conversation history so Gemini
     * remembers it for follow-up questions
     */
    fun injectVisionContext(visionDescription: String) {
        val ws = webSocket
        if (ws == null) {
            Log.w(TAG, "Cannot inject vision context - WebSocket not connected")
            return
        }
        
        try {
            val contextMessage = "VISION CONTEXT: $visionDescription"
            
            if (activeProvider == ModelProvider.GPT_REALTIME) {
                // OpenAI Realtime: inject as a user message
                val itemCreate = mapOf(
                    "type" to "conversation.item.create",
                    "item" to mapOf(
                        "type" to "message",
                        "role" to "user",
                        "content" to listOf(
                            mapOf("type" to "input_text", "text" to contextMessage)
                        )
                    )
                )
                val sent = ws.send(gson.toJson(itemCreate))
                Log.d(TAG, "📝 Injected vision context (GPT): sent=$sent")
            } else {
                // Gemini Live: client_content
                val clientContent = mapOf(
                    "client_content" to mapOf(
                        "turns" to listOf(
                            mapOf("role" to "user", "parts" to listOf(mapOf("text" to contextMessage)))
                        ),
                        "turn_complete" to false
                    )
                )
                val sent = ws.send(gson.toJson(clientContent))
                Log.d(TAG, "📝 Injected vision context (Gemini): sent=$sent")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to inject vision context: ${e.message}", e)
        }
    }
    
    /**
     * 🆕 Mute Gemini Live output (stops audio playback)
     * Used during vision chat processing - Gemini will be silent
     * Input audio recording continues (user can still speak)
     */
    fun muteOutput() {
        isMuted.set(true)
        Log.d(TAG, "🔇 Gemini Live OUTPUT MUTED (input still active)")
        // Clear any queued audio
        synchronized(audioQueueLock) {
            audioQueue.clear()
            isPreBuffering = true
        }
        // Stop current playback
        audioTrack?.pause()
    }
    
    /**
     * 🆕 Unmute Gemini Live output (resumes audio playback)
     * Used when vision description is ready to be spoken
     */
    fun unmuteOutput() {
        isMuted.set(false)
        Log.d(TAG, "🔊 Gemini Live OUTPUT UNMUTED (ready to speak)")
        // Resume playback if needed
        if (isPlaying.get()) {
            audioTrack?.play()
        }
    }
    
    /**
     * 🆕 Check if Gemini Live is currently muted
     */
    fun isOutputMuted(): Boolean = isMuted.get()
    
    /**
     * 🆕 Play thinking sound while waiting for vision analysis
     * Gemini will say "hmm hmm hmm" naturally to indicate processing
     * CONTINUOUS: Keeps saying until interrupted by actual response
     */
    fun playThinkingSound() {
        val ws = webSocket
        if (ws == null) {
            Log.w(TAG, "Cannot play thinking sound - WebSocket not connected")
            return
        }
        
        try {
            val thinkingPrompt = "Say naturally like you're thinking: 'hmm... hmm... let me see... hmm... looking at this... hmm hmm...' Keep going for about 5-10 seconds, like you're carefully examining something. Sound natural and thoughtful."

            if (activeProvider == ModelProvider.GPT_REALTIME) {
                // OpenAI Realtime: send as user message + trigger response
                val itemCreate = mapOf(
                    "type" to "conversation.item.create",
                    "item" to mapOf(
                        "type" to "message",
                        "role" to "user",
                        "content" to listOf(
                            mapOf(
                                "type" to "input_text",
                                "text" to thinkingPrompt
                            )
                        )
                    )
                )
                ws.send(gson.toJson(itemCreate))
                val responseCreate = mapOf("type" to "response.create")
                ws.send(gson.toJson(responseCreate))
                Log.d(TAG, "🎵 Playing CONTINUOUS thinking sound through GPT Realtime")
            } else {
                // Gemini Live: send as client_content user turn
                val clientContent = mapOf(
                    "client_content" to mapOf(
                        "turns" to listOf(
                            mapOf(
                                "role" to "user",
                                "parts" to listOf(mapOf("text" to thinkingPrompt))
                            )
                        ),
                        "turn_complete" to true
                    )
                )
                ws.send(gson.toJson(clientContent))
                Log.d(TAG, "🎵 Playing CONTINUOUS thinking sound through Gemini Live")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to play thinking sound: ${e.message}", e)
        }
    }

    // Tracks whether we currently hold SCO, so the release/reacquire pair below is
    // idempotent and cheap to call from the audio loop.
    private val scoHeldForListening = AtomicBoolean(false)

    // Total PCM frames handed to AudioTrack this session. Compared against the
    // hardware playback head to know when the reply has really finished sounding.
    @Volatile private var totalFramesWritten = 0L

    /**
     * Block until AudioTrack has actually played out everything written to it.
     *
     * Counts total frames written and waits for the hardware playback head
     * (playbackHeadPosition) to catch up. Without this the reply's last words are
     * lost whenever we change the Bluetooth route straight after the final write.
     * Bounded so a stalled or silent track can never hang the playback loop.
     */
    private suspend fun waitForTrackToDrain() {
        val track = audioTrack ?: return
        try {
            val deadline = System.currentTimeMillis() + MAX_DRAIN_WAIT_MS
            while (System.currentTimeMillis() < deadline) {
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) break
                // playbackHeadPosition is an unsigned frame counter that wraps; the
                // mask keeps the comparison correct past 2^31 frames.
                val played = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                if (played >= totalFramesWritten) break
                delay(10)
            }
            // A2DP adds its own buffering downstream of the track, so give the
            // headset a moment to emit the final frames before we suspend it.
            if (HIGH_QUALITY_PLAYBACK) delay(A2DP_TAIL_DRAIN_MS)
        } catch (e: Exception) {
            Log.w(TAG, "Error waiting for track drain: ${e.message}")
        }
    }

    /**
     * Hand the Bluetooth device over to A2DP for the AI's reply.
     *
     * SCO and A2DP cannot be active on the same device simultaneously; while SCO is
     * open the A2DP output track gets invalidated mid-stream and the reply is lost.
     * We only need the mic while the user is talking, so we drop SCO for the
     * duration of the reply and take it back afterwards.
     */
    private fun releaseScoForPlayback() {
        if (!HIGH_QUALITY_PLAYBACK) return

        // Only hand the device over to A2DP if an A2DP output actually exists.
        //
        // These glasses connect over HFP. With no A2DP endpoint, dropping SCO removes
        // the ONLY route to the headset: the reply is written to a track with nowhere
        // to go and the user hears silence ("AudioTrack ... disabled due to previous
        // underrun"). Keep SCO for the whole turn in that case — call-quality audio
        // that is audible beats high-quality audio that is not.
        if (!hasA2dpOutput()) {
            Log.d(TAG, "🔈 No A2DP output — keeping SCO for playback (call quality, audible)")
            return
        }

        if (!scoHeldForListening.compareAndSet(true, false)) return
        try {
            audioManager?.let { am ->
                am.isBluetoothScoOn = false
                am.stopBluetoothSco()
            }
            Log.d(TAG, "🔉 SCO released — A2DP free for high-quality reply")
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing SCO: ${e.message}")
        }
    }

    /**
     * Pin playback to the A2DP endpoint, now that SCO has actually been released.
     *
     * The pin in initializeAudioComponents() runs while SCO is held for listening,
     * and A2DP is suspended (so absent from getDevices()) for exactly as long as
     * SCO is up. That pin therefore always fell through to the SCO device and stuck
     * there for the whole session — a USAGE_MEDIA track pinned to SCO under
     * MODE_IN_COMMUNICATION is accepted by write() but never rendered, which is why
     * the reply was silent while the logs looked healthy.
     *
     * Re-pinning per turn, after releaseScoForPlayback() and the route-settle delay,
     * is the only point at which the A2DP endpoint is actually enumerable.
     */
    private fun repinPlaybackToA2dp() {
        if (!HIGH_QUALITY_PLAYBACK) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val a2dp = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.let {
                PreferredAudioDeviceResolver.findGlasses(context, it, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            }
            if (a2dp != null) {
                val ok = audioTrack?.setPreferredDevice(a2dp)
                Log.d(TAG, "🎯 Playback re-pinned → ${a2dp.productName} [A2DP], success=$ok")
            } else {
                // No A2DP right now (SCO-only headset, or the profile has not come
                // back yet). Clearing the stale pin lets the OS pick the live route
                // instead of holding playback on a device that cannot render it.
                audioTrack?.setPreferredDevice(null)
                Log.d(TAG, "ℹ️ No A2DP endpoint — cleared pin, using system routing")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to re-pin playback device: ${e.message}")
        }
    }

    /**
     * Take SCO back so the microphone works again once the AI has finished speaking.
     *
     * On Mark 2 this suspends until the route is genuinely back, because
     * startBluetoothSco() is asynchronous: returning early let mic capture resume
     * while the phone mic was still the active input, so the glasses mic went dead
     * after the first reply. Mark 1 returns immediately as before.
     */
    private suspend fun reacquireScoForListening() {
        if (!HIGH_QUALITY_PLAYBACK) return
        // Mirrors releaseScoForPlayback(): when there is no A2DP endpoint SCO was
        // never released, so there is nothing to take back.
        if (!hasA2dpOutput()) return
        if (!scoHeldForListening.compareAndSet(false, true)) return
        try {
            // MARK 2 ONLY — see the matching note in initializeAudioComponents().
            if (isMark2) {
                // Register for the SCO state broadcast BEFORE asking for SCO, so a
                // fast CONNECTED cannot be missed. isBluetoothScoOn is set only once
                // the link is genuinely up — setting it up front is what made the old
                // wait return in 0ms and tear down the playback track mid-reply.
                val connected = awaitScoConnected(SCO_ROUTE_TIMEOUT_MS) {
                    audioManager?.startBluetoothSco()
                }
                if (connected) {
                    audioManager?.isBluetoothScoOn = true
                    Log.d(TAG, "🎙️ SCO reacquired — microphone live again")
                } else {
                    Log.w(TAG, "⚠️ SCO did not come back within ${SCO_ROUTE_TIMEOUT_MS}ms — " +
                            "microphone may fall back to the phone")
                }
            } else {
                audioManager?.let { am ->
                    am.startBluetoothSco()
                    am.isBluetoothScoOn = true
                }
                Log.d(TAG, "🎙️ SCO reacquired — microphone live again")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error reacquiring SCO: ${e.message}")
            scoHeldForListening.set(false) // allow a retry next turn
        }
    }

    /**
     * Route voice capture to the Bluetooth headset using the modern, supported
     * API (Android 12 / API 31+).
     *
     * The app's original path asked the system for HFP through reflection on the
     * hidden BluetoothHeadset.connect(), which Android has blocklisted as a
     * non-SDK interface since Android 11. On a modern device that call simply
     * returns false ("HFP connect command failed immediately"), so HFP never
     * reaches STATE_CONNECTED and capture falls back to the PHONE mic while the
     * glasses sit there connected over BLE.
     *
     * setCommunicationDevice() replaces that: it asks the framework to route
     * communication audio to the chosen device and brings SCO up itself, with no
     * hidden APIs involved.
     *
     * @return true if a Bluetooth SCO communication device was selected.
     */
    private fun selectBluetoothCommunicationDevice(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val am = audioManager ?: return false
        return try {
            val current = am.communicationDevice
            if (current?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                Log.d(TAG, "🎧 Communication device already on Bluetooth SCO (${current.productName})")
                return true
            }
            val bt = PreferredAudioDeviceResolver.findGlasses(
                context, am.availableCommunicationDevices, AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            )
            if (bt == null) {
                Log.w(TAG, "⚠️ No Bluetooth SCO communication device offered by the system — " +
                        "the glasses may not expose HFP, or are not connected as a headset")
                return false
            }
            val ok = am.setCommunicationDevice(bt)
            Log.d(TAG, "🎧 setCommunicationDevice(${bt.productName}) → $ok")
            ok
        } catch (e: Exception) {
            Log.w(TAG, "setCommunicationDevice failed: ${e.message}")
            false
        }
    }

    /**
     * Wait until the Bluetooth SCO route is genuinely usable.
     *
     * Waits on ACTION_SCO_AUDIO_STATE_UPDATED rather than polling AudioManager.
     *
     * The previous polling version always returned in 0ms and so never actually
     * waited. Both of the signals it checked are true the instant the caller asks
     * for SCO, long before the link exists:
     *  - isBluetoothScoOn reflects the REQUEST made by setBluetoothScoOn(true) on
     *    the line above the call, not the state of the link.
     *  - a TYPE_BLUETOOTH_SCO entry stays enumerable between sessions, because the
     *    headset supports HFP whether or not SCO is currently up.
     *
     * Returning early let the mic resume while the real handover was still in
     * flight; that handover then landed underneath the playback AudioTrack and
     * invalidated it ("dead IAudioTrack ... creating a new one" every ~500ms in
     * logcat), which is what made the AI reply silent.
     *
     * SCO_AUDIO_STATE_CONNECTED is the only signal the OS emits when the link is
     * genuinely up, so we suspend on it. Returns false if [timeoutMs] elapses or
     * the OS reports SCO_AUDIO_STATE_ERROR (e.g. a BLE-only headset with no HFP),
     * in which case the caller falls back to default routing rather than blocking.
     *
     * Must be called BEFORE startBluetoothSco(), so the receiver is registered in
     * time to see a fast CONNECTED broadcast.
     */
    private suspend fun awaitScoConnected(timeoutMs: Long, startSco: () -> Unit): Boolean {
        val am = audioManager ?: return false

        // Preferred path on API 31+. When this succeeds the framework owns the
        // SCO handover, so there is nothing to wait for and no legacy
        // startBluetoothSco() handshake to race.
        if (selectBluetoothCommunicationDevice()) return true

        // Already connected — nothing to wait for. Checked against the broadcast's
        // sticky value rather than isBluetoothScoOn, which lies as described above.
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Boolean> { cont ->
                val started = System.currentTimeMillis()
                // Unregister exactly once, whether we finish via CONNECTED, ERROR,
                // or the withTimeoutOrNull above cancelling us.
                val unregistered = AtomicBoolean(false)
                var receiver: BroadcastReceiver? = null
                val cleanup = {
                    if (unregistered.compareAndSet(false, true)) {
                        try { receiver?.let { context.unregisterReceiver(it) } } catch (_: Exception) {}
                    }
                }

                receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        val state = intent?.getIntExtra(
                            AudioManager.EXTRA_SCO_AUDIO_STATE,
                            AudioManager.SCO_AUDIO_STATE_ERROR
                        ) ?: return
                        when (state) {
                            AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                                val waited = System.currentTimeMillis() - started
                                Log.d(TAG, "✅ SCO route is live after ${waited}ms")
                                cleanup()
                                if (cont.isActive) cont.resume(true)
                            }
                            AudioManager.SCO_AUDIO_STATE_ERROR -> {
                                Log.w(TAG, "⚠️ SCO reported ERROR while connecting")
                                cleanup()
                                if (cont.isActive) cont.resume(false)
                            }
                            // DISCONNECTED/CONNECTING are transient here: the link is
                            // still coming up, so keep waiting for CONNECTED.
                        }
                    }
                }

                try {
                    context.registerReceiver(
                        receiver,
                        IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Could not register SCO state receiver: ${e.message}")
                    unregistered.set(true) // nothing to unregister
                    if (cont.isActive) cont.resume(false)
                    return@suspendCancellableCoroutine
                }

                cont.invokeOnCancellation { cleanup() }

                // Ask for SCO only now that we are listening for the result.
                try {
                    startSco()
                } catch (e: Exception) {
                    Log.w(TAG, "startBluetoothSco failed: ${e.message}")
                    cleanup()
                    if (cont.isActive) cont.resume(false)
                }
            }
        } ?: run {
            Log.w(TAG, "⚠️ SCO did not connect within ${timeoutMs}ms")
            false
        }
    }

    private suspend fun initializeAudioComponents() {
    Log.d(TAG, "🎧 ======================================")
    Log.d(TAG, "🎧 AUDIO INITIALIZATION STARTED")
    Log.d(TAG, "🎧 ======================================")
    
    audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    if (audioManager == null) {
        Log.e(TAG, "❌ FATAL: AudioManager is null!")
        throw IllegalStateException("AudioManager not available")
    }
    Log.d(TAG, "✅ AudioManager obtained")

    // Decode the thinking cue into memory now, while we are already off the main
    // thread, so the first turn does not pay for it.
    initThinkingSound()

    // ========== OPTION A: Use System Bluetooth for Audio ==========
    // BLE handles data (photos, commands) - System Bluetooth handles audio
    
    // 1. Audio mode. ALWAYS MODE_IN_COMMUNICATION.
    //
    //    MODE_IN_CALL is reserved for real telephony: a normal app cannot route
    //    playback through it, so selecting it here (the old SCO-only branch) makes
    //    the reply inaudible in a different way than the A2DP handover did.
    //    MODE_IN_COMMUNICATION is the correct mode for VoIP-style audio and gives
    //    us SCO mic capture plus playback on the same SCO link.
    audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
    Log.d(TAG, "📞 Audio mode: IN_COMMUNICATION " +
            "(playback ${if (HIGH_QUALITY_PLAYBACK) "via A2DP split routing" else "over SCO — one stable route"})")
    
    // 2. Check if Bluetooth audio is available
    val isBluetoothAvailable = audioManager?.isBluetoothScoAvailableOffCall == true
    val isBluetoothOn = audioManager?.isBluetoothScoOn == true

    Log.d(TAG, "🎧 System Bluetooth Audio Status:")
    Log.d(TAG, "   - Available: $isBluetoothAvailable")
    Log.d(TAG, "   - Active: $isBluetoothOn")

    // 3. Always cycle SCO off→on so the glasses speaker is correctly routed on
    //    every session (including the 2nd, 3rd, … after "goodbye" + wake-word).
    //    Skipping this when isBluetoothOn==true caused audio to play on the phone
    //    speaker instead of the glasses on subsequent sessions.
    if (isBluetoothAvailable) {
        try {
            if (isBluetoothOn) {
                // Force a clean re-handshake so the output route is refreshed.
                audioManager?.stopBluetoothSco()
                delay(80)
            }
            scoHeldForListening.set(true) // keep the release/reacquire pair in sync

            // MARK 2 ONLY. startBluetoothSco() is ASYNCHRONOUS: it returns
            // immediately and the SCO route only becomes usable once the OS has
            // finished the handshake (typically 500-2000ms). The fixed 80ms delay
            // used here expired long before that on Mark 2, so the device
            // enumeration below found no SCO endpoint, setPreferredDevice() was
            // never called, and BOTH the mic and playback silently fell back to the
            // phone — the glasses connected but all audio came out of the handset.
            // Wait for the route to really appear instead.
            //
            // isBluetoothScoOn is set only AFTER the link reports CONNECTED. Setting
            // it before the wait is what let the old poll succeed instantly.
            //
            // Mark 1 keeps the original fixed delay: its timing already works and
            // this fix is deliberately scoped to Mark 2.
            if (isMark2) {
                val scoReady = awaitScoConnected(SCO_ROUTE_TIMEOUT_MS) {
                    audioManager?.startBluetoothSco()
                }
                // REGRESSION FIX: assert the SCO request whether or not the
                // CONNECTED broadcast arrived in time.
                //
                // This used to be `if (scoReady) { isBluetoothScoOn = true }`, so on a
                // device where the broadcast never lands the flag was NEVER set, SCO
                // routing never engaged, and both mic and playback fell back to the
                // phone — the glasses stayed silent even though the link was fine.
                // The previously-working code set this unconditionally right after
                // startBluetoothSco() and let the OS finish the handshake in the
                // background; waiting is still useful (it lets a fast handshake
                // proceed immediately) but it must not GATE the request.
                audioManager?.isBluetoothScoOn = true
                if (scoReady) {
                    Log.d(TAG, "📡 Bluetooth SCO connected for this session")
                } else {
                    Log.w(TAG, "⚠️ SCO CONNECTED broadcast not seen within ${SCO_ROUTE_TIMEOUT_MS}ms — " +
                            "keeping the SCO request active anyway and letting the OS finish routing")
                    // Give the in-flight handshake the same brief settle the working
                    // Mark 1 path uses, so the device enumeration below can see it.
                    delay(80)
                }
            } else {
                audioManager?.startBluetoothSco()
                audioManager?.isBluetoothScoOn = true
                Log.d(TAG, "📡 Bluetooth SCO (re-)started for this session")
                delay(80)
            }
        } catch (e: Exception) {
            Log.w(TAG, "SCO start attempt: ${e.message}")
        }
    }
    
    // 4. Calculate buffer sizes
    val inputBufferSize = AudioRecord.getMinBufferSize(
        inputSampleRate,
        CHANNEL_CONFIG_IN,
        AUDIO_FORMAT
    ) * BUFFER_SIZE_MULTIPLIER

    val outputBufferSize = AudioTrack.getMinBufferSize(
        outputSampleRate,
        CHANNEL_CONFIG_OUT,
        AUDIO_FORMAT
    ) * BUFFER_SIZE_MULTIPLIER

    // 5. Create AudioRecord with VOICE_COMMUNICATION (auto-routes to Bluetooth)
    try {
        audioRecord = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AUDIO_FORMAT)
                    .setSampleRate(inputSampleRate)
                    .setChannelMask(CHANNEL_CONFIG_IN)
                    .build()
            )
            .setBufferSizeInBytes(inputBufferSize)
            .build()
        
        // 6. Try to set preferred device to Bluetooth SCO (API 23+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val devices = audioManager?.getDevices(AudioManager.GET_DEVICES_INPUTS) ?: arrayOf()
                
                Log.d(TAG, "🎙️ Available input devices:")
                devices.forEach { dev ->
                    val typeStr = when (dev.type) {
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO (Glass)"
                        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Phone Mic"
                        else -> "Other (${dev.type})"
                    }
                    Log.d(TAG, "   - ${dev.productName}: $typeStr")
                }
                
                // Find and prefer the paired glasses' Bluetooth SCO device specifically —
                // not just any Bluetooth SCO device, in case a second BT accessory is
                // also connected (see PreferredAudioDeviceResolver).
                val bluetoothDevice = PreferredAudioDeviceResolver.findGlasses(
                    context, devices, AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                )

                if (bluetoothDevice != null) {
                    val success = audioRecord?.setPreferredDevice(bluetoothDevice)
                    Log.d(TAG, "🎯 Set preferred device to: ${bluetoothDevice.productName}, success=$success")
                } else {
                    Log.d(TAG, "ℹ️ No Bluetooth SCO device found - will use default routing")
                    Log.d(TAG, "   (Android will auto-route to Bluetooth when available)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set preferred device: ${e.message}")
            }
        }
        
        // 7. Enable audio processing (echo cancellation, noise suppression)
        audioRecord?.audioSessionId?.let { sessionId ->
            if (AcousticEchoCanceler.isAvailable()) {
                acousticEchoCanceler = AcousticEchoCanceler.create(sessionId)
                acousticEchoCanceler?.enabled = true
                Log.d(TAG, "✅ AcousticEchoCanceler enabled")
            }
            
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId)
                noiseSuppressor?.enabled = true
                Log.d(TAG, "✅ NoiseSuppressor enabled")
            }
            
            if (AutomaticGainControl.isAvailable()) {
                automaticGainControl = AutomaticGainControl.create(sessionId)
                automaticGainControl?.enabled = true
                Log.d(TAG, "✅ AutomaticGainControl enabled")
            }
        }

        // 8. Create AudioTrack for speaker output (auto-routes to Bluetooth).
        //
        //    The usage MUST match the routing strategy:
        //    - Split routing (HIGH_QUALITY_PLAYBACK=true): USAGE_MEDIA is what makes
        //      the A2DP high-quality route eligible at all. It only actually lands
        //      there while SCO is released — see releaseScoForPlayback().
        //    - SCO-only (false): USAGE_MEDIA is NOT carried over the SCO link under
        //      MODE_IN_COMMUNICATION. The track is accepted and write() succeeds, but
        //      nothing is rendered to the headset — silent replies with healthy-looking
        //      logs. USAGE_VOICE_COMMUNICATION is the usage that routes to SCO.
        val playbackUsage = if (HIGH_QUALITY_PLAYBACK) {
            android.media.AudioAttributes.USAGE_MEDIA
        } else {
            android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION
        }
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(playbackUsage)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(outputSampleRate)
                    .setChannelMask(CHANNEL_CONFIG_OUT)
                    .setEncoding(AUDIO_FORMAT)
                    .build()
            )
            .setBufferSizeInBytes(outputBufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        // 9. Route AudioTrack output.
        //
        //    SPLIT AUDIO (HIGH_QUALITY_PLAYBACK=true): the mic keeps using Bluetooth
        //    SCO (the only Bluetooth mode with a microphone), but PLAYBACK is pinned
        //    to the A2DP device instead. SCO is mono 8–16 kHz call-quality, which is
        //    why the glasses sounded muffled/low; A2DP carries the full 24 kHz Gemini
        //    output. USAGE_MEDIA (set above) is what makes A2DP eligible at all.
        //
        //    Set HIGH_QUALITY_PLAYBACK=false to fall back to the old SCO-only routing
        //    if split routing causes dropouts on a particular headset.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val outputDevices = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS) ?: arrayOf()

                val a2dpDevice = PreferredAudioDeviceResolver.findGlasses(
                    context, outputDevices, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                )
                val scoDevice = PreferredAudioDeviceResolver.findGlasses(
                    context, outputDevices, AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                )

                val chosen = if (HIGH_QUALITY_PLAYBACK && a2dpDevice != null) a2dpDevice else scoDevice

                if (chosen != null) {
                    val success = audioTrack?.setPreferredDevice(chosen)
                    val kind = if (chosen.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
                        "A2DP (high quality)" else "SCO (call quality)"
                    Log.d(TAG, "🎯 AudioTrack preferred output → ${chosen.productName} [$kind], success=$success")
                } else {
                    Log.d(TAG, "ℹ️ No Bluetooth output device found — using system default routing")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set AudioTrack preferred device: ${e.message}")
            }
        }

        // 10. Check final audio routing
        val finalDevice = audioRecord?.routedDevice
        val deviceName = finalDevice?.productName ?: "default"
        val deviceType = when (finalDevice?.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "✅ Bluetooth SCO (Glass Mic)"
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "📱 Phone Mic"
            else -> "Other (${finalDevice?.type})"
        }
        
        Log.d(TAG, "🎧 ===== AUDIO INITIALIZATION COMPLETE =====")
        Log.d(TAG, "   📡 BLE Connection: Active (for data/commands)")
        Log.d(TAG, "   🎤 Audio Input: $deviceName ($deviceType)")
        Log.d(TAG, "   🔊 Audio Output: ${outputSampleRate}Hz")
        Log.d(TAG, "   🎧 Provider: $activeProvider")
        Log.d(TAG, "==========================================")
        
    } catch (e: Exception) {
        Log.e(TAG, "❌ Failed to initialize audio: ${e.message}", e)
        throw e
    }
}

    /**
     * Connect to Realtime WebSocket (GPT or Gemini based on activeProvider)
     */
    private fun connectWebSocket(apiKey: String, systemInstruction: String) {
        val url: String
        val requestBuilder = Request.Builder()
        lastSystemInstruction = systemInstruction

        if (activeProvider == ModelProvider.GPT_REALTIME) {
            url = "wss://api.openai.com/v1/realtime?model=$GPT_MODEL"
            requestBuilder.url(url)
                .header("Authorization", "Bearer $apiKey")
                .header("OpenAI-Beta", "realtime=v1")
            Log.d(TAG, "🌐 Connecting to OpenAI Realtime: $GPT_MODEL")
        } else {
            url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
            requestBuilder.url(url)
            Log.d(TAG, "🌐 Connecting to Gemini Live: $activeGeminiModel")
        }
        // Remember the key so a 404 fallback can reconnect without re-plumbing it.
        lastApiKey = apiKey
        
        val request = requestBuilder.build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val providerName = if (activeProvider == ModelProvider.GPT_REALTIME) "OpenAI Realtime" else "Gemini Live"
                Log.d(TAG, "✅ ======================================")
                Log.d(TAG, "✅ WEBSOCKET CONNECTED TO $providerName")
                Log.d(TAG, "✅ Response code: ${response.code}")
                Log.d(TAG, "✅ ======================================")
                this@GeminiLiveService.webSocket = webSocket
                isSetupComplete.set(false)

                // NOTE: "connected" is deliberately NOT reported here. The socket
                // being open only means the transport is up - the server has not
                // seen our setup message yet, and anything sent before it lands is
                // rejected with 1007 (INVALID_ARGUMENT), killing the session.
                // Listeners are told we're connected once setup is acknowledged
                // (setupComplete / session.created).

                // Send session configuration
                Log.d(TAG, "📤 Sending setup message...")
                sendSetupMessage(webSocket, systemInstruction)

                // Start audio playback (ready to receive)
                Log.d(TAG, "🔊 Starting audio playback...")
                startAudioPlayback()
                
                // Start audio capture immediately
                scope.launch {
                    delay(100) // Brief startup delay
                    Log.d(TAG, "⚡ Starting audio capture for $providerName")
                    startAudioCapture(webSocket)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "📩 WebSocket onMessage (text): ${text.take(200)}...")
                handleWebSocketMessage(text)
            }
            
            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                Log.d(TAG, "📩 WebSocket onMessage (bytes): ${bytes.size} bytes")
                handleWebSocketMessage(bytes.utf8())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "❌ WebSocket failure: ${t.message}", t)
                val responseBody = try {
                    response?.body?.string().orEmpty()
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Could not read response body")
                    ""
                }
                Log.e(TAG, "❌ Response: $responseBody")

                // If the Gemini Live primary model isn't available for this key
                // (404 / NOT_FOUND), retry once with the more widely-enabled
                // fallback model instead of surfacing a connection error.
                val notFound = response?.code == 404 ||
                    responseBody.contains("NOT_FOUND", ignoreCase = true) ||
                    responseBody.contains("not found", ignoreCase = true) ||
                    (t.message?.contains("404") == true)
                if (activeProvider == ModelProvider.GEMINI_LIVE && notFound && !triedGeminiFallback) {
                    triedGeminiFallback = true
                    activeGeminiModel = GEMINI_MODEL_FALLBACK
                    Log.w(TAG, "⚠️ Gemini Live model unavailable, retrying with $activeGeminiModel")
                    this@GeminiLiveService.webSocket = null
                    scope.launch {
                        try {
                            connectWebSocket(lastApiKey, lastSystemInstruction)
                        } catch (e: Exception) {
                            callbacks.onError("Connection failed: ${e.message}")
                        }
                    }
                    return
                }

                // Transient hiccup (Gemini occasionally drops the socket mid-setup
                // or right after connecting). Previously the user had to manually
                // "quick start" again; instead, silently re-establish the session a
                // couple of times before surfacing any error. Auth/quota/billing
                // problems are not transient, so don't retry those - a suspended key
                // will fail identically on every retry, and retrying just delayed and
                // muddied the real error (see errorMessage below).
                val isAuthError = response?.code == 401 || response?.code == 403 ||
                    response?.code == 429 ||
                    t.message?.contains("401", ignoreCase = true) == true ||
                    t.message?.contains("403", ignoreCase = true) == true ||
                    t.message?.contains("429", ignoreCase = true) == true
                if (!isAuthError && autoReconnects < MAX_AUTO_RECONNECTS) {
                    autoReconnects++
                    Log.w(TAG, "⚠️ Connection dropped, auto-reconnecting (attempt $autoReconnects/$MAX_AUTO_RECONNECTS)")
                    this@GeminiLiveService.webSocket = null
                    isSetupComplete.set(false)
                    scope.launch {
                        delay(600) // brief backoff before retrying
                        try {
                            connectWebSocket(lastApiKey, lastSystemInstruction)
                        } catch (e: Exception) {
                            Log.e(TAG, "Auto-reconnect failed: ${e.message}")
                            callbacks.onError("Connection failed: ${e.message}")
                            cleanup()
                        }
                    }
                    return
                }

                // Distinguishes "no network" from "the server refused us", which used
                // to collapse into one generic "Connection failed" message that never
                // named the actual HTTP status - so a 429 quota rejection and a 401
                // revoked key both looked identical to a dropped WiFi connection, and
                // the (previously mislabelled "Invalid OpenAI API Key") 401 branch
                // fired for Gemini too since it only checked the message text, not
                // which provider was active.
                val providerLabel = if (activeProvider == ModelProvider.GPT_REALTIME) "OpenAI" else "Gemini"
                val errorMessage = when {
                    response?.code == 429 ->
                        "$providerLabel API quota exceeded — check billing/usage limits (429: $responseBody)"
                    response?.code == 401 ->
                        "$providerLabel API key rejected as invalid (401: $responseBody)"
                    response?.code == 403 ->
                        "$providerLabel API key lacks permission or billing is not enabled (403: $responseBody)"
                    response?.code != null ->
                        "$providerLabel connection rejected (${response.code}: $responseBody)"
                    t.message?.contains("network", ignoreCase = true) == true -> "Network disconnected"
                    t.message?.contains("internet", ignoreCase = true) == true -> "No internet connection"
                    t.message?.contains("connection", ignoreCase = true) == true -> "Connection lost"
                    t.message?.contains("timeout", ignoreCase = true) == true -> "Connection timeout"
                    else -> "Connection failed: ${t.message}"
                }
                
                try {
                    callbacks.onError(errorMessage)
                    callbacks.onConnectionStatusChanged(false)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in callbacks: ${e.message}")
                }
                
                try {
                    cleanup()
                } catch (e: Exception) {
                    Log.e(TAG, "Error during cleanup: ${e.message}")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "⚠️ WebSocket closing: $code - $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "🔒 WebSocket closed: $code - $reason")

                // 🆕 A server-side rejection arrives here as a close frame, NOT via
                // onFailure - so the model-fallback / auto-reconnect logic in
                // onFailure never saw it and the session just died silently, with
                // nothing ever spoken.
                //
                // 1007 (INVALID_ARGUMENT) means the server refused our setup
                // message - most often because the configured Live model isn't
                // enabled for this API key. Retry once on the more widely-enabled
                // fallback model, mirroring the 404 handling in onFailure.
                val configRejected = code == 1007 ||
                    reason.contains("invalid argument", ignoreCase = true)
                if (activeProvider == ModelProvider.GEMINI_LIVE &&
                    configRejected &&
                    !triedGeminiFallback
                ) {
                    triedGeminiFallback = true
                    activeGeminiModel = GEMINI_MODEL_FALLBACK
                    Log.w(TAG, "⚠️ Setup rejected ($code: $reason) - retrying with $activeGeminiModel")
                    this@GeminiLiveService.webSocket = null
                    isSetupComplete.set(false)
                    scope.launch {
                        try {
                            connectWebSocket(lastApiKey, lastSystemInstruction)
                        } catch (e: Exception) {
                            Log.e(TAG, "Fallback reconnect failed: ${e.message}")
                            callbacks.onError("Connection failed: ${e.message}")
                            callbacks.onConnectionStatusChanged(false)
                            cleanup()
                        }
                    }
                    return
                }

                if (configRejected) {
                    // Surface it instead of failing silently - otherwise the user
                    // just sees the screen close with no answer and no explanation.
                    Log.e(TAG, "❌ Gemini Live rejected the session setup ($code: $reason)")
                    callbacks.onError("AI rejected the session setup: $reason")
                } else if (code != 1000) {
                    // Any other non-clean close (clean = 1000, the normal end-of-turn
                    // shutdown this app itself requests) used to fall straight through
                    // to cleanup() with nothing reported. That is exactly what a quota
                    // exhaustion, billing suspension, or revoked-key rejection looks
                    // like from the server: it can close the frame with a code other
                    // than 1007 instead of failing the handshake, so it never hit
                    // onFailure's error handling either. The user just saw the session
                    // end with no explanation - indistinguishable from a normal stop.
                    //
                    // 1008 = policy violation, 1011 = internal error: Gemini uses both
                    // for auth/quota/billing rejections depending on where in the
                    // pipeline the request was refused. Log the reason text too, since
                    // that is where "quota", "billing" or "permission" actually shows.
                    val looksLikeAccessProblem = code == 1008 || code == 1011 ||
                        reason.contains("quota", ignoreCase = true) ||
                        reason.contains("billing", ignoreCase = true) ||
                        reason.contains("permission", ignoreCase = true) ||
                        reason.contains("exhausted", ignoreCase = true) ||
                        reason.contains("suspended", ignoreCase = true)

                    val message = if (looksLikeAccessProblem) {
                        "AI unavailable — the API key may be out of quota, unbilled, or revoked ($code: ${reason.ifBlank { "no reason given" }})"
                    } else {
                        "AI session ended unexpectedly ($code: ${reason.ifBlank { "no reason given" }})"
                    }
                    Log.e(TAG, "❌ $message")
                    callbacks.onError(message)
                }

                callbacks.onConnectionStatusChanged(false)
                cleanup()
            }
        }

        client.newWebSocket(request, listener)
    }

    /**
     * Send session.update to configure the OpenAI Realtime session with tools
     */
    private fun sendSetupMessage(webSocket: WebSocket, systemInstruction: String) {
        // Define tools/functions for OpenAI Realtime format
        val allTools = listOf(
            mapOf(
                "type" to "function",
                "name" to "make_phone_call",
                "description" to "Make a phone call to a contact by name",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "contact_name" to mapOf("type" to "string", "description" to "Name of the contact to call")
                    ),
                    "required" to listOf("contact_name")
                )
            ),
            mapOf(
                "type" to "function",
                "name" to "play_music",
                "description" to "Play music or a specific song on Spotify",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "song_or_artist" to mapOf("type" to "string", "description" to "Song name, artist name, or music genre to play")
                    ),
                    "required" to listOf("song_or_artist")
                )
            ),
            mapOf(
                "type" to "function",
                "name" to "play_youtube",
                "description" to "Play a video on YouTube",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "search_query" to mapOf("type" to "string", "description" to "Video or topic to search on YouTube")
                    ),
                    "required" to listOf("search_query")
                )
            ),
            mapOf(
                "type" to "function",
                "name" to "open_camera",
                "description" to "Open the camera to take a photo or record video",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "mode" to mapOf("type" to "string", "description" to "Camera mode: 'photo', 'video', or 'view'", "enum" to listOf("photo", "video", "view"))
                    ),
                    "required" to listOf("mode")
                )
            ),
            mapOf(
                "type" to "function",
                "name" to "take_photo",
                "description" to "Capture a photo with the camera",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf<String, Any>()
                )
            ),
            mapOf(
                "type" to "function",
                "name" to "record_video",
                "description" to "Start or stop video recording",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "action" to mapOf("type" to "string", "description" to "Action: 'start' to begin recording, 'stop' to end recording", "enum" to listOf("start", "stop"))
                    ),
                    "required" to listOf("action")
                )
            ),
            mapOf(
                "type" to "function",
                "name" to "analyze_view",
                "description" to "Analyze what is in front of the user using the camera (what is this, what is in front of me, identify object)",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "question" to mapOf("type" to "string", "description" to "The question about what the user wants to know about their view")
                    )
                )
            ),
            mapOf(
                "type" to "function",
                "name" to "capture_new_frame",
                "description" to "Capture a new photo/frame from glasses camera when user wants to see something new",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf<String, Any>()
                )
            ),
            mapOf(
                "type" to "function",
                "name" to "open_maps",
                "description" to "Open Google Maps for navigation or location search",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "destination" to mapOf("type" to "string", "description" to "Destination or place to navigate to or search for"),
                        "mode" to mapOf("type" to "string", "description" to "Navigation mode: 'driving', 'walking', 'transit'", "enum" to listOf("driving", "walking", "transit"))
                    )
                )
            ),
            mapOf(
                "type" to "function",
                "name" to "send_message",
                "description" to "Send a WhatsApp or SMS message to a contact",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "contact_name" to mapOf("type" to "string", "description" to "Name of the contact to message"),
                        "message" to mapOf("type" to "string", "description" to "Message content to send"),
                        "app" to mapOf("type" to "string", "description" to "Messaging app to use: 'whatsapp' or 'sms'", "enum" to listOf("whatsapp", "sms"))
                    ),
                    "required" to listOf("contact_name", "message")
                )
            ),
            mapOf(
                "type" to "function",
                "name" to "set_reminder",
                "description" to "Set a reminder or alarm",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "reminder_text" to mapOf("type" to "string", "description" to "What to remind about"),
                        "time" to mapOf("type" to "string", "description" to "When to remind (e.g., 'in 10 minutes', '3 PM', 'tomorrow 9 AM')")
                    ),
                    "required" to listOf("reminder_text")
                )
            ),
            mapOf(
                "type" to "function",
                "name" to "create_note",
                "description" to "Create a quick note or reminder when user asks to remember something or add to notes",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "title" to mapOf("type" to "string", "description" to "Short title for the note"),
                        "content" to mapOf("type" to "string", "description" to "Content of the note")
                    ),
                    "required" to listOf("title", "content")
                )
            ),
            mapOf(
                "type" to "function",
                "name" to "capture_photo_note",
                "description" to "Take a photo with the glasses camera and attach it to a new note. Use when user says 'take a pic and add to notes', 'click photo and save in notes', 'capture this and note it down', or similar requests to photograph something and save it as a note.",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "title" to mapOf("type" to "string", "description" to "Short title for the photo note"),
                        "content" to mapOf("type" to "string", "description" to "Optional text description to go with the photo")
                    ),
                    "required" to listOf("title")
                )
            ),
            mapOf(
                "type" to "function",
                "name" to "start_meeting",
                "description" to "Start meeting minutes recording with speech-to-text transcription",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "title" to mapOf("type" to "string", "description" to "Optional meeting title, auto-generated if not provided")
                    )
                )
            ),
            mapOf(
                "type" to "function",
                "name" to "get_weather",
                "description" to "Get current weather information",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "location" to mapOf("type" to "string", "description" to "City or location to get weather for (optional, uses current location if not specified)")
                    )
                )
            ),
            mapOf(
                "type" to "function",
                "name" to "web_search",
                "description" to "Perform a web search and return a concise summary",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "query" to mapOf("type" to "string", "description" to "Search query to look up on the web")
                    ),
                    "required" to listOf("query")
                )
            ),
            mapOf(
                "type" to "function",
                "name" to "get_news",
                "description" to "Fetch top news headlines for a topic or location",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "query" to mapOf("type" to "string", "description" to "Topic or location for news (optional)")
                    )
                )
            ),
            mapOf(
                "type" to "function",
                "name" to "play_shayari",
                "description" to "Play or return a short shayari/poem",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "mood" to mapOf("type" to "string", "description" to "Optional mood: romantic, sad, funny")
                    )
                )
            ),
            mapOf(
                "type" to "function",
                "name" to "control_volume",
                "description" to "Control the device volume",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "action" to mapOf("type" to "string", "description" to "Volume action: 'up', 'down', 'mute', 'unmute', or a number 0-100", "enum" to listOf("up", "down", "mute", "unmute", "max"))
                    ),
                    "required" to listOf("action")
                )
            ),
            mapOf(
                "type" to "function",
                "name" to "read_notifications",
                "description" to "Read the user's recent phone notifications. Use this whenever the user asks about their notifications, e.g. 'what notifications do I have', 'any new messages', 'read my notifications', 'koi notification aaya kya'.",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "limit" to mapOf("type" to "integer", "description" to "How many recent notifications to read (default 5)")
                    )
                )
            ),
            mapOf(
                "type" to "function",
                "name" to "read_emails",
                "description" to "Read the user's recent Gmail inbox, or the count of unread emails. Use when the user asks 'do I have any new emails', 'read my emails', 'check my inbox', 'any mail from someone', or similar.",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "mode" to mapOf("type" to "string", "description" to "'unread_count' for just a count, or 'recent' to list recent emails", "enum" to listOf("unread_count", "recent")),
                        "limit" to mapOf("type" to "integer", "description" to "How many recent emails to read when mode is 'recent' (default 5)")
                    )
                )
            ),
            mapOf(
                "type" to "function",
                "name" to "draft_email",
                "description" to "Prepare an email to send on the user's behalf. Use whenever the user asks to 'send an email to X', 'email X about Y', 'write a mail to X'. Extract the recipient's name (or email address if spoken), a short subject, and the message body from what the user said. This does NOT send the email yet — it only prepares it and reads it back for confirmation. You MUST call confirm_send_email after the user says yes.",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "recipient" to mapOf("type" to "string", "description" to "The contact name or email address to send to"),
                        "subject" to mapOf("type" to "string", "description" to "A short email subject line"),
                        "body" to mapOf("type" to "string", "description" to "The email message body, written out in full sentences")
                    ),
                    "required" to listOf("recipient", "subject", "body")
                )
            ),
            mapOf(
                "type" to "function",
                "name" to "confirm_send_email",
                "description" to "Actually send the email that was most recently prepared with draft_email. Only call this after you have read the draft back to the user and they clearly confirmed with something like 'yes', 'send it', 'go ahead'. If the user says no or asks to change something, do NOT call this — call draft_email again instead with the correction.",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf<String, Any>()
                )
            ),
            mapOf(
                "type" to "function",
                "name" to "enter_silent_mode",
                "description" to "Go into silent mode and stop speaking out loud. Use when the user asks you to 'be quiet', 'go silent', 'stop talking', 'mute yourself', 'silent mode', 'chup ho jao', 'shaant ho jao', or similar. You keep listening so you can still be told to speak again, but you stop producing voice output until silent mode is turned off.",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf<String, Any>()
                )
            ),
            mapOf(
                "type" to "function",
                "name" to "exit_silent_mode",
                "description" to "Leave silent mode and start speaking out loud again. Use when the user asks you to 'start talking', 'speak again', 'you can talk now', 'unmute', 'exit silent mode', 'wapas bolo', or similar.",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf<String, Any>()
                )
            ),
            mapOf(
                "type" to "function",
                "name" to "identify_song",
                "description" to "Identify the music currently playing around the user, like Shazam. Use whenever the user asks what song is playing, e.g. 'what song is this', 'tell me what song this is', 'what's this track', 'who sings this', 'name this song', 'kaunsa gaana hai ye', 'ye gaana kaunsa hai'. This listens to the ambient audio already being captured — do NOT ask the user to hold the phone up or play the song again, just call it.",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf<String, Any>()
                )
            )
        )

        // 👁️ Vision is Mark 2 only. Mark 1's tool handler (Mark1MainActivity /
        // ListeningService) has no analyze_view / capture_new_frame case, so
        // declaring them there only gets the model to call something that comes
        // back "not yet implemented" — worse than not offering them at all.
        val visionFiltered = if (isMark2) {
            allTools
        } else {
            allTools.filterNot { (it["name"] as? String) in VISION_TOOL_NAMES }
        }

        // 🌐 Browser tools work on both marks — they drive an off-screen WebView
        // on the phone, not the glasses hardware — so they are not mark-gated.
        val tools = visionFiltered + com.sdk.glassessdksample.ui.web.GlassBrowserTools.declarations()

        // 👁️ Every other tool in this list has an explicit "call this when the
        // user says X" section below. Vision had none, so with the "reply FAST
        // and CONCISELY" rule above the model just answered "what is in front of
        // me" verbally instead of calling analyze_view — and nothing opened.
        // Mark 2 only, matching the tool gating above.
        val visionInstruction = if (isMark2) """

VISION - SEEING THROUGH THE GLASSES CAMERA: You CAN see. The glasses have a camera and analyze_view takes a fresh photo through it and describes it.
When the user asks about anything in their surroundings - "what is in front of me", "what's this", "what do you see", "who is in front of me", "describe this", "what am I looking at", "read this for me", "mere samne kya hai", "ye kya hai", "kya dikh raha hai", "dekho kya hai" - call analyze_view IMMEDIATELY. Pass what they actually asked in the 'question' parameter.
Call the tool FIRST, before saying anything. Do not describe the scene from memory or guess.
NEVER say you cannot see, that you have no camera, that you are only a voice assistant, or ask the user to send/describe a photo. That is wrong - you have a camera, so use it.
If the user then asks about something NEW after already getting a description, call capture_new_frame to take a fresh photo rather than reusing the old one.
""" else ""

        // Enhanced system instruction
        val enhancedInstruction = """$systemInstruction

You are Imi Glass, a smart glasses assistant.
IMPORTANT LANGUAGE RULE: ALWAYS Reply in the EXACT SAME LANGUAGE the user speaks.
- If user speaks English -> Reply in English.
- If user speaks Hindi -> Reply in Hindi.
- If user speaks Hinglish -> Reply in Hinglish.

CRITICAL: Reply FAST and CONCISELY. No filler words. Match the user's vibe.

QUICK NOTES: When the user asks to "remember this", "add to notes", "note this down", or mentions saving information, use the create_note tool to save it.
When the user asks to "take a pic and add to notes", "click photo and save in notes", "capture this and note it", or wants to photograph something AND save it as a note, use the capture_photo_note tool.

MEETING MINUTES: When the user asks to "start meeting minutes", "record this meeting", "start recording the meeting", or similar, use the start_meeting tool to begin recording. If they mention a specific meeting name (e.g., "start meeting minutes for Raghav Meeting"), extract the meeting name and pass it in the 'title' parameter. Otherwise leave title empty for auto-generation.

NOTIFICATIONS: You CAN read the user's phone notifications. When the user asks "what notifications do I have", "any new messages/notifications", "read my notifications", or similar (in any language), call the read_notifications tool and tell them about their recent notifications in a brief, spoken-word style.

SILENT MODE: When the user asks you to be quiet, go silent, stop talking, mute yourself, or similar (in any language, e.g. "chup ho jao", "shaant ho jao"), call the enter_silent_mode tool. Give a single very short spoken acknowledgement (like "Okay") and then stay quiet. When the user later asks you to speak again, talk, unmute, or exit silent mode (e.g. "wapas bolo"), call the exit_silent_mode tool and give a short spoken confirmation that you're back.

SONG IDENTIFICATION: When the user asks what song or music is playing (in any language), call identify_song. It listens to the audio already around you, so never ask the user to replay the song or hold up the phone. Report the result naturally in one short line, like "That's Warriors by Imagine Dragons." If it comes back saying it couldn't identify the song, just say so briefly without apologising at length.

EMAIL - READING: When the user asks about new emails, their inbox, or unread mail, call read_emails and tell them the result briefly.

WEB BROWSER - YOU CAN USE WEBSITES: You control a real browser on the user's phone, already signed in to sites they use. When the user wants something DONE on a website rather than just answered from memory - "open my email and check", "search Amazon for headphones and tell me the price", "book a table on this site", "check the score on the cricket site" - call browse_web and put their whole request in the 'goal' parameter. To read back whatever page is open, call read_current_page. For "how far is my Claude project", "what was I doing in ChatGPT", call catch_up_on_ai.
If a browser tool comes back saying you need the user to sign in, solve a security check, or finish something on the phone, tell them EXACTLY that in one short line and stop - do not try another way around it and never ask them for a password or a one-time code. When they say they are done ("done", "logged in", "carry on", "ho gaya"), call browser_continue.
Browser tools take a few seconds. Say one short line like "Let me check" BEFORE calling, then report what came back.

EMAIL - SENDING (always confirm first): When the user asks you to email or write to someone, call draft_email with your best guess at recipient, subject, and body from what they said. Then READ THE DRAFT BACK to the user out loud in your own next spoken turn (recipient, subject, and a short summary of the body) and ask "should I send it?". Do NOT call confirm_send_email in the same turn as draft_email. Only call confirm_send_email in a LATER turn, after the user has explicitly agreed (e.g. "yes", "send it", "go ahead"). If the user wants changes, call draft_email again with the corrected details and read it back again. If the user declines, do not send anything.
$visionInstruction"""

        if (activeProvider == ModelProvider.GPT_REALTIME) {
            // ====== OpenAI Realtime: session.update event ======
            val sessionUpdate = mapOf(
                "type" to "session.update",
                "session" to mapOf(
                    "modalities" to listOf("text", "audio"),
                    "instructions" to enhancedInstruction,
                    "voice" to GPT_VOICE,
                    "input_audio_format" to "pcm16",
                    "output_audio_format" to "pcm16",
                    "input_audio_transcription" to mapOf(
                        "model" to "whisper-1"
                    ),
                    "turn_detection" to mapOf(
                        "type" to "server_vad",
                        "threshold" to 0.5,
                        "prefix_padding_ms" to 300,
                        "silence_duration_ms" to 700
                    ),
                    "tools" to tools,
                    "tool_choice" to "auto",
                    "temperature" to 0.6,
                    "max_response_output_tokens" to 512
                )
            )
            val json = gson.toJson(sessionUpdate)
            Log.d(TAG, "📤 Sending GPT session.update: ${json.take(500)}...")
            webSocket.send(json)
        } else {
            // ====== Gemini Live: BidiGenerateContent setup ======
            val geminiToolDeclarations = tools.map { tool ->
                val name = tool["name"] as? String ?: ""
                val desc = tool["description"] as? String ?: ""
                val params = tool["parameters"] as? Map<*, *> ?: emptyMap<String, Any>()
                mapOf(
                    "name" to name,
                    "description" to desc,
                    "parameters" to params
                )
            }
            
            val setupMessage = mapOf(
                "setup" to mapOf(
                    "model" to "models/$activeGeminiModel",
                    "generation_config" to mapOf(
                        "response_modalities" to listOf("AUDIO"),
                        "speech_config" to mapOf(
                            "voice_config" to mapOf(
                                "prebuilt_voice_config" to mapOf(
                                    "voice_name" to GEMINI_VOICE
                                )
                            )
                        )
                    ),
                    "system_instruction" to mapOf(
                        "parts" to listOf(
                            mapOf("text" to enhancedInstruction)
                        )
                    ),
                    // Ask Gemini Live to return text transcripts of both the user's
                    // speech and the model's audio reply. Without these the turn
                    // text is empty and nothing can be saved to history.
                    "input_audio_transcription" to mapOf<String, Any>(),
                    "output_audio_transcription" to mapOf<String, Any>(),
                    "tools" to listOf(
                        mapOf("function_declarations" to geminiToolDeclarations),
                        mapOf("google_search" to mapOf<String, Any>())
                    )
                )
            )
            val json = gson.toJson(setupMessage)
            Log.d(TAG, "📤 Sending Gemini setup: ${json.take(500)}...")
            webSocket.send(json)
        }
    }

    /**
     * Start capturing audio from microphone and streaming to WebSocket
     * GPT: input_audio_buffer.append with base64 PCM16 24kHz
     * Gemini: realtime_input with base64 PCM16 16kHz
     */
    private fun startAudioCapture(webSocket: WebSocket) {
        scope.launch {
            try {
                audioRecord?.startRecording()
                isRecording.set(true)
                // Fresh VAD state per session, so a previous conversation cannot
                // leave us "mid-turn" and chime on the first frame of this one.
                speechActive = false
                lastVoiceFrameMs = 0L
                // Start the song-ID rolling window alongside the session.
                songIdBuffer = SongIdentifier.PcmRingBuffer(inputSampleRate)
                Log.d(TAG, "🎤 Audio capture started (${inputSampleRate}Hz for $activeProvider)")

                // Buffer: ~30ms worth of samples
                val bufferSize = (inputSampleRate * 30 / 1000) // 30ms
                val buffer = ShortArray(bufferSize)
                var chunkCount = 0
                var totalBytes = 0
                var halfDuplexSkips = 0

                while (isRecording.get()) {
                    val readSize = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                    
                    if (readSize > 0) {
                        // 🎵 Song-ID tee — MUST stay ABOVE the half-duplex check below.
                        // That check drops mic frames while the AI is speaking, which is
                        // right for the websocket (it prevents echo) but wrong for music
                        // fingerprinting: a clip with gaps punched in it fails to match
                        // and AudD returns error #300. Teeing here keeps the rolling
                        // window continuous regardless of what Imi is doing.
                        songIdBuffer?.let { ring ->
                            val raw = shortArrayToByteArray(buffer, readSize)
                            ring.write(raw, raw.size)
                        }

                        // Don't stream mic audio before the server has acknowledged
                        // setup - those frames are refused (1007) and take the
                        // session down with them. Drop them; the user hasn't been
                        // prompted to speak yet at this point anyway.
                        if (!isSetupComplete.get()) {
                            continue
                        }

                        // Half-duplex: Skip sending audio when AI is speaking (prevents echo)
                        if (isAIPlaying.get()) {
                            halfDuplexSkips++
                            // The mic is muted while Imi talks, so the VAD would see
                            // this as silence and fire a bogus speech-end. Reset it
                            // instead: the next real user turn starts from scratch.
                            speechActive = false
                            lastVoiceFrameMs = 0L
                            continue
                        }

                        // ---- Local speech-end VAD (drives the processing chime) ----
                        // This is the EARLIEST point at which we can know the user has
                        // stopped talking — it reads the same mic frames already being
                        // streamed, before Gemini has received, transcribed, or
                        // responded to any of them. Gemini Live has no speech_stopped
                        // event, so without this the cue could only be hung off
                        // inputTranscription, which lands far too late to fill the gap.
                        // Cheap enough to sit inline: one pass over a 30 ms frame.
                        detectSpeechEdge(buffer, readSize)

                        // Convert PCM16 to base64
                        val pcmData = shortArrayToByteArray(buffer, readSize)
                        val base64Data = Base64.encodeToString(pcmData, Base64.NO_WRAP)
                        totalBytes += pcmData.size

                        val json: String
                        if (activeProvider == ModelProvider.GPT_REALTIME) {
                            // OpenAI Realtime: input_audio_buffer.append
                            val audioAppend = mapOf(
                                "type" to "input_audio_buffer.append",
                                "audio" to base64Data
                            )
                            json = gson.toJson(audioAppend)
                        } else {
                            // Gemini Live: realtime_input
                            val audioAppend = mapOf(
                                "realtime_input" to mapOf(
                                    "media_chunks" to listOf(
                                        mapOf(
                                            "data" to base64Data,
                                            "mime_type" to "audio/pcm;rate=${inputSampleRate}"
                                        )
                                    )
                                )
                            )
                            json = gson.toJson(audioAppend)
                        }

                        val sent = webSocket.send(json)
                        
                        chunkCount++
                        
                        if (chunkCount == 1) {
                            Log.d(TAG, "📤 First audio chunk: ${pcmData.size} bytes, base64 length: ${base64Data.length}")
                            if (!sent) {
                                Log.e(TAG, "❌ Failed to send first audio chunk!")
                            }
                        }
                        
                        if (chunkCount % 100 == 0) {
                            Log.d(TAG, "📤 Sent $chunkCount audio chunks (${totalBytes/1024} KB total) | Half-duplex skips: $halfDuplexSkips")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in audio capture: ${e.message}", e)
                callbacks.onError("Audio capture error: ${e.message}")
            }
        }
    }

    /**
     * 🎵 Most recent [seconds] of mic audio as raw PCM16 mono, for song
     * identification. Null if no live session is capturing, or if the rolling
     * window hasn't filled yet. See [SongIdentifier].
     */
    fun getSongIdClip(seconds: Int): ByteArray? = songIdBuffer?.lastSeconds(seconds)

    /** Sample rate of the audio returned by [getSongIdClip] (16k Gemini / 24k GPT). */
    fun songIdSampleRate(): Int = inputSampleRate

        // Send image over WebSocket for GPT Realtime vision analysis
        // Note: OpenAI Realtime API supports images via conversation.item.create
        fun sendRealtimeImage(imageBytes: ByteArray) {
            if (webSocket == null) {
                Log.e(TAG, "❌ WebSocket not connected. Cannot send image.")
                return
            }

            try {
                val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)

                if (activeProvider == ModelProvider.GPT_REALTIME) {
                    // GPT Realtime: send as conversation item with text context
                    val itemCreate = mapOf(
                        "type" to "conversation.item.create",
                        "item" to mapOf(
                            "type" to "message",
                            "role" to "user",
                            "content" to listOf(
                                mapOf(
                                    "type" to "input_text",
                                    "text" to "I'm sharing an image from my glasses camera. Please analyze what you see."
                                )
                            )
                        )
                    )
                    val sent = webSocket?.send(gson.toJson(itemCreate)) ?: false
                    val responseCreate = mapOf("type" to "response.create")
                    webSocket?.send(gson.toJson(responseCreate))
                    Log.d(TAG, "📷 Sent image context to GPT Realtime (${imageBytes.size} bytes). sent=$sent")
                } else {
                    // Gemini Live: send image as inline_data in realtime_input
                    val realtimeInput = mapOf(
                        "realtime_input" to mapOf(
                            "media_chunks" to listOf(
                                mapOf(
                                    "mime_type" to "image/jpeg",
                                    "data" to base64Image
                                )
                            )
                        )
                    )
                    webSocket?.send(gson.toJson(realtimeInput))
                    // Follow up with a text prompt asking to analyze
                    val clientContent = mapOf(
                        "client_content" to mapOf(
                            "turns" to listOf(
                                mapOf(
                                    "role" to "user",
                                    "parts" to listOf(mapOf("text" to "I'm sharing an image from my glasses camera. Please analyze what you see."))
                                )
                            ),
                            "turn_complete" to true
                        )
                    )
                    webSocket?.send(gson.toJson(clientContent))
                    Log.d(TAG, "📷 Sent image to Gemini Live (${imageBytes.size} bytes)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to send realtime image: ${e.message}", e)
                callbacks.onError("Failed to send image: ${e.message}")
            }
        }

    /**
     * Start audio playback coroutine with pre-buffering for smooth playback
     */
    private fun startAudioPlayback() {
        scope.launch {
            try {
                audioTrack?.play()
                totalFramesWritten = 0L // playback head starts at 0 with the track
                isPlaying.set(true)
                isPreBuffering = true // Start in pre-buffering mode
                var lastAudioTime = 0L // Track when we last played audio
                Log.d(TAG, "🔊 Audio playback started (pre-buffering enabled, PRE_BUFFER_COUNT=$PRE_BUFFER_COUNT)")

                // When the first chunk of a turn lands, we start a short grace window.
                // If PRE_BUFFER_COUNT chunks arrive within it we start normally; if the
                // reply is shorter than that, the window expires and we play what we
                // have. This is measured per-turn from the first chunk, so unlike a
                // "turn ended" latch it can never leak into a later turn and cause a
                // premature one-chunk flush.
                var firstChunkTime = 0L

                while (isPlaying.get()) {
                    // Pre-buffering: Wait until we have enough chunks for smooth playback
                    val queueSize = synchronized(audioQueueLock) { audioQueue.size }

                    if (isPreBuffering && queueSize == 0) {
                        firstChunkTime = 0L // nothing pending; reset the window
                        delay(1)
                        continue
                    }

                    if (isPreBuffering && firstChunkTime == 0L && queueSize > 0) {
                        firstChunkTime = System.currentTimeMillis()
                    }

                    val waitedMs = if (firstChunkTime == 0L) 0L
                                   else System.currentTimeMillis() - firstChunkTime
                    val flushNow = queueSize > 0 && waitedMs >= SHORT_REPLY_FLUSH_MS

                    if (isPreBuffering && queueSize < PRE_BUFFER_COUNT && !flushNow) {
                        delay(1) // Ultra-fast polling for instant start
                        continue
                    }

                    if (isPreBuffering && (queueSize >= PRE_BUFFER_COUNT || flushNow)) {
                        isPreBuffering = false
                        firstChunkTime = 0L
                        isAIPlaying.set(true) // AI is speaking, pause mic capture EARLY
                        // Give the Bluetooth device to A2DP before we write a single
                        // byte, otherwise the output track is torn down mid-reply.
                        releaseScoForPlayback()
                        // stopBluetoothSco() is asynchronous like its start: A2DP only
                        // becomes the live route a moment later. Writing immediately
                        // meant the first chunks landed on a track the OS was still
                        // tearing down ("Track invalidated" 2ms after playback start),
                        // and re-pinning below while SCO was still up chose the wrong
                        // device. Let the route settle, then pin to the A2DP endpoint
                        // that is only now enumerable.
                        delay(ROUTE_SETTLE_MS)
                        repinPlaybackToA2dp()
                        callbacks.onAudioPlaybackStart()
                        lastAudioTime = System.currentTimeMillis()
                        val why = if (flushNow) "short-reply flush after ${waitedMs}ms"
                                  else "pre-buffer filled"
                        Log.d(TAG, "🔊 GEMINI RESPONSE PLAYBACK STARTED ($queueSize chunks, $why)")
                    }
                    
                    // Play ALL available chunks in one go for smooth continuous audio
                    val chunksToPlay = synchronized(audioQueueLock) {
                        if (audioQueue.isNotEmpty()) {
                            val chunks = audioQueue.toList()
                            audioQueue.clear()
                            chunks
                        } else {
                            emptyList()
                        }
                    }

                    if (chunksToPlay.isNotEmpty()) {
                        lastAudioTime = System.currentTimeMillis()
                        
                        // Play all chunks continuously without interruption
                        for (chunk in chunksToPlay) {
                            playAudioChunk(chunk)
                        }
                    } else if (!isPreBuffering) {
                        // Queue is empty but we were playing - check if more audio is coming
                        val timeSinceLastAudio = System.currentTimeMillis() - lastAudioTime
                        
                        if (timeSinceLastAudio > AUDIO_END_TIMEOUT_MS) {
                            // No new audio for a while, AI likely finished speaking.
                            //
                            // IMPORTANT: "no more writes" is NOT "finished playing".
                            // AudioTrack.write() returns once bytes are buffered, so
                            // several hundred ms of speech can still be queued in the
                            // track and the A2DP pipeline. Reacquiring SCO here would
                            // suspend A2DP and cut the tail off — "goodbye" came out
                            // as "good". Wait for the hardware playback head to reach
                            // everything we wrote before touching the route.
                            waitForTrackToDrain()
                            reacquireScoForListening()
                            isAIPlaying.set(false) // Resume mic capture
                            isPreBuffering = true // Reset for next turn
                            callbacks.onAudioPlaybackEnd()
                            Log.d(TAG, "🔇 Audio playback ended (no new audio for ${AUDIO_END_TIMEOUT_MS}ms)")
                        }
                        delay(5) // Quick check for new audio
                    } else {
                        delay(1) // Ultra-fast polling when pre-buffering
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in audio playback", e)
                callbacks.onError("Audio playback error: ${e.message}")
            }
        }
    }

    /**
     * Apply software gain to PCM16 samples with clipping protection
     */
    private fun applyGainToPcm16(pcm: ShortArray, gain: Float) {
        for (i in pcm.indices) {
            val amplified = (pcm[i] * gain).toInt()
            pcm[i] = when {
                amplified > Short.MAX_VALUE -> Short.MAX_VALUE
                amplified < Short.MIN_VALUE -> Short.MIN_VALUE
                else -> amplified.toShort()
            }
        }
    }
    
    /**
     * Play a single audio chunk with software gain applied using blocking write
     */
    private fun playAudioChunk(audioData: ByteArray) {
        try {
            // 🔇 Check if output is muted (for vision chat processing)
            if (isMuted.get()) {
                Log.v(TAG, "🔇 Audio muted - skipping playback")
                return // Don't play audio while muted
            }
            
            // Convert byte array to short array for AudioTrack
            val shortBuffer = byteArrayToShortArray(audioData)
            
            // Apply software gain for louder playback
            applyGainToPcm16(shortBuffer, SOFTWARE_GAIN)
            
            // Use WRITE_BLOCKING to ensure all audio is written without dropping
            val written = audioTrack?.write(
                shortBuffer, 0, shortBuffer.size, AudioTrack.WRITE_BLOCKING
            ) ?: 0
            // Mono PCM16: one frame per sample. Tracked so waitForTrackToDrain() knows
            // how far the playback head still has to travel.
            if (written > 0) totalFramesWritten += written.toLong()
        } catch (e: Exception) {
            Log.e(TAG, "Error playing audio chunk", e)
        }
    }

    /**
     * Handle incoming WebSocket messages (routes to GPT or Gemini handler)
     */
    private fun handleWebSocketMessage(text: String) {
        try {
            val logText = if (text.length > 500) text.substring(0, 500) + "...(truncated)" else text
            Log.d(TAG, "📩 Raw message: $logText")
            
            val messageMap = gson.fromJson(text, Map::class.java) as Map<*, *>
            
            if (activeProvider == ModelProvider.GEMINI_LIVE) {
                handleGeminiMessage(messageMap)
                return
            }
            
            val eventType = messageMap["type"] as? String ?: ""
            Log.d(TAG, "📨 Event type: $eventType")

            when (eventType) {
                // Session created/updated - setup is complete
                "session.created", "session.updated" -> {
                    Log.d(TAG, "✅ Session configured: $eventType")
                    val wasSetupComplete = isSetupComplete.getAndSet(true)
                    autoReconnects = 0
                    if (!wasSetupComplete) {
                        callbacks.onConnectionStatusChanged(true)
                        flushPendingSpeakText()
                    }
                    // Greet only once, on the initial session.created (session.updated
                    // can fire again later for config changes).
                    if (eventType == "session.created") maybeSendGreeting()
                }
                
                // Input audio buffer speech started (VAD detected speech)
                "input_audio_buffer.speech_started" -> {
                    Log.d(TAG, "🎤 Speech detected - user is speaking")
                    // Interrupt current AI response if playing
                    if (isAIPlaying.get()) {
                        Log.d(TAG, "⚠️ User interrupted AI - clearing audio queue")
                        stopThinkingSound()
                        synchronized(audioQueueLock) {
                            audioQueue.clear()
                        }
                        audioTrack?.flush()
                        totalFramesWritten = 0L // flush() zeroes the playback head too
                        isAIPlaying.set(false)
                        callbacks.onAudioPlaybackEnd()
                    }
                }
                
                // Input audio buffer speech stopped
                "input_audio_buffer.speech_stopped" -> {
                    Log.d(TAG, "🎤 Speech ended - processing...")
                    startThinkingSound()
                }
                
                // Input audio buffer committed
                "input_audio_buffer.committed" -> {
                    Log.d(TAG, "📤 Audio buffer committed for processing")
                }
                
                // Conversation item input audio transcription completed
                "conversation.item.input_audio_transcription.completed" -> {
                    val transcript = messageMap["transcript"] as? String
                    if (!transcript.isNullOrEmpty()) {
                        currentInputTranscription.clear()
                        currentInputTranscription.append(transcript)
                        callbacks.onTranscriptionUpdate(
                            currentInputTranscription.toString(),
                            currentOutputTranscription.toString(),
                            true
                        )
                        Log.d(TAG, "👤 User transcription: $transcript")
                        
                        // Notify vision listener
                        visionTranscriptionListener?.onUserTranscription(transcript, true)
                    }
                }
                
                // Response created
                "response.created" -> {
                    Log.d(TAG, "🤖 Response generation started")
                    receivedAudioInCurrentTurn = false
                    hasTranscriptionForCurrentTurn = false
                }
                
                // Response audio delta - streaming audio chunks
                "response.audio.delta" -> {
                    val delta = messageMap["delta"] as? String
                    if (delta != null) {
                        // First AI audio chunk - stop thinking sound
                        if (!receivedAudioInCurrentTurn) {
                            stopThinkingSound()
                        }
                        
                        val audioData = Base64.decode(delta, Base64.DEFAULT)
                        synchronized(audioQueueLock) {
                            audioQueue.add(audioData)
                        }
                        receivedAudioInCurrentTurn = true
                        Log.v(TAG, "🎵 Received audio delta: ${audioData.size} bytes")
                    }
                }
                
                // Response audio done
                "response.audio.done" -> {
                    Log.d(TAG, "🎵 Audio streaming complete for this response")
                }
                
                // Response audio transcript delta - streaming text of AI speech
                "response.audio_transcript.delta" -> {
                    val delta = messageMap["delta"] as? String
                    if (!delta.isNullOrEmpty()) {
                        currentOutputTranscription.append(delta)
                        hasTranscriptionForCurrentTurn = true
                        callbacks.onTranscriptionUpdate(
                            currentInputTranscription.toString(),
                            currentOutputTranscription.toString(),
                            false
                        )
                        Log.d(TAG, "🤖 Model transcript delta: $delta")
                    }
                }
                
                // Response audio transcript done
                "response.audio_transcript.done" -> {
                    val transcript = messageMap["transcript"] as? String
                    if (!transcript.isNullOrEmpty()) {
                        currentOutputTranscription.clear()
                        currentOutputTranscription.append(transcript)
                        hasTranscriptionForCurrentTurn = true
                        callbacks.onTranscriptionUpdate(
                            currentInputTranscription.toString(),
                            currentOutputTranscription.toString(),
                            true
                        )
                        Log.d(TAG, "🤖 Model transcript final: $transcript")
                    }
                }
                
                // Response text delta (for text-only responses)
                "response.text.delta" -> {
                    val delta = messageMap["delta"] as? String
                    if (!delta.isNullOrEmpty()) {
                        currentOutputTranscription.append(delta)
                        hasTranscriptionForCurrentTurn = true
                        callbacks.onTranscriptionUpdate(
                            currentInputTranscription.toString(),
                            currentOutputTranscription.toString(),
                            false
                        )
                    }
                }
                
                // Response function call arguments delta
                "response.function_call_arguments.delta" -> {
                    // Accumulate function call arguments
                    val delta = messageMap["delta"] as? String
                    Log.d(TAG, "🔧 Function call args delta: $delta")
                }
                
                // Response function call arguments done - execute the function
                "response.function_call_arguments.done" -> {
                    handleOpenAIFunctionCall(messageMap)
                }
                
                // Response output item done
                "response.output_item.done" -> {
                    val item = messageMap["item"] as? Map<*, *>
                    val itemType = item?.get("type") as? String
                    if (itemType == "function_call") {
                        handleOpenAIFunctionCallFromItem(item)
                    }
                }
                
                // Response done - turn complete
                "response.done" -> {
                    stopThinkingSound()
                    
                    if (receivedAudioInCurrentTurn && !hasTranscriptionForCurrentTurn) {
                        currentOutputTranscription.append("[AI speaking...]")
                        Log.d(TAG, "📝 Generated placeholder transcription (no text from API)")
                    }
                    
                    val fullInput = currentInputTranscription.toString()
                    val fullOutput = currentOutputTranscription.toString()
                    
                    callbacks.onTranscriptionUpdate(fullInput, fullOutput, true)
                    
                    if (fullInput.isNotEmpty()) {
                        visionTranscriptionListener?.onUserTranscription(fullInput, true)
                    }
                    
                    Log.d(TAG, "✅ Response done - Input: '$fullInput', Output: '$fullOutput'")
                    callbacks.onTurnComplete(fullInput, fullOutput)
                    
                    // Reset transcriptions
                    currentInputTranscription.clear()
                    currentOutputTranscription.clear()
                    receivedAudioInCurrentTurn = false
                    hasTranscriptionForCurrentTurn = false
                }
                
                // Rate limit info
                "rate_limits.updated" -> {
                    Log.d(TAG, "📊 Rate limits updated")
                }
                
                // Error from server
                "error" -> {
                    val error = messageMap["error"] as? Map<*, *>
                    val errorMsg = error?.get("message") as? String ?: "Unknown error"
                    val errorCode = error?.get("code") as? String ?: ""
                    Log.e(TAG, "❌ Server error [$errorCode]: $errorMsg")
                    callbacks.onError(errorMsg)
                }
                
                else -> {
                    Log.d(TAG, "📩 Unhandled event: $eventType")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message: ${e.message}", e)
        }
    }
    
    /**
     * Handle Gemini Live WebSocket messages
     */
    private fun handleGeminiMessage(messageMap: Map<*, *>) {
        try {
            // Check for setupComplete
            val setupComplete = messageMap["setupComplete"] as? Map<*, *>
            if (setupComplete != null) {
                Log.d(TAG, "✅ Gemini session setup complete")
                isSetupComplete.set(true)
                // Session reached a healthy state, so the reconnect budget is no
                // longer needed for this connection.
                autoReconnects = 0
                callbacks.onConnectionStatusChanged(true)
                flushPendingSpeakText()
                maybeSendGreeting()
                return
            }
            
            // Check for serverContent (audio/text responses)
            val serverContent = messageMap["serverContent"] as? Map<*, *>
            if (serverContent != null) {
                val turnComplete = serverContent["turnComplete"] as? Boolean ?: false
                val interrupted = serverContent["interrupted"] as? Boolean ?: false

                // User-speech transcript (requested via input_audio_transcription).
                val inputTranscription = serverContent["inputTranscription"] as? Map<*, *>
                val inputText = inputTranscription?.get("text") as? String
                if (!inputText.isNullOrEmpty()) {
                    currentInputTranscription.append(inputText)
                    // NOTE: the processing chime is deliberately NOT triggered here.
                    // inputTranscription arrives only after Gemini has received and
                    // transcribed the turn, which is well into the silent gap the cue
                    // is meant to cover. The local VAD in the capture loop
                    // (detectSpeechEdge) fires it the moment the user stops talking,
                    // which is as early as the signal exists.
                    callbacks.onTranscriptionUpdate(
                        currentInputTranscription.toString(),
                        currentOutputTranscription.toString(),
                        false
                    )
                }

                // Model-reply transcript (requested via output_audio_transcription).
                val outputTranscription = serverContent["outputTranscription"] as? Map<*, *>
                val outputText = outputTranscription?.get("text") as? String
                if (!outputText.isNullOrEmpty()) {
                    currentOutputTranscription.append(outputText)
                    hasTranscriptionForCurrentTurn = true
                    callbacks.onTranscriptionUpdate(
                        currentInputTranscription.toString(),
                        currentOutputTranscription.toString(),
                        false
                    )
                }
                
                val modelTurn = serverContent["modelTurn"] as? Map<*, *>
                if (modelTurn != null) {
                    val parts = modelTurn["parts"] as? List<*>
                    parts?.forEach { part ->
                        val partMap = part as? Map<*, *> ?: return@forEach
                        
                        // Audio data from Gemini
                        val inlineData = partMap["inlineData"] as? Map<*, *>
                        if (inlineData != null) {
                            val audioBase64 = inlineData["data"] as? String
                            if (audioBase64 != null) {
                                if (!receivedAudioInCurrentTurn) {
                                    Log.d(TAG, "🤖 GEMINI RESPONSE AUDIO RECEIVED")
                                    stopThinkingSound()
                                }
                                val audioData = Base64.decode(audioBase64, Base64.DEFAULT)
                                synchronized(audioQueueLock) {
                                    audioQueue.add(audioData)
                                }
                                receivedAudioInCurrentTurn = true
                            }
                        }
                        
                        // Text response
                        val textContent = partMap["text"] as? String
                        if (!textContent.isNullOrEmpty()) {
                            currentOutputTranscription.append(textContent)
                            hasTranscriptionForCurrentTurn = true
                            callbacks.onTranscriptionUpdate(
                                currentInputTranscription.toString(),
                                currentOutputTranscription.toString(),
                                false
                            )
                        }
                    }
                }
                
                if (turnComplete || interrupted) {
                    stopThinkingSound()
                    val fullInput = currentInputTranscription.toString()
                    val fullOutput = currentOutputTranscription.toString()

                    // Gemini can close a turn having produced no audio and no text -
                    // most often right after a tool response, where it treats the tool
                    // result as the whole answer. Nothing plays and the user waits for
                    // a reply that never comes, until the 10s session fallback fires.
                    // Detect it here and ask once for the answer to actually be spoken.
                    val producedNothing = !receivedAudioInCurrentTurn &&
                        !hasTranscriptionForCurrentTurn &&
                        fullOutput.isBlank()
                    if (producedNothing && !interrupted && recoverFromSilentTurn(fullInput)) {
                        // Retry sent: keep the turn's transcripts so the recovered
                        // reply is reported against the question the user actually
                        // asked, and do not signal turn-complete yet.
                        return
                    }

                    callbacks.onTranscriptionUpdate(fullInput, fullOutput, true)
                    if (fullInput.isNotEmpty()) {
                        visionTranscriptionListener?.onUserTranscription(fullInput, true)
                    }
                    callbacks.onTurnComplete(fullInput, fullOutput)
                    currentInputTranscription.clear()
                    currentOutputTranscription.clear()
                    receivedAudioInCurrentTurn = false
                    hasTranscriptionForCurrentTurn = false
                    toolCallInCurrentTurn = false
                    lastToolResultSummary = null
                    silentTurnRetried = false
                }
                return
            }
            
            // Check for toolCall
            val toolCall = messageMap["toolCall"] as? Map<*, *>
            if (toolCall != null) {
                val functionCalls = toolCall["functionCalls"] as? List<*>
                functionCalls?.forEach { fc ->
                    val fcMap = fc as? Map<*, *> ?: return@forEach
                    val name = fcMap["name"] as? String ?: return@forEach
                    val id = fcMap["id"] as? String ?: ""
                    val args = fcMap["args"] as? Map<String, Any> ?: emptyMap()
                    
                    Log.d(TAG, "🔧 Gemini function call: $name, args: $args")
                    toolCallInCurrentTurn = true
                    scope.launch {
                        try {
                            val result = callbacks.onToolCall(name, args)
                            Log.d(TAG, "✅ Gemini function $name result: $result")
                            // Kept so a turn that ends without narration can still be
                            // salvaged from the data the tool already fetched.
                            lastToolResultSummary = result
                            sendGeminiFunctionResponse(id, name, result)
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Error executing Gemini function $name: ${e.message}")
                            sendGeminiFunctionResponse(id, name, "Error: ${e.message}")
                        }
                    }
                }
                return
            }
            
            // Check for toolCallCancellation
            val toolCallCancellation = messageMap["toolCallCancellation"] as? Map<*, *>
            if (toolCallCancellation != null) {
                Log.d(TAG, "⚠️ Gemini tool call cancelled")
                return
            }
            
            Log.d(TAG, "📩 Unhandled Gemini message keys: ${messageMap.keys}")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling Gemini message: ${e.message}", e)
        }
    }
    
    /**
     * Salvages a turn that ended with no spoken reply.
     *
     * Gemini occasionally completes a turn after a tool response without narrating
     * the result. The data is present and correct - it simply never gets spoken, so
     * the user hears silence and the session eventually force-stops. Rather than
     * leaving that dead air, ask the model once to say the answer.
     *
     * Only ever retries once per turn: if the model stays silent after being asked
     * directly, retrying again would just repeat the silence. When a tool did run,
     * its result is included so the answer can be given even if the model has lost
     * the thread of it.
     *
     * @return true if a retry was sent and the turn should stay open.
     */
    private fun recoverFromSilentTurn(userInput: String): Boolean {
        if (silentTurnRetried) {
            Log.w(TAG, "⚠️ Turn produced no reply again after retry - giving up on this turn")
            return false
        }
        // A turn with no user speech is usually a stray/empty model turn rather
        // than a failed answer; nudging there would speak into a silent room.
        if (userInput.isBlank() && !toolCallInCurrentTurn) {
            return false
        }
        if (webSocket == null || !isSetupComplete.get()) {
            Log.w(TAG, "⚠️ Silent turn but session is not ready - cannot retry")
            return false
        }

        silentTurnRetried = true
        val toolResult = lastToolResultSummary

        val prompt = if (toolCallInCurrentTurn && !toolResult.isNullOrBlank()) {
            Log.w(TAG, "⚠️ Silent turn after tool call - asking Gemini to speak the result")
            "You called a tool and received this result but did not reply to the user. " +
                "Answer their question now in 1-2 short sentences using this information, " +
                "speaking naturally and without mentioning tools or this instruction. " +
                "Question: \"$userInput\". Information: $toolResult"
        } else {
            Log.w(TAG, "⚠️ Silent turn with no reply - asking Gemini to answer")
            "You did not reply to the user. Answer their question now in 1-2 short " +
                "sentences, speaking naturally and without mentioning this instruction. " +
                "If you cannot answer, say so briefly. Question: \"$userInput\""
        }

        // speakDirectly=false: this is an instruction to answer, not a script to
        // read aloud verbatim.
        speakText(prompt, speakDirectly = false)
        return true
    }

    /**
     * Send function response back to Gemini Live
     */
    private fun sendGeminiFunctionResponse(id: String, name: String, result: String) {
        val response = mapOf(
            "tool_response" to mapOf(
                "function_responses" to listOf(
                    mapOf(
                        "id" to id,
                        "name" to name,
                        "response" to mapOf("result" to result)
                    )
                )
            )
        )
        val json = gson.toJson(response)
        Log.d(TAG, "📤 Sending Gemini function response: $json")
        webSocket?.send(json)
    }
    
    /**
     * Handle function call from OpenAI response.function_call_arguments.done
     */
    private fun handleOpenAIFunctionCall(messageMap: Map<*, *>) {
        val name = messageMap["name"] as? String
        val callId = messageMap["call_id"] as? String
        val argsJson = messageMap["arguments"] as? String ?: "{}"
        
        if (name == null || callId == null) {
            Log.w(TAG, "⚠️ Function call missing name or call_id")
            return
        }
        
        Log.d(TAG, "🔧 Function call: $name, args: $argsJson")
        
        try {
            val argsMap = gson.fromJson(argsJson, Map::class.java) as? Map<String, Any> ?: emptyMap()
            
            scope.launch {
                try {
                    val result = callbacks.onToolCall(name, argsMap)
                    Log.d(TAG, "✅ Function $name result: $result")
                    
                    // Send function output back to OpenAI
                    sendOpenAIFunctionResponse(callId, result)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error executing function $name: ${e.message}", e)
                    sendOpenAIFunctionResponse(callId, "Error: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to parse function args: ${e.message}")
        }
    }
    
    /**
     * Handle function call from response.output_item.done event
     */
    private fun handleOpenAIFunctionCallFromItem(item: Map<*, *>) {
        val name = item["name"] as? String
        val callId = item["call_id"] as? String
        val argsJson = item["arguments"] as? String ?: "{}"
        
        if (name == null || callId == null) {
            Log.w(TAG, "⚠️ Function call item missing name or call_id")
            return
        }
        
        Log.d(TAG, "🔧 Function call from item: $name, args: $argsJson")
        
        try {
            val argsMap = gson.fromJson(argsJson, Map::class.java) as? Map<String, Any> ?: emptyMap()
            
            scope.launch {
                try {
                    val result = callbacks.onToolCall(name, argsMap)
                    Log.d(TAG, "✅ Function $name result: $result")
                    sendOpenAIFunctionResponse(callId, result)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error executing function $name: ${e.message}", e)
                    sendOpenAIFunctionResponse(callId, "Error: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to parse function args from item: ${e.message}")
        }
    }
    
    /**
     * Send function output back to OpenAI Realtime and trigger a new response
     */
    private fun sendOpenAIFunctionResponse(callId: String, result: String) {
        // conversation.item.create with function_call_output
        val functionOutput = mapOf(
            "type" to "conversation.item.create",
            "item" to mapOf(
                "type" to "function_call_output",
                "call_id" to callId,
                "output" to result
            )
        )
        
        val json = gson.toJson(functionOutput)
        Log.d(TAG, "📤 Sending function output: $json")
        webSocket?.send(json)
        
        // Trigger response generation after function output
        val responseCreate = mapOf("type" to "response.create")
        webSocket?.send(gson.toJson(responseCreate))
        Log.d(TAG, "📤 Triggered new response after function output")
    }

    private fun cleanup() {
        try {
            isRecording.set(false)
            isPlaying.set(false)
            isAIPlaying.set(false)
            isMuted.set(false) // always clear mute on cleanup so next session starts unmuted

            // Drop the song-ID window so we never fingerprint audio from a
            // previous session, and don't hold ~380KB after teardown.
            songIdBuffer = null

            // 🆕 Clear singleton instance
            instance = null

            // Release the thinking cue's SoundPool so its stream does not linger and
            // hold the A2DP route open after the session ends.
            try {
                stopThinkingSound()
                thinkingSoundPool?.release()
                thinkingSoundPool = null
                thinkingSoundId = 0
                isThinkingSoundLoaded.set(false)
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing thinking SoundPool: ${e.message}")
            }

            // Enhanced SCO cleanup using helper
            try {
                scoHelper?.disconnectSco()
                scoHelper?.cleanup()
                scoHelper = null
            } catch (e: Exception) {
                Log.w(TAG, "Error cleaning up SCO: ${e.message}")
            }

            // Hand the audio system back to normal so music/video and the glasses'
            // A2DP route behave correctly after the conversation ends. Without this
            // the device stays in communication/telephony mode.
            try {
                audioManager?.let { am ->
                    // Hand the routing override back, or the phone stays pinned to
                    // Bluetooth communication routing after the session ends.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        try { am.clearCommunicationDevice() } catch (e: Exception) {
                            Log.w(TAG, "clearCommunicationDevice failed: ${e.message}")
                        }
                    }
                    if (am.isBluetoothScoOn) {
                        am.isBluetoothScoOn = false
                        am.stopBluetoothSco()
                    }
                    am.mode = AudioManager.MODE_NORMAL
                }
                scoHeldForListening.set(false) // next session re-acquires from scratch
                Log.d(TAG, "🔄 Audio mode restored to NORMAL")
            } catch (e: Exception) {
                Log.w(TAG, "Error restoring audio mode: ${e.message}")
            }

            // Release audio effects
            try {
                acousticEchoCanceler?.release()
                acousticEchoCanceler = null
                noiseSuppressor?.release()
                noiseSuppressor = null
                automaticGainControl?.release()
                automaticGainControl = null
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing audio effects: ${e.message}")
            }

            // Stop and release audio components
            try {
                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing audioRecord: ${e.message}")
            }

            try {
                audioTrack?.stop()
                audioTrack?.flush()
                audioTrack?.release()
                audioTrack = null
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing audioTrack: ${e.message}")
            }

            // Close WebSocket
            try {
                webSocket?.close(1000, "Session ended")
                webSocket = null
            } catch (e: Exception) {
                Log.w(TAG, "Error closing websocket: ${e.message}")
            }

            // Clear audio queue
            synchronized(audioQueueLock) {
                audioQueue.clear()
            }

            // Reset transcriptions
            currentInputTranscription.clear()
            currentOutputTranscription.clear()

            Log.d(TAG, "🧹 Enhanced cleanup complete - Glass headset disconnected")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error during cleanup: ${e.message}")
        }
    }

    /**
     * Helper function to convert ShortArray to ByteArray (PCM16)
     */
    private fun shortArrayToByteArray(shortArray: ShortArray, size: Int): ByteArray {
        val byteBuffer = ByteBuffer.allocate(size * 2)
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until size) {
            byteBuffer.putShort(shortArray[i])
        }
        return byteBuffer.array()
    }

    /**
     * Helper function to convert ByteArray to ShortArray
     */
    private fun byteArrayToShortArray(byteArray: ByteArray): ShortArray {
        val shortBuffer = ShortArray(byteArray.size / 2)
        ByteBuffer.wrap(byteArray).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shortBuffer)
        return shortBuffer
    }
    
    /**
     * Calculate RMS (Root Mean Square) energy of audio samples
     * Used for Voice Activity Detection to filter out quiet/noise frames
     */
    private fun calculateRMS(buffer: ShortArray, size: Int): Double {
        var sum = 0.0
        for (i in 0 until size) {
            val sample = buffer[i].toDouble()
            sum += sample * sample
        }
        return sqrt(sum / size)
    }

    /**
     * Release all resources when service is destroyed
     */
    fun destroy() {
        stopThinkingSound() // Clean up thinking sound
        scope.cancel()
        cleanup()
    }

    /**
     * Interrupt the current AI response immediately: clear audio queue, flush track and resume mic.
     */
    fun interruptCurrentResponse() {
        Log.d(TAG, "🛑 interruptCurrentResponse called - clearing audio queue and stopping AI playback")
        synchronized(audioQueueLock) {
            audioQueue.clear()
        }
        try {
            audioTrack?.flush()
            totalFramesWritten = 0L // flush() zeroes the playback head too
        } catch (e: Exception) {
            Log.w(TAG, "Error flushing audioTrack during interrupt: ${e.message}")
        }
        isAIPlaying.set(false)
        callbacks.onAudioPlaybackEnd()
    }
}
