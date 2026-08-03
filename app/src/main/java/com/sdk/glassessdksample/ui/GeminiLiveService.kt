package com.sdk.glassessdksample.ui

import com.sdk.glassessdksample.RemoteConfigManager
import android.media.AudioFormat
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.MediaRecorder
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
        private const val GEMINI_MODEL_FALLBACK = "gemini-live-2.5-flash-preview"
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
         */
        private const val HIGH_QUALITY_PLAYBACK = true
        
        // Echo cancellation and noise suppression
        private const val ENERGY_THRESHOLD = 200.0

        // How many times to silently re-establish a dropped/failed connection
        // before surfacing the error to the user (covers transient Gemini hiccups
        // that previously required the user to manually "quick start" again).
        private const val MAX_AUTO_RECONNECTS = 2
    }
    
    // Active model provider (read from prefs at start)
    private var activeProvider: ModelProvider = getSavedModelProvider(context)

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
    
    // Echo cancellation and noise suppression
    private val isAIPlaying = AtomicBoolean(false) // Half-duplex flag: true when AI is speaking
    private var acousticEchoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var automaticGainControl: AutomaticGainControl? = null
    
    // Transcription state
    private var currentInputTranscription = StringBuilder()
    private var currentOutputTranscription = StringBuilder()
    private var receivedAudioInCurrentTurn = false
    private var hasTranscriptionForCurrentTurn = false
    
    // Audio playback queue
    private val audioQueue = mutableListOf<ByteArray>()
    private val audioQueueLock = Any()
    private var isPreBuffering = true // Wait for buffer to fill before playing
    
    // 🆕 Mute functionality for vision chat integration
    private val isMuted = AtomicBoolean(false) // When true, blocks audio output (but keeps listening)
    
    // 🎵 Thinking sound - plays during delay between user question and AI reply
    private var thinkingPlayer: MediaPlayer? = null
    private val isThinkingSoundPlaying = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper()) // Must use main thread for MediaPlayer
    
    /**
     * 🎵 Start the thinking/processing sound (loops until AI starts speaking)
     * Runs on main thread because MediaPlayer requires it
     */
    private fun startThinkingSound() {
        if (isThinkingSoundPlaying.get()) return // Already playing
        mainHandler.post {
            try {
                // Release previous player if any
                thinkingPlayer?.let { p ->
                    try { if (p.isPlaying) p.stop() } catch (_: Exception) {}
                    try { p.release() } catch (_: Exception) {}
                }
                thinkingPlayer = null
                
                thinkingPlayer = MediaPlayer.create(context, R.raw.swar_chakra_thinking)?.apply {
                    isLooping = true
                    setVolume(0.35f, 0.35f) // 35% volume - subtle but audible
                    start()
                }
                if (thinkingPlayer != null) {
                    isThinkingSoundPlaying.set(true)
                    Log.d(TAG, "🎵 Thinking sound STARTED on main thread")
                } else {
                    Log.e(TAG, "🎵 MediaPlayer.create returned null! Check res/raw/swar_chakra_thinking.mp3")
                }
            } catch (e: Exception) {
                Log.e(TAG, "🎵 Error starting thinking sound: ${e.message}", e)
            }
        }
    }
    
    /**
     * 🎵 Stop the thinking/processing sound (called when AI reply audio arrives)
     * Runs on main thread for safety
     */
    private fun stopThinkingSound() {
        if (!isThinkingSoundPlaying.get() && thinkingPlayer == null) return
        isThinkingSoundPlaying.set(false) // Set immediately to prevent race conditions
        mainHandler.post {
            try {
                thinkingPlayer?.let { player ->
                    try { if (player.isPlaying) player.stop() } catch (_: Exception) {}
                    try { player.release() } catch (_: Exception) {}
                }
                thinkingPlayer = null
                Log.d(TAG, "🎵 Thinking sound STOPPED on main thread")
            } catch (e: Exception) {
                Log.e(TAG, "🎵 Error stopping thinking sound: ${e.message}")
                thinkingPlayer = null
            }
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
    
    // ========== OPTION A: Use System Bluetooth for Audio ==========
    // BLE handles data (photos, commands) - System Bluetooth handles audio
    
    // 1. Audio mode.
    //    MODE_IN_CALL puts the whole device into telephony mode, which forces every
    //    stream through the narrowband SCO path — that is a big part of why the
    //    glasses sounded muffled. MODE_IN_COMMUNICATION still gives us SCO mic
    //    capture but leaves media playback free to use high-quality A2DP.
    audioManager?.mode = if (HIGH_QUALITY_PLAYBACK) {
        AudioManager.MODE_IN_COMMUNICATION
    } else {
        AudioManager.MODE_IN_CALL
    }
    Log.d(TAG, "📞 Audio mode: ${if (HIGH_QUALITY_PLAYBACK) "IN_COMMUNICATION (A2DP playback allowed)" else "IN_CALL"}")
    
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
            audioManager?.startBluetoothSco()
            audioManager?.isBluetoothScoOn = true
            Log.d(TAG, "📡 Bluetooth SCO (re-)started for this session")
            delay(80)
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
                
                // Find and prefer Bluetooth SCO device
                val bluetoothDevice = devices.firstOrNull { 
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO 
                }
                
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

        // 8. Create AudioTrack for speaker output (auto-routes to Bluetooth)
        // Using USAGE_MEDIA for crystal clear audio quality like Gemini Chat
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)  // Better quality than VOICE_COMMUNICATION
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

                val a2dpDevice = outputDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                }
                val scoDevice = outputDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                }

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
                callbacks.onConnectionStatusChanged(true)

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
                // couple of times before surfacing any error. Auth/config problems
                // are not transient, so don't retry those.
                val isAuthError = response?.code == 401 || response?.code == 403 ||
                    t.message?.contains("401", ignoreCase = true) == true
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

                val errorMessage = when {
                    t.message?.contains("network", ignoreCase = true) == true -> "Network disconnected"
                    t.message?.contains("internet", ignoreCase = true) == true -> "No internet connection"
                    t.message?.contains("connection", ignoreCase = true) == true -> "Connection lost"
                    t.message?.contains("timeout", ignoreCase = true) == true -> "Connection timeout"
                    t.message?.contains("401", ignoreCase = true) == true -> "Invalid OpenAI API Key"
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
        val tools = listOf(
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
            )
        )

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

EMAIL - READING: When the user asks about new emails, their inbox, or unread mail, call read_emails and tell them the result briefly.

EMAIL - SENDING (always confirm first): When the user asks you to email or write to someone, call draft_email with your best guess at recipient, subject, and body from what they said. Then READ THE DRAFT BACK to the user out loud in your own next spoken turn (recipient, subject, and a short summary of the body) and ask "should I send it?". Do NOT call confirm_send_email in the same turn as draft_email. Only call confirm_send_email in a LATER turn, after the user has explicitly agreed (e.g. "yes", "send it", "go ahead"). If the user wants changes, call draft_email again with the corrected details and read it back again. If the user declines, do not send anything.
"""

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
                        // Half-duplex: Skip sending audio when AI is speaking (prevents echo)
                        if (isAIPlaying.get()) {
                            halfDuplexSkips++
                            continue
                        }
                        
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
                isPlaying.set(true)
                isPreBuffering = true // Start in pre-buffering mode
                var lastAudioTime = 0L // Track when we last played audio
                Log.d(TAG, "🔊 Audio playback started (pre-buffering enabled, PRE_BUFFER_COUNT=$PRE_BUFFER_COUNT)")

                while (isPlaying.get()) {
                    // Pre-buffering: Wait until we have enough chunks for smooth playback
                    val queueSize = synchronized(audioQueueLock) { audioQueue.size }
                    
                    if (isPreBuffering && queueSize < PRE_BUFFER_COUNT) {
                        delay(1) // Ultra-fast polling for instant start
                        continue
                    }
                    
                    if (isPreBuffering && queueSize >= PRE_BUFFER_COUNT) {
                        isPreBuffering = false
                        isAIPlaying.set(true) // AI is speaking, pause mic capture EARLY
                        callbacks.onAudioPlaybackStart()
                        lastAudioTime = System.currentTimeMillis()
                        Log.d(TAG, "✅ Pre-buffer filled ($queueSize chunks), starting smooth playback")
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
                            // No new audio for a while, AI likely finished speaking
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
            audioTrack?.write(shortBuffer, 0, shortBuffer.size, AudioTrack.WRITE_BLOCKING)
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
                    isSetupComplete.set(true)
                    autoReconnects = 0
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
                    callbacks.onTranscriptionUpdate(fullInput, fullOutput, true)
                    if (fullInput.isNotEmpty()) {
                        visionTranscriptionListener?.onUserTranscription(fullInput, true)
                    }
                    callbacks.onTurnComplete(fullInput, fullOutput)
                    currentInputTranscription.clear()
                    currentOutputTranscription.clear()
                    receivedAudioInCurrentTurn = false
                    hasTranscriptionForCurrentTurn = false
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
                    scope.launch {
                        try {
                            val result = callbacks.onToolCall(name, args)
                            Log.d(TAG, "✅ Gemini function $name result: $result")
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

            // 🆕 Clear singleton instance
            instance = null
            
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
                    if (am.isBluetoothScoOn) {
                        am.isBluetoothScoOn = false
                        am.stopBluetoothSco()
                    }
                    am.mode = AudioManager.MODE_NORMAL
                }
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
        } catch (e: Exception) {
            Log.w(TAG, "Error flushing audioTrack during interrupt: ${e.message}")
        }
        isAIPlaying.set(false)
        callbacks.onAudioPlaybackEnd()
    }
}
