package com.sdk.glassessdksample
import com.sdk.glassessdksample.ui.AiResponsePrefs
import com.sdk.glassessdksample.ui.BluetoothEvent
import com.sdk.glassessdksample.ui.DeviceType
import com.sdk.glassessdksample.ui.DevicePreferenceManager
import com.sdk.glassessdksample.ui.GeminiLiveService
import com.sdk.glassessdksample.ui.HotHelper
import com.sdk.glassessdksample.ui.LocalToolHandlers
import com.sdk.glassessdksample.ui.Mark1MainActivity
import com.sdk.glassessdksample.ui.QuickNote
import com.sdk.glassessdksample.ui.QuickNotesManager
import com.sdk.glassessdksample.ui.SongIdentifier
import com.sdk.glassessdksample.ui.UserMemoryManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class ListeningService : Service() {
    companion object {
        const val ACTION_STOP = "com.sdk.ACTION_STOP_LISTENING"
        const val ACTION_WAKE_WORD_DETECTED = "com.sdk.glassessdksample.ACTION_WAKE_WORD_DETECTED"
        /** Ends an in-progress background conversation but keeps wake-word listening. */
        const val ACTION_STOP_BG_CONVERSATION = "com.sdk.ACTION_STOP_BG_CONVERSATION"
        private const val TAG = "ListeningService"
        private const val CHANNEL_ID = "imi_listening_channel"
        private const val NOTIF_ID = 1001
        // Separate high-importance channel + id for the full-screen "wake" alert
        // that brings the app forward when the phone is locked / screen off.
        private const val WAKE_CHANNEL_ID = "imi_wake_alert_channel"
        private const val WAKE_NOTIF_ID = 1002

        /**
         * Length of res/raw/wake_chime.wav: 414,032 data bytes at 44.1kHz stereo
         * 16-bit (176,400 B/s) = 2,347 ms, rounded up. Every delay that must outlast
         * the chime is derived from this, so swapping the file only needs one edit.
         */
        private const val CHIME_DURATION_MS = 2_350L

        /**
         * Settle time before wake-word detection is re-armed. MUST exceed
         * [CHIME_DURATION_MS]: the detector plays its own copy of the chime on
         * detection, so re-arming sooner lets a freshly-armed detector hear the
         * tail of that chime and fire again — the self-trigger loop this delay
         * exists to prevent. Was 1_500, which was correct only while the chime was
         * a ~200ms ToneGenerator beep.
         */
        private const val REARM_DELAY_MS = CHIME_DURATION_MS + 650

        /** How often the deferred arm re-checks whether vision has finished. */
        private const val VISION_ARM_POLL_MS = 1_000L

        /**
         * Whether wake-word listening *should* be active. Kept in the companion so it
         * survives the OS re-creating this START_STICKY service: on a sticky restart
         * we must not re-arm the detector while a conversation owns the mic.
         */
        @Volatile
        private var wakeWordEnabled = false

        /**
         * True while a conversation started by the background service is running.
         * The Activity checks this on resume so that turning the screen on mid-chat
         * doesn't tear down an in-progress conversation.
         */
        @Volatile
        private var bgConversationActive = false

        /** Public: is a background (phone-locked) conversation currently running? */
        @JvmStatic
        fun isBackgroundConversationActive(): Boolean = bgConversationActive
    }

    private var wakeLock: PowerManager.WakeLock? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var chimePlayer: MediaPlayer? = null
    private var bgGeminiService: GeminiLiveService? = null

    // Single-reply mode (Continuous Chat OFF): set once the AI has answered a real
    // user turn, so the session closes as soon as that reply finishes playing and
    // we go back to waiting for "Hey IMI".
    private var endSessionAfterPlayback = false
    // Backstop for the above, in case onAudioPlaybackEnd() never fires.
    private var endSessionFallback: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // foregroundServiceType="microphone": startForeground() throws on
        // Android 14+ if RECORD_AUDIO isn't granted. Guard so a stray start (OS
        // restart of a sticky service, or a start before the user granted the
        // mic) can never crash the app.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "⏹️ RECORD_AUDIO not granted — cannot run microphone FGS, stopping self")
            // We were launched via startForegroundService(), so the OS requires a
            // startForeground() call within ~5s even on this bail-out path —
            // stopSelf() alone throws ForegroundServiceDidNotStartInTimeException
            // and kills the process (see Mark1MainActivity.startWakeWordListening).
            // Post the notification to satisfy that contract, then immediately stop.
            // On Android 14+ this itself throws SecurityException because the
            // declared type is "microphone" and RECORD_AUDIO is exactly what we are
            // missing — but the attempt still clears the pending-FGS obligation, so
            // we swallow it and stop cleanly instead of crashing.
            try {
                startForeground(NOTIF_ID, buildNotification())
            } catch (e: Exception) {
                Log.w(TAG, "startForeground rejected on permission bail-out: ${e.message}")
            }
            stopSelf()
            return
        }

        try {
            startForeground(NOTIF_ID, buildNotification())

            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "HeyIMI::ListeningWakeLock"
            )
            wakeLock?.acquire()

            // Subscribe to wake word events so we can forward them to MainActivity even
            // when the Activity is stopped (minimised / screen off).
            EventBus.getDefault().register(this)
        } catch (e: Exception) {
            Log.e(TAG, "❌ startForeground failed — stopping self: ${e.message}", e)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            wakeWordEnabled = false
            endBackgroundConversation()
            try { HotHelper.getInstance(applicationContext).stop() } catch (_: Exception) {}
            wakeLock?.let { if (it.isHeld) it.release() }
            stopSelf()
            return START_NOT_STICKY
        }

        // The Activity took over (or the user tapped Stop): end the background
        // conversation but keep the service alive for wake-word listening.
        if (intent?.action == ACTION_STOP_BG_CONVERSATION) {
            endBackgroundConversation()
            return START_STICKY
        }

        // A null intent means the OS re-created a START_STICKY service. Do NOT
        // blindly restart the detector in that case: if a conversation is in
        // progress the mic belongs to Gemini Live, and re-arming here would let the
        // AI's own voice (or the wake chime) re-trigger "Hey IMI", which stopped the
        // conversation and replayed the chime in a loop.
        if (intent == null && !wakeWordEnabled) {
            Log.i(TAG, "Sticky restart with no intent and wake word disabled — not re-arming")
            return START_STICKY
        }

        wakeWordEnabled = true

        // 👁️ Vision owns the audio route right now. Arming the detector here
        // starts a phone-mic recorder against the MODE_IN_COMMUNICATION session
        // Gemini Live is holding, which re-routes Bluetooth and silences the
        // spoken vision result. Defer instead of dropping — the caller genuinely
        // does want listening on, just not this instant.
        if (MainActivity.visionBusy) {
            Log.i(TAG, "👁️ Vision analysis in progress — deferring wake-word arm")
            scheduleArmAfterVision()
            return START_STICKY
        }

        try { HotHelper.getInstance(applicationContext).start() } catch (_: Exception) {}

        return START_STICKY
    }

    /**
     * Poll until the vision window closes, then arm the detector. Terminates
     * either way: MainActivity.visionBusy has its own hard timeout
     * (VISION_BUSY_TIMEOUT_MS), so this can never spin forever.
     */
    private fun scheduleArmAfterVision() {
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                if (!wakeWordEnabled || bgConversationActive) {
                    Log.d(TAG, "Deferred wake-word arm abandoned (enabled=$wakeWordEnabled bgChat=$bgConversationActive)")
                    return
                }
                if (MainActivity.visionBusy) {
                    mainHandler.postDelayed(this, VISION_ARM_POLL_MS)
                    return
                }
                try { HotHelper.getInstance(applicationContext).start() } catch (_: Exception) {}
                Log.i(TAG, "🔁 Wake word armed now that the vision window has closed")
            }
        }, VISION_ARM_POLL_MS)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        EventBus.getDefault().unregister(this)
        wakeLock?.let { if (it.isHeld) it.release() }
        mainHandler.removeCallbacksAndMessages(null)
        try { bgGeminiService?.stopLiveConversation() } catch (_: Exception) {}
        bgGeminiService = null
        bgConversationActive = false
        chimePlayer?.release()
        chimePlayer = null
    }

    /**
     * Receive HotHelper's wake-word event on the background thread.
     * If MainActivity is alive it will also receive this (it subscribes in onStart/onStop).
     * But when it's stopped we bring it back to the foreground here.
     */
    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    fun onBluetoothEvent(event: BluetoothEvent) {
        if (event.type == BluetoothEvent.EventType.VOICE_TEXT) {
            val text = event.data as? String ?: return
            if (text.trim().lowercase() == "wake up") {
                // 🆕 Vision capture/analysis owns the mic and the Gemini session
                // right now. Starting a conversation here (foreground activity or
                // background) would tear down the session that is about to speak
                // the vision answer. Drop the wake word entirely.
                if (MainActivity.visionBusy) {
                    Log.i(TAG, "👁️ Ignoring wake word: vision analysis in progress")
                    return
                }

                val targetActivity = if (DevicePreferenceManager.getDeviceType(applicationContext) == DeviceType.MARK1) {
                    Mark1MainActivity::class.java
                } else {
                    MainActivity::class.java
                }
                // The detector auto-stops on detection; mark listening as disabled so
                // a sticky restart can't re-arm it while the conversation owns the
                // mic (that caused the AI's own voice / the chime to re-trigger
                // "Hey IMI" in a loop).
                wakeWordEnabled = false

                Log.i(TAG, "🔥 Wake word received in ListeningService")

                // Do NOT force the phone open. The conversation runs through the
                // glasses, so the phone stays exactly as the user left it (locked,
                // screen off). We only bring the Activity forward if the app is
                // ALREADY in the foreground — then it's just a normal in-app flow.
                if (isAppInForeground()) {
                    val activityIntent = Intent(applicationContext, targetActivity).apply {
                        action = ACTION_WAKE_WORD_DETECTED
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    try {
                        startActivity(activityIntent)
                    } catch (e: Exception) {
                        // The Activity never came up, so nothing will take over the
                        // mic — re-arm here or the wake word stays dead until the
                        // service restarts ("the AI stopped answering").
                        Log.w(TAG, "startActivity skipped: ${e.message} — re-arming wake word")
                        rearmWakeWord("startActivity failed")
                    }
                } else {
                    Log.i(TAG, "📵 App not in foreground — starting conversation in background (phone stays locked)")
                    startBackgroundConversation()
                }
            }
        }
    }

    /**
     * Runs the full "Hey IMI" conversation from this background service, so the
     * phone is never forced open. Audio goes to/from the glasses over Bluetooth
     * SCO exactly as it does in-app. When the conversation ends we re-arm the
     * wake word so the user can say "Hey IMI" again.
     */
    private fun startBackgroundConversation() {
        if (bgConversationActive) {
            // onBluetoothEvent already disarmed the detector, and the running
            // conversation won't re-arm it on our behalf — re-arm here so a
            // duplicate wake event can't leave the assistant permanently deaf.
            Log.w(TAG, "Background conversation already active — ignoring, re-arming wake word")
            rearmWakeWord("duplicate wake event")
            return
        }
        bgConversationActive = true

        // 🔂 Honour the SAME "Continuous Chat" setting the in-app flow uses. With it
        // OFF the session must answer ONCE and go back to the wake word, so we also
        // skip the spoken greeting — otherwise "Hi, how can I help?" would burn the
        // single reply before the user has asked anything. (This is exactly what
        // MainActivity.proceedWithGeminiLive does; the background path used to
        // hardcode greetOnStart = true and never end the session, which is why the
        // screen-off assistant always behaved like continuous mode.)
        val continuousChat = isContinuousChatEnabled()
        Log.i(TAG, "🔂 Background conversation mode: ${if (continuousChat) "CONTINUOUS" else "SINGLE-REPLY"}")

        mainHandler.post {
            playWakeChime {
                try {
                    val svc = GeminiLiveService(applicationContext, backgroundCallbacks)
                    bgGeminiService = svc
                    svc.startLiveConversation(
                        buildBackgroundSystemInstruction(),
                        greetOnStart = continuousChat
                    )
                    Log.i(TAG, "🎙️ Background conversation started (phone stays locked)")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start background conversation: ${e.message}", e)
                    endBackgroundConversation()
                }
            }
        }
    }

    /** Reads the same pref the Settings switch and MainActivity use. */
    private fun isContinuousChatEnabled(): Boolean =
        applicationContext
            .getSharedPreferences("imi_prefs", Context.MODE_PRIVATE)
            .getBoolean("continuous_chat", MainActivity.CONTINUOUS_CHAT_DEFAULT)

    /**
     * Arms the single-reply shutdown for the background session: it closes as soon
     * as the current reply finishes playing, so we fall back to "Hey IMI".
     *
     * Mirrors MainActivity.endSessionAfterCurrentReply — onAudioPlaybackEnd() is the
     * normal path and the posted task is the backstop for when that callback never
     * arrives (text-only reply, dropped playback signal). Whichever runs first
     * cancels the other.
     */
    private fun endBackgroundSessionAfterCurrentReply(reason: String) {
        endSessionAfterPlayback = true
        endSessionFallback?.let { mainHandler.removeCallbacks(it) }
        val fallback = Runnable {
            if (endSessionAfterPlayback) {
                endSessionAfterPlayback = false
                endSessionFallback = null
                Log.d(TAG, "🔂 $reason — playback-end never arrived, force-stopping")
                endBackgroundConversation()
            }
        }
        endSessionFallback = fallback
        mainHandler.postDelayed(fallback, 10_000)
    }

    /** Ends the background conversation and re-arms wake-word listening. */
    private fun endBackgroundConversation() {
        if (!bgConversationActive) return
        bgConversationActive = false
        // Drop any armed single-reply shutdown, or a stale fallback from this
        // session would fire during the NEXT conversation and cut it short.
        endSessionAfterPlayback = false
        endSessionFallback?.let { mainHandler.removeCallbacks(it) }
        endSessionFallback = null
        try { bgGeminiService?.stopLiveConversation() } catch (_: Exception) {}
        bgGeminiService = null

        rearmWakeWord("background conversation ended")
    }

    /**
     * Re-arms wake-word detection after a settle delay, so the tail of the AI's
     * audio (or the chime) can't immediately re-trigger "Hey IMI".
     *
     * Every path that disarms the detector MUST end at this method. onBluetoothEvent
     * sets wakeWordEnabled = false as soon as the wake word fires, so any path that
     * then fails to hand the mic to a conversation would otherwise leave the
     * assistant permanently deaf — which is what made the AI "sometimes stop
     * answering" until the app was reopened.
     */
    private fun rearmWakeWord(reason: String) {
        mainHandler.postDelayed({
            if (!bgConversationActive) {
                wakeWordEnabled = true
                try { HotHelper.getInstance(applicationContext).start() } catch (_: Exception) {}
                Log.i(TAG, "🔁 Wake word re-armed ($reason)")
            }
        }, REARM_DELAY_MS)
    }

    /** Plays the wake chime through the current audio route, then invokes [then]. */
    private fun playWakeChime(then: () -> Unit) {
        val fired = java.util.concurrent.atomic.AtomicBoolean(false)
        val once = { if (fired.compareAndSet(false, true)) then() }
        // Backstop so a failed chime can never swallow the conversation start. Must
        // stay LONGER than the chime itself, or it fires every time and the session
        // starts while the chime is still sounding — feeding the chime straight into
        // the live mic. It was 1_200 against a ~1s chime; wake_chime.wav is 2_350.
        mainHandler.postDelayed({ once() }, CHIME_DURATION_MS + 250)
        try {
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                val afd = resources.openRawResourceFd(R.raw.wake_chime)
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                setOnCompletionListener { p -> p.release(); once() }
                setOnErrorListener { p, _, _ -> p.release(); once(); true }
                prepare()
                start()
            }
            chimePlayer?.release()
            chimePlayer = mp
        } catch (e: Exception) {
            Log.w(TAG, "Background chime failed: ${e.message}")
            once()
        }
    }

    /** Minimal user/notes context so the background AI behaves like the in-app one. */
    private fun buildBackgroundSystemInstruction(): String {
        val sb = StringBuilder()
        sb.append("You are imi glass, an intelligent AI assistant built into smart glasses. ")
        sb.append(AiResponsePrefs.buildResponseStyleInstruction(applicationContext)).append(' ')
        sb.append("Be helpful and conversational. Keep replies short and spoken-friendly.\n\n")
        try {
            val memory = UserMemoryManager(applicationContext).getUserMemory()
            if (!memory.isEmpty()) {
                sb.append("## User Profile\n")
                if (memory.userName.isNotBlank()) sb.append("Name: ${memory.userName}\n")
                if (memory.occupation.isNotBlank()) sb.append("Occupation: ${memory.occupation}\n")
                if (memory.location.isNotBlank()) sb.append("Location: ${memory.location}\n")
                if (memory.interests.isNotEmpty()) sb.append("Interests: ${memory.interests.joinToString(", ")}\n")
                if (memory.autoLearnedFacts.isNotEmpty()) {
                    sb.append("Known facts: ${memory.autoLearnedFacts.takeLast(10).joinToString("; ")}\n")
                }
                sb.append('\n')
            }
            val notes = QuickNotesManager(applicationContext).getNotesContextForAI()
            if (notes.isNotBlank()) sb.append("## User's Quick Notes\n$notes\n\n")
        } catch (e: Exception) {
            Log.w(TAG, "Could not build full background context: ${e.message}")
        }
        return sb.toString()
    }

    /** Callbacks for the background conversation — no UI, just lifecycle handling. */
    private val backgroundCallbacks = object : GeminiLiveService.GeminiLiveCallbacks {
        override fun onTranscriptionUpdate(input: String, output: String, isFinal: Boolean) {}

        override fun onTurnComplete(fullInput: String, fullOutput: String) {
            // Let the user say goodbye to end the session, mirroring the in-app flow.
            val said = fullInput.lowercase()
            if (said.contains("goodbye") || said.contains("good bye") || said.contains("bye imi") ||
                said.contains("band karo") || said.contains("alvida") || said.contains("stop listening")
            ) {
                Log.i(TAG, "👋 Goodbye detected in background conversation — ending")
                endBackgroundConversation()
                return
            }

            // 🔂 Continuous Chat OFF → answer once, then go back to the wake word.
            // The isNotEmpty guard matters: a model turn with no user speech (a
            // stray/empty turn, or a greeting) must not consume the single reply.
            // The actual stop happens in onAudioPlaybackEnd() so the reply is heard
            // in full instead of being cut off mid-sentence.
            if (!isContinuousChatEnabled() && fullInput.trim().isNotEmpty()) {
                Log.i(TAG, "🔂 Continuous Chat off — ending background session once this reply finishes")
                endBackgroundSessionAfterCurrentReply("Continuous Chat off")
            }
        }

        override fun onToolCall(toolName: String, args: Map<String, Any>): String {
            Log.d(TAG, "Background tool call: $toolName args=$args")
            return handleBackgroundTool(toolName, args)
        }

        override fun onAudioPlaybackStart() {}

        override fun onAudioPlaybackEnd() {
            // Single-reply mode: the answer has now been spoken in full, so close
            // the session and hand control back to the wake word.
            if (endSessionAfterPlayback) {
                endSessionAfterPlayback = false
                endSessionFallback?.let { mainHandler.removeCallbacks(it) }
                endSessionFallback = null
                Log.i(TAG, "🔂 Reply finished — closing background session, back to 'Hey IMI'")
                endBackgroundConversation()
            }
        }

        override fun onError(error: String) {
            Log.e(TAG, "Background conversation error: $error")
            endBackgroundConversation()
        }

        override fun onConnectionStatusChanged(isConnected: Boolean) {
            if (!isConnected) {
                Log.i(TAG, "Background conversation disconnected")
                endBackgroundConversation()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BACKGROUND TOOL HANDLERS
    //
    // These mirror Mark1MainActivity's tool handlers so the assistant behaves the
    // SAME with the phone locked as it does with the app open. Everything here
    // works from a Service context — no Activity required.
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleBackgroundTool(toolName: String, args: Map<String, Any>): String {
        return try {
            when (toolName) {
                "get_weather" -> bgWeather(args)
                "web_search" -> bgWebSearch(args)
                "define_word" -> bgDefineWord(args)
                "wiki_summary" -> bgWikiSummary(args)
                "stock_quote" -> bgStockQuote(args)
                "get_news" -> bgWebSearch(mapOf("query" to (args["topic"] as? String ?: "top news today")))
                "create_note" -> bgCreateNote(args)
                "make_phone_call" -> bgPhoneCall(args)
                "get_directions", "open_maps" -> bgDirections(args)
                "read_notifications" -> bgReadNotifications()
                "send_message" -> bgSendMessage(args)
                "read_emails" -> bgReadEmails()
                "identify_song" -> bgIdentifySong()
                "say_goodbye" -> { endBackgroundConversation(); "Goodbye!" }
                "mute_ai" -> { endBackgroundConversation(); "Muting now." }
                // Genuinely need the on-screen app (camera preview, meeting UI, media UI).
                "start_meeting", "play_music", "play_youtube", "take_photo", "capture_photo_note" ->
                    "That one needs the IMI app open on your phone."
                else -> "Tool $toolName not yet implemented."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Background tool '$toolName' failed: ${e.message}", e)
            "Sorry, that didn't work: ${e.message}"
        }
    }

    private fun bgWeather(args: Map<String, Any>): String {
        val location = args["location"] as? String ?: args["city"] as? String
            ?: return "Please specify a location."
        return try {
            val encoded = java.net.URLEncoder.encode(location, "UTF-8")
            val client = okhttp3.OkHttpClient()
            val request = okhttp3.Request.Builder().url("https://wttr.in/$encoded?format=3").build()
            client.newCall(request).execute().use { resp ->
                resp.body?.string()?.trim() ?: "Could not get weather for $location."
            }
        } catch (e: Exception) {
            "Could not retrieve weather: ${e.message}"
        }
    }

    /**
     * 🎵 Shazam-style song ID. Works screen-off: it reuses the PCM already being
     * captured for this background conversation, so there's no camera, no UI and
     * no second recorder involved — unlike play_music, which does need the app open.
     */
    private fun bgIdentifySong(): String {
        return try {
            kotlinx.coroutines.runBlocking { SongIdentifier.identifyFromLiveSession() }
        } catch (e: Exception) {
            Log.e(TAG, "Song identification failed: ${e.message}", e)
            "I couldn't identify that song right now."
        }
    }

    private fun bgWebSearch(args: Map<String, Any>): String {
        val query = args["query"] as? String ?: return "Please specify a search query."
        return try {
            kotlinx.coroutines.runBlocking { LocalToolHandlers.webSearchInstant(query) }
        } catch (e: Exception) {
            "Search failed: ${e.message}"
        }
    }

    private fun bgDefineWord(args: Map<String, Any>): String {
        val word = args["word"] as? String ?: return "Please specify a word."
        return try {
            kotlinx.coroutines.runBlocking { LocalToolHandlers.dictionaryLookup(word) }
        } catch (e: Exception) {
            "Definition not found for: $word"
        }
    }

    private fun bgWikiSummary(args: Map<String, Any>): String {
        val topic = args["topic"] as? String ?: return "Please specify a topic."
        return try {
            kotlinx.coroutines.runBlocking { LocalToolHandlers.wikiSummary(topic) }
        } catch (e: Exception) {
            "Could not retrieve Wikipedia summary for: $topic"
        }
    }

    private fun bgStockQuote(args: Map<String, Any>): String {
        val symbol = args["symbol"] as? String ?: args["ticker"] as? String
            ?: return "Please specify a stock symbol."
        return try {
            kotlinx.coroutines.runBlocking { LocalToolHandlers.stockQuote(symbol) }
        } catch (e: Exception) {
            "Could not retrieve stock price for: $symbol"
        }
    }

    private fun bgCreateNote(args: Map<String, Any>): String {
        val title = args["title"] as? String ?: "Note"
        val content = args["content"] as? String ?: ""
        return try {
            QuickNotesManager(applicationContext)
                .createNote(title, content, QuickNote.CreatedBy.AI)
            "Note saved successfully."
        } catch (e: Exception) {
            "Could not save the note: ${e.message}"
        }
    }

    /**
     * Places a call with the phone locked. ACTION_CALL dials directly (needs
     * CALL_PHONE); otherwise we fall back to ACTION_DIAL. FLAG_ACTIVITY_NEW_TASK is
     * required to launch from a Service.
     */
    private fun bgPhoneCall(args: Map<String, Any>): String {
        val name = args["name"] as? String ?: args["contact"] as? String
            ?: return "Please tell me who to call."

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return "I need contacts permission — please open the IMI app once to grant it."
        }

        var number: String? = null
        var matched: String? = null
        try {
            contentResolver.query(
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER,
                    android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                ),
                "${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$name%"),
                null
            )?.use { c ->
                if (c.moveToFirst()) {
                    number = c.getString(0)
                    matched = c.getString(1)
                }
            }
        } catch (e: Exception) {
            return "Could not look up $name in contacts."
        }

        val num = number ?: return "I couldn't find $name in your contacts."

        return try {
            val canCallDirectly = ContextCompat.checkSelfPermission(
                this, Manifest.permission.CALL_PHONE
            ) == PackageManager.PERMISSION_GRANTED
            val intent = Intent(
                if (canCallDirectly) Intent.ACTION_CALL else Intent.ACTION_DIAL,
                android.net.Uri.parse("tel:$num")
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            startActivity(intent)
            // The call takes over the audio route, so end our session cleanly.
            endBackgroundConversation()
            if (canCallDirectly) "Calling ${matched ?: name}." else "Opening the dialer for ${matched ?: name}."
        } catch (e: Exception) {
            "I couldn't start the call: ${e.message}"
        }
    }

    private fun bgDirections(args: Map<String, Any>): String {
        val destination = args["destination"] as? String ?: args["location"] as? String
            ?: return "Where do you want directions to?"
        return try {
            val uri = android.net.Uri.parse(
                "google.navigation:q=${java.net.URLEncoder.encode(destination, "UTF-8")}"
            )
            startActivity(
                Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage("com.google.android.apps.maps")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            "Starting directions to $destination."
        } catch (e: Exception) {
            "I couldn't open navigation for $destination."
        }
    }

    /** Sends an SMS to a contact by name, with the phone still locked. */
    private fun bgSendMessage(args: Map<String, Any>): String {
        val name = args["name"] as? String ?: args["contact"] as? String
            ?: return "Who should I message?"
        val body = args["message"] as? String ?: args["text"] as? String
            ?: return "What should the message say?"

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return "I need contacts permission — please open the IMI app once to grant it."
        }

        var number: String? = null
        try {
            contentResolver.query(
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$name%"),
                null
            )?.use { c -> if (c.moveToFirst()) number = c.getString(0) }
        } catch (e: Exception) {
            return "Could not look up $name in contacts."
        }
        val num = number ?: return "I couldn't find $name in your contacts."

        return if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                val sms = android.telephony.SmsManager.getDefault()
                sms.sendTextMessage(num, null, body, null, null)
                "Message sent to $name."
            } catch (e: Exception) {
                "I couldn't send the message: ${e.message}"
            }
        } else {
            "I need SMS permission — please open the IMI app once to grant it."
        }
    }

    /** Reads recent emails. Bridges GmailService's callback API to a blocking result. */
    private fun bgReadEmails(): String {
        return try {
            val latch = java.util.concurrent.CountDownLatch(1)
            val result = java.util.concurrent.atomic.AtomicReference("Could not read your emails.")
            GmailService(applicationContext).getRecentEmails(5) { summary ->
                result.set(summary)
                latch.countDown()
            }
            if (!latch.await(12, java.util.concurrent.TimeUnit.SECONDS)) {
                "Reading your emails took too long."
            } else {
                result.get()
            }
        } catch (e: Exception) {
            "Could not read your emails: ${e.message}"
        }
    }

    private fun bgReadNotifications(): String {
        return try {
            val notifications = NotificationListener.getRecentNotifications(applicationContext)
            if (notifications.isEmpty()) "No recent notifications."
            else "Recent notifications: " +
                notifications.take(5).joinToString("; ") { "${it.title}: ${it.text}" }
        } catch (e: Exception) {
            "Could not read notifications."
        }
    }

    /** True when our own app currently has a foreground (resumed) Activity. */
    private fun isAppInForeground(): Boolean {
        return try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val procs = am.runningAppProcesses ?: return false
            procs.any {
                it.processName == packageName &&
                    it.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "IMI Listening",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "IMI wake word detection"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)

            // High-importance channel for the full-screen wake alert (required for
            // setFullScreenIntent to actually launch the UI from a locked screen).
            val wakeChannel = NotificationChannel(
                WAKE_CHANNEL_ID,
                "IMI Wake Alert",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Brings IMI forward when the wake word is detected"
                setShowBadge(false)
                setSound(null, null)
            }
            nm.createNotificationChannel(wakeChannel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, ListeningService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("IMI is listening")
            .setContentText("Say \"Hey IMI\" to start")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .addAction(android.R.drawable.ic_delete, "Stop", stopIntent)
            .build()
    }
}
