package com.sdk.glassessdksample.ui

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.*
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.*
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.sdk.glassessdksample.RemoteConfigManager
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import com.sdk.glassessdksample.ListeningService
import com.sdk.glassessdksample.NotificationListener
import com.sdk.glassessdksample.R
import com.sdk.glassessdksample.SettingsActivity
import com.sdk.glassessdksample.wakeword.HeyImiWakeWordDetector
import com.sdk.glassessdksample.databinding.ActivityMark1MainBinding
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.Locale

class Mark1MainActivity : AppCompatActivity(), GeminiLiveService.GeminiLiveCallbacks {

    companion object {
        private const val TAG = "Mark1MainActivity"
        private const val PREF_VOICE_MODE = "voice_mode"
        private const val VOICE_MODE_SEAMLESS = "seamless"
        private const val VOICE_MODE_SINGLE_SHOT = "single_shot"
        private const val REQUEST_PERMISSIONS = 1001
        private const val PREFS_CONVERSATION = "mark1_conversation"
        private const val KEY_HISTORY = "conversation_history"
        private const val KEY_TIMESTAMP = "conversation_timestamp"
        private const val HISTORY_TTL_MS = 60 * 60 * 1000L
        private const val MAX_HISTORY_PAIRS = 100
    }

    private lateinit var binding: ActivityMark1MainBinding

    private var geminiLiveService: GeminiLiveService? = null
    private var notesManager: QuickNotesManager? = null
    private var meetingManager: MeetingMinutesManager? = null
    private lateinit var userMemoryManager: UserMemoryManager

    private var tts: TextToSpeech? = null
    private var itunesMediaPlayer: MediaPlayer? = null
    private val musicProgressHandler = Handler(Looper.getMainLooper())
    private val wakeWordHandler = Handler(Looper.getMainLooper())
    private var pulseAnimator: AnimatorSet? = null
    private var wakeChimePlayer: MediaPlayer? = null
    // Separate from wakeWordHandler: stopWakeWordListening() clears that one, which
    // would silently cancel an in-flight BLE-gate connection poll.
    private val bleGateHandler = Handler(Looper.getMainLooper())

    private var isGeminiLiveActive = false
    private var wakeWordStarted = false
    private var isAiMuted = false
    // Set by onNewIntent() when the wake word brought this Activity to the foreground,
    // so the next onResume() doesn't re-run the BLE gate and stomp on the conversation
    // that's already starting (it would otherwise stop/restart the wake-word detector
    // and even tear down an active Gemini Live session).
    private var skipNextBleGateCheck = false
    // Set when the wake word COLD-launches this Activity (onCreate). Once the BLE
    // gate passes we auto-start the conversation so the user can talk to the AI
    // right away, exactly as if the app had already been open.
    private var pendingWakeConversation = false

    private val conversationHistory = mutableListOf<Pair<String, String>>()
    private val gson = Gson()

    // Id of the conversation session currently being recorded (null when idle).
    private var currentSessionId: String? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val level = intent?.getIntExtra(BatteryStatusStore.EXTRA_BATTERY_LEVEL, -1) ?: return
            if (level in 0..100) updateBatteryChip(level)
        }
    }

    // Re-show the BLE gate whenever the glasses disconnect or Bluetooth is turned off,
    // and dismiss it again once they reconnect.
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED,
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_ACL_DISCONNECTED,
                BluetoothAdapter.ACTION_STATE_CHANGED -> checkBleAndShowGate()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LIFECYCLE
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMark1MainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        userMemoryManager = UserMemoryManager(this)
        notesManager = QuickNotesManager(this)
        meetingManager = MeetingMinutesManager(this)

        // Fetch API keys securely from Firebase Remote Config (same as Mark 2's MainActivity)
        RemoteConfigManager.fetchAndActivate { success ->
            Log.d(TAG, if (success) "✅ Remote config loaded" else "⚠️ Using cached remote config")
        }

        loadConversationHistory()
        initTts()
        initGeminiLive()
        setupActions()
        setupBottomNav()
        preWarmWakeWord()
        checkAndRequestPermissions()

        // If the OS had destroyed this Activity while backgrounded, the wake word
        // cold-launches it here (onCreate) rather than onNewIntent. Remember that
        // so we auto-start the conversation once the BLE gate passes — otherwise
        // the wake word "fires" but the user can never talk to the AI.
        if (intent?.action == ListeningService.ACTION_WAKE_WORD_DETECTED) {
            pendingWakeConversation = true
        }

        checkBleAndShowGate()
    }

    /**
     * Called when ListeningService brings this Activity back to the foreground after
     * detecting the wake word while the app was minimised or the screen was off.
     * launchMode="singleTop" in the manifest ensures this fires instead of onCreate.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ListeningService.ACTION_WAKE_WORD_DETECTED) {
            // Don't start the conversation here — onNewIntent runs BEFORE onResume,
            // and when the phone is locked the window/audio route isn't ready yet,
            // so the chime and mic silently fail. Instead flag it and let the
            // wake-conversation kick off from onResume, once the Activity is truly
            // foregrounded (over the lock screen) and audio can route correctly.
            pendingWakeConversation = true
            skipNextBleGateCheck = true
        }
    }

    override fun onResume() {
        super.onResume()
        EventBus.getDefault().register(this)
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(batteryReceiver, IntentFilter(BatteryStatusStore.ACTION_BATTERY_UPDATED))
        ContextCompat.registerReceiver(
            this,
            bluetoothStateReceiver,
            IntentFilter().apply {
                addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        binding.bottomNavigation.selectedItemId = R.id.nav_home
        if (skipNextBleGateCheck) {
            skipNextBleGateCheck = false
            // Warm wake path: the Activity was alive in the background, so the BLE
            // gate is already hidden. If the wake word brought us here, start the
            // conversation now that we're resumed (window up, audio can route).
            maybeStartPendingWakeConversation()
        } else {
            checkBleAndShowGate()
        }
    }

    /**
     * Starts the conversation that the wake word requested, but only once the
     * Activity is truly resumed and (for Mark 1) the glasses are connected. Called
     * from both the warm path (onResume) and the cold path (hideBleGate). Runs on a
     * short post so the window is fully up — important when we came up over the lock
     * screen, where an immediate chime/mic grab would otherwise be dropped.
     */
    private fun maybeStartPendingWakeConversation() {
        if (!pendingWakeConversation) return
        pendingWakeConversation = false
        if (isAiMuted || isGeminiLiveActive) return
        if (!isGlassConnected()) {
            // No glasses → assistant is gated off; show the gate instead.
            checkBleAndShowGate()
            return
        }
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isGeminiLiveActive && !isAiMuted) {
                Log.i(TAG, "🎙️ Starting wake-word conversation (background/locked path)")
                playChimeThenStartConversation()
            }
        }, 350)
    }

    override fun onPause() {
        super.onPause()
        EventBus.getDefault().unregister(this)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(batteryReceiver)
        try { unregisterReceiver(bluetoothStateReceiver) } catch (_: Exception) {}
        saveConversationHistory()
    }

    override fun onDestroy() {
        super.onDestroy()
        wakeWordHandler.removeCallbacksAndMessages(null)
        bleGateHandler.removeCallbacksAndMessages(null)
        tts?.stop()
        tts?.shutdown()
        itunesMediaPlayer?.release()
        wakeChimePlayer?.release()
        wakeChimePlayer = null
        musicProgressHandler.removeCallbacksAndMessages(null)
        pulseAnimator?.cancel()
        geminiLiveService?.stopLiveConversation()
        // NOTE: Do NOT stop HotHelper here. Wake-word listening is owned by the
        // foreground ListeningService so it keeps running when this Activity is
        // backgrounded or reclaimed by the OS (screen off / locked / minimised).
        // The listener is only stopped via the notification's "Stop" action, when
        // the glasses disconnect (BLE gate), or when the AI is muted — all of which
        // go through stopWakeWordListening() and tell the service to stop too.
    }

    // ─────────────────────────────────────────────────────────────────────────
    // INITIALISATION
    // ─────────────────────────────────────────────────────────────────────────

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
            }
        }
    }

    private fun initGeminiLive() {
        geminiLiveService = GeminiLiveService(applicationContext, this)
    }

    private fun preWarmWakeWord() {
        HotHelper.getInstance(applicationContext).apply {
            setPreferGlassBleAudio(false)
        }
    }

    private fun setupBottomNav() {
        Mark1BottomNavManager.setup(this, binding.bottomNavigation, R.id.nav_home)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BLE GATE
    // ─────────────────────────────────────────────────────────────────────────

    private fun checkBleAndShowGate() {
        // Seamless hand-back: if a conversation started while the phone was locked is
        // still running, turning the screen on must NOT tear it down. Skip the gate
        // entirely and just show the live conversation UI instead.
        if (ListeningService.isBackgroundConversationActive()) {
            Log.i(TAG, "🔗 Background conversation in progress — skipping BLE gate, adopting session")
            adoptBackgroundConversationUi()
            return
        }

        // Cancel any poll from a previous gate pass so they can't race each other.
        bleGateHandler.removeCallbacksAndMessages(null)

        binding.layoutBleGate.visibility = View.VISIBLE
        binding.layoutBleChecking.visibility = View.VISIBLE
        binding.layoutBleNotConnected.visibility = View.GONE
        // Hide the bottom nav while the BLE gate is covering the screen;
        // hideBleGate() restores it once glasses are connected.
        binding.bottomNavigation.visibility = View.GONE
        // Stop the AI chatbot while the gate is shown — no assistant without glasses.
        // stopConversation() restarts wake-word listening, so stop it last to leave
        // the assistant fully off; hideBleGate() restarts it once connected.
        if (isGeminiLiveActive) stopConversation()
        stopWakeWordListening()

        // Poll briefly instead of sampling once: the glasses' profiles can take a
        // moment to register after the app opens, and a single 800ms check would
        // wrongly show "not connected" for an already-paired, connected pair.
        pollForGlassConnection(attempt = 0)
    }

    /** Re-checks the connection a few times before declaring the glasses absent. */
    private fun pollForGlassConnection(attempt: Int) {
        val maxAttempts = 6      // ~3s total
        val intervalMs = 500L

        bleGateHandler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            when {
                isGlassConnected() -> {
                    Log.i(TAG, "✅ Glasses detected on attempt ${attempt + 1}")
                    hideBleGate()
                }
                attempt + 1 < maxAttempts -> pollForGlassConnection(attempt + 1)
                else -> {
                    Log.i(TAG, "❌ Glasses not detected after $maxAttempts attempts")
                    binding.layoutBleChecking.visibility = View.GONE
                    binding.layoutBleNotConnected.visibility = View.VISIBLE
                }
            }
        }, intervalMs)
    }

    /**
     * The user turned the screen on while a phone-locked conversation was running.
     * Show the live conversation UI and leave the session completely untouched — the
     * background service still owns the mic/WebSocket, so the chat continues without
     * a break. When it ends, the service re-arms the wake word as usual.
     */
    private fun adoptBackgroundConversationUi() {
        binding.layoutBleGate.visibility = View.GONE
        binding.bottomNavigation.visibility = View.VISIBLE
        binding.cardConversation.visibility = View.VISIBLE
        binding.tvConversationStatus.text = "🎤 Listening…"
        binding.btnQuickStart.text = "Stop Listening"
        startPulseAnimation()
    }

    /**
     * True when the glasses are connected by ANY meaningful transport.
     *
     * The old check only looked at the HFP/HEADSET profile, but Mark 1 connects over
     * BLE (GATT) for data and only registers on HFP once voice audio (SCO) is
     * actually up. That made a properly-connected pair report "not connected" on the
     * gate right after opening the app. We now accept HEADSET, A2DP or GATT, and
     * fall back to asking the BluetoothManager which devices are really connected.
     */
    private fun isGlassConnected(): Boolean {
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
            if (!adapter.isEnabled) return false

            // 1. Classic profiles (HFP for voice, A2DP for media).
            val profiles = intArrayOf(
                BluetoothProfile.HEADSET,
                BluetoothProfile.A2DP,
                BluetoothProfile.GATT
            )
            for (p in profiles) {
                val state = try {
                    adapter.getProfileConnectionState(p)
                } catch (_: Exception) {
                    BluetoothProfile.STATE_DISCONNECTED
                }
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    Log.d(TAG, "Glasses connected (profile=$p)")
                    return true
                }
            }

            // 2. BLE/GATT devices the system reports as actively connected.
            try {
                val bm = getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
                val gattConnected = bm?.getConnectedDevices(BluetoothProfile.GATT).orEmpty() +
                    bm?.getConnectedDevices(BluetoothProfile.GATT_SERVER).orEmpty()
                if (gattConnected.isNotEmpty()) {
                    Log.d(TAG, "Glasses connected (GATT devices=${gattConnected.size})")
                    return true
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "No BLUETOOTH_CONNECT permission for GATT check: ${e.message}")
            }

            // 3. Last resort: ask each bonded device whether it is actually connected.
            //    BluetoothDevice.isConnected() is hidden API, hence reflection. This
            //    catches classic audio headsets (e.g. "F-16") that the profile proxy
            //    can momentarily report as disconnected.
            try {
                val bonded = adapter.bondedDevices.orEmpty()
                for (device in bonded) {
                    val connected = try {
                        val m = device.javaClass.getMethod("isConnected")
                        m.invoke(device) as? Boolean ?: false
                    } catch (_: Exception) {
                        false
                    }
                    if (connected) {
                        Log.d(TAG, "Glasses connected (bonded device reports connected)")
                        return true
                    }
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "No permission to read bonded devices: ${e.message}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "BLE check error: ${e.message}")
        }
        return false
    }

    private fun hideBleGate() {
        // Stop any pending connection poll so a late callback can't re-show the gate.
        bleGateHandler.removeCallbacksAndMessages(null)
        binding.layoutBleGate.animate()
            .alpha(0f)
            .setDuration(400)
            .withEndAction {
                binding.layoutBleGate.visibility = View.GONE
                binding.layoutBleGate.alpha = 1f
            }
            .start()
        binding.bottomNavigation.visibility = View.VISIBLE
        startEntranceAnimations()

        // Cold path: the wake word launched us from scratch (Activity had been
        // killed). Now that glasses are confirmed connected, start the conversation
        // instead of dropping back to idle listening — the user just said "Hey IMI".
        if (pendingWakeConversation) {
            pendingWakeConversation = false
            if (!isAiMuted && !isGeminiLiveActive) {
                Handler(Looper.getMainLooper()).postDelayed({
                    if (!isGeminiLiveActive && !isAiMuted) {
                        Log.i(TAG, "🎙️ Starting wake-word conversation (cold-launch path)")
                        playChimeThenStartConversation()
                    }
                }, 350)
                return
            }
        }

        if (!isAiMuted) startWakeWordListening()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENTRANCE ANIMATIONS
    // ─────────────────────────────────────────────────────────────────────────

    private fun startEntranceAnimations() {
        val slideFadeIn = AnimationUtils.loadAnimation(this, R.anim.slide_fade_in)
        val views = listOf(
            binding.cardHero,
            binding.tvQuickActionsLabel,
            binding.rowTiles1,
            binding.rowTiles2,
            binding.rowTiles3
        )
        views.forEachIndexed { i, view ->
            view.alpha = 0f
            view.postDelayed({
                view.alpha = 1f
                view.startAnimation(slideFadeIn)
            }, (i * 100L))
        }

        // Floating animation on glasses image
        val floatAnim = ObjectAnimator.ofFloat(binding.imgGlasses, "translationY", 0f, -10f * resources.displayMetrics.density).apply {
            duration = 2200
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
        }
        floatAnim.start()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WAKE WORD
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Re-arm wake-word listening after a short settle delay. Used when a
     * conversation has just ended: the tail of the AI's audio / the stop
     * chime / the user's last words are still echoing in the mic, and starting
     * the detector immediately re-triggers "Hey IMI" on that residual audio
     * (worse now that the Mark 1 threshold is lowered). The delay lets the
     * audio environment settle before we start scoring frames again.
     */
    private fun startWakeWordListeningDelayed(delayMs: Long = 1_200L) {
        wakeWordHandler.removeCallbacksAndMessages(null)
        wakeWordHandler.postDelayed({
            if (!isAiMuted && !isGeminiLiveActive) {
                startWakeWordListening()
            }
        }, delayMs)
    }

    private fun startWakeWordListening() {
        // Cancel any pending delayed re-arm so we don't double-start.
        wakeWordHandler.removeCallbacksAndMessages(null)
        if (isAiMuted || wakeWordStarted) return
        wakeWordStarted = true

        try {
            val serviceIntent = Intent(this, ListeningService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not start ListeningService: ${e.message}")
        }

        HotHelper.getInstance(applicationContext).apply {
            // Use the SAME threshold as the iOS app (0.55). With the iOS model
            // (imi_cnn_mobile.onnx) a real "Hey IMI" scores 0.85–0.99 while
            // ambient speech/noise stays below ~0.53, so 0.55 sits cleanly in
            // the gap. (The old 0.27 was tuned for the previous, weaker model
            // whose positives only reached ≈0.39 — do NOT reintroduce it.)
            setThreshold(HeyImiWakeWordDetector.DEFAULT_THRESHOLD) // 0.55, matches iOS
            start()
        }
    }

    private fun stopWakeWordListening() {
        wakeWordHandler.removeCallbacksAndMessages(null)
        wakeWordStarted = false
        HotHelper.getInstance(applicationContext).stop()
        // The foreground ListeningService owns the detector for background
        // operation, so tell it to stop too — otherwise it would keep listening
        // (and holding the mic + wake-lock) after the glasses disconnect / AI mute.
        try {
            val stopIntent = Intent(this, ListeningService::class.java).apply {
                action = ListeningService.ACTION_STOP
            }
            startService(stopIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not stop ListeningService: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CONVERSATION
    // ─────────────────────────────────────────────────────────────────────────

    private fun playChimeThenStartConversation() {
        stopWakeWordListening()

        // Guarantee the conversation starts exactly once, even if the chime fails
        // to play or its completion callback never fires (which is what happened on
        // a locked screen — the old code also mis-set the stream type AFTER
        // MediaPlayer.create()/prepare(), which threw and silently swallowed the
        // conversation start). A short watchdog is the backstop.
        val started = java.util.concurrent.atomic.AtomicBoolean(false)
        val startOnce = {
            if (started.compareAndSet(false, true)) startInlineGeminiLive()
        }
        // Backstop: if the chime hasn't handed off within 1.2s, start anyway.
        Handler(Looper.getMainLooper()).postDelayed({ startOnce() }, 1_200)

        try {
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                val afd = resources.openRawResourceFd(R.raw.wake_chime)
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                setVolume(1f, 1f)
                setOnCompletionListener { player ->
                    player.release()
                    startOnce()
                }
                setOnErrorListener { player, _, _ ->
                    player.release()
                    startOnce()
                    true
                }
                prepare()
                start()
            }
            wakeChimePlayer?.release()
            wakeChimePlayer = mp
        } catch (e: Exception) {
            Log.w(TAG, "Chime failed: ${e.message}")
            startOnce()
        }
    }

    private fun playChimeManuallyThenStartConversation() {
        if (isGeminiLiveActive) {
            stopConversation()
            return
        }
        playChimeThenStartConversation()
    }

    private fun startInlineGeminiLive() {
        if (isGeminiLiveActive) return
        isGeminiLiveActive = true

        // Begin a new conversation session so every turn in this run is grouped
        // together (date/time stamped) in the History screen.
        currentSessionId = ConversationSessionStore.startSession(this)

        val systemInstruction = buildSystemInstruction()

        runOnUiThread {
            binding.cardConversation.visibility = View.VISIBLE
            binding.tvConversationStatus.text = "🎤 Listening…"
            binding.btnQuickStart.text = "Stop Listening"
            startPulseAnimation()
        }

        geminiLiveService?.startLiveConversation(systemInstruction, greetOnStart = true)
    }

    private fun stopConversation() {
        isGeminiLiveActive = false
        // End the current session so the next conversation is grouped separately.
        currentSessionId = null
        geminiLiveService?.stopLiveConversation()
        // Also end a conversation that was started by the background service (e.g. the
        // user woke IMI with the phone locked, then turned the screen on and tapped Stop).
        if (ListeningService.isBackgroundConversationActive()) {
            try {
                startService(
                    Intent(this, ListeningService::class.java)
                        .apply { action = ListeningService.ACTION_STOP_BG_CONVERSATION }
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not stop background conversation: ${e.message}")
            }
        }
        stopPulseAnimation()

        runOnUiThread {
            binding.tvConversationStatus.text = "Ready"
            binding.btnQuickStart.text = "Quick Start"
        }

        // Re-arm after a settle delay so the tail of the conversation audio /
        // stop chime doesn't immediately re-trigger the wake word.
        if (!isAiMuted) {
            startWakeWordListeningDelayed()
        }
    }

    private fun buildSystemInstruction(): String {
        val memory = userMemoryManager.getUserMemory()
        val notes = notesManager?.getNotesContextForAI() ?: ""
        val history = buildHistorySummary()
        val responseStyle = com.sdk.glassessdksample.ui.AiResponsePrefs.buildResponseStyleInstruction(this)

        val sb = StringBuilder()
        sb.append("You are imi glass, an intelligent AI assistant built into smart glasses. ")
        sb.append("$responseStyle ")
        sb.append("Be helpful and conversational. You are aware of your environment through what the user shares with you.\n\n")

        if (!memory.isEmpty()) {
            sb.append("## User Profile\n")
            if (memory.userName.isNotBlank()) sb.append("Name: ${memory.userName}\n")
            if (memory.occupation.isNotBlank()) sb.append("Occupation: ${memory.occupation}\n")
            if (memory.location.isNotBlank()) sb.append("Location: ${memory.location}\n")
            if (memory.likes.isNotEmpty()) sb.append("Likes: ${memory.likes.joinToString(", ")}\n")
            if (memory.interests.isNotEmpty()) sb.append("Interests: ${memory.interests.joinToString(", ")}\n")
            if (memory.autoLearnedFacts.isNotEmpty()) sb.append("Known facts: ${memory.autoLearnedFacts.takeLast(10).joinToString("; ")}\n")
            sb.append("\n")
        }

        if (history.isNotBlank()) {
            sb.append("## Recent Conversation\n$history\n\n")
        }

        if (notes.isNotBlank()) {
            sb.append("## User's Quick Notes\n$notes\n\n")
        }

        sb.append("## Available Tools\n")
        sb.append("You have access to these tools: create_note, capture_photo_note, start_meeting, ")
        sb.append("play_music, play_youtube, make_phone_call, get_directions, get_weather, ")
        sb.append("web_search, define_word, wiki_summary, stock_quote, mute_ai, ")
        sb.append("read_notifications, say_goodbye.\n\n")
        sb.append("Use tools when the user's request matches them. Keep responses brief and spoken-word friendly.")

        return sb.toString()
    }

    private fun buildHistorySummary(): String {
        val recent = conversationHistory.takeLast(10)
        if (recent.isEmpty()) return ""
        return recent.joinToString("\n") { (user, ai) -> "User: $user\nimi glass: $ai" }
    }

    private fun addToHistory(userText: String, aiText: String) {
        if (userText.isBlank() && aiText.isBlank()) return
        conversationHistory.add(Pair(userText, aiText))
        if (conversationHistory.size > MAX_HISTORY_PAIRS) {
            conversationHistory.removeAt(0)
        }
        // Persist the completed turn into the current conversation session so the
        // whole conversation is grouped and saved (not just the last few turns).
        // If no session is active yet (e.g. wake-word triggered before the UI
        // started one), the store creates one so the turn is never lost.
        if (currentSessionId == null) {
            currentSessionId = ConversationSessionStore.startSession(this)
        }
        ConversationSessionStore.appendTurn(this, currentSessionId, userText, aiText)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CONVERSATION HISTORY PERSISTENCE
    // ─────────────────────────────────────────────────────────────────────────

    private fun saveConversationHistory() {
        if (conversationHistory.isEmpty()) return
        val prefs = getSharedPreferences(PREFS_CONVERSATION, Context.MODE_PRIVATE)
        val json = gson.toJson(conversationHistory)
        prefs.edit()
            .putString(KEY_HISTORY, json)
            .putLong(KEY_TIMESTAMP, System.currentTimeMillis())
            .apply()
    }

    private fun loadConversationHistory() {
        val prefs = getSharedPreferences(PREFS_CONVERSATION, Context.MODE_PRIVATE)
        val savedAt = prefs.getLong(KEY_TIMESTAMP, 0L)
        if (System.currentTimeMillis() - savedAt > HISTORY_TTL_MS) {
            prefs.edit().remove(KEY_HISTORY).remove(KEY_TIMESTAMP).apply()
            return
        }
        val json = prefs.getString(KEY_HISTORY, null) ?: return
        try {
            val type = object : TypeToken<List<Pair<String, String>>>() {}.type
            val loaded: List<Pair<String, String>> = gson.fromJson(json, type)
            conversationHistory.addAll(loaded)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load history: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PULSE ANIMATION
    // ─────────────────────────────────────────────────────────────────────────

    private fun startPulseAnimation() {
        val ring = binding.viewPulseRing
        ring.visibility = View.VISIBLE
        pulseAnimator?.cancel()
        pulseAnimator = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(ring, "scaleX", 1f, 2.2f).apply {
                    duration = 900; repeatCount = ObjectAnimator.INFINITE; repeatMode = ObjectAnimator.RESTART
                },
                ObjectAnimator.ofFloat(ring, "scaleY", 1f, 2.2f).apply {
                    duration = 900; repeatCount = ObjectAnimator.INFINITE; repeatMode = ObjectAnimator.RESTART
                },
                ObjectAnimator.ofFloat(ring, "alpha", 0.7f, 0f).apply {
                    duration = 900; repeatCount = ObjectAnimator.INFINITE; repeatMode = ObjectAnimator.RESTART
                }
            )
            start()
        }
    }

    private fun stopPulseAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        binding.viewPulseRing.visibility = View.GONE
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MUTE TOGGLE
    // ─────────────────────────────────────────────────────────────────────────

    private fun toggleAiMute() {
        isAiMuted = !isAiMuted
        HotHelper.getInstance(applicationContext).setMuted(isAiMuted)

        if (isAiMuted) {
            stopWakeWordListening()
            if (isGeminiLiveActive) stopConversation()
            binding.tvMuteLabel.text = "Unmute AI"
            Toast.makeText(this, "AI muted — not listening", Toast.LENGTH_SHORT).show()
        } else {
            binding.tvMuteLabel.text = "Silent Mode"
            if (isGlassConnected()) {
                checkBleAndShowGate()
            } else {
                startWakeWordListening()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BATTERY UI
    // ─────────────────────────────────────────────────────────────────────────

    private fun updateBatteryChip(level: Int) {
        binding.tvHomeBatteryLevel.text = "$level%"
        binding.layoutBatteryChip.visibility = View.VISIBLE

        val minutes = (level * 150) / 100
        val h = minutes / 60
        val m = minutes % 60
        binding.tvDeviceBatteryTime.text = if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TILE PRESS ANIMATION
    // ─────────────────────────────────────────────────────────────────────────

    private fun animateTilePress(view: View, action: () -> Unit) {
        view.animate().scaleX(0.92f).scaleY(0.92f).setDuration(100).withEndAction {
            view.animate().scaleX(1.03f).scaleY(1.03f).setDuration(120).withEndAction {
                view.animate().scaleX(1f).scaleY(1f).setDuration(80).withEndAction {
                    action()
                }.start()
            }.start()
        }.start()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SETUP CLICK LISTENERS
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupActions() {
        binding.btnQuickStart.setOnClickListener {
            playChimeManuallyThenStartConversation()
        }

        binding.imgProfileAvatar.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnBleGateRetry.setOnClickListener {
            // If BLUETOOTH_CONNECT is still missing we literally cannot see the
            // glasses, so ask for it again rather than re-running a check that is
            // guaranteed to fail.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_PERMISSIONS
                )
            } else {
                checkBleAndShowGate()
            }
        }

        binding.btnBleGateInfo.setOnClickListener {
            showConnectionGuideDialog()
        }

        binding.btnGlassControls.setOnClickListener {
            animateTilePress(it) {
                startActivity(Intent(this, DeviceBindActivity::class.java))
            }
        }

        binding.btnSunny.setOnClickListener {
            animateTilePress(it) {
                if (isGeminiLiveActive) {
                    stopConversation()
                    Toast.makeText(this, "AI conversation stopped", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "No active conversation", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnMuteAi.setOnClickListener {
            animateTilePress(it) { toggleAiMute() }
        }

        binding.btnQuickNotes.setOnClickListener {
            animateTilePress(it) {
                startActivity(Intent(this, QuickNotesActivity::class.java))
            }
        }

        binding.btnMeetingMinutes.setOnClickListener {
            animateTilePress(it) {
                startActivity(Intent(this, MeetingMinutesActivity::class.java))
            }
        }

        binding.btnConversationHistory.setOnClickListener {
            animateTilePress(it) {
                startActivity(Intent(this, ConversationHistoryActivity::class.java))
            }
        }

        // "Controls" tile — opens device controls
        binding.btnNotifications.setOnClickListener {
            animateTilePress(it) {
                startActivity(Intent(this, DeviceBindActivity::class.java))
            }
        }

        // Music player controls
        binding.btnMusicPlayPause.setOnClickListener {
            itunesMediaPlayer?.let { mp ->
                if (mp.isPlaying) { mp.pause(); binding.btnMusicPlayPause.text = "Play" }
                else { mp.start(); binding.btnMusicPlayPause.text = "Pause" }
            }
        }

        binding.btnMusicStop.setOnClickListener {
            itunesMediaPlayer?.stop()
            itunesMediaPlayer?.release()
            itunesMediaPlayer = null
            musicProgressHandler.removeCallbacksAndMessages(null)
            binding.cardMusicPlayer.visibility = View.GONE
        }
    }

    private fun showConnectionGuideDialog() {
        AlertDialog.Builder(this)
            .setTitle("Connect Your IMI Glasses")
            .setMessage(
                "1. Open phone Bluetooth settings\n\n" +
                "2. Power on IMI glasses\n\n" +
                "3. Pair the device\n\n" +
                "4. Return here and tap Retry"
            )
            .setPositiveButton("Got it", null)
            .show()
    }

    private fun handleNotificationsTile() {
        if (!isNotificationListenerEnabled()) {
            val prefs = getSharedPreferences("imi_prefs", Context.MODE_PRIVATE)
            val alreadyAsked = prefs.getBoolean("notification_listener_asked", false)
            if (!alreadyAsked) {
                prefs.edit().putBoolean("notification_listener_asked", true).apply()
                AlertDialog.Builder(this)
                    .setTitle("Notification Access")
                    .setMessage("Enable notification access so IMI can read your notifications.")
                    .setPositiveButton("Enable") { _, _ ->
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }
                    .setNegativeButton("Not Now", null)
                    .show()
            } else {
                Toast.makeText(this, "Enable notification access in Settings", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Notifications: ask IMI \"What notifications do I have?\"", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val cn = ComponentName(this, NotificationListener::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(cn.flattenToString())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // EVENTBUS
    // ─────────────────────────────────────────────────────────────────────────

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onBluetoothEvent(event: BluetoothEvent) {
        when (event.type) {
            BluetoothEvent.EventType.CONNECTED -> {
                hideBleGate()
            }
            BluetoothEvent.EventType.DISCONNECTED -> {
                if (isGeminiLiveActive) stopConversation()
                checkBleAndShowGate()
            }
            BluetoothEvent.EventType.VOICE_TEXT -> {
                val data = event.data as? String ?: return
                if (data == "wake up" && !isGeminiLiveActive && !isAiMuted) {
                    playChimeThenStartConversation()
                }
            }
            BluetoothEvent.EventType.BATTERY_LEVEL -> {
                val level = (event.data as? Int) ?: return
                BatteryStatusStore.saveBatteryLevel(applicationContext, level)
            }
            else -> {}
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GEMINI LIVE CALLBACKS
    // ─────────────────────────────────────────────────────────────────────────

    override fun onTranscriptionUpdate(input: String, output: String, isFinal: Boolean) {
        runOnUiThread {
            val statusText = when {
                input.isNotBlank() -> "🎤 You: $input"
                output.isNotBlank() -> "🔊 Imi: $output"
                else -> "🎤 Listening…"
            }
            binding.tvConversationStatus.text = statusText

            if (isFinal && (input.isNotBlank() || output.isNotBlank())) {
                val current = binding.tvConversation.text.toString()
                val line = when {
                    input.isNotBlank() && output.isNotBlank() -> "You: $input\nIMI: $output\n"
                    input.isNotBlank() -> "You: $input\n"
                    else -> "IMI: $output\n"
                }
                binding.tvConversation.text = if (current.isBlank()) line else "$current$line"
                binding.scrollConversation.post {
                    binding.scrollConversation.fullScroll(View.FOCUS_DOWN)
                }
            }
        }

        if (isFinal && input.isNotBlank()) {
            userMemoryManager.learnFromUserMessage(input)
            userMemoryManager.incrementMessageStats(false)
        }
    }

    override fun onTurnComplete(fullInput: String, fullOutput: String) {
        addToHistory(fullInput, fullOutput)
        com.sdk.glassessdksample.ui.sync.ChatSync.pushTurn(
            this, "Mark 1 Glasses", fullInput, fullOutput,
            channel = com.sdk.glassessdksample.ui.sync.ChatSync.Channel.VOICE,
            sessionId = currentSessionId
        )

        if (isSingleShotMode()) {
            runOnUiThread { stopConversation() }
        }
    }

    override fun onToolCall(toolName: String, args: Map<String, Any>): String {
        Log.d(TAG, "Tool call: $toolName args=$args")
        return when (toolName) {
            "create_note" -> handleCreateNote(args)
            "start_meeting" -> handleStartMeeting()
            "play_music" -> handlePlayMusic(args)
            "play_youtube" -> handlePlayYoutube(args)
            "make_phone_call" -> handlePhoneCall(args)
            "get_directions" -> handleDirections(args)
            "get_weather" -> handleWeather(args)
            "web_search" -> handleWebSearch(args)
            "define_word" -> handleDefineWord(args)
            "wiki_summary" -> handleWikiSummary(args)
            "stock_quote" -> handleStockQuote(args)
            "mute_ai" -> handleMuteAi()
            "read_notifications" -> handleReadNotifications()
            "identify_song" -> handleIdentifySong()
            "say_goodbye" -> handleSayGoodbye()
            else -> "Tool $toolName not yet implemented."
        }
    }

    override fun onAudioPlaybackStart() {
        runOnUiThread {
            binding.tvConversationStatus.text = "🔊 Imi speaking…"
        }
    }

    override fun onAudioPlaybackEnd() {
        runOnUiThread {
            if (isGeminiLiveActive) binding.tvConversationStatus.text = "🎤 Listening…"
        }
    }

    override fun onError(error: String) {
        Log.e(TAG, "GeminiLive error: $error")
        runOnUiThread {
            Toast.makeText(this, "Connection error — tap Quick Start to retry", Toast.LENGTH_SHORT).show()
            stopConversation()
        }
    }

    override fun onConnectionStatusChanged(isConnected: Boolean) {
        Log.d(TAG, "Gemini connection: $isConnected")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL HANDLERS
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleCreateNote(args: Map<String, Any>): String {
        val title = args["title"] as? String ?: "Note"
        val content = args["content"] as? String ?: ""
        notesManager?.createNote(title, content, QuickNote.CreatedBy.AI)
        runOnUiThread { Toast.makeText(this, "Note saved: $title", Toast.LENGTH_SHORT).show() }
        return "Note saved successfully."
    }

    /**
     * 🎵 Shazam-style song ID from the ambient audio the live session is already
     * capturing. Returns "Title by Artist" for the model to speak, or a short
     * plain-language miss message.
     */
    private fun handleIdentifySong(): String {
        return try {
            kotlinx.coroutines.runBlocking { SongIdentifier.identifyFromLiveSession() }
        } catch (e: Exception) {
            Log.e(TAG, "Song identification failed: ${e.message}", e)
            "I couldn't identify that song right now."
        }
    }

    private fun handleStartMeeting(): String {
        runOnUiThread {
            stopConversation()
            startActivity(Intent(this, ActiveMeetingActivity::class.java))
        }
        return "Opening meeting minutes."
    }

    private fun handlePlayMusic(args: Map<String, Any>): String {
        val query = args["query"] as? String ?: args["song"] as? String ?: return "Please specify a song."
        runOnUiThread {
            Toast.makeText(this, "Searching for: $query", Toast.LENGTH_SHORT).show()
        }
        try {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "https://itunes.apple.com/search?term=$encodedQuery&media=music&entity=song&limit=1"
            val client = okhttp3.OkHttpClient()
            val request = okhttp3.Request.Builder().url(url).build()
            client.newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    runOnUiThread { openSpotifySearch(query) }
                }
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    val body = response.body?.string() ?: ""
                    val json = com.google.gson.JsonParser.parseString(body).asJsonObject
                    val results = json.getAsJsonArray("results")
                    if (results.size() > 0) {
                        val track = results[0].asJsonObject
                        val previewUrl = track.get("previewUrl")?.asString
                        val trackName = track.get("trackName")?.asString ?: query
                        val artistName = track.get("artistName")?.asString ?: ""
                        if (!previewUrl.isNullOrBlank()) {
                            runOnUiThread { playMusicPreview(previewUrl, trackName, artistName) }
                        } else {
                            runOnUiThread { openSpotifySearch(query) }
                        }
                    } else {
                        runOnUiThread { openSpotifySearch(query) }
                    }
                }
            })
        } catch (e: Exception) {
            runOnUiThread { openSpotifySearch(query) }
        }
        return "Searching for music: $query"
    }

    private fun playMusicPreview(url: String, trackName: String, artistName: String) {
        itunesMediaPlayer?.release()
        try {
            itunesMediaPlayer = MediaPlayer().apply {
                setAudioStreamType(AudioManager.STREAM_MUSIC)
                setDataSource(url)
                prepareAsync()
                setOnPreparedListener { mp ->
                    mp.start()
                    binding.cardMusicPlayer.visibility = View.VISIBLE
                    binding.tvMusicTrackName.text = trackName
                    binding.tvMusicArtistName.text = artistName
                    binding.btnMusicPlayPause.text = "Pause"
                    binding.tvMusicDuration.text = formatMs(mp.duration)
                    startMusicProgress(mp)
                }
                setOnCompletionListener {
                    binding.cardMusicPlayer.visibility = View.GONE
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Music play failed: ${e.message}")
        }
    }

    private fun startMusicProgress(mp: MediaPlayer) {
        musicProgressHandler.removeCallbacksAndMessages(null)
        val runnable = object : Runnable {
            override fun run() {
                if (mp.isPlaying) {
                    binding.musicSeekBar.max = mp.duration
                    binding.musicSeekBar.progress = mp.currentPosition
                    binding.tvMusicCurrentTime.text = formatMs(mp.currentPosition)
                    musicProgressHandler.postDelayed(this, 500)
                }
            }
        }
        musicProgressHandler.post(runnable)
    }

    private fun formatMs(ms: Int): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun openSpotifySearch(query: String) {
        try {
            val uri = android.net.Uri.parse("spotify:search:$query")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                putExtra(Intent.EXTRA_REFERRER, android.net.Uri.parse("android-app://$packageName"))
            }
            startActivity(intent)
        } catch (e: Exception) {
            val webUri = android.net.Uri.parse("https://open.spotify.com/search/$query")
            startActivity(Intent(Intent.ACTION_VIEW, webUri))
        }
    }

    private fun handlePlayYoutube(args: Map<String, Any>): String {
        val query = args["query"] as? String ?: args["video"] as? String ?: return "Please specify a video."
        runOnUiThread {
            try {
                val searchUri = android.net.Uri.parse("https://www.youtube.com/results?search_query=${java.net.URLEncoder.encode(query, "UTF-8")}")
                val intent = Intent(Intent.ACTION_VIEW, searchUri)
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open YouTube", Toast.LENGTH_SHORT).show()
            }
        }
        return "Opening YouTube for: $query"
    }

    private fun handlePhoneCall(args: Map<String, Any>): String {
        val name = args["name"] as? String ?: return "Please specify who to call."
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return "Contacts permission required to make calls."
        }
        var number: String? = null
        val cursor = contentResolver.query(
            android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            null
        )
        cursor?.use { if (it.moveToFirst()) number = it.getString(0) }

        runOnUiThread {
            if (number != null) {
                val uri = android.net.Uri.parse("tel:$number")
                val intent = if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                    Intent(Intent.ACTION_CALL, uri)
                } else {
                    Intent(Intent.ACTION_DIAL, uri)
                }
                startActivity(intent)
            } else {
                startActivity(Intent(Intent.ACTION_DIAL))
                Toast.makeText(this, "Contact '$name' not found", Toast.LENGTH_SHORT).show()
            }
        }
        return if (number != null) "Calling $name." else "Contact not found, opening dial pad."
    }

    private fun handleDirections(args: Map<String, Any>): String {
        val destination = args["destination"] as? String ?: return "Please specify a destination."
        val mode = (args["mode"] as? String ?: "").lowercase()
        runOnUiThread {
            val uri = when {
                "train" in mode -> android.net.Uri.parse("https://www.irctc.co.in")
                "bus" in mode -> android.net.Uri.parse("https://www.redbus.in")
                "flight" in mode || "plane" in mode ->
                    android.net.Uri.parse("https://www.google.com/flights?q=flights+to+${java.net.URLEncoder.encode(destination, "UTF-8")}")
                else ->
                    android.net.Uri.parse("https://maps.google.com/maps?daddr=${java.net.URLEncoder.encode(destination, "UTF-8")}")
            }
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        }
        return "Opening directions to $destination."
    }

    private fun handleWeather(args: Map<String, Any>): String {
        val location = args["location"] as? String ?: args["city"] as? String ?: return "Please specify a location."
        return try {
            val encoded = java.net.URLEncoder.encode(location, "UTF-8")
            val client = okhttp3.OkHttpClient()
            val request = okhttp3.Request.Builder()
                .url("https://wttr.in/$encoded?format=3")
                .build()
            val response = client.newCall(request).execute()
            response.body?.string()?.trim() ?: "Could not get weather for $location."
        } catch (e: Exception) {
            "Could not retrieve weather: ${e.message}"
        }
    }

    private fun handleWebSearch(args: Map<String, Any>): String {
        val query = args["query"] as? String ?: return "Please specify a search query."
        return try {
            kotlinx.coroutines.runBlocking { LocalToolHandlers.webSearchInstant(query) }
        } catch (e: Exception) {
            "Search failed: ${e.message}"
        }
    }

    private fun handleDefineWord(args: Map<String, Any>): String {
        val word = args["word"] as? String ?: return "Please specify a word."
        return try {
            kotlinx.coroutines.runBlocking { LocalToolHandlers.dictionaryLookup(word) }
        } catch (e: Exception) {
            "Definition not found for: $word"
        }
    }

    private fun handleWikiSummary(args: Map<String, Any>): String {
        val topic = args["topic"] as? String ?: return "Please specify a topic."
        return try {
            kotlinx.coroutines.runBlocking { LocalToolHandlers.wikiSummary(topic) }
        } catch (e: Exception) {
            "Could not retrieve Wikipedia summary for: $topic"
        }
    }

    private fun handleStockQuote(args: Map<String, Any>): String {
        val symbol = args["symbol"] as? String ?: args["ticker"] as? String ?: return "Please specify a stock symbol."
        return try {
            kotlinx.coroutines.runBlocking { LocalToolHandlers.stockQuote(symbol) }
        } catch (e: Exception) {
            "Could not retrieve stock price for: $symbol"
        }
    }

    private fun handleMuteAi(): String {
        runOnUiThread { toggleAiMute() }
        return "AI muted."
    }

    private fun handleReadNotifications(): String {
        if (!isNotificationListenerEnabled()) return "Notification access not enabled."
        return try {
            val notifications = com.sdk.glassessdksample.NotificationListener.getRecentNotifications(applicationContext)
            if (notifications.isEmpty()) "No recent notifications."
            else "Recent notifications: ${notifications.take(5).joinToString("; ") { "${it.title}: ${it.text}" }}"
        } catch (e: Exception) {
            "Could not read notifications."
        }
    }

    private fun handleSayGoodbye(): String {
        runOnUiThread { stopConversation() }
        return "Goodbye! Wake me with 'Hey IMI' anytime."
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    private fun isSingleShotMode(): Boolean {
        val prefs = getSharedPreferences("imi_prefs", Context.MODE_PRIVATE)
        return prefs.getString(PREF_VOICE_MODE, VOICE_MODE_SEAMLESS) == VOICE_MODE_SINGLE_SHOT
    }

    private fun checkAndRequestPermissions() {
        val needed = mutableListOf<String>()
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS
        )
        // Android 12+ gates ALL Bluetooth queries behind BLUETOOTH_CONNECT. Without
        // it, getProfileConnectionState() reports DISCONNECTED and getConnectedDevices()
        // throws — so the BLE gate showed "no device connected" even with the glasses
        // plainly connected. (Mark 2 already requested this; Mark 1 never did.)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        perms.forEach {
            if (ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED) {
                needed.add(it)
            }
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    /**
     * Re-run the BLE gate once permissions come back. Granting BLUETOOTH_CONNECT is
     * what makes the glasses actually visible to us, so without this the gate would
     * stay stuck on "no device connected" until the user restarted the app.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            val btGranted = permissions.indices.any { i ->
                permissions[i] == Manifest.permission.BLUETOOTH_CONNECT &&
                    grantResults.getOrNull(i) == PackageManager.PERMISSION_GRANTED
            }
            if (btGranted) {
                Log.i(TAG, "BLUETOOTH_CONNECT granted — re-checking glasses connection")
                checkBleAndShowGate()
            }
        }
    }
}
