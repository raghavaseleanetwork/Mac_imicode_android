package com.sdk.glassessdksample.ui

import android.content.Context
import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import com.sdk.glassessdksample.ListeningService
import com.sdk.glassessdksample.MainActivity
import com.sdk.glassessdksample.wakeword.HeyImiWakeWordDetector
import com.sdk.glassessdksample.wakeword.SnowboyWakeWordDetector
import com.sdk.glassessdksample.wakeword.WakeWordEngine
import com.sdk.glassessdksample.wakeword.WakeWordEngineSettings
import org.greenrobot.eventbus.EventBus

/**
 * HotHelper - Wake Word Detection Manager
 * 
 * ========================================================================
 * 🎤 SUPPORTS MULTIPLE WAKE-WORD ENGINES
 * ========================================================================
 * 
 * Engines:
 * - Custom ONNX model (default)
 * - Snowboy (optional, requires Snowboy assets + SDK runtime)
 * 
 * Engine selection is controlled from SettingsActivity and stored in imi_prefs.
 * Users can change engine before connecting the device.
 * 
 * Optional Chime Sound (for wake acknowledgment):
 * - res/raw/chime.mp3 OR assets/sounds/chime.mp3
 * ========================================================================
 */
class HotHelper private constructor(private val context: Context) {
    companion object {
        private const val TAG = "HotHelper"
        
        @Volatile
        private var instance: HotHelper? = null

        fun getInstance(context: Context): HotHelper {
            return instance ?: synchronized(this) {
                instance ?: HotHelper(context.applicationContext).also { instance = it }
            }
        }
        
    }

    // Detectors
    private var heyImiDetector: HeyImiWakeWordDetector? = null
    private var snowboyDetector: SnowboyWakeWordDetector? = null
    private var activeEngine: WakeWordEngine = WakeWordEngineSettings.getSelectedEngine(context)

    // Null means "use detector/model default" (for ONNX this includes metadata threshold).
    private var configuredThreshold: Float? = null

    private var isStarted = false
    private var isStartPending = false
    private var startPendingSinceMs = 0L
    private var startRequestId = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private var audioManager: AudioManager? = null
    private var scoReceiver: BroadcastReceiver? = null
    private val SCO_WAIT_TIMEOUT_MS = 1500L
    private var scoFallbackRunnable: Runnable? = null
    private val STALE_PENDING_TIMEOUT_MS = 6_000L
    
    // Audio buffer for Glass BLE audio processing
    private val audioBuffer = mutableListOf<Short>()
    private val bufferLock = Any()
    private var lastExternalFeedLogTs = 0L
    
    // Mode: true = Glass BLE audio, false = Phone mic
    private var useGlassBLEAudio = false
    
    // Mute state - when true, wake word detection is disabled
    private var isMuted = false

    init {
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    /**
     * Set mute state - when muted, wake word detection is disabled
     */
    fun setMuted(muted: Boolean) {
        isMuted = muted
        if (muted && (isStarted || isStartPending)) {
            Log.d(TAG, "🔇 Mute enabled - stopping wake word detection")
            stop()
        }
        Log.d(TAG, "🔇 Wake word detection mute: ${if (muted) "ENABLED" else "DISABLED"}")
    }

    /**
     * Allow external callers to prefer glass BLE audio for wake detection.
     * When enabled, processGlassAudio() will be used instead of phone mic.
     */
    fun setPreferGlassBleAudio(prefer: Boolean) {
        useGlassBLEAudio = prefer
    }

    /**
     * Set detection threshold (0.0 to 1.0)
     * Lower = more sensitive but more false positives
     * Higher = less sensitive but fewer false positives
     */
    fun setThreshold(value: Float) {
        configuredThreshold = value
        heyImiDetector?.setThreshold(value)
        snowboyDetector?.setThreshold(value)
        Log.d(TAG, "Detection threshold set to: $value (engine=${activeEngine.displayName})")
    }

    /**
     * Save preferred wake-word engine from Settings.
     * If detector is currently running, restart with the new engine.
     */
    fun setWakeWordEngine(engine: WakeWordEngine) {
        WakeWordEngineSettings.setSelectedEngine(context, engine)

        if (engine == activeEngine) return

        val shouldRestart = isStarted || isStartPending
        stop()
        releaseDetectorForEngine(activeEngine)
        activeEngine = engine

        Log.i(TAG, "Wake-word engine changed to ${engine.displayName}")
        if (shouldRestart) {
            start()
        }
    }

    fun getWakeWordEngine(): WakeWordEngine {
        return WakeWordEngineSettings.getSelectedEngine(context)
    }

    fun isWakeDetectorActive(): Boolean {
        return isStarted || isStartPending || activeDetectorIsListening()
    }

    fun start() {
        if (isMuted) {
            Log.d(TAG, "🔇 Wake word detection is muted - not starting")
            return
        }

        syncEngineFromSettings()

        if (isStartPending) {
            val pendingAge = System.currentTimeMillis() - startPendingSinceMs
            if (pendingAge > STALE_PENDING_TIMEOUT_MS) {
                Log.w(TAG, "⚠️ Stale wake start pending ($pendingAge ms). Resetting pending state.")
                isStartPending = false
                startPendingSinceMs = 0L
                unregisterScoReceiverForStart()
            }
        }
        
        if (isStarted || isStartPending) {
            val detectorListening = activeDetectorIsListening()
            if (isStarted) {
                Log.d(TAG, "Wake detector already active. engine=${activeEngine.displayName} detectorListening=$detectorListening")
            } else {
                val pendingAge = System.currentTimeMillis() - startPendingSinceMs
                Log.d(TAG, "Wake detector start pending ($pendingAge ms). engine=${activeEngine.displayName} detectorListening=$detectorListening")
            }
            return
        }
        
        try {
            if (!ensureDetectorInitializedForActiveEngine()) {
                Log.e(TAG, "Wake detector initialization failed for engine=${activeEngine.displayName}")
                return
            }
            
            if (useGlassBLEAudio) {
                Log.i(TAG, "Glass BLE audio preferred: external PCM can be fed via processGlassAudio()")
            }

            isStartPending = true
            startPendingSinceMs = System.currentTimeMillis()
            val requestId = ++startRequestId

            // Prefer SCO (glass mic) when available; fallback to default mic.
            attemptScoThenStartDetector(requestId)
        } catch (e: Exception) {
            isStartPending = false
            startPendingSinceMs = 0L
            Log.e(TAG, "Error starting wake word detection: ${e.message}", e)
        }
    }

    /**
     * Keep runtime engine aligned with settings without requiring app restart.
     */
    private fun syncEngineFromSettings() {
        val selected = WakeWordEngineSettings.getSelectedEngine(context)
        if (selected == activeEngine) return

        val wasRunning = isStarted || isStartPending
        stop()
        releaseDetectorForEngine(activeEngine)
        activeEngine = selected

        Log.i(TAG, "Wake-word engine synced from settings: ${activeEngine.displayName}")
        if (wasRunning) {
            Log.d(TAG, "Wake detector will restart with updated engine")
        }
    }

    private fun ensureDetectorInitializedForActiveEngine(): Boolean {
        return when (activeEngine) {
            WakeWordEngine.CUSTOM_ONNX -> initHeyImiDetector()
            WakeWordEngine.SNOWBOY -> {
                if (initSnowboyDetector()) {
                    true
                } else {
                    Log.w(TAG, "Snowboy unavailable at runtime, falling back to Custom ONNX for this session")
                    activeEngine = WakeWordEngine.CUSTOM_ONNX
                    initHeyImiDetector()
                }
            }
        }
    }

    /**
     * Initialize the ONNX-based Hey IMI wake word detector.
     */
    private fun initHeyImiDetector(): Boolean {
        if (heyImiDetector != null) return true

        try {
            Log.i(TAG, "🔄 Initializing ONNX 'Hey IMI' Detector...")
            
            heyImiDetector = HeyImiWakeWordDetector(context) { confidence ->
                onWakeWordDetected("Custom ONNX", confidence)
            }
            
            heyImiDetector?.initialize()
            configuredThreshold?.let { heyImiDetector?.setThreshold(it) }
            
            Log.i(TAG, "✅ ONNX Detector initialized successfully")
            Log.i(TAG, "   📦 Model: custom_wakeword/imi_cnn_mobile.onnx")
            Log.i(TAG, "   🎯 Threshold: ${heyImiDetector?.getThreshold()}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize ONNX detector: ${e.message}", e)
            Log.e(TAG, "   Check assets/custom_wakeword/imi_cnn_mobile.onnx exists and onnxruntime dependency is present")
            heyImiDetector = null
            return false
        }
    }

    /**
     * Initialize Snowboy detector if SDK + model assets are available.
     */
    private fun initSnowboyDetector(): Boolean {
        if (snowboyDetector != null) return true

        try {
            Log.i(TAG, "🔄 Initializing Snowboy Detector...")

            snowboyDetector = SnowboyWakeWordDetector(context) { confidence ->
                onWakeWordDetected("Snowboy", confidence)
            }

            snowboyDetector?.initialize()
            configuredThreshold?.let { snowboyDetector?.setThreshold(it) }

            Log.i(TAG, "✅ Snowboy detector initialized successfully")
            Log.i(TAG, "   🎯 Threshold: ${snowboyDetector?.getThreshold()}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize Snowboy detector: ${e.message}", e)
            snowboyDetector = null
            return false
        }
    }

    private fun onWakeWordDetected(source: String, confidence: Float) {
        Log.i(TAG, "🔥 WAKE WORD DETECTED by $source! Confidence: ${"%.2f".format(confidence)}")

        // Mark as stopped (detector auto-stops on detection)
        isStarted = false

        synchronized(bufferLock) {
            audioBuffer.clear()
        }

        Log.d(TAG, "📢 Posting 'wake up' event to EventBus")
        EventBus.getDefault().post(
            BluetoothEvent(BluetoothEvent.EventType.VOICE_TEXT, "wake up")
        )

        // NOTE: We deliberately do NOT startActivity() here any more. When the app is
        // in the background the conversation is run by ListeningService (which is
        // subscribed to this same event), so the phone is never forced open / unlocked.
        // The user talks to IMI through the glasses with the phone left as-is.
    }

    /**
     * Attempt to start SCO (if HFP/headset profile available) and wait briefly
     * for SCO to become active before starting detection.
     */
    private fun attemptScoThenStartDetector(requestId: Int) {
        if (!useGlassBLEAudio) {
            // Reliability mode: use direct phone mic capture for wake detection.
            Log.d(TAG, "Using phone mic mode for wake detection (SCO bypassed)")
            unregisterScoReceiverForStart()
            startDetectorInternal(requestId)
            return
        }

        val am = audioManager
        
        // If SCO already on, start immediately
        if (am?.isBluetoothScoOn == true) {
            Log.d(TAG, "SCO already active — starting ${activeEngine.displayName} detector immediately")
            startDetectorInternal(requestId)
            return
        }

        // If no Bluetooth/HFP available, just start detector
        val adapter = BluetoothAdapter.getDefaultAdapter()
        val hfpConnected = try {
            adapter?.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED
        } catch (e: Exception) {
            false
        }

        if (!hfpConnected) {
            Log.d(TAG, "HFP not connected — starting ${activeEngine.displayName} detector on default mic")
            startDetectorInternal(requestId)
            return
        }

        // Register receiver and start SCO
        registerScoReceiverForStart(requestId)
        try {
            Log.d(TAG, "Attempting to start Bluetooth SCO for wake detection")
            am?.startBluetoothSco()
        } catch (e: Exception) {
            Log.w(TAG, "startBluetoothSco() failed: ${e.message}")
        }

        // Fallback after timeout
        scoFallbackRunnable?.let { mainHandler.removeCallbacks(it) }
        scoFallbackRunnable = Runnable {
            if (!isStartPending || requestId != startRequestId) return@Runnable
            Log.d(TAG, "SCO wait timeout — falling back to default mic")
            unregisterScoReceiverForStart()
            startDetectorInternal(requestId)
        }
        mainHandler.postDelayed(scoFallbackRunnable!!, SCO_WAIT_TIMEOUT_MS)
    }

    private fun registerScoReceiverForStart(requestId: Int) {
        unregisterScoReceiverForStart()
        scoReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val state = intent?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                Log.d(TAG, "HotHelper SCO state: $state")
                if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED && isStartPending && requestId == startRequestId) {
                    Log.d(TAG, "SCO active — starting ${activeEngine.displayName} detector on SCO mic")
                    unregisterScoReceiverForStart()
                    startDetectorInternal(requestId)
                }
            }
        }
        try {
            context.registerReceiver(scoReceiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register SCO receiver: ${e.message}")
            scoReceiver = null
        }
    }

    private fun unregisterScoReceiverForStart() {
        scoFallbackRunnable?.let { mainHandler.removeCallbacks(it) }
        scoFallbackRunnable = null
        scoReceiver?.let {
            try { context.unregisterReceiver(it) } catch (_: Exception) {}
        }
        scoReceiver = null
    }

    /**
     * Internal method to start the active detector.
     */
    private fun startDetectorInternal(requestId: Int) {
        if (!isStartPending || requestId != startRequestId) return
        if (isMuted) {
            isStartPending = false
            startPendingSinceMs = 0L
            unregisterScoReceiverForStart()
            Log.d(TAG, "🔇 Canceled pending wake start because mute is enabled")
            return
        }

        try {
            startActiveDetector()
            val detectorListening = activeDetectorIsListening()
            isStarted = detectorListening
            isStartPending = false
            startPendingSinceMs = 0L
            if (detectorListening) {
                Log.i(TAG, "🎤 ${activeEngine.displayName} wake word detection STARTED")
                Log.i(TAG, "   Say your wake phrase to trigger...")
            } else {
                Log.e(TAG, "❌ Wake detector start attempted but detector is not listening (engine=${activeEngine.displayName})")
            }
        } catch (e: Exception) {
            isStarted = false
            isStartPending = false
            startPendingSinceMs = 0L
            Log.e(TAG, "Failed to start wake detector (${activeEngine.displayName}): ${e.message}", e)
        }
    }

    fun stop() {
        isStarted = false
        isStartPending = false
        startPendingSinceMs = 0L
        unregisterScoReceiverForStart()

        val detectorListening = activeDetectorIsListening()
        if (!detectorListening) return
        try {
            stopActiveDetector()
            synchronized(bufferLock) {
                audioBuffer.clear()
            }
            Log.i(TAG, "🛑 ${activeEngine.displayName} detector stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping wake detector (${activeEngine.displayName})", e)
        }
    }

    private fun startActiveDetector() {
        when (activeEngine) {
            WakeWordEngine.CUSTOM_ONNX -> heyImiDetector?.start()
            WakeWordEngine.SNOWBOY -> snowboyDetector?.start()
        }
    }

    private fun stopActiveDetector() {
        when (activeEngine) {
            WakeWordEngine.CUSTOM_ONNX -> heyImiDetector?.stop()
            WakeWordEngine.SNOWBOY -> snowboyDetector?.stop()
        }
    }

    private fun activeDetectorIsListening(): Boolean {
        return when (activeEngine) {
            WakeWordEngine.CUSTOM_ONNX -> heyImiDetector?.isListening() == true
            WakeWordEngine.SNOWBOY -> snowboyDetector?.isListening() == true
        }
    }

    private fun releaseDetectorForEngine(engine: WakeWordEngine) {
        when (engine) {
            WakeWordEngine.CUSTOM_ONNX -> {
                try {
                    heyImiDetector?.cleanup()
                } catch (_: Exception) {
                }
                heyImiDetector = null
            }
            WakeWordEngine.SNOWBOY -> {
                try {
                    snowboyDetector?.cleanup()
                } catch (_: Exception) {
                }
                snowboyDetector = null
            }
        }
    }

    private fun activeDetectorThreshold(): Float? {
        return when (activeEngine) {
            WakeWordEngine.CUSTOM_ONNX -> heyImiDetector?.getThreshold()
            WakeWordEngine.SNOWBOY -> snowboyDetector?.getThreshold()
        }
    }

    private fun processExternalAudioForActiveDetector(pcmData: ByteArray) {
        when (activeEngine) {
            WakeWordEngine.CUSTOM_ONNX -> heyImiDetector?.processExternalAudio(pcmData)
            WakeWordEngine.SNOWBOY -> snowboyDetector?.processExternalAudio(pcmData)
        }
    }

    private fun playChimeForActiveDetector() {
        when (activeEngine) {
            WakeWordEngine.CUSTOM_ONNX -> heyImiDetector?.playChimeSound()
            WakeWordEngine.SNOWBOY -> snowboyDetector?.playChimeSound()
        }
    }

    /**
     * Ensure runtime permissions required for recording and Bluetooth connectivity.
     * Returns true if all required permissions are already granted.
     */
    fun ensurePermissions(activity: Activity, requestCode: Int = 1234): Boolean {
        val needed = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }

        return if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(activity, needed.toTypedArray(), requestCode)
            false
        } else {
            true
        }
    }

    fun release() {
        stop()
        releaseDetectorForEngine(WakeWordEngine.CUSTOM_ONNX)
        releaseDetectorForEngine(WakeWordEngine.SNOWBOY)
        Log.i(TAG, "✅ Wake detectors released")
    }
    
    /**
     * Process Glass BLE audio for wake word detection
     * @param pcmData Raw PCM audio from Glass microphone (16-bit PCM, 16kHz, mono)
     */
    fun processGlassAudio(pcmData: ByteArray) {
        if (!isStarted) return

        val now = System.currentTimeMillis()
        if (now - lastExternalFeedLogTs >= 3000L) {
            Log.d(TAG, "Feeding external glass PCM to wake detector (${pcmData.size} bytes)")
            lastExternalFeedLogTs = now
        }
        
        // Feed external PCM stream (e.g., BLE mic) to the detector.
        processExternalAudioForActiveDetector(pcmData)
    }
    
    /**
     * Play the chime/acknowledgment sound manually
     * Note: Chime is automatically played on wake word detection
     */
    fun playChimeSound() {
        playChimeForActiveDetector()
    }
    
    /**
     * Check if detector is currently listening
     */
    fun isListening(): Boolean = isStarted
    
    /**
     * Get current detection threshold
     */
    fun getThreshold(): Float = activeDetectorThreshold() ?: configuredThreshold ?: HeyImiWakeWordDetector.DEFAULT_THRESHOLD
}
