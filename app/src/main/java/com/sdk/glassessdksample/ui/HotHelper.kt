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
import android.media.AudioDeviceInfo
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

        /**
         * Single-frame instant-fire level for Mark 2 only (stock default is 0.85).
         *
         * Measured on Mark 2 hardware the two populations OVERLAP:
         *     false: 0.9344, 0.9521, 0.9642, 0.9667
         *     true:  0.9405, 0.9595
         * so no cut-off removes every false trigger while keeping every real one.
         * Tried in order: 0.85 (stock, everything fires), 0.98 (killed BOTH real
         * detections), 0.93 (blocked only 0.9344). At 0.955 the two lowest false
         * positives are rejected and the 0.9405 real detection is rejected with
         * them — i.e. this trades a missed wake word for fewer false ones, and
         * 0.9642/0.9667 still get through. This is the practical ceiling: above
         * ~0.96 both known real detections are lost.
         *
         * The real fix is the mic route, not this number. The detector is running
         * on the glasses SCO mic while HotHelper logs "phone mic mode (SCO
         * bypassed)" — iOS runs its wake detector on the PHONE mic, and Mark 1
         * has no HFP route to be captured by, which is why both of those work.
         */
        private const val MARK2_PEAK_TRIGGER = 0.955f

        /**
         * Sustained-EMA fire level for Mark 2 only (stock default is 0.55).
         *
         * Without this, [MARK2_PEAK_TRIGGER] does almost nothing. The two gates in
         * HeyImiWakeWordDetector.evaluateCurrentWindow are independent: a raw frame
         * of 0.9344 is rejected by the 0.955 peak gate, but with emaAlpha 0.667 its
         * EMA reaches ~0.93 after two frames — miles above 0.55 — so DEFAULT_CONSEC
         * (2) frames of the same ordinary conversation fire it anyway through the
         * threshold path. Every false trigger the raised peak gate was meant to stop
         * simply arrives one frame later.
         *
         * 0.95 sits just under the peak trigger, so the sustained path can no longer
         * undo the peak decision: scores that the peak gate rejects (0.9344) are
         * rejected here too, and scores that pass it (0.9642, 0.9667) had already
         * fired. It inherits the same known limits as [MARK2_PEAK_TRIGGER] — the
         * populations overlap, so this reduces false triggers rather than
         * eliminating them, and the 0.9405 real detection stays lost.
         */
        private const val MARK2_THRESHOLD = 0.95f


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

    /**
     * 🆕 Temporary suppression, independent of the user-facing mute state.
     *
     * Engaged for the duration of a vision capture → download → analysis →
     * spoken answer. The detector shares the glasses' SCO mic, so a wake word
     * landing mid-analysis starts a fresh Gemini Live session and tears down the
     * one that is about to speak the vision answer.
     *
     * This is deliberately a SEPARATE flag from [isMuted] so engaging/releasing
     * it can never clobber the user's own mute choice.
     *
     * Checked in both [start] and [startDetectorInternal] — the latter matters
     * because a start() issued before suppression can still land its async SCO
     * continuation ~1.5s later, after suppression was engaged.
     *
     * @Volatile: set from MainActivity/VisionChat callbacks on different threads.
     */
    @Volatile
    private var isSuppressed = false

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
     * 🆕 Engage/release temporary suppression (see [isSuppressed]).
     * Engaging stops any in-flight or pending detector immediately.
     * Releasing does NOT auto-restart - the caller decides when to restart, so
     * this can't re-arm the detector while the user is muted or mid-conversation.
     */
    fun setSuppressed(suppressed: Boolean) {
        isSuppressed = suppressed
        if (suppressed && (isStarted || isStartPending)) {
            Log.d(TAG, "👁️ Suppression enabled - stopping wake word detection")
            stop()
        }
        Log.d(TAG, "👁️ Wake word suppression: ${if (suppressed) "ENGAGED" else "RELEASED"}")
    }

    fun isSuppressed(): Boolean = isSuppressed

    /**
     * Allow external callers to prefer glass BLE audio for wake detection.
     * When enabled, processGlassAudio() will be used instead of phone mic.
     */
    fun setPreferGlassBleAudio(prefer: Boolean) {
        useGlassBLEAudio = prefer
    }

    /**
     * Arm the detector on the GLASSES mic.
     *
     * [useGlassBLEAudio] is sticky process-wide state, and a bare [start] just
     * inherits whatever the last caller happened to leave it as. Mark 1 sets it
     * false during pre-warm (so the pre-warm pass doesn't try to bring SCO up
     * before the glasses are connected), which meant every later start — the
     * background service's included — silently ran on the phone mic instead.
     *
     * Callers that want the glasses mic should use this rather than setting the
     * flag and calling start() separately, so the two can't drift apart again.
     */
    fun armOnGlassMic() {
        setPreferGlassBleAudio(true)
        start()
    }

    /**
     * Set detection threshold (0.0 to 1.0)
     * Lower = more sensitive but more false positives
     * Higher = less sensitive but fewer false positives
     *
     * On Mark 2 this is a request, not a command: [applyDeviceSpecificTuning] runs
     * last and keeps the device threshold. Callers pass the stock iOS value
     * unconditionally, which would otherwise drop Mark 2 back to Mark 1's gate.
     */
    fun setThreshold(value: Float) {
        configuredThreshold = value
        heyImiDetector?.setThreshold(value)
        snowboyDetector?.setThreshold(value)
        applyDeviceSpecificTuning()
        Log.d(TAG, "Detection threshold requested: $value (engine=${activeEngine.displayName}, effective=${activeDetectorThreshold()})")
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

        // 🆕 Vision analysis in flight - see isSuppressed. Blocks every restart
        // path (ListeningService rearm, onResume, engine sync, Mark1, ...) since
        // they all terminate here.
        if (isSuppressed) {
            Log.d(TAG, "👁️ Wake word detection suppressed (vision in progress) - not starting")
            return
        }

        syncEngineFromSettings()

        // With two Bluetooth audio devices connected at once, Android's own
        // routing decides which one gets the audio — the app cannot reliably
        // force it to the glasses (see PreferredAudioDeviceResolver). Rather than
        // silently risking "Hey IMI" going to the wrong device, block starting
        // until only the glasses are connected, and tell the user why.
        if (PreferredAudioDeviceResolver.hasMultipleBluetoothAudioDevicesConnected(context)) {
            val names = PreferredAudioDeviceResolver.connectedBluetoothAudioDeviceNames(context)
            Log.w(TAG, "⚠️ Multiple Bluetooth audio devices connected ($names) — " +
                "not starting wake word detection until only the glasses are connected")
            MultipleBluetoothDeviceNotifier.notify(context, names)
            return
        } else {
            MultipleBluetoothDeviceNotifier.clear(context)
        }

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

            // Re-apply on EVERY start, not just on first init: the detector is
            // created once per process but the selected device can change after
            // that, and a stale Mark 1 / Mark 2 tuning is what made both models
            // listen with the same gates.
            applyDeviceSpecificTuning()

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
            // Sets BOTH gates, and honours configuredThreshold on Mark 1.
            applyDeviceSpecificTuning()

            Log.i(TAG, "✅ ONNX Detector initialized successfully")
            Log.i(TAG, "   📦 Model: custom_wakeword/imi_cnn_mobile.onnx")
            Log.i(TAG, "   🎯 Threshold: ${heyImiDetector?.getThreshold()}")
            Log.i(TAG, "   🎯 Peak trigger: ${heyImiDetector?.getPeakTrigger()}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize ONNX detector: ${e.message}", e)
            Log.e(TAG, "   Check assets/custom_wakeword/imi_cnn_mobile.onnx exists and onnxruntime dependency is present")
            heyImiDetector = null
            return false
        }
    }

    /**
     * Applies the wake-word tuning for the CURRENTLY selected device. BOTH gates
     * are set, because raising only one leaves the other free to fire on exactly
     * the scores the raised one rejected — see [MARK2_THRESHOLD].
     *
     * - Mark 2: threshold [MARK2_THRESHOLD] + peak [MARK2_PEAK_TRIGGER].
     * - Mark 1 (and "no device chosen yet"): the iOS-parity stock values, because
     *   its quieter mic does not produce these scores.
     *
     * Both branches write explicitly — this must be able to UNDO a previous
     * device's tuning. HotHelper is a process-lifetime singleton and the detector
     * it owns outlives any single Activity, so a user who switches Mark 2 → Mark 1
     * in ProfileActivity/DeviceSelectionActivity would otherwise keep the Mark 2
     * gates (near-deaf) until the process is killed. Called from [start] rather
     * than from detector construction alone for the same reason: construction
     * happens once, device selection can change at any time after it.
     *
     * On Mark 2 this deliberately overrides [configuredThreshold]: device tuning
     * is authoritative for the ONNX detector, and callers such as
     * Mark1MainActivity set the stock 0.55 unconditionally.
     *
     * This remains a PARTIAL mitigation by construction — see [MARK2_PEAK_TRIGGER]
     * for the measurements. The two score populations overlap, so this trades some
     * false triggers away without eliminating them, and the gates cannot be pushed
     * higher without losing real detections. A complete fix needs discrimination
     * from somewhere other than these numbers (input route parity with iOS,
     * two-stage phrase confirmation, or a retrained model).
     */
    private fun applyDeviceSpecificTuning() {
        val detector = heyImiDetector ?: return

        if (DevicePreferenceManager.getDeviceType(context) == DeviceType.MARK2) {
            detector.setThreshold(MARK2_THRESHOLD)
            detector.setPeakTrigger(MARK2_PEAK_TRIGGER)
            Log.i(
                TAG,
                "🎚️ Mark 2 — threshold ${HeyImiWakeWordDetector.DEFAULT_THRESHOLD} → $MARK2_THRESHOLD, " +
                    "peak trigger ${HeyImiWakeWordDetector.DEFAULT_PEAK_TRIGGER} → $MARK2_PEAK_TRIGGER (mic is more sensitive)"
            )
        } else {
            val threshold = configuredThreshold ?: HeyImiWakeWordDetector.DEFAULT_THRESHOLD
            detector.setThreshold(threshold)
            detector.setPeakTrigger(HeyImiWakeWordDetector.DEFAULT_PEAK_TRIGGER)
            Log.i(
                TAG,
                "🎚️ Mark 1 / unset — iOS-parity gates: threshold=$threshold " +
                    "peak trigger=${HeyImiWakeWordDetector.DEFAULT_PEAK_TRIGGER}"
            )
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

        // Modern path (Android 12+): ask the framework to route communication
        // capture to the headset. getProfileConnectionState(HEADSET) below often
        // reports DISCONNECTED on these glasses even though the system CAN route
        // to them, because the app's HFP connect went through a hidden API that
        // Android blocklists — so checking that first would send every wake-word
        // session to the phone mic. Try the supported API before giving up.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && am != null) {
            try {
                // setCommunicationDevice() only takes effect in communication mode;
                // in MODE_NORMAL the request is ignored and capture stays on the phone.
                if (am.mode != AudioManager.MODE_IN_COMMUNICATION) {
                    am.mode = AudioManager.MODE_IN_COMMUNICATION
                }
                val bt = PreferredAudioDeviceResolver.findGlasses(
                    context, am.availableCommunicationDevices, AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                )
                if (bt != null && am.setCommunicationDevice(bt)) {
                    Log.d(TAG, "🎧 Wake detection routed to glasses via setCommunicationDevice(${bt.productName})")
                    startDetectorInternal(requestId)
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "setCommunicationDevice for wake detection failed: ${e.message}")
            }
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

        // 🆕 Suppression may have been engaged AFTER this start was requested but
        // before its SCO wait completed - drop it rather than arming mid-vision.
        if (isSuppressed) {
            isStartPending = false
            startPendingSinceMs = 0L
            unregisterScoReceiverForStart()
            Log.d(TAG, "👁️ Canceled pending wake start because vision is in progress")
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
