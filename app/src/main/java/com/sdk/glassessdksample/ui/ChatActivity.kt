package com.sdk.glassessdksample.ui

import com.sdk.glassessdksample.RemoteConfigManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.sdk.glassessdksample.BuildConfig
import com.sdk.glassessdksample.R
    import com.sdk.glassessdksample.WakeWordGate
import android.net.wifi.p2p.WifiP2pDevice
import com.sdk.glassessdksample.ui.wifi.AlbumDownloader
import com.sdk.glassessdksample.ui.wifi.WifiP2pHelper
import com.sdk.glassessdksample.utils.SafeBleCommandHelper
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyListener
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyRsp
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.sdk.glassessdksample.utils.SystemBarsInsets

/**
 * ChatActivity - Unified seamless chat experience
 * 
 * FEATURES:
 * 1. UNIFIED CHAT HISTORY - Text + images in one continuous thread
 * 2. INLINE IMAGE CAPTURE - Capture from glasses without leaving chat
 * 3. MULTI-MODAL CONTEXT - AI remembers all images + conversation history
 * 
 * The AI automatically detects when the user wants vision analysis and 
 * captures images inline, analyzing them on-the-fly without switching screens.
 */
class ChatActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "UnifiedChat"
        private const val MAX_AI_CONTEXT_CHARS = 1000
        private const val MAX_AI_HISTORY_CHARS = 800
        private const val MAX_AI_HISTORY_MESSAGES = 6
        
        // Action to receive images from VisionChatActivity or other sources
        const val ACTION_IMAGE_RECEIVED = "com.sdk.glassessdksample.IMAGE_RECEIVED"
        const val EXTRA_IMAGE_PATH = "image_path"
        const val EXTRA_AUTO_VISION = "auto_vision"
        const val EXTRA_VISION_QUERY = "vision_query"

        private const val REQ_AUDIO_FOR_VOICE = 7401
        private const val MENU_CAPTURE_GLASSES = 9001
        private const val MENU_ATTACH_GALLERY = 9002
    }

    // --- UI Views ---
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: LinearLayout
    private lateinit var rvChatMessages: RecyclerView
    private lateinit var rvConversations: RecyclerView
    private lateinit var etChatInput: EditText
    private lateinit var btnSendMessage: ImageView
    private lateinit var btnMenu: ImageView
    private lateinit var btnNewChat: View
    private lateinit var btnVoiceInput: ImageView
    private lateinit var btnAddAttachment: ImageView
    private lateinit var etSearchConversations: EditText

    // Empty-state landing
    private lateinit var layoutChatEmpty: View
    private lateinit var tvGreetingLine1: TextView
    private lateinit var rvSuggestions: RecyclerView

    // Voice listening UI
    private lateinit var layoutComposer: View
    private lateinit var layoutListening: View
    private lateinit var btnStopListening: ImageView
    private lateinit var voiceWave: VoiceWaveView
    private var speechRecognizer: android.speech.SpeechRecognizer? = null
    private var isListening = false

    // System speech-to-text dialog (reliable fallback / primary on flaky devices)
    private val speechLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        showListeningUi(false)
        if (result.resultCode == RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                .orEmpty()
            if (spoken.isNotBlank()) {
                etChatInput.setText(spoken)
                etChatInput.setSelection(etChatInput.text.length)
                sendMessage()
            }
        }
    }

    // Pick an image from the gallery, copy it locally, then analyze inline.
    private val galleryPickLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        val typed = etChatInput.text.toString().trim()
        val query = if (typed.isNotBlank()) typed else "Describe what you see in this image"
        if (typed.isNotBlank()) etChatInput.setText("")
        lifecycleScope.launch {
            val path = withContext(Dispatchers.IO) { copyUriToCache(uri) }
            if (path != null) {
                handleInlineImageCapture(path, query)
            } else {
                Toast.makeText(this@ChatActivity, "Could not load image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun copyUriToCache(uri: android.net.Uri): String? {
        return try {
            val dir = File(cacheDir, "vision_images").apply { mkdirs() }
            val outFile = File(dir, "picked_${System.currentTimeMillis()}.jpg")
            contentResolver.openInputStream(uri)?.use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            }
            if (outFile.exists() && outFile.length() > 0) outFile.absolutePath else null
        } catch (e: Exception) {
            Log.e(TAG, "copyUriToCache failed", e)
            null
        }
    }

    // Action chips
    private lateinit var chipCreateImage: TextView
    private lateinit var chipSummarize: TextView
    private lateinit var chipDeepThinking: TextView

    // --- Adapters ---
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var conversationAdapter: ConversationAdapter
    
    // --- Data ---
    private val conversations = mutableListOf<Conversation>()
    private var currentConversation: Conversation? = null
    
    // --- Services ---
    private lateinit var prefs: SharedPreferences
    private lateinit var geminiClient: GeminiAIClient
    private lateinit var conversationMemory: ConversationMemory
    private lateinit var albumDownloader: AlbumDownloader
    private lateinit var memoryManager: UserMemoryManager  // Auto-learning AI memory
    private var visionModel: GenerativeModel? = null
    
    private val gson = Gson()
    
    // --- Vision State ---
    private var isCapturingImage = false
    private var lastCapturedImagePath: String? = null
    private var lastCapturedBitmap: Bitmap? = null
    private var cachedGlassIp: String? = null

    // Active WiFi P2P connection — kept alive during the whole HTTP download,
    // then torn down. Tearing it down too early drops the P2P group and the
    // phone falls back to the home router, breaking the transfer.
    private var activeWifiP2pHelper: WifiP2pHelper? = null
    
    // --- Broadcast receiver for images from other activities ---
    private val imageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val imagePath = intent?.getStringExtra(EXTRA_IMAGE_PATH) ?: return
            val query = intent.getStringExtra(EXTRA_VISION_QUERY) ?: "Describe what you see"
            lifecycleScope.launch {
                handleInlineImageCapture(imagePath, query)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)
        SystemBarsInsets.apply(this)
        
        initViews()
        initServices()
        loadConversations()
        setupRecyclerViews()
        setupDrawer()
        setupClickListeners()
        setupBottomNav()
        registerImageReceiver()
        
        // Create or load first conversation
        if (conversations.isEmpty()) {
            createNewConversation()
        } else {
            switchToConversation(conversations[0])
        }
        
        // Handle incoming vision intent
        handleVisionIntent(intent)
    }
    
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleVisionIntent(it) }
    }

    private fun initViews() {
        drawerLayout = findViewById(R.id.drawerLayout)
        navigationView = findViewById(R.id.navigationView)
        rvChatMessages = findViewById(R.id.rvChatMessages)
        rvConversations = findViewById(R.id.rvConversations)
        etChatInput = findViewById(R.id.etChatInput)
        btnSendMessage = findViewById(R.id.btnSendMessage)
        btnMenu = findViewById(R.id.btnMenu)
        btnNewChat = findViewById(R.id.btnNewChat)
        btnVoiceInput = findViewById(R.id.btnVoiceInput)
        etSearchConversations = findViewById(R.id.etSearchConversations)
        btnAddAttachment = findViewById(R.id.btnAddAttachment)
        
        chipCreateImage = findViewById(R.id.chipCreateImage)
        chipSummarize = findViewById(R.id.chipSummarize)
        chipDeepThinking = findViewById(R.id.chipDeepThinking)

        layoutChatEmpty = findViewById(R.id.layoutChatEmpty)
        tvGreetingLine1 = findViewById(R.id.tvGreetingLine1)
        rvSuggestions = findViewById(R.id.rvSuggestions)

        layoutComposer = findViewById(R.id.layoutComposer)
        layoutListening = findViewById(R.id.layoutListening)
        btnStopListening = findViewById(R.id.btnStopListening)
        voiceWave = findViewById(R.id.voiceWave)

        prefs = getSharedPreferences("IMI_CHAT_PREFS", MODE_PRIVATE)

        setupGreetingAndSuggestions()
    }

    /** Greeting uses the stored profile name; suggestions prefill the composer. */
    private fun setupGreetingAndSuggestions() {
        val name = resolveUserFirstName()
        tvGreetingLine1.text = if (name.isNullOrBlank()) "Hey there," else "Hey $name,"

        val suggestions = listOf(
            ChatSuggestion("Schedule a meeting", "Block time and start meeting"),
            ChatSuggestion("Summarize my notes", "Get a quick recap"),
            ChatSuggestion("What can you see?", "Capture and describe from glasses")
        )
        rvSuggestions.layoutManager = LinearLayoutManager(this)
        rvSuggestions.adapter = SuggestionAdapter(suggestions) { suggestion ->
            etChatInput.setText(suggestion.title)
            etChatInput.setSelection(etChatInput.text.length)
            etChatInput.requestFocus()
        }
    }

    /** Reads the user's name from the API session, falling back to legacy prefs. */
    private fun resolveUserFirstName(): String? {
        val session = com.sdk.glassessdksample.auth.SessionManager(this)
        val full = session.userName?.takeIf { it.isNotBlank() }
            ?: getSharedPreferences("user_prefs", MODE_PRIVATE).getString("user_name", null)
        return full?.trim()?.split(" ")?.firstOrNull()?.takeIf { it.isNotBlank() && !it.equals("User", true) }
    }

    /** Show the greeting landing only when the active conversation has no messages. */
    private fun updateEmptyState() {
        // Don't override the listening UI's composer swap.
        if (isListening) return
        val isEmpty = (currentConversation?.messages?.isEmpty() != false)
        layoutChatEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        rvChatMessages.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    // ========================================================================
    // VOICE LISTENING (mic → wavy gradient "Listening" bar)
    // ========================================================================

    private fun startVoiceListening() {
        if (isListening) return

        // Mic permission gate
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.RECORD_AUDIO
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            androidx.core.app.ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_AUDIO_FOR_VOICE
            )
            return
        }

        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Voice recognition not available", Toast.LENGTH_SHORT).show()
            return
        }

        showListeningUi(true)

        speechRecognizer?.destroy()
        speechRecognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : android.speech.RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {
                    // rmsdB roughly -2..10; map to 0f..1f for the wave amplitude.
                    voiceWave.setAmplitude(((rmsdB + 2f) / 12f))
                }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    Log.w(TAG, "Speech onError: $error (${speechErrorMessage(error)})")
                    runOnUiThread {
                        // When the inline recognizer can't hear / mis-binds, fall back
                        // to the system speech dialog which is far more reliable.
                        val recoverable = error == android.speech.SpeechRecognizer.ERROR_NO_MATCH ||
                            error == android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                            error == android.speech.SpeechRecognizer.ERROR_CLIENT ||
                            error == android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                        if (recoverable) {
                            launchSystemSpeechDialog()
                        } else {
                            showListeningUi(false)
                            Toast.makeText(this@ChatActivity, speechErrorMessage(error), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    runOnUiThread {
                        showListeningUi(false)
                        if (text.isNotBlank()) {
                            etChatInput.setText(text)
                            etChatInput.setSelection(etChatInput.text.length)
                            sendMessage()
                        }
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val text = partialResults
                        ?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (!text.isNullOrBlank()) etChatInput.setText(text)
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault().toString())
            putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
            putExtra(android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
            putExtra(android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500)
            // Identify the calling app to the system recognizer (required on many devices).
            putExtra(android.speech.RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        try {
            Log.d(TAG, "🎙️ Voice listening started")
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "startListening failed", e)
            Toast.makeText(this, "Couldn't start voice input", Toast.LENGTH_SHORT).show()
            showListeningUi(false)
        }
    }

    // ========================================================================
    // ADD (+) BUTTON OPTIONS
    // Mark 2 glasses have a camera → offer "Capture from Glasses" so the user
    // can snap a photo and ask a question about it (vision flow).
    // ========================================================================

    private fun showAddOptionsMenu(anchor: View) {
        val deviceType = DevicePreferenceManager.getDeviceType(this)
        val popup = android.widget.PopupMenu(this, anchor)

        if (deviceType == DeviceType.MARK2) {
            popup.menu.add(0, MENU_CAPTURE_GLASSES, 0, "Capture from Glasses")
        }
        popup.menu.add(0, MENU_ATTACH_GALLERY, 1, "Add from Gallery")

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_CAPTURE_GLASSES -> {
                    captureFromGlassesThenAsk()
                    true
                }
                MENU_ATTACH_GALLERY -> {
                    pickImageFromGallery()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    /** Capture a photo from the glasses, then let the user type a question about it. */
    private fun captureFromGlassesThenAsk() {
        if (isCapturingImage) {
            Toast.makeText(this, "Already capturing...", Toast.LENGTH_SHORT).show()
            return
        }
        // Widen the BLE pipe before the image starts moving. At the default MTU a
        // photo takes hundreds of round trips; see BleSpeedTuner.
        BleSpeedTuner.tune(this, "Chat/capture")

        val typed = etChatInput.text.toString().trim()
        val query = if (typed.isNotBlank()) typed else "Describe what you see in detail"
        if (typed.isNotBlank()) etChatInput.setText("")
        captureAndAnalyzeFromGlasses(query)
    }

    /** Pick an image from the device gallery and analyze it inline. */
    private fun pickImageFromGallery() {
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
            }
            galleryPickLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open gallery", Toast.LENGTH_SHORT).show()
        }
    }

    /** Reliable system speech-to-text dialog (Google's own recognizer UI). */
    private fun launchSystemSpeechDialog() {
        try { speechRecognizer?.cancel() } catch (_: Exception) {}
        try { speechRecognizer?.destroy() } catch (_: Exception) {}
        speechRecognizer = null

        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault().toString())
            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Speak now")
            putExtra(android.speech.RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        try {
            speechLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e(TAG, "System speech dialog failed", e)
            showListeningUi(false)
            Toast.makeText(this, "Voice input not available on this device", Toast.LENGTH_SHORT).show()
        }
    }

    private fun speechErrorMessage(error: Int): String = when (error) {
        android.speech.SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
        android.speech.SpeechRecognizer.ERROR_CLIENT -> "Voice client error"
        android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mic permission denied"
        android.speech.SpeechRecognizer.ERROR_NETWORK -> "Network error"
        android.speech.SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
        android.speech.SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that — try again"
        android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy — try again"
        android.speech.SpeechRecognizer.ERROR_SERVER -> "Speech server error"
        android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
        else -> "Voice input error"
    }

    private fun stopVoiceListening(cancelled: Boolean) {
        try {
            if (cancelled) speechRecognizer?.cancel() else speechRecognizer?.stopListening()
        } catch (_: Exception) {}
        showListeningUi(false)
    }

    private fun showListeningUi(listening: Boolean) {
        isListening = listening
        layoutListening.visibility = if (listening) View.VISIBLE else View.GONE
        layoutComposer.visibility = if (listening) View.GONE else View.VISIBLE
        if (listening) {
            voiceWave.start()
        } else {
            voiceWave.stop()
            // Restore correct empty/message visibility.
            updateEmptyState()
        }
    }

    private fun setupBottomNav() {
        val bottomNav = findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNavigation)
        BottomNavManager.setup(bottomNav, R.id.nav_chat, this)
    }

    private fun initServices() {
        geminiClient = GeminiAIClient(
            context = this,
            // Lowest-cost Gemini model for chat, with a fallback to the model we
            // know this key can access (vision already uses gemini-2.5-flash).
            chatModelName = GeminiAIClient.CHAT_MODEL_FLASH_LITE,
            fallbackChatModelName = GeminiAIClient.CHAT_MODEL_FALLBACK
        )
        conversationMemory = ConversationMemory(this)
        albumDownloader = AlbumDownloader(this)
        memoryManager = UserMemoryManager(this)  // Initialise auto-learning memory
        
        // Initialize vision model for image analysis
        try {
            visionModel = GenerativeModel(
                modelName = "gemini-2.5-flash",
                apiKey = RemoteConfigManager.geminiApiKey
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize vision model", e)
        }
        
        // Load cached glass IP
        cachedGlassIp = prefs.getString("cached_glass_ip", null)
    }
    
    private fun registerImageReceiver() {
        val filter = IntentFilter(ACTION_IMAGE_RECEIVED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(imageReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(imageReceiver, filter)
        }
    }
    
    private fun handleVisionIntent(intent: Intent) {
        val imagePath = intent.getStringExtra(EXTRA_IMAGE_PATH)
        val autoVision = intent.getBooleanExtra(EXTRA_AUTO_VISION, false)
        val visionQuery = intent.getStringExtra(EXTRA_VISION_QUERY)
        
        if (imagePath != null) {
            lifecycleScope.launch {
                handleInlineImageCapture(imagePath, visionQuery ?: "Describe what you see in this image")
            }
        } else if (autoVision) {
            lifecycleScope.launch {
                captureAndAnalyzeFromGlasses(visionQuery ?: "What do you see?")
            }
        }
    }

    private fun setupRecyclerViews() {
        // Chat messages RecyclerView with image click support
        chatAdapter = ChatAdapter(
            messages = mutableListOf(),
            onImageClick = { message ->
                // Could open full-screen image viewer here
                Toast.makeText(this, "Image from conversation", Toast.LENGTH_SHORT).show()
            }
        )
        rvChatMessages.apply {
            layoutManager = LinearLayoutManager(this@ChatActivity)
            adapter = chatAdapter
        }
        
        // Conversations RecyclerView
        conversationAdapter = ConversationAdapter(
            conversations = conversations,
            onConversationClick = { conversation ->
                switchToConversation(conversation)
                drawerLayout.closeDrawer(GravityCompat.START)
            },
            onDeleteClick = { conversation, position ->
                deleteConversation(conversation, position)
            }
        )
        rvConversations.apply {
            layoutManager = LinearLayoutManager(this@ChatActivity)
            adapter = conversationAdapter
        }
    }

    private fun setupDrawer() {
        btnMenu.setOnClickListener {
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START)
            } else {
                drawerLayout.openDrawer(GravityCompat.START)
            }
        }
    }

    private fun setupClickListeners() {
        btnSendMessage.setOnClickListener {
            sendMessage()
        }
        
        btnNewChat.setOnClickListener {
            createNewConversation()
            drawerLayout.closeDrawer(GravityCompat.START)
        }
        
        btnVoiceInput.setOnClickListener {
            startVoiceListening()
        }

        btnStopListening.setOnClickListener {
            stopVoiceListening(cancelled = true)
        }
        
        // ✨ ADD BUTTON - shows options (capture from glasses on Mark 2)
        btnAddAttachment.setOnClickListener {
            showAddOptionsMenu(it)
        }
        
        // Action chips
        chipCreateImage.setOnClickListener {
            // Changed: Now triggers vision capture instead of text
            captureAndAnalyzeFromGlasses("Describe what's in front of me")
        }
        
        chipSummarize.setOnClickListener {
            summarizeConversation()
        }
        
        chipDeepThinking.setOnClickListener {
            etChatInput.setText("Use deep thinking to analyze")
        }
        
        // Send on Enter key
        etChatInput.setOnEditorActionListener { _, _, _ ->
            sendMessage()
            true
        }
    }

    // ========================================================================
    // FEATURE 1: UNIFIED CHAT HISTORY
    // Text + images in one continuous conversation thread
    // ========================================================================

    private fun sendMessage() {
        val message = etChatInput.text.toString().trim()
        if (message.isEmpty()) return

        val wakeWordInput = WakeWordGate.parse(message)
        
        val conversation = currentConversation ?: return
        
        // Add user message
        val chatMessage = ChatMessage(text = message, isFromUser = true, isFinal = true)
        conversation.messages.add(chatMessage)
        chatAdapter.addMessage(chatMessage)
        updateEmptyState()
        scrollToBottom()
        
        etChatInput.setText("")
        
        // Update conversation preview
        conversation.updatePreview()
        if (conversation.messages.size == 1) {
            val previewSource = if (wakeWordInput.cleanedInput.isNotBlank()) {
                wakeWordInput.cleanedInput
            } else {
                message
            }
            conversation.title = previewSource.take(30) + if (previewSource.length > 30) "..." else ""
        }
        conversationAdapter.notifyDataSetChanged()
        saveConversations()

        val cleanedMessage = if (wakeWordInput.hasWakePhrase) {
            wakeWordInput.cleanedInput
        } else {
            message
        }
        if (wakeWordInput.hasWakePhrase && cleanedMessage.isBlank()) {
            Log.d(TAG, "Wake phrase received without a follow-up query")
            Toast.makeText(this, "Say your question after 'Hey Imi'", Toast.LENGTH_SHORT).show()
            return
        }

        // Record only the cleaned user query for AI context/memory.
        conversationMemory.recordMessage(cleanedMessage, isFromUser = true)
        
        // ✨ FEATURE 2: INLINE IMAGE CAPTURE
        // Auto-detect if user wants vision and capture on-the-fly
        if (conversationMemory.isVisionRequest(cleanedMessage)) {
            Log.d(TAG, "Vision request detected: $cleanedMessage")
            captureAndAnalyzeFromGlasses(cleanedMessage)
            return
        }
        
        // ✨ FEATURE 3: MULTI-MODAL CONTEXT AWARENESS
        // Check if user is referring to a previous image
        val referencedImage = conversationMemory.detectImageReference(cleanedMessage)
        if (referencedImage != null) {
            Log.d(TAG, "User referenced previous image: ${referencedImage.id}")
            getAIResponseWithImageContext(cleanedMessage, referencedImage)
            return
        }
        
        // Normal text response with context
        getAIResponse(cleanedMessage)
    }

    // ========================================================================
    // FEATURE 2: INLINE IMAGE CAPTURE FROM CHAT
    // Captures from glasses and inserts into conversation seamlessly
    // ========================================================================

    /**
     * Capture image from glasses and analyze it inline - all without leaving chat
     */
    private fun captureAndAnalyzeFromGlasses(userQuery: String) {
        if (isCapturingImage) return
        isCapturingImage = true
        
        val conversation = currentConversation ?: run {
            isCapturingImage = false
            return
        }
        
        // Show system message
        val captureMsg = ChatMessage(
            text = "📸 Capturing image from glasses...",
            isFromUser = false,
            isFinal = false,
            messageType = MessageType.SYSTEM
        )
        conversation.messages.add(captureMsg)
        chatAdapter.addMessage(captureMsg)
        scrollToBottom()
        
        lifecycleScope.launch {
            try {
                // Step 1: Trigger glass camera via BLE
                val cameraSuccess = triggerGlassCamera()
                
                if (!cameraSuccess) {
                    updateSystemMessage(conversation, "⚠️ Could not trigger glass camera. Check BLE connection.")
                    isCapturingImage = false
                    return@launch
                }
                
                updateSystemMessage(conversation, "📸 Photo taken! Connecting to glasses WiFi...")

                // Step 2: Wait for CoV mode to fully disable and photo to save before P2P
                // CoV disable takes ~1-2s; Glass ignores {2,1,4} while CoV is still active
                delay(4000)

                updateSystemMessage(conversation, "📡 Connecting to glasses to download photo...")

                // Step 3: Download image from glasses (triggers P2P via BLE internally)
                val imagePath = downloadLatestImage()
                
                if (imagePath == null) {
                    updateSystemMessage(
                        conversation,
                        "⚠️ Couldn't get the photo. Connect your phone to the glasses' Wi-Fi (or open the Gallery once to pair) and try again."
                    )
                    isCapturingImage = false
                    return@launch
                }
                
                // Step 4: Insert image inline in chat
                handleInlineImageCapture(imagePath, userQuery)
                
                // Remove the system message
                removeLastSystemMessage(conversation)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error in capture flow", e)
                updateSystemMessage(conversation, "⚠️ Error: ${e.message}")
            } finally {
                isCapturingImage = false
            }
        }
    }
    
    /**
     * Handle an image that's been captured (from glasses or received externally)
     * Inserts it inline in the chat and triggers AI analysis
     */
    private suspend fun handleInlineImageCapture(imagePath: String, userQuery: String) {
        val conversation = currentConversation ?: return
        
        lastCapturedImagePath = imagePath
        
        // Load bitmap for analysis
        try {
            lastCapturedBitmap = withContext(Dispatchers.IO) {
                BitmapFactory.decodeFile(imagePath)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading bitmap", e)
        }
        
        // ✨ Add image inline in the unified chat thread
        withContext(Dispatchers.Main) {
            val imageMessage = ChatMessage(
                text = "📸 Image captured",
                isFromUser = true,
                isFinal = true,
                imagePath = imagePath,
                messageType = MessageType.IMAGE
            )
            conversation.messages.add(imageMessage)
            conversation.hasVisionContent = true
            chatAdapter.addMessage(imageMessage)
            scrollToBottom()
        }
        
        // Show analyzing indicator
        val analyzingMsg = ChatMessage(
            text = "🔍 Analyzing image...",
            isFromUser = false,
            isFinal = false,
            messageType = MessageType.SYSTEM
        )
        withContext(Dispatchers.Main) {
            conversation.messages.add(analyzingMsg)
            chatAdapter.addMessage(analyzingMsg)
            scrollToBottom()
        }
        
        // Analyze with Gemini Vision
        try {
            val analysisResult = analyzeImageWithGemini(imagePath, userQuery)
            
            // Remove analyzing message
            withContext(Dispatchers.Main) {
                removeLastSystemMessage(conversation)
            }
            
            // Add AI's vision analysis as a unified message with the image
            withContext(Dispatchers.Main) {
                val aiVisionMessage = ChatMessage(
                    text = analysisResult,
                    isFromUser = false,
                    isFinal = true,
                    imagePath = null, // Don't duplicate the image
                    messageType = MessageType.VISION_RESULT,
                    imageDescription = analysisResult.take(200)
                )
                conversation.messages.add(aiVisionMessage)
                chatAdapter.addMessage(aiVisionMessage)
                scrollToBottom()
                
                // Record in memory
                val imageId = conversationMemory.recordImage(
                    imagePath = imagePath,
                    aiDescription = analysisResult,
                    userQuery = userQuery,
                    conversationId = conversation.id
                )
                conversation.imageIds.add(imageId)
                conversationMemory.recordMessage(analysisResult, isFromUser = false, hasImage = true, imageId = imageId)

                // Push the vision interaction (photo question + AI description) to the backend.
                com.sdk.glassessdksample.ui.sync.VisionSync.pushDescription(
                    this@ChatActivity,
                    fileName = java.io.File(imagePath).name,
                    description = analysisResult,
                    userQuery = userQuery
                )
                
                conversation.updatePreview()
                conversationAdapter.notifyDataSetChanged()
                saveConversations()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Vision analysis error", e)
            withContext(Dispatchers.Main) {
                removeLastSystemMessage(conversation)
                val errorMsg = ChatMessage(
                    text = "⚠️ Could not analyze image: ${e.message}",
                    isFromUser = false,
                    isFinal = true,
                    messageType = MessageType.SYSTEM
                )
                conversation.messages.add(errorMsg)
                chatAdapter.addMessage(errorMsg)
                scrollToBottom()
            }
        }
    }
    
    /**
     * Trigger the glass camera via BLE
     */
    private suspend fun triggerGlassCamera(): Boolean = withContext(Dispatchers.IO) {
        var success = false
        try {
            val latch = CountDownLatch(1)
            SafeBleCommandHelper.takePhoto { result, error ->
                success = result
                if (error != null) Log.e(TAG, "Camera trigger error: $error")
                latch.countDown()
            }
            latch.await(5, TimeUnit.SECONDS)
            delay(500)
        } catch (e: Exception) {
            Log.e(TAG, "Error triggering camera", e)
        }
        success
    }
    
    /**
     * Download the latest image from glasses via HTTP.
     *
     * 1. resolveGlassIp() establishes the WiFi-Direct connection and returns the Glass IP
     * 2. bind the process to the P2P network so HTTP routes to Glass
     * 3. fetch the media config and download the newest image
     * 4. unbind + tear down the P2P group in the finally block
     */
    private suspend fun downloadLatestImage(): String? = withContext(Dispatchers.IO) {
        try {
            val glassIp = resolveGlassIp()
            if (glassIp == null) {
                Log.w(TAG, "No Glass IP found — phone not on Glass WiFi and BLE IP unavailable")
                return@withContext null
            }

            // Pin the process to the WiFi-Direct network so HTTP traffic routes
            // to the Glass (192.168.6.x) instead of the home router. Without this,
            // OkHttp binds to the default WiFi network and the connect fails with
            // "from /192.168.1.42" even though the P2P group is up.
            bindProcessToGlassNetwork(glassIp)

            val files = albumDownloader.fetchConfig(glassIp)
            val imageFiles = files.filter { item ->
                val name = item.fileName.lowercase()
                name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")
            }

            if (imageFiles.isEmpty()) {
                Log.w(TAG, "No image files found on Glass at $glassIp")
                return@withContext null
            }

            val latestFile = imageFiles.last()
            val outputDir = File(cacheDir, "vision_images")
            if (!outputDir.exists()) outputDir.mkdirs()

            val downloadedFile = albumDownloader.downloadFile(glassIp, latestFile.fileName, outputDir)
            if (downloadedFile != null && downloadedFile.exists()) {
                cachedGlassIp = glassIp
                prefs.edit().putString("cached_glass_ip", glassIp).apply()
                Log.d(TAG, "✅ Downloaded image from $glassIp: ${downloadedFile.absolutePath}")
                return@withContext downloadedFile.absolutePath
            }

            Log.w(TAG, "Download returned null for ${latestFile.fileName}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading image", e)
            null
        } finally {
            // Restore the default network and release the P2P group after the
            // transfer (whether it succeeded or not).
            unbindProcessFromGlassNetwork()
            teardownWifiP2p()
        }
    }

    /**
     * Bind the whole process to the WiFi network that can route to the Glass IP.
     *
     * When a WiFi-Direct group is formed, Android keeps the home WiFi as the
     * default network, so sockets opened by OkHttp route to the wrong interface.
     * We find the network whose routes/addresses match the Glass subnet and pin
     * the process to it for the duration of the download.
     */
    private suspend fun bindProcessToGlassNetwork(glassIp: String) {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val glassPrefix = glassIp.substringBeforeLast('.') + "."
            // The P2P network may take a moment to appear in allNetworks after
            // the group forms — retry a few times before giving up.
            repeat(6) { attempt ->
                for (network in cm.allNetworks) {
                    val caps = cm.getNetworkCapabilities(network) ?: continue
                    if (!caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) continue
                    val linkProps = cm.getLinkProperties(network) ?: continue
                    val matches = linkProps.linkAddresses.any { la ->
                        (la.address as? java.net.Inet4Address)?.hostAddress?.startsWith(glassPrefix) == true
                    }
                    if (matches) {
                        val ok = cm.bindProcessToNetwork(network)
                        Log.d(TAG, "🔗 Bound process to Glass network (${linkProps.interfaceName}) success=$ok")
                        return
                    }
                }
                if (attempt < 5) delay(500)
            }
            Log.w(TAG, "Could not find a WiFi network matching $glassPrefix — download may use wrong route")
        } catch (e: Exception) {
            Log.w(TAG, "bindProcessToGlassNetwork failed: ${e.message}")
        }
    }

    /** Restore the default network binding after a Glass download. */
    private fun unbindProcessFromGlassNetwork() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            cm.bindProcessToNetwork(null)
            Log.d(TAG, "🔗 Restored default network binding")
        } catch (e: Exception) {
            Log.w(TAG, "unbindProcessFromGlassNetwork failed: ${e.message}")
        }
    }

    /**
     * Resolve Glass IP using BLE P2P trigger first, then fallbacks.
     *
     * Flow:
     *   1. Check cached IP (fastest, no radio needed)
     *   2. BLE commands trigger Glass P2P hotspot + wait 4s for type-8 BLE IP
     *   3. WiFi P2P discovery+connect via WifiP2pHelper (phone joins Glass network)
     *   4. Parallel HTTP scan of all known Glass IPs
     */
    private suspend fun resolveGlassIp(): String? = withContext(Dispatchers.IO) {
        // 1. BLE P2P trigger + short wait for type-8 IP notification
        val bleIp = triggerP2pAndWaitForIp()
        if (bleIp != null) return@withContext bleIp

        // 2. WiFi P2P: start discovery and connect — phone needs to join Glass network.
        //    This mirrors what GlassMediaGalleryActivity does via WifiP2pHelper and is
        //    the path that reliably yields 192.168.6.1.
        val p2pIp = connectViaWifiP2pAndGetIp()
        if (p2pIp != null) return@withContext p2pIp

        // 3. Parallel HTTP scan (last resort — only works if phone already on Glass WiFi)
        Log.d(TAG, "Falling back to parallel IP scan...")
        albumDownloader.discoverGlassesIP()
    }

    /**
     * Use WifiP2pHelper to discover Glass, connect to it, and return its IP.
     * Runs the full WiFi Direct discovery → connect → IP detection cycle,
     * the same path the Gallery uses, with a 25-second overall timeout.
     *
     * IMPORTANT: on success the helper is kept alive (stored in
     * [activeWifiP2pHelper]) so the P2P group stays up for the HTTP transfer.
     * The caller MUST call [teardownWifiP2p] once the download finishes.
     */
    private suspend fun connectViaWifiP2pAndGetIp(): String? = withContext(Dispatchers.IO) {
        var resolvedIp: String? = null
        val latch = CountDownLatch(1)

        val helper = WifiP2pHelper(this@ChatActivity)
        val callback = object : WifiP2pHelper.WifiP2pCallback {
            override fun onP2pStateChanged(enabled: Boolean) {}
            override fun onPeersDiscovered(peers: List<WifiP2pDevice>) {}
            override fun onGlassConnected(glassIp: String) {
                Log.d(TAG, "📡 WiFi P2P gave IP: $glassIp")
                resolvedIp = glassIp
                latch.countDown()
            }
            override fun onP2pDisconnected() {}
            override fun onP2pError(message: String) {
                Log.w(TAG, "WiFi P2P error: $message")
            }
        }

        try {
            withContext(Dispatchers.Main) {
                helper.setCallback(callback)
                helper.initialize()
                // CRITICAL: register the broadcast receiver so PEERS_CHANGED and
                // CONNECTION_CHANGED events are delivered. Without this, discovery
                // runs but no peer/connection callbacks ever fire (the bug that
                // caused chat capture to fail while the Gallery worked).
                helper.registerReceiver()
                // Clear any stale group left over from a previous capture —
                // otherwise the second connection never forms (works once, then
                // fails). removeGroup is async, so give it a moment below.
                helper.removeExistingGroup()
            }
            delay(1500)
            withContext(Dispatchers.Main) {
                helper.startDiscovery()
            }
            latch.await(25, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "WiFi P2P connect failed: ${e.message}")
        }

        if (resolvedIp != null) {
            // Keep the P2P group alive for the HTTP transfer — DO NOT cleanup here.
            activeWifiP2pHelper = helper
        } else {
            // No connection — safe to tear down immediately.
            withContext(Dispatchers.Main) {
                try { helper.unregisterReceiver() } catch (_: Exception) {}
                try { helper.cleanup() } catch (_: Exception) {}
            }
        }

        resolvedIp
    }

    /**
     * Tear down the active WiFi P2P connection after a download completes.
     * Safe to call even if no connection was established.
     */
    private suspend fun teardownWifiP2p() {
        val helper = activeWifiP2pHelper ?: return
        activeWifiP2pHelper = null
        withContext(Dispatchers.Main) {
            try { helper.unregisterReceiver() } catch (_: Exception) {}
            try { helper.cleanup() } catch (_: Exception) {}
        }
        Log.d(TAG, "WiFi P2P torn down after download")
    }

    /**
     * Send the BLE P2P hotspot command and wait up to 4s for the type-8
     * notification that tells us the Glass IP.
     *
     * Glass sometimes responds with a dropped P2P advertisement that the SDK
     * ignores ("暂时不处理"), so the type-8 notification may never arrive.
     * We use a short timeout so that resolveGlassIp() can fall through to
     * the parallel IP scan quickly rather than blocking for 12 seconds.
     */
    private suspend fun triggerP2pAndWaitForIp(): String? = withContext(Dispatchers.IO) {
        var resolvedIp: String? = null
        val latch = CountDownLatch(1)

        val listener = object : GlassesDeviceNotifyListener() {
            override fun parseData(cmdType: Int, rsp: GlassesDeviceNotifyRsp?) {
                try {
                    val loadData = rsp?.loadData ?: return
                    if (loadData.size < 11) return
                    val notifyType = loadData[6].toInt() and 0xFF
                    if (notifyType == 8) {
                        val ip = "${loadData[7].toInt() and 0xFF}" +
                                 ".${loadData[8].toInt() and 0xFF}" +
                                 ".${loadData[9].toInt() and 0xFF}" +
                                 ".${loadData[10].toInt() and 0xFF}"
                        Log.d(TAG, "📡 BLE type-8 IP for chat download: $ip")
                        resolvedIp = ip
                        latch.countDown()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "BLE parse error: ${e.message}")
                }
            }
        }

        try {
            LargeDataHandler.getInstance().addOutDeviceListener(3, listener)

            // Reset then start P2P transfer mode (same commands as GlassMediaTransfer)
            LargeDataHandler.getInstance().glassesControl(byteArrayOf(2, 1, 15)) { _, _ -> }
            delay(300)
            LargeDataHandler.getInstance().glassesControl(byteArrayOf(2, 1, 4)) { _, _ -> }

            // Short wait — Glass may not send type-8 if SDK drops the P2P advertisement
            latch.await(4, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "BLE P2P trigger error: ${e.message}")
        } finally {
            try { LargeDataHandler.getInstance().removeOutDeviceListener(3) } catch (_: Exception) {}
        }

        if (resolvedIp != null) {
            Log.d(TAG, "✅ BLE P2P gave IP: $resolvedIp")
        } else {
            Log.d(TAG, "ℹ️ No BLE type-8 within 4s — falling through to IP scan")
        }
        resolvedIp
    }
    
    /**
     * Analyze an image using Gemini Vision API
     */
    private suspend fun analyzeImageWithGemini(imagePath: String, userQuery: String): String {
        return withContext(Dispatchers.IO) {
            val bitmap = BitmapFactory.decodeFile(imagePath)
                ?: throw Exception("Could not decode image")
            
            // Build context-aware prompt using conversation memory
            val contextPrompt = conversationMemory.buildVisionContextPrompt(userQuery)
            
            val fullPrompt = """
                You are Imi Glass AI assistant. Analyze this image and respond naturally.
                
                $contextPrompt
                
                Be descriptive and specific. Mention brands, colors, text, objects visible.
                ${AiResponsePrefs.buildResponseStyleInstruction(this@ChatActivity)}
                If the user asked a specific question about the image, answer that directly.
            """.trimIndent()
            
            val model = visionModel ?: throw Exception("Vision model not initialized")

            if (!UsageLimitManager.tryConsume(this@ChatActivity, TokenUsageTracker.Mode.SEEING)) {
                UsageLimitManager.promptUpgradeIfPossible(this@ChatActivity, TokenUsageTracker.Mode.SEEING)
                return@withContext UsageLimitManager.limitReachedMessage(TokenUsageTracker.Mode.SEEING)
            }
            
            val response = model.generateContent(
                content { image(bitmap) },
                content { text(fullPrompt) }
            )

            response.usageMetadata?.let {
                TokenUsageTracker.track(this@ChatActivity, TokenUsageTracker.Mode.SEEING, it)
            }
            
            response.text ?: "I couldn't analyze this image."
        }
    }

    // ========================================================================
    // FEATURE 3: MULTI-MODAL CONTEXT AWARENESS
    // AI remembers all images and can reference them in conversation
    // ========================================================================

    /**
     * Get AI response with full multi-modal context
     */
    private fun getAIResponse(userMessage: String) {
        lifecycleScope.launch {
            try {
                val conversation = currentConversation ?: return@launch
                
                // Show loading message
                val loadingMessage = ChatMessage(
                    text = "Thinking...",
                    isFromUser = false,
                    isFinal = false
                )
                conversation.messages.add(loadingMessage)
                chatAdapter.addMessage(loadingMessage)
                scrollToBottom()
                
                // Build compact context payload to reduce token usage.
                val contextPrompt = compactPromptText(
                    conversationMemory.buildContextPrompt(userMessage),
                    MAX_AI_CONTEXT_CHARS
                )
                
                // Also include compact conversation-local history.
                val conversationHistory = compactPromptText(
                    conversation.buildHistoryForAI(maxMessages = MAX_AI_HISTORY_MESSAGES),
                    MAX_AI_HISTORY_CHARS
                )
                
                val fullPrompt = if (conversationHistory.isNotBlank()) {
                    """
                    User message: $userMessage
                    
                    Context (compact):
                    $contextPrompt
                    
                    Recent chat:
                    $conversationHistory

                    ${AiResponsePrefs.buildResponseStyleInstruction(this@ChatActivity)}
                    """.trimIndent()
                } else {
                    userMessage
                }
                
                val response = withContext(Dispatchers.IO) {
                    geminiClient.chat(fullPrompt)
                }

                // Replace the "Thinking..." bubble in place with the reply
                // instead of removing it and adding a second bubble.
                loadingMessage.text = response
                loadingMessage.isFinal = true
                chatAdapter.updateLastMessage(response, isFinal = true, isFromUser = false)
                scrollToBottom()
                
                // Record in memory
                conversationMemory.recordMessage(response, isFromUser = false)

                // Push this text-chat turn to the backend (channel = TEXT).
                com.sdk.glassessdksample.ui.sync.ChatSync.pushTurn(
                    this@ChatActivity, "Text Chat", userMessage, response,
                    channel = com.sdk.glassessdksample.ui.sync.ChatSync.Channel.TEXT
                )

                // ✨ Auto-learn from what the user said – no manual input needed
                memoryManager.learnFromUserMessage(userMessage)
                memoryManager.incrementMessageStats(isNewConversation = false)

                // Update preview
                conversation.updatePreview()
                conversationAdapter.notifyDataSetChanged()
                saveConversations()
                
            } catch (e: Exception) {
                handleAIError(e)
            }
        }
    }
    
    /**
     * Get AI response when user references a previous image
     * Re-loads the image and includes its context in the prompt
     */
    private fun getAIResponseWithImageContext(userMessage: String, referencedImage: ImageMemory) {
        lifecycleScope.launch {
            try {
                val conversation = currentConversation ?: return@launch
                
                // Show loading with image reference indicator
                val loadingMessage = ChatMessage(
                    text = "🔗 Looking at the referenced image...",
                    isFromUser = false,
                    isFinal = false,
                    referencedImageId = referencedImage.id
                )
                conversation.messages.add(loadingMessage)
                chatAdapter.addMessage(loadingMessage)
                scrollToBottom()
                
                // Check if we can re-analyze the image
                val imageFile = File(referencedImage.imagePath)
                val response: String
                
                if (imageFile.exists() && visionModel != null) {
                    // Re-analyze the image with the new question
                    response = withContext(Dispatchers.IO) {
                        val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
                        if (bitmap != null) {
                            if (!UsageLimitManager.tryConsume(this@ChatActivity, TokenUsageTracker.Mode.SEEING)) {
                                UsageLimitManager.promptUpgradeIfPossible(this@ChatActivity, TokenUsageTracker.Mode.SEEING)
                                return@withContext UsageLimitManager.limitReachedMessage(TokenUsageTracker.Mode.SEEING)
                            }

                            val contextPrompt = conversationMemory.buildVisionContextPrompt(
                                userMessage, referencedImage
                            )
                            
                            val model = visionModel!!
                            val result = model.generateContent(
                                content { image(bitmap) },
                                content { text("""
                                    The user is asking about an image they shared earlier.
                                    Previous description: ${referencedImage.description}
                                    Their original question was: "${referencedImage.userQuery}"
                                    
                                    Now they are asking: "$userMessage"
                                    
                                    $contextPrompt
                                    
                                    Respond naturally and helpfully.
                                """.trimIndent()) }
                            )

                            result.usageMetadata?.let {
                                TokenUsageTracker.track(this@ChatActivity, TokenUsageTracker.Mode.SEEING, it)
                            }
                            result.text ?: "I couldn't re-analyze that image."
                        } else {
                            // Fall back to text-only with context
                            val compactContext = compactPromptText(
                                conversationMemory.buildContextPrompt(userMessage),
                                MAX_AI_CONTEXT_CHARS
                            )
                            geminiClient.chat(compactContext)
                        }
                    }
                } else {
                    // Image file no longer exists, use text context with image description
                    val contextPrompt = """
                        The user previously shared an image. Here's what was in it:
                        "${referencedImage.description}"
                        They originally asked: "${referencedImage.userQuery}"
                        
                        Now they're asking: "$userMessage"
                        
                        Respond naturally using the image context provided.
                    """.trimIndent()
                    
                    response = withContext(Dispatchers.IO) {
                        geminiClient.chat(contextPrompt)
                    }
                }
                
                // Replace the loading bubble in place with the reply (keeps the
                // image-reference badge that was set on the loading message).
                loadingMessage.text = response
                loadingMessage.isFinal = true
                chatAdapter.updateLastMessage(response, isFinal = true, isFromUser = false)
                scrollToBottom()
                
                // Record in memory
                conversationMemory.recordMessage(response, isFromUser = false, hasImage = true, imageId = referencedImage.id)

                // Push this text-chat turn (referencing a prior image) to the backend.
                com.sdk.glassessdksample.ui.sync.ChatSync.pushTurn(
                    this@ChatActivity, "Text Chat", userMessage, response,
                    channel = com.sdk.glassessdksample.ui.sync.ChatSync.Channel.TEXT
                )

                // ✨ Auto-learn from what the user said
                memoryManager.learnFromUserMessage(userMessage)
                memoryManager.incrementMessageStats(isNewConversation = false)

                conversation.updatePreview()
                conversationAdapter.notifyDataSetChanged()
                saveConversations()
                
            } catch (e: Exception) {
                handleAIError(e)
            }
        }
    }

    private fun compactPromptText(text: String, maxChars: Int): String {
        val normalized = text.replace("\r", "").trim()
        if (normalized.length <= maxChars) return normalized
        return normalized.takeLast(maxChars)
    }
    
    private fun handleAIError(e: Exception) {
        e.printStackTrace()
        
        val conversation = currentConversation ?: return
        
        // Remove loading message if exists
        if (conversation.messages.isNotEmpty() && !conversation.messages.last().isFinal) {
            conversation.messages.removeAt(conversation.messages.size - 1)
        }
        
        val errorMessage = ChatMessage(
            text = "Sorry, I encountered an error. Please try again.",
            isFromUser = false,
            isFinal = true,
            messageType = MessageType.SYSTEM
        )
        conversation.messages.add(errorMessage)
        chatAdapter.notifyDataSetChanged()
        scrollToBottom()
        
        Toast.makeText(
            this@ChatActivity,
            "Error: ${e.message}",
            Toast.LENGTH_SHORT
        ).show()
    }
    
    // ========================================================================
    // HELPER METHODS
    // ========================================================================
    
    private fun updateSystemMessage(conversation: Conversation, newText: String) {
        if (conversation.messages.isNotEmpty()) {
            val lastMsg = conversation.messages.last()
            if (lastMsg.messageType == MessageType.SYSTEM && !lastMsg.isFinal) {
                lastMsg.text = newText
                chatAdapter.notifyDataSetChanged()
                return
            }
        }
    }
    
    private fun removeLastSystemMessage(conversation: Conversation) {
        if (conversation.messages.isNotEmpty()) {
            val lastMsg = conversation.messages.last()
            if (lastMsg.messageType == MessageType.SYSTEM) {
                conversation.messages.removeAt(conversation.messages.size - 1)
                chatAdapter.notifyDataSetChanged()
            }
        }
    }

    private fun summarizeConversation() {
        val conversation = currentConversation ?: return
        
        if (conversation.messages.isEmpty()) {
            Toast.makeText(this, "No messages to summarize", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Build enhanced conversation history including image notes
        val conversationText = conversation.buildHistoryForAI()
        
        etChatInput.setText("Please summarize this conversation:\n$conversationText")
        sendMessage()
    }

    private fun createNewConversation() {
        val newConversation = Conversation(
            title = "New Chat ${conversations.size + 1}"
        )
        conversations.add(0, newConversation)
        conversationAdapter.notifyItemInserted(0)
        switchToConversation(newConversation)
        saveConversations()

        // Track that a new conversation has started
        memoryManager.incrementMessageStats(isNewConversation = true)

        Toast.makeText(this, "New chat created", Toast.LENGTH_SHORT).show()
    }

    private fun deleteConversation(conversation: Conversation, position: Int) {
        conversations.removeAt(position)
        conversationAdapter.notifyItemRemoved(position)
        saveConversations()
        
        if (currentConversation == conversation) {
            if (conversations.isNotEmpty()) {
                switchToConversation(conversations[0])
            } else {
                createNewConversation()
            }
        }
        
        Toast.makeText(this, "Chat deleted", Toast.LENGTH_SHORT).show()
    }

    private fun switchToConversation(conversation: Conversation) {
        currentConversation = conversation
        
        chatAdapter.clearMessages()
        conversation.messages.forEach { message ->
            chatAdapter.addMessage(message)
        }
        
        val position = conversations.indexOf(conversation)
        conversationAdapter.setSelected(position)

        updateEmptyState()
        scrollToBottom()
    }

    private fun scrollToBottom() {
        rvChatMessages.post {
            if (chatAdapter.itemCount > 0) {
                rvChatMessages.smoothScrollToPosition(chatAdapter.itemCount - 1)
            }
        }
    }

    private fun loadConversations() {
        try {
            val json = prefs.getString("conversations", null)
            if (json != null) {
                val type = object : TypeToken<List<Conversation>>() {}.type
                val loaded = gson.fromJson<List<Conversation>>(json, type)
                conversations.clear()
                conversations.addAll(loaded)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveConversations() {
        try {
            val json = gson.toJson(conversations)
            prefs.edit().putString("conversations", json).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }

    override fun onPause() {
        super.onPause()
        saveConversations()
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AUDIO_FOR_VOICE) {
            if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                startVoiceListening()
            } else {
                Toast.makeText(this, "Mic permission needed for voice input", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(imageReceiver)
        } catch (e: Exception) {
            // Already unregistered
        }
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
    }

    // ========================================================================
    // EMPTY-STATE SUGGESTIONS ("Things you can do")
    // ========================================================================

    data class ChatSuggestion(val title: String, val subtitle: String)

    private class SuggestionAdapter(
        private val items: List<ChatSuggestion>,
        private val onClick: (ChatSuggestion) -> Unit
    ) : RecyclerView.Adapter<SuggestionAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.tvSuggestionTitle)
            val subtitle: TextView = view.findViewById(R.id.tvSuggestionSubtitle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_chat_suggestion, parent, false)
            // Add bottom spacing between cards
            (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.bottomMargin = (12 * parent.resources.displayMetrics.density).toInt()
                view.layoutParams = lp
            }
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.title.text = item.title
            holder.subtitle.text = item.subtitle
            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount(): Int = items.size
    }
}
