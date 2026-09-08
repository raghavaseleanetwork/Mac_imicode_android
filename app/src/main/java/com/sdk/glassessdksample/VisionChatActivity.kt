package com.sdk.glassessdksample

import com.sdk.glassessdksample.RemoteConfigManager
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.wifi.p2p.WifiP2pDevice
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyListener
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyRsp
import com.sdk.glassessdksample.ui.GeminiLiveService
import com.sdk.glassessdksample.ui.HotHelper
import com.sdk.glassessdksample.ui.TokenUsageTracker
import com.sdk.glassessdksample.ui.UsageLimitManager
import com.sdk.glassessdksample.ui.wifi.WifiP2pHelper
import com.sdk.glassessdksample.ui.wifi.AlbumDownloader
import com.sdk.glassessdksample.ui.gallery.LiveGalleryManager
import com.sdk.glassessdksample.utils.SafeBleCommandHelper
import kotlinx.coroutines.*
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.sdk.glassessdksample.utils.SystemBarsInsets

/**
 * Vision Chat Activity - WiFi HTTP-based Image Transfer
 * 
 * Flow:
 * 1. Click "Capture from Glasses" button
 * 2. Trigger glasses camera via BLE
 * 3. Trigger glasses hotspot via BLE  
 * 4. Connect to Glass WiFi (manual or auto)
 * 5. Download latest image via HTTP (http://{ip}/files/{filename})
 * 6. Analyze with Gemini Vision API
 * 7. Speak response via TTS
 */

// Universal helper to extract data bytes from GlassesDeviceNotifyRsp (direct or reflection)
fun extractNotifyData(rsp: Any?): ByteArray? {
    if (rsp == null) return null
    // Try direct property first
    try {
        val loadDataProp = rsp.javaClass.getDeclaredField("loadData")
        loadDataProp.isAccessible = true
        val value = loadDataProp.get(rsp)
        if (value is ByteArray) return value
    } catch (_: Exception) {}
    // Try 'data' field (obfuscated SDKs)
    try {
        val dataField = rsp.javaClass.getDeclaredField("data")
        dataField.isAccessible = true
        val value = dataField.get(rsp)
        if (value is ByteArray) return value
    } catch (_: Exception) {}
    // Try 'a' field (heavily obfuscated)
    try {
        val aField = rsp.javaClass.getDeclaredField("a")
        aField.isAccessible = true
        val value = aField.get(rsp)
        if (value is ByteArray) return value
    } catch (_: Exception) {}
    return null
}

class VisionChatActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var recyclerView: RecyclerView
    private lateinit var chatAdapter: VisionChatAdapter
    private val messages = mutableListOf<VisionChatMessage>()
    
    private lateinit var generativeModel: GenerativeModel
    private lateinit var statusText: TextView
    private lateinit var captureButton: Button
    
    // TTS for speaking responses
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    
    // Coroutine scope
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // WiFi P2P Helper for automatic connection
    private lateinit var wifiP2pHelper: WifiP2pHelper
    private var glassIpAddress: String? = null
    private var isWaitingForConnection = false

    // 🔗 PERSISTENT CONNECTION: Once Glass WiFi IP is discovered, cache it so
    // subsequent captures skip the entire P2P/discovery phase entirely.
    private var cachedGlassIp: String? = null      // in-memory (session)
    private var persistentConnectionReady = false  // true after first successful download

    // Album Downloader for HTTP-based file transfer
    private lateinit var albumDownloader: AlbumDownloader
    
    // WiFi Transfer settings
    companion object {
        private const val TAG = "VisionChatActivity"

        // 🔗 Persistent connection prefs keys
        const val PREFS_NAME = "vision_chat_prefs"
        const val KEY_GLASS_IP = "cached_glass_ip"
        
        const val EXTRA_AUTO_CAPTURE = "auto_capture"
        const val EXTRA_VISION_QUERY = "vision_query"
        // 🆕 Force new capture - clears previous image to capture fresh
        const val EXTRA_FORCE_NEW_CAPTURE = "force_new_capture"
        
        // 🆕 Broadcast action to resume Gemini Live after vision analysis completes
        const val ACTION_RESUME_GEMINI_LIVE = "com.sdk.glassessdksample.ACTION_RESUME_GEMINI_LIVE"
        
        // 🆕 Broadcast action to stop continuous streaming
        const val ACTION_STOP_VISION_STREAMING = "com.sdk.glassessdksample.ACTION_STOP_VISION_STREAMING"
        
        // 🆕 Extra key for passing vision result text to Gemini Live
        const val EXTRA_VISION_TEXT = "vision_text"

        // 🆕 Broadcast MainActivity sends back once the reconnected Gemini Live
        // session has actually started speaking the vision result (or definitively
        // failed to). VisionChatActivity waits for this instead of finishing on a
        // fixed timer, so it never closes before the summary is audible.
        const val ACTION_VISION_RESULT_SPOKEN = "com.sdk.glassessdksample.ACTION_VISION_RESULT_SPOKEN"

        // Safety net: if MainActivity never confirms (e.g. reconnect hangs), don't
        // leave VisionChatActivity open forever - finish anyway after this long.
        // Must outlast MainActivity's own give-up window (VISION_SPEAK_GIVE_UP_MS,
        // 12s - long enough for a model-fallback reconnect), otherwise this screen
        // closes while the session that is about to speak is still coming up.
        private const val VISION_RESULT_SPOKEN_TIMEOUT_MS = 15000L
    }
    
    // 🆕 Flag: true if launched from Gemini Live (should auto-return after TTS)
    private var shouldResumeGeminiLive = false
    
    // 🆕 Store user's vision query to customize response
    private var userVisionQuery: String? = null
    
    private var isProcessing = false
    
    // 🆕 Track ALL processed photos to prevent ANY duplicates
    private val processedPhotos = mutableSetOf<String>()
    private var lastProcessedPhotoName: String? = null

    // 🆕 Snapshot of photos that existed on the Glass BEFORE the current capture.
    // Used to reliably identify the freshly-captured image (the one NOT in this set),
    // instead of blindly grabbing the "newest filename" which may still be an old photo
    // that finished saving before the new one. This is what prevents downloading a
    // previously-clicked image and ensures every new capture downloads the right photo.
    private var preCaptureFileNames: MutableSet<String> = mutableSetOf()
    // True only when preCaptureFileNames was actually read from the Glass (trustworthy).
    private var preCaptureSnapshotValid: Boolean = false
    
    // 🆕 Processing lock to prevent parallel analysis
    private var isImageAnalysisInProgress = false
    
    // 🆕 Auto-capture: automatically capture next photo after Q&A completes
    private var shouldAutoCaptureNext = false
    private var waitingForNextCapture = false
    
    // 🔍 Debug: Track dumped classes to avoid spam
    private val dumpedClasses = mutableSetOf<String>()
    
    // 🆕 Thinking tune player - plays while waiting for image description
    private var thinkingPlayer: MediaPlayer? = null
    
    // 🆕 Store last captured image for follow-up questions without re-capture
    private var lastCapturedImageBytes: ByteArray? = null
    private var lastCapturedBitmap: Bitmap? = null
    private var lastImageAnalysisTime: Long = 0
    // NOTE: the captured image now persists until the user says "take another picture"
    // (see canUseLastImage), so there is no follow-up time limit anymore.
    
    // 🆕 Store last vision description for Gemini Live context memory
    private var lastVisionDescription: String? = null
    
    // 🎥 Continuous vision streaming (live video feel)
    private var isContinuousStreaming = false
    private var streamingJob: Job? = null
    private val STREAMING_INTERVAL_MS = 7000L  // 7 seconds per NEW frame capture
    private var shouldAutoStartStreaming = false  // Auto-start after first analysis
    private var isSpeaking = false  // Track if audio is currently playing
    
    // 🆕 BLE-based continuous streaming manager (for Gemini Live integration)
    private var bleContinuousStreamManager: ContinuousVisionStreamManager? = null
    
    // 🎥 Frame counter for live streaming stats
    private var frameCount = 0
    private var lastFrameTime = 0L
    private var lastBitmap: Bitmap? = null  // Store last received frame for display
    
    // WiFi connection monitoring
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    
    // 🛑 Broadcast receiver for stop command
    private val stopStreamingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_STOP_VISION_STREAMING) {
                Log.i(TAG, "🛑 Received stop streaming broadcast")
                handleStopCommand()
            }
        }
    }
    
    //  BLE notification listener for Glass IP (receives IP directly from Glass via type 8 notification)
    private val bleGlassIpListener = object : GlassesDeviceNotifyListener() {
        override fun parseData(cmdType: Int, rsp: GlassesDeviceNotifyRsp?) {
            try {
                val loadData = extractNotifyData(rsp)
                if (loadData == null || loadData.size < 11) return
                val notifyType = loadData[6].toInt() and 0xFF
                Log.d(TAG, "📡 BLE Notification type: $notifyType")
                when (notifyType) {
                    8 -> {
                        val ip = "${loadData[7].toInt() and 0xFF}.${loadData[8].toInt() and 0xFF}.${loadData[9].toInt() and 0xFF}.${loadData[10].toInt() and 0xFF}"
                        Log.i(TAG, "🌐 BLE returned Glass IP: $ip")
                        runOnUiThread {
                            if (isWaitingForConnection) {
                                glassIpAddress = ip
                                isWaitingForConnection = false
                                Log.d(TAG, "✅ Glass IP from BLE: $ip")
                                mainScope.launch {
                                    downloadAndAnalyzeImage(ip)
                                }
                            } else {
                                Log.d(TAG, "📡 Received Glass IP but not waiting for connection")
                            }
                        }
                    }
                    9 -> {
                        val errorCode = loadData[7].toInt() and 0xFF
                        Log.w(TAG, "⚠️ BLE Error notification: $errorCode")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing BLE IP notification: ${e.message}")
            }
        }
    }
    
    // Permission request launcher for Nearby Devices
    private val nearbyPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Log.i(TAG, "✅ All nearby permissions granted")
            // Permissions granted - initialize WiFi P2P now
            initializeWifiP2p()
        } else {
            Log.w(TAG, "⚠️ Some permissions denied: ${permissions.filter { !it.value }.keys}")
            Toast.makeText(this, "Nearby devices permission required for WiFi transfer", Toast.LENGTH_LONG).show()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        
        Log.d(TAG, "🔄 onNewIntent received in VisionChatActivity")
        
        val autoCapture = intent?.getBooleanExtra(EXTRA_AUTO_CAPTURE, false) == true
        val forceNewCapture = intent?.getBooleanExtra(EXTRA_FORCE_NEW_CAPTURE, false) == true
        
        if (autoCapture) {
            Log.d(TAG, "🎤 Auto-capture triggered via onNewIntent")
            
            // Update query if provided
            if (intent?.hasExtra(EXTRA_VISION_QUERY) == true) {
                userVisionQuery = intent.getStringExtra(EXTRA_VISION_QUERY)
                Log.d(TAG, "💬 Updated user vision query: $userVisionQuery")
            }
            
            // If triggered from outside, always enable Gemini connection
            shouldResumeGeminiLive = true

            // Force new capture setup
            if (forceNewCapture) {
                Log.d(TAG, "📸 FORCE NEW CAPTURE via onNewIntent - clearing prev image")
                clearLastCapturedImage()
                shouldAutoStartStreaming = false 
            }
            
            // Trigger capture logic
            if (!isProcessing) {
                startThinkingTune()
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    startCaptureProcess()
                }, 500)
            } else {
                Log.w(TAG, "⚠️ Already processing, request ignored")
                Toast.makeText(this, "Analysis in progress...", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vision_chat)
        SystemBarsInsets.apply(this)

        // ⚡ PRE-WARM: Start server & network immediately on open
        // so by the time user captures a photo, connection is ready (no cold-start delay)
        mainScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "⚡ Pre-warming network connection...")
                // Pre-resolve any pending DNS / socket resources
                val testUrl = java.net.InetAddress.getLocalHost()
                Log.d(TAG, "⚡ Local host pre-warmed: $testUrl")
            } catch (e: Exception) {
                Log.d(TAG, "⚡ Pre-warm skipped: ${e.message}")
            }
        }

        // 🆕 Set flag that VisionChat is open (prevent MainActivity from opening another)
        MainActivity.visionChatOpenFlag = true
        Log.d(TAG, "🔓 VisionChat opened - set visionChatOpenFlag = true")
        
        // Initialize Album Downloader (HTTP-based transfer)
        albumDownloader = AlbumDownloader(this)

        // 🔗 PERSISTENT CONNECTION: Load last known Glass IP from SharedPreferences
        // so first capture of the session skips discovery if the Glass is still reachable.
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedIp = prefs.getString(KEY_GLASS_IP, null)
        if (!savedIp.isNullOrBlank()) {
            cachedGlassIp = savedIp
            Log.i(TAG, "🔗 Loaded persistent Glass IP from prefs: $savedIp")
        }
        
        // Initialize WiFi P2P Helper (don't register yet - wait for permissions)
        wifiP2pHelper = WifiP2pHelper(this)
        
        // 🆕 Initialize BLE continuous stream manager (for Gemini Live integration)
        bleContinuousStreamManager = ContinuousVisionStreamManager(mainScope)
        Log.d(TAG, "✅ BLE Continuous Stream Manager initialized")

        // Attempt to register a BigData listener dynamically (reflective proxy).
        tryRegisterBigDataListener()

        // 🚀 Register listener for ACTION_GPT_UPLOAD (89 / 0x59) — this is where bc59 packets go!
        registerGptUploadListener()
        
        // 🔧 Try to intercept raw BLE packets at lower level
        registerRawBlePacketInterceptor()
        
        // 🎯 NEW: Hook into SDK's receiveData to capture raw bc59 packets
        hookSdkReceiveData()
        
        // 🚀 Request higher MTU for faster packet transfer (5x speed boost!)
        requestHighMtu()
        
        // Check and request nearby device permissions
        checkNearbyDevicesPermission()
        
        // Initialize TTS
        tts = TextToSpeech(this, this)
        
        // Initialize Gemini Vision model (2.5 Flash for image analysis)
        val apiKey = RemoteConfigManager.geminiApiKey
        generativeModel = GenerativeModel(
            modelName = "gemini-2.5-flash",
            apiKey = apiKey
        )
        
        // Setup WiFi connection monitoring
        setupWifiMonitoring()
        
        setupUI()
        
        // 🆕 Register as secondary listener to GeminiLiveService for voice commands
        // This allows detecting "who is in front of me" while VisionChat is open
        registerVisionTranscriptionListener()
        
        // Only trigger auto-capture if explicitly requested (e.g. from voice command)
        val autoCapture = intent.getBooleanExtra(EXTRA_AUTO_CAPTURE, false)
        val forceNewCapture = intent.getBooleanExtra(EXTRA_FORCE_NEW_CAPTURE, false)
        
        if (autoCapture) {
            Log.d(TAG, "🎤 Auto-capture triggered from voice command (Gemini Live)")
            // 🆕 Save user's vision query to customize response
            userVisionQuery = intent.getStringExtra(EXTRA_VISION_QUERY)
            Log.d(TAG, "💬 User vision query: $userVisionQuery")
            // 🆕 If launched from voice command, enable auto-return to Gemini Live
            shouldResumeGeminiLive = true
            Log.d(TAG, "🔄 Will auto-resume Gemini Live after vision analysis")
            
            // 🆕 Force new capture - clear previous image to capture fresh
            if (forceNewCapture) {
                Log.d(TAG, "📸 FORCE NEW CAPTURE - clearing previous image")
                clearLastCapturedImage()
            }
            
            // 🆕 DISABLE auto-streaming - only describe ONCE when from Gemini Live
            // User can ask again if they want another description
            shouldAutoStartStreaming = false
            Log.d(TAG, "🛑 Auto-streaming DISABLED for Gemini Live mode (one-shot description)")
            
            // 🆕 Start tuning sound IMMEDIATELY while waiting for description
            startThinkingTune()
            Log.d(TAG, "🎵 Tuning started immediately while waiting for description")
            
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                startCaptureProcess()
            }, 500)
        }
    }
    
    /**
     * Check if nearby devices permission is granted
     * Required for Android 12+ (API 31+) for WiFi P2P/WiFi Direct
     */
    private fun checkNearbyDevicesPermission() {
        val permissionsToRequest = mutableListOf<String>()
        
        // Android 12+ requires NEARBY_WIFI_DEVICES
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.NEARBY_WIFI_DEVICES) 
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(android.Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }
        
        // Fine location is still needed for some WiFi operations
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) 
            != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
        
        // WiFi state permissions
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_WIFI_STATE) 
            != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(android.Manifest.permission.ACCESS_WIFI_STATE)
        }
        
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CHANGE_WIFI_STATE) 
            != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(android.Manifest.permission.CHANGE_WIFI_STATE)
        }
        
        if (permissionsToRequest.isNotEmpty()) {
            Log.i(TAG, "📱 Requesting permissions: $permissionsToRequest")
            nearbyPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            Log.i(TAG, "✅ All nearby permissions already granted")
            initializeWifiP2p()
        }
    }
    
    /**
     * Initialize WiFi P2P after permissions are granted
     */
    private fun initializeWifiP2p() {
        wifiP2pHelper.initialize()
        wifiP2pHelper.registerReceiver()
        wifiP2pHelper.setCallback(wifiP2pCallback)
        
        // 🆕 Register BLE notification listener for Glass IP (type 8)
        // This receives IP directly from Glass via BLE - much faster than subnet scanning!
        LargeDataHandler.getInstance().addOutDeviceListener(3, bleGlassIpListener)
        Log.i(TAG, "📡 BLE Glass IP listener registered")
        
        // 🎥 Register listeners for suspected camera frame ACTIONs (115, 120, 130 range)
        // These may deliver large 5KB-100KB camera frames instead of small 46-byte AI packets
        registerCameraFrameListeners()
        
        // 🛑 Register stop streaming broadcast receiver
        val stopFilter = IntentFilter(ACTION_STOP_VISION_STREAMING)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stopStreamingReceiver, stopFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stopStreamingReceiver, stopFilter)
        }
        Log.i(TAG, "🛑 Stop streaming receiver registered")
        
        Log.i(TAG, "📶 WiFi P2P initialized")
    }

    /**
     * Try to register a BigData-like listener on LargeDataHandler using reflection.
     * This creates a dynamic proxy that forwards incoming ByteArray payloads to the
     * `bleContinuousStreamManager.acceptBigData()` method.
     */
    private fun tryRegisterBigDataListener() {
        try {
            val handler = LargeDataHandler.getInstance()
            val cls = handler.javaClass
            val methods = cls.methods

            for (m in methods) {
                // Candidate registration methods typically start with add/set and take one param
                if (!(m.name.startsWith("add") || m.name.startsWith("set"))) continue
                val params = m.parameterTypes
                if (params.size != 1) continue
                val param = params[0]
                if (!param.isInterface) continue
                val pname = param.name.lowercase()
                if (!(pname.contains("big") || pname.contains("data") || pname.contains("file") || pname.contains("img"))) continue

                try {
                    val proxy = java.lang.reflect.Proxy.newProxyInstance(
                        param.classLoader,
                        arrayOf(param),
                        java.lang.reflect.InvocationHandler { _proxy, method, args ->
                            try {
                                val mname = method.name.lowercase()
                                // Look for finish/complete style callbacks with ByteArray data
                                if (args != null && args.isNotEmpty()) {
                                    for (arg in args) {
                                        if (arg is ByteArray) {
                                            Log.i(TAG, "🚀 BigData proxy received ${arg.size} bytes via ${m.name}.${method.name}")
                                            bleContinuousStreamManager?.acceptBigData(arg)
                                            break
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "BigData proxy handler error: ${e.message}")
                            }
                            null
                        }
                    )

                    m.invoke(handler, proxy)
                    Log.i(TAG, "✅ Registered big-data listener via ${m.name} (param ${param.name})")
                    return
                } catch (e: Exception) {
                    Log.w(TAG, "Attempt to register via ${m.name} failed: ${e.message}")
                }
            }

            // Spy fallback: list candidate methods for manual inspection
            Log.w("SpyMode", "🕵️‍♂️ BigData registration not auto-registered — dumping candidate methods:")
            for (m in methods) {
                if (m.name.contains("Listener") || m.name.contains("Callback") || m.name.contains("BigData") || m.name.contains("File") || m.name.contains("Img")) {
                    Log.w("SpyMode", "👉 FOUND METHOD: ${m.name}")
                    for (p in m.parameterTypes) {
                        Log.d("SpyMode", "   - Takes param: ${p.name}")
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "tryRegisterBigDataListener error: ${e.message}")
        }
    }

    /**
     * Register listeners for suspected camera frame ACTION codes.
     * ACTION_GPT_UPLOAD (89) only gives 46-byte AI packets.
     * Try ACTION codes in 115, 120, 130 range for large camera frames (5KB-100KB).
     */
    private fun registerCameraFrameListeners() {
        try {
            val handler = LargeDataHandler.getInstance()
            val suspectedActionCodes = listOf(115, 120, 130, 110, 125, 135, 140)
            
            for (actionCode in suspectedActionCodes) {
                try {
                    // Create listener interface dynamically
                    val listenerClass = Class.forName("com.oudmon.bleserver.listener.OutDeviceListener")
                    val proxy = java.lang.reflect.Proxy.newProxyInstance(
                        listenerClass.classLoader,
                        arrayOf(listenerClass),
                        java.lang.reflect.InvocationHandler { _, method, args ->
                            try {
                                if (args != null && args.isNotEmpty()) {
                                    for ((idx, arg) in args.withIndex()) {
                                        if (arg == null) continue
                                        
                                        // Log size of all incoming data
                                        val argClass = arg.javaClass
                                        Log.d(TAG, "🎥 ACTION[$actionCode] callback ${method.name} arg[$idx] type=${argClass.simpleName}")
                                        
                                        // Dump methods/fields once per class
                                        if (!dumpedClasses.contains(argClass.name)) {
                                            dumpedClasses.add(argClass.name)
                                            Log.d(TAG, "🔬 ========== CAMERA FRAME DUMP ACTION[$actionCode] ${argClass.simpleName} ==========")
                                            for (m in argClass.declaredMethods) {
                                                val params = m.parameterTypes.joinToString(", ") { it.simpleName }
                                                Log.d(TAG, "  📋 Method: ${m.name}($params) -> ${m.returnType.simpleName}")
                                            }
                                            for (f in argClass.declaredFields) {
                                                f.isAccessible = true
                                                try {
                                                    val value = f.get(arg)
                                                    val valueStr = when {
                                                        value is ByteArray -> "byte[${value.size}] 🚨 LARGE ARRAY!"
                                                        value is Array<*> -> "Array[${value.size}]"
                                                        value is Collection<*> -> "${value.javaClass.simpleName}[${value.size}]"
                                                        else -> value?.toString() ?: "null"
                                                    }
                                                    Log.d(TAG, "  📦 Field: ${f.name} (${f.type.simpleName}) = $valueStr")
                                                } catch (e: Exception) {
                                                    Log.d(TAG, "  📦 Field: ${f.name} (${f.type.simpleName}) = [error]")
                                                }
                                            }
                                            Log.d(TAG, "🔬 ========== END CAMERA DUMP ==========")
                                        }
                                        
                                        // Direct ByteArray
                                        if (arg is ByteArray) {
                                            Log.i(TAG, "🎥 ACTION[$actionCode] direct ByteArray: ${arg.size} bytes")
                                            if (arg.size > 100) {
                                                Log.w(TAG, "🚨 LARGE PACKET DETECTED! ${arg.size} bytes from ACTION[$actionCode]")
                                                bleContinuousStreamManager?.acceptBigData(arg)
                                            }
                                            return@InvocationHandler null
                                        }
                                        
                                        // Extract bytes via reflection
                                        val bytes = extractAllBytesFromObject(arg)
                                        if (bytes != null) {
                                            Log.i(TAG, "🎥 ACTION[$actionCode] extracted: ${bytes.size} bytes")
                                            if (bytes.size > 100) {
                                                Log.w(TAG, "🚨 LARGE PACKET EXTRACTED! ${bytes.size} bytes from ACTION[$actionCode]")
                                                bleContinuousStreamManager?.acceptBigData(bytes)
                                            }
                                        }
                                    }
                                }
                                null
                            } catch (e: Exception) {
                                Log.e(TAG, "🎥 ACTION[$actionCode] callback error: ${e.message}")
                                null
                            }
                        }
                    )
                    
                    // Register via addOutDeviceListener(actionCode, listener)
                    handler.javaClass.getMethod("addOutDeviceListener", Int::class.java, listenerClass)
                        .invoke(handler, actionCode, proxy)
                    Log.i(TAG, "🎥 Registered camera frame listener for ACTION[$actionCode]")
                    
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to register ACTION[$actionCode]: ${e.message}")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "registerCameraFrameListeners error: ${e.message}")
        }
    }

    /**
     * Register a listener for ACTION_GPT_UPLOAD (89 / 0x59) packets.
     * This is where bc59 image data packets are routed by the SDK.
     * Uses initPackageNotify() which maps to respMap.put(89, listener).
     */
    private fun registerGptUploadListener() {
        try {
            val handler = LargeDataHandler.getInstance()

            // Try to find initPackageNotify method via reflection
            val methods = handler.javaClass.methods
            for (m in methods) {
                if (m.name == "initPackageNotify") {
                    val params = m.parameterTypes
                    if (params.size == 1 && params[0].isInterface) {
                        val listenerInterface = params[0]
                        Log.i(TAG, "🎯 Found initPackageNotify with interface: ${listenerInterface.name}")

                        // Create dynamic proxy for the listener interface
                        val proxy = java.lang.reflect.Proxy.newProxyInstance(
                            listenerInterface.classLoader,
                            arrayOf(listenerInterface),
                            java.lang.reflect.InvocationHandler { _, method, args ->
                                try {
                                    val methodName = method.name
                                    Log.i(TAG, "📡 GPT_UPLOAD callback: ${methodName}")

                                    // Handle different callback methods
                                    if (args != null && args.isNotEmpty()) {
                                        // Log full argument structure for debugging
                                        for ((idx, arg) in args.withIndex()) {
                                            if (arg == null) continue
                                            
                                            val argClass = arg.javaClass
                                            Log.d(TAG, "  arg[$idx] type: ${argClass.name}")
                                            
                                            // 🔍 STEP 1: DUMP ALL METHODS of this class (ONE TIME ONLY)
                                            if (!dumpedClasses.contains(argClass.name)) {
                                                dumpedClasses.add(argClass.name)
                                                Log.d(TAG, "🔬 ========== METHOD DUMP for ${argClass.simpleName} ==========")
                                                for (m in argClass.declaredMethods) {
                                                    val params = m.parameterTypes.joinToString(", ") { it.simpleName }
                                                    Log.d(TAG, "  📋 Method: ${m.name}($params) -> ${m.returnType.simpleName}")
                                                }
                                                Log.d(TAG, "🔬 ========== FIELD DUMP for ${argClass.simpleName} ==========")
                                                for (f in argClass.declaredFields) {
                                                    f.isAccessible = true
                                                    try {
                                                        val value = f.get(arg)
                                                        val valueStr = when {
                                                            value is ByteArray -> "byte[${value.size}]"
                                                            value is Array<*> -> "Array[${value.size}]"
                                                            value is Collection<*> -> "${value.javaClass.simpleName}[${value.size}]"
                                                            else -> value?.toString() ?: "null"
                                                        }
                                                        Log.d(TAG, "  📦 Field: ${f.name} (${f.type.simpleName}) = $valueStr")
                                                    } catch (e: Exception) {
                                                        Log.d(TAG, "  📦 Field: ${f.name} (${f.type.simpleName}) = [access error]")
                                                    }
                                                }
                                                Log.d(TAG, "🔬 ========== END DUMP ==========")
                                            }
                                            
                                            // Direct ByteArray
                                            if (arg is ByteArray && arg.size > 10) {
                                                Log.i(TAG, "🚀 GPT_UPLOAD direct ByteArray: ${arg.size} bytes")
                                                bleContinuousStreamManager?.acceptBigData(arg)
                                                return@InvocationHandler null
                                            }
                                            
                                            // Check if it's AiChatResponse or similar - dump ALL fields
                                            val bytes = extractAllBytesFromObject(arg)
                                            if (bytes != null && bytes.size > 10) {
                                                Log.i(TAG, "🚀 GPT_UPLOAD extracted: ${bytes.size} bytes")
                                                bleContinuousStreamManager?.acceptBigData(bytes)
                                                return@InvocationHandler null
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "initPackageNotify proxy error: ${e.message}")
                                }
                                null
                            }
                        )

                        m.invoke(handler, proxy)
                        Log.i(TAG, "✅ Registered GPT_UPLOAD (89) listener via initPackageNotify!")
                        return
                    }
                }
            }

            Log.w(TAG, "⚠️ initPackageNotify method not found or not compatible")

        } catch (e: Exception) {
            Log.e(TAG, "registerGptUploadListener error: ${e.message}")
        }
    }

    /**
     * Hook into SDK's internal BLE notification handler to capture raw bc59 packets.
     * This intercepts packets BEFORE the SDK processes them.
     */
    private fun registerRawBlePacketInterceptor() {
        try {
            val handler = LargeDataHandler.getInstance()
            
            // Look for BleManager or BleHelper in handler
            for (field in handler.javaClass.declaredFields) {
                try {
                    field.isAccessible = true
                    val value = field.get(handler) ?: continue
                    val clsName = value.javaClass.name.lowercase()
                    
                    if (clsName.contains("ble") || clsName.contains("gatt") || clsName.contains("bluetooth")) {
                        Log.i(TAG, "🔍 Found BLE field: ${field.name} -> ${value.javaClass.name}")
                        
                        // Try to find and hook notification callback
                        hookBleNotificationCallback(value)
                    }
                } catch (_: Exception) {}
            }
            
            // Also try to find and hook LargeDataParser
            for (field in handler.javaClass.declaredFields) {
                try {
                    field.isAccessible = true
                    val value = field.get(handler) ?: continue
                    val clsName = value.javaClass.name.lowercase()
                    
                    if (clsName.contains("parser") || clsName.contains("largedata")) {
                        Log.i(TAG, "🔍 Found Parser field: ${field.name} -> ${value.javaClass.name}")
                        hookParserReceiveData(value)
                    }
                } catch (_: Exception) {}
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "registerRawBlePacketInterceptor error: ${e.message}")
        }
    }
    
    /**
     * Hook BLE notification callback to intercept raw packets
     */
    private fun hookBleNotificationCallback(bleManager: Any) {
        try {
            val cls = bleManager.javaClass
            
            // Find notification callback field
            for (field in cls.declaredFields) {
                val fname = field.name.lowercase()
                if (fname.contains("callback") || fname.contains("listener") || fname.contains("notify")) {
                    try {
                        field.isAccessible = true
                        val callback = field.get(bleManager) ?: continue
                        Log.d(TAG, "🎯 BLE callback field: ${field.name} = ${callback.javaClass.name}")
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "hookBleNotificationCallback error: ${e.message}")
        }
    }
    
    /**
     * Hook LargeDataParser's receiveData method
     */
    private fun hookParserReceiveData(parser: Any) {
        try {
            val cls = parser.javaClass
            Log.d(TAG, "🔍 Parser class methods:")
            for (m in cls.declaredMethods) {
                Log.d(TAG, "  📌 ${m.name}(${m.parameterTypes.joinToString { it.simpleName }})")
            }
        } catch (e: Exception) {
            Log.e(TAG, "hookParserReceiveData error: ${e.message}")
        }
    }

    /**
     * Extract the LARGEST ByteArray from an object (ignore small status arrays).
     * The image payload is typically 30+ bytes per packet.
     * 
     * 🚨 ENHANCED: Logs ALL discovered ByteArray fields/methods with sizes for debugging.
     */
    private fun extractAllBytesFromObject(obj: Any?, depth: Int = 0): ByteArray? {
        if (obj == null || depth > 4) return null
        if (obj is ByteArray) return if (obj.size > 10) obj else null

        var largestBytes: ByteArray? = null
        val allArrays = mutableListOf<Pair<String, Int>>() // Track field/method name + size
        
        try {
            val cls = obj.javaClass

            // Try common getter methods
            for (methodName in listOf("getLoadData", "getData", "getBytes", "getContent", "getPayload", "getImageData", "getRawData", "getBuffer", "getFrame")) {
                try {
                    val m = cls.getMethod(methodName)
                    val result = m.invoke(obj)
                    if (result is ByteArray) {
                        allArrays.add(Pair("${methodName}()", result.size))
                        if (result.size > (largestBytes?.size ?: 10)) {
                            largestBytes = result
                            Log.d(TAG, "✅ Method ${methodName}() returned byte[${result.size}]")
                            if (result.size > 500) {
                                Log.w(TAG, "🚨 LARGE ARRAY from ${methodName}(): ${result.size} bytes!")
                            }
                        }
                    } else if (result != null && result !is Number && result !is String) {
                        val nested = extractAllBytesFromObject(result, depth + 1)
                        if (nested != null && nested.size > (largestBytes?.size ?: 10)) {
                            largestBytes = nested
                        }
                    }
                } catch (_: Exception) {}
            }

            // Try ALL declared fields
            for (field in cls.declaredFields) {
                try {
                    field.isAccessible = true
                    val value = field.get(obj) ?: continue
                    
                    if (value is ByteArray) {
                        allArrays.add(Pair(field.name, value.size))
                        if (value.size > (largestBytes?.size ?: 10)) {
                            Log.d(TAG, "✅ Field ${field.name} = byte[${value.size}]")
                            if (value.size > 500) {
                                Log.w(TAG, "🚨 LARGE ARRAY FIELD: ${field.name} = ${value.size} bytes!")
                            }
                            largestBytes = value
                        }
                    } else if (value !is Number && value !is String && value !is Boolean) {
                        val nested = extractAllBytesFromObject(value, depth + 1)
                        if (nested != null && nested.size > (largestBytes?.size ?: 10)) {
                            largestBytes = nested
                        }
                    }
                } catch (_: Exception) {}
            }
            
            // Also check superclass fields
            var superCls = cls.superclass
            while (superCls != null && superCls != Any::class.java) {
                for (field in superCls.declaredFields) {
                    try {
                        field.isAccessible = true
                        val value = field.get(obj) ?: continue
                        if (value is ByteArray) {
                            allArrays.add(Pair("super.${field.name}", value.size))
                            if (value.size > (largestBytes?.size ?: 10)) {
                                Log.d(TAG, "✅ Superclass field ${field.name} = byte[${value.size}]")
                                if (value.size > 500) {
                                    Log.w(TAG, "🚨 LARGE ARRAY SUPERCLASS: ${field.name} = ${value.size} bytes!")
                                }
                                largestBytes = value
                            }
                        }
                    } catch (_: Exception) {}
                }
                superCls = superCls.superclass
            }
            
            // Log all discovered ByteArray fields/methods (even small ones)
            if (allArrays.isNotEmpty() && depth == 0) {
                Log.d(TAG, "📊 All ByteArrays in ${cls.simpleName}: ${allArrays.joinToString { "${it.first}[${it.second}]" }}")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "extractAllBytesFromObject error: ${e.message}")
        }
        
        return largestBytes
    }
    
        /**
     * Recursively extract ByteArray from an object using reflection (depth-limited).
     * DEPRECATED - use extractAllBytesFromObject instead
     */
    private fun extractBytesDeep(obj: Any?, depth: Int = 0): ByteArray? {
        return extractAllBytesFromObject(obj, depth)
    }

    /**
     * Hook into SDK's receiveData() method to capture raw bc59 packets.
     * This is the most direct way to get the raw BLE packet bytes.
     */
    private fun hookSdkReceiveData() {
        try {
            val handler = LargeDataHandler.getInstance()
            
            // Find parser/decoder objects that process raw packets
            for (field in handler.javaClass.declaredFields) {
                try {
                    field.isAccessible = true
                    val value = field.get(handler) ?: continue
                    val clsName = value.javaClass.simpleName
                    
                    Log.d(TAG, "🔍 LargeDataHandler field: ${field.name} -> $clsName")
                    
                    // Look for respMap which maps action codes to listeners
                    if (field.name.contains("resp") || field.name.contains("map") || field.name.contains("Map")) {
                        if (value is Map<*, *>) {
                            Log.i(TAG, "📍 Found respMap with ${value.size} entries")
                            for ((key, v) in value) {
                                Log.d(TAG, "  📌 action=$key -> ${v?.javaClass?.simpleName}")
                            }
                        }
                    }
                    
                    // Look for data buffer
                    if (value is ByteArray) {
                        Log.d(TAG, "  📦 Buffer field: ${field.name} (${value.size} bytes)")
                    }
                    
                } catch (_: Exception) {}
            }
            
            // Now try to find the BleCallBackAdapter or similar that receives raw packets
            val bleManagerClass = try {
                Class.forName("com.oudmon.ble.base.BleManager")
            } catch (_: Exception) {
                try { Class.forName("com.oudmon.ble.BleManager") } catch (_: Exception) { null }
            }
            
            if (bleManagerClass != null) {
                Log.i(TAG, "🎯 Found BleManager class: ${bleManagerClass.name}")
                
                // Try to get instance
                try {
                    val getInstanceMethod = bleManagerClass.getMethod("getInstance")
                    val bleManager = getInstanceMethod.invoke(null)
                    if (bleManager != null) {
                        Log.i(TAG, "✅ Got BleManager instance")
                        dumpBleManagerStructure(bleManager)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not get BleManager instance: ${e.message}")
                }
            }
            
            // Try to find and wrap the notification callback
            wrapBleNotificationCallback()
            
        } catch (e: Exception) {
            Log.e(TAG, "hookSdkReceiveData error: ${e.message}")
        }
    }
    
    /**
     * Dump BleManager structure to understand packet flow
     */
    private fun dumpBleManagerStructure(bleManager: Any) {
        try {
            val cls = bleManager.javaClass
            Log.d(TAG, "📋 BleManager fields:")
            
            for (field in cls.declaredFields) {
                try {
                    field.isAccessible = true
                    val value = field.get(bleManager)
                    val valueName = value?.javaClass?.simpleName ?: "null"
                    Log.d(TAG, "  📌 ${field.name}: $valueName")
                    
                    // Look for callback/listener fields
                    val fname = field.name.lowercase()
                    if (fname.contains("callback") || fname.contains("listener") || fname.contains("notify")) {
                        if (value != null) {
                            Log.i(TAG, "  🎯 Potential callback: ${field.name} = ${value.javaClass.name}")
                        }
                    }
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "dumpBleManagerStructure error: ${e.message}")
        }
    }
    
    /**
     * Attempt to wrap the BLE notification callback to intercept raw packets
     */
    private fun wrapBleNotificationCallback() {
        try {
            // Find BleCallBackAdapter class
            val callbackClass = try {
                Class.forName("com.oudmon.ble.base.callback.BleCallBackAdapter")
            } catch (_: Exception) {
                try { Class.forName("com.oudmon.ble.callback.BleCallBackAdapter") } catch (_: Exception) { null }
            }
            
            if (callbackClass != null) {
                Log.i(TAG, "🎯 Found BleCallBackAdapter: ${callbackClass.name}")
                
                // List methods that receive data
                for (m in callbackClass.methods) {
                    if (m.parameterTypes.any { it == ByteArray::class.java }) {
                        Log.d(TAG, "  📡 ByteArray method: ${m.name}(${m.parameterTypes.joinToString { it.simpleName }})")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "wrapBleNotificationCallback error: ${e.message}")
        }
    }
    
    /**
     * Speed up BLE image transfer. See [com.sdk.glassessdksample.ui.BleSpeedTuner]
     * — shared with every other Mark 2 screen that pulls images from the glasses.
     */
    private fun requestHighMtu() {
        com.sdk.glassessdksample.ui.BleSpeedTuner.tune(this, "VisionChat")
    }

    /**
     * Check if we have nearby devices permission
     */
    private fun hasNearbyPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * WiFi P2P Callback - handles discovery and connection events
     */
    private val wifiP2pCallback = object : WifiP2pHelper.WifiP2pCallback {
        override fun onP2pStateChanged(enabled: Boolean) {
            Log.d(TAG, "📶 P2P State: ${if (enabled) "ENABLED" else "DISABLED"}")
            if (!enabled) {
                showError("WiFi Direct is disabled. Please enable WiFi.")
            }
        }
        
        override fun onPeersDiscovered(peers: List<WifiP2pDevice>) {
            Log.d(TAG, "👥 Discovered ${peers.size} peers")
            
            if (isWaitingForConnection && peers.isNotEmpty()) {
                // 🆕 SILENT AUTO-CONNECT: Find Glass device and connect without showing dialog
                val glassDevice = peers.find { device ->
                    val name = device.deviceName.lowercase()
                    name.contains("m01") || name.contains("glass") || 
                    name.contains("heycyan") || name.contains("cyan") ||
                    name.contains("cy01") || name.contains("bond")
                }
                
                val deviceToConnect = glassDevice ?: peers[0] // Use first device if no glass found
                
                Log.i(TAG, "👉 Auto-connecting to: ${deviceToConnect.deviceName}")
                wifiP2pHelper.connectToDevice(deviceToConnect)
                
                // Show brief feedback without dialog
                runOnUiThread {
                    Toast.makeText(this@VisionChatActivity, "🔗 Connecting...", Toast.LENGTH_SHORT).show()
                }
            }
        }
        
        override fun onGlassConnected(glassIp: String) {
            Log.i(TAG, "✅ Glass connected at: $glassIp")
            glassIpAddress = glassIp
            isWaitingForConnection = false
            
            runOnUiThread {
                // Technical message hidden from user
                Log.d(TAG, "✅ WiFi Connected ($glassIp)")
                
                // FIX: Pass the IP directly to avoid re-scan
                mainScope.launch {
                    downloadAndAnalyzeImage(glassIp)
                }
            }
        }
        
        override fun onP2pDisconnected() {
            Log.w(TAG, "⚠️ P2P Disconnected")
            glassIpAddress = null
        }
        
        override fun onP2pError(message: String) {
            Log.w(TAG, "⚠️ P2P failed: $message, falling back to hotspot mode")
            // ✅ DON'T show error - hotspot + HTTP scan can still succeed
            // P2P is just one connection method, not the only one
            // isWaitingForConnection will be handled by hotspot success or timeout
        }
    }
    
    /**
     * Setup WiFi connection monitoring - detects when phone connects to Glass hotspot
     */
    private fun setupWifiMonitoring() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "📶 Network available")
                checkAndConnectToGlass()
            }
            
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                Log.d(TAG, "📶 Network capabilities changed")
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    checkAndConnectToGlass()
                }
            }
        }
        
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        
        connectivityManager?.registerNetworkCallback(request, networkCallback!!)
        Log.d(TAG, "📶 WiFi monitoring started")
    }
    
    /**
     * Check if connected to Glass and download image if waiting
     */
    private fun checkAndConnectToGlass() {
        if (!isWaitingForConnection) return
        
        mainScope.launch {
            val ip = withContext(Dispatchers.IO) {
                scanForGlassServer()
            }
            
            if (ip != null) {
                Log.i(TAG, "✅ Found Glass server at: $ip")
                glassIpAddress = ip
                isWaitingForConnection = false
                
                // Technical message hidden from user
                Log.d(TAG, "✅ WiFi Connected ($ip)")
                
                // FIX: Pass the IP directly
                downloadAndAnalyzeImage(ip)
            }
        }
    }
    
    /**
     * Scan for Glass HTTP server.
     * OPTIMIZED: Checks Gateway IP first (Hotspot mode), then falls back to scan.
     */
    private suspend fun scanForGlassServer(): String? {
        // 1. 🚀 FAST PATH: Check Gateway IP (Hotspot/P2P Group Owner)
        // When phone connects to Glass, Glass is usually the Gateway (e.g., 192.168.43.1)
        val gatewayIp = getGlassGatewayIp()
        
        if (gatewayIp != null) {
            Log.d(TAG, "🔍 Checking Gateway IP: $gatewayIp")
            if (isGlassServerReachable(gatewayIp)) {
                Log.i(TAG, "🎯 Found Glass at Gateway IP: $gatewayIp")
                return gatewayIp
            }
        }
        
        // 2. 🐢 SLOW PATH: Fallback to AlbumDownloader scan (if Gateway failed)
        Log.w(TAG, "⚠️ Gateway unreachable, falling back to full scan...")
        return albumDownloader.discoverGlassesIP()
    }
    
    /**
     * 🚀 FASTEST METHOD: Get the Gateway IP (The Glass) directly from WiFi settings.
     * Since Phone connects to Glass Hotspot, Glass IS the Gateway.
     * No scanning required. 0.01s execution time.
     */
    private fun getGlassGatewayIp(): String? {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val dhcp = wifiManager.dhcpInfo
            val gateway = dhcp.gateway
            
            if (gateway == 0) return null
            
            // Convert integer IP to String
            return String.format(
                "%d.%d.%d.%d",
                (gateway and 0xff),
                (gateway shr 8 and 0xff),
                (gateway shr 16 and 0xff),
                (gateway shr 24 and 0xff)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting gateway IP: ${e.message}")
            return null
        }
    }
    
    /**
     * Quick check if port 80 is open on the specific IP
     */
    private fun isGlassServerReachable(ip: String): Boolean {
        return try {
            val socket = java.net.Socket()
            val address = java.net.InetSocketAddress(ip, 80)
            // Fast timeout (500ms is enough if we are directly connected)
            socket.connect(address, 500)
            socket.close()
            true
        } catch (e: Exception) {
            false
        }
    }
    
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("en", "IN"))
            isTtsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
            tts?.setSpeechRate(0.95f) // Slightly slower for clarity
            tts?.setPitch(1.0f)
            
            // ✅ Optimize audio for voice communication mode
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val audioAttributes = android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                tts?.setAudioAttributes(audioAttributes)
                Log.d(TAG, "🔊 TTS audio optimized: VOICE_COMMUNICATION mode")
            }
            
            // 🆕 Add listener to detect when TTS finishes speaking
            tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.d(TAG, "🔊 TTS started: $utteranceId")
                }
                
                override fun onDone(utteranceId: String?) {
                    Log.d(TAG, "🔊 TTS done: $utteranceId")
                    // Note: Vision result now goes through Gemini Live, not TTS
                }
                
                override fun onError(utteranceId: String?) {
                    Log.e(TAG, "🔊 TTS error: $utteranceId")
                }
            })
            
            Log.d(TAG, "🔊 TTS initialized: ready=$isTtsReady")
        }
    }
    
    private var visionResultSpokenReceiver: android.content.BroadcastReceiver? = null
    private var visionResultSpokenTimeoutRunnable: Runnable? = null

    /**
     * 🆕 Turn wake-word detection OFF for the vision window. The detector shares
     * the glasses' SCO mic; a wake word landing during capture/download/analysis
     * starts a fresh Gemini Live session and destroys the one that is about to
     * speak this answer. See MainActivity.visionBusy.
     */
    private fun engageVisionWakeSuppression() {
        if (MainActivity.visionBusy) return
        MainActivity.visionBusy = true
        try {
            HotHelper.getInstance(this).setSuppressed(true)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to suppress wake word for vision: ${e.message}")
        }
        Log.i(TAG, "👁️ Wake word suppressed for vision capture/analysis")
    }

    /**
     * 🆕 Release the vision window. Used by paths that finish inside this
     * activity (standalone TTS), where MainActivity never runs the resume flow
     * that would otherwise release it.
     */
    private fun releaseVisionWakeSuppression(reason: String) {
        if (!MainActivity.visionBusy) return
        MainActivity.visionBusy = false
        try {
            HotHelper.getInstance(this).setSuppressed(false)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release wake suppression ($reason): ${e.message}")
        }
        Log.i(TAG, "👁️ Wake word suppression released ($reason)")
    }

    /**
     * 🆕 Resume Gemini Live conversation and close this activity
     * Sends broadcast to MainActivity to restart Gemini Live with vision result.
     * 🆕 Does NOT finish on a fixed timer anymore - a dead/reconnecting Gemini Live
     * session can take longer than any fixed delay to actually speak, and closing
     * this screen before that happens loses the summary and confuses the user
     * ("it exits vision chat without speaking"). Instead this waits for
     * MainActivity to confirm (via ACTION_VISION_RESULT_SPOKEN) that speaking has
     * actually started, with a generous timeout as a safety net only.
     * @param visionText The vision analysis result for Gemini Live to speak
     */
    private fun resumeGeminiLiveAndFinish(visionText: String? = null) {
        runOnUiThread {
            Log.i(TAG, "📡 Broadcasting resume Gemini Live with vision text: ${visionText?.take(50)}...")

            val lbm = androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this)

            if (visionResultSpokenReceiver == null) {
                visionResultSpokenReceiver = object : android.content.BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        Log.d(TAG, "👋 Gemini Live confirmed speaking - finishing VisionChatActivity")
                        finishAfterVisionResultResolved()
                    }
                }
                lbm.registerReceiver(
                    visionResultSpokenReceiver!!,
                    IntentFilter(ACTION_VISION_RESULT_SPOKEN)
                )
            }

            // Send broadcast to MainActivity to resume Gemini Live
            val resumeIntent = Intent(ACTION_RESUME_GEMINI_LIVE)
            visionText?.let {
                resumeIntent.putExtra(EXTRA_VISION_TEXT, it)
            }
            lbm.sendBroadcast(resumeIntent)

            // Safety net only - MainActivity failing to ever confirm shouldn't trap
            // the user on this screen forever.
            visionResultSpokenTimeoutRunnable?.let {
                android.os.Handler(android.os.Looper.getMainLooper()).removeCallbacks(it)
            }
            visionResultSpokenTimeoutRunnable = Runnable {
                Log.w(TAG, "⏱️ Timed out waiting for Gemini Live speak confirmation - finishing anyway")
                finishAfterVisionResultResolved()
            }
            android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(visionResultSpokenTimeoutRunnable!!, VISION_RESULT_SPOKEN_TIMEOUT_MS)
        }
    }

    private fun finishAfterVisionResultResolved() {
        visionResultSpokenTimeoutRunnable?.let {
            android.os.Handler(android.os.Looper.getMainLooper()).removeCallbacks(it)
        }
        visionResultSpokenTimeoutRunnable = null
        visionResultSpokenReceiver?.let {
            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this).unregisterReceiver(it)
        }
        visionResultSpokenReceiver = null
        finish()
    }
    
    /**
     * 🆕 Poll briefly for Gemini Live to reconnect before falling back to
     * resumeGeminiLiveAndFinish(). Avoids closing VisionChatActivity just because
     * the websocket happened to be momentarily down when the vision response landed.
     */
    private fun waitForGeminiLiveThenResume(
        visionText: String,
        attemptsLeft: Int = 6,
        retryDelayMs: Long = 300
    ) {
        val geminiLive = GeminiLiveService.getInstance()
        if (geminiLive != null && GeminiLiveService.isActive()) {
            Log.i(TAG, "🎙️ Gemini Live reconnected - speaking vision result instead of finishing")
            speakVisionResultImmediately(visionText)
            return
        }
        if (attemptsLeft <= 0) {
            Log.i(TAG, "🔄 Gemini Live still not active after waiting - resuming/finishing")
            resumeGeminiLiveAndFinish(visionText)
            return
        }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            waitForGeminiLiveThenResume(visionText, attemptsLeft - 1, retryDelayMs)
        }, retryDelayMs)
    }

    private fun speakOut(text: String) {
        if (isTtsReady && tts != null) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "vision_${System.currentTimeMillis()}")
        }
    }
    
    /**
     * 🆕 Send vision analysis result to Gemini Live IMMEDIATELY for voice output
     * Also injects vision context so Gemini remembers it for follow-up questions
     * This uses Gemini Live's streaming voice directly (no TTS, no delay)
     * 🆕 Prevents duplicate descriptions from being spoken
     */
    private fun speakVisionResultImmediately(text: String) {
        Log.d(TAG, "🔊 IMMEDIATE voice output: ${text.take(100)}...")
        
        // 🆕 Check for duplicate description - don't repeat the same description
        val trimmedText = text.trim()
        if (lastSpokenDescription != null && 
            trimmedText.equals(lastSpokenDescription, ignoreCase = true)) {
            Log.w(TAG, "🚫 DUPLICATE DESCRIPTION - skipping voice output (already spoken)")
            return
        }
        
        // Check if Gemini Live is currently active
        val geminiLive = GeminiLiveService.getInstance()
        
        if (geminiLive != null && GeminiLiveService.isActive()) {
            // 🆕 Gemini Live is active - inject context and speak immediately!
            Log.i(TAG, "🎙️ Using ACTIVE Gemini Live - injecting vision context + speaking COMPLETELY")
            
            // 🆕 Store as last spoken to prevent duplicates
            lastSpokenDescription = trimmedText
            
            // REMOVED: injectVisionContext(text) - Calling speakText ALREADY puts it in context!
            // Sending it twice confuses the model and can cause double-speaking.
            // geminiLive.injectVisionContext(text)
            
            // Mark as speaking
            isSpeaking = true
            Log.d(TAG, "🔊 Started speaking - isSpeaking = true")
            
            // 🔊 UNMUTE Gemini Live BEFORE speaking vision result
            geminiLive.unmuteOutput()
            Log.d(TAG, "🔊 Gemini Live UNMUTED - ready to speak vision description")
            
            // 🆕 Speak COMPLETE description - Gemini Live will speak it fully
            // speakDirectly = true means Gemini will read the ENTIRE text without stopping
            geminiLive.speakText(text, speakDirectly = true)

            // 🆕 Answer is being spoken on the live session - vision window over.
            releaseVisionWakeSuppression("spoken-via-gemini-live")

            // ⛔ DISABLE auto-capture completely - user must manually trigger capture
            Log.d(TAG, "🛑 Auto-capture DISABLED - manual capture mode only")
            shouldAutoCaptureNext = false
            waitingForNextCapture = false
            
            // Estimate speaking time: ~2 words per second for precise descriptions
            val wordCount = text.split(" ").size
            val estimatedSpeakTimeMs = (wordCount / 2.0 * 1000).toLong() + 3000L // +3s buffer
            
            mainScope.launch {
                delay(estimatedSpeakTimeMs)
                if (shouldAutoCaptureNext && !waitingForNextCapture) {
                    Log.i(TAG, "🔄 Auto-capturing next photo after description complete")
                    waitingForNextCapture = true
                    isSpeaking = false
                    shouldAutoCaptureNext = false
                    
                    // Auto-capture next photo
                    startCaptureProcess()
                }
            }
            
            Log.d(TAG, "🔊 Vision description sent - will auto-capture in ~${estimatedSpeakTimeMs/1000}s")
            
        } else if (shouldResumeGeminiLive) {
            // 🆕 Gemini Live isn't active RIGHT NOW, but it may just be mid-reconnect
            // (e.g. a brief websocket drop that coincides with the vision response
            // arriving). Give it a short grace period to come back before treating
            // this as "user is done" and closing the screen — otherwise this race
            // causes VisionChatActivity to randomly finish() right after a summary.
            Log.i(TAG, "🔄 Gemini Live not active - waiting briefly for reconnect before resuming/finishing")
            waitForGeminiLiveThenResume(text)

        } else {
            // 🆕 Standalone mode (opened directly, not via Gemini Live): speak the
            // description with the local TTS so the user ALWAYS hears the result.
            // Previously this path was silent, so a direct capture never "told" the
            // user what it saw.
            Log.d(TAG, "🔊 Gemini Live unavailable — speaking vision result via TTS")
            lastSpokenDescription = trimmedText
            speakOut(text)
            // 🆕 Standalone path finishes here - MainActivity never runs the resume
            // flow, so release the vision window ourselves.
            releaseVisionWakeSuppression("spoken-via-tts")
        }
    }
    
    /**
     * 🎥 Start continuous vision streaming (live video feel)
     * Analyzes image every 7 seconds and speaks results continuously
     * Creates illusion of watching live video with AI commentary
     */
    private fun startContinuousVisionStream() {
        if (isContinuousStreaming) {
            Log.d(TAG, "⚠️ Continuous streaming already active")
            return
        }
        
        isContinuousStreaming = true
        Log.i(TAG, "🎥 Starting continuous vision stream (7s cycles) - capturing NEW frames")
        
        streamingJob = mainScope.launch {
            try {
                // Wait for first capture to complete before starting continuous loop
                delay(5000L)  // Wait 5 seconds for first capture to complete
                
                while (isContinuousStreaming) {
                    // Wait for previous audio to finish before triggering new capture
                    var audioWaitCount = 0
                    while (isSpeaking && audioWaitCount < 60) {  // Wait max 30 seconds for audio
                        delay(500)
                        audioWaitCount++
                        if (audioWaitCount % 4 == 0) {
                            Log.d(TAG, "⏳ Waiting for audio to finish (${audioWaitCount / 2}s)...")
                        }
                    }
                    
                    if (!isContinuousStreaming) break  // Check if stopped during wait
                    
                    // Wait for previous capture processing to complete (IMPORTANT!)
                    var processingWaitCount = 0
                    while (isProcessing && processingWaitCount < 40) {  // Wait max 20 seconds
                        delay(500)
                        processingWaitCount++
                        if (processingWaitCount % 4 == 0) {
                            Log.d(TAG, "⏳ Waiting for previous capture to finish (${processingWaitCount / 2}s)...")
                        }
                    }
                    
                    if (!isContinuousStreaming) break  // Check if stopped during wait
                    
                    // Wait a bit more for glasses to be ready (cooldown period)
                    Log.d(TAG, "⏳ Cooldown before next capture (3s)...")
                    delay(3000L)
                    
                    if (!isContinuousStreaming) break
                    
                    // Trigger NEW camera capture from glasses
                    Log.d(TAG, "📸 Triggering camera for new frame...")
                    withContext(Dispatchers.Main) {
                        updateStatus("🎥 Capturing new frame...")
                        startCaptureProcess()  // This will trigger camera, download, and analyze
                    }
                    
                    // Wait for isProcessing to become true (confirm capture started)
                    var startWaitCount = 0
                    while (!isProcessing && startWaitCount < 10) {  // Wait max 5 seconds
                        delay(500)
                        startWaitCount++
                    }
                    
                    // Now wait for it to complete
                    Log.d(TAG, "📷 Capture triggered, waiting for completion...")
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "🛑 Streaming cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Streaming error: ${e.message}", e)
            }
        }
    }
    
    /**
     * 🆕 🎥 Start BLE-based continuous streaming (real-time to Gemini Live)
     * Uses CoV mode + Vision AI thumbnails for ultra-low latency
     * Directly streams small frames via BLE to Gemini Live
     */
    private fun startBleBasedContinuousStream() {
        Log.i(TAG, "🚀 Starting BLE-based continuous streaming...")
        frameCount = 0
        lastFrameTime = System.currentTimeMillis()
        
        bleContinuousStreamManager?.startStreaming(this) { frameBytes ->
            // ✅ Got new frame from BLE - process it!
            frameCount++
            val now = System.currentTimeMillis()
            val elapsed = now - lastFrameTime
            val fps = if (elapsed > 0) 1000.0 / elapsed else 0.0
            lastFrameTime = now
            
            Log.d(TAG, "🖼️ Frame #$frameCount: ${frameBytes.size} bytes (${String.format("%.1f", fps)} FPS)")
            
            mainScope.launch {
                try {
                    // 1. Decode and display the frame
                    val bitmap = BitmapFactory.decodeByteArray(frameBytes, 0, frameBytes.size)
                    if (bitmap != null) {
                        lastBitmap = bitmap
                        Log.d(TAG, "✅ Decoded bitmap: ${bitmap.width}x${bitmap.height}")
                        
                        withContext(Dispatchers.Main) {
                            updateStatus("🎥 Frame #$frameCount (${bitmap.width}x${bitmap.height}) - ${String.format("%.1f", fps)} FPS")
                        }
                    } else {
                        Log.w(TAG, "⚠️ Failed to decode JPEG (corrupted?)")
                    }
                    
                    // 2. Send to Gemini Live for AI commentary (every 5th frame to reduce load)
                    if (frameCount % 5 == 1) {
                        val geminiLiveService = GeminiLiveService.getInstance()
                        
                        if (geminiLiveService != null && GeminiLiveService.isActive()) {
                            try {
                                Log.d(TAG, "📤 Sending frame #$frameCount to Gemini Live...")
                                geminiLiveService.sendRealtimeImage(frameBytes)
                            } catch (e: Exception) {
                                Log.e(TAG, "❌ Failed to send frame to Gemini Live: ${e.message}")
                            }
                        }
                    }
                    
                    // 3. Save preview image periodically (every 10th frame)
                    if (frameCount % 10 == 1) {
                        try {
                            val fileName = "live_frame_${System.currentTimeMillis()}.jpg"
                            val tmpFile = File(cacheDir, fileName)
                            FileOutputStream(tmpFile).use { it.write(frameBytes) }
                            withContext(Dispatchers.Main) {
                                addMessage(VisionChatMessage(
                                    text = "Live frame #$frameCount",
                                    isUser = false,
                                    timestamp = System.currentTimeMillis(),
                                    imagePath = tmpFile.absolutePath
                                ))
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "⚠️ Failed to save preview: ${e.message}")
                        }
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error processing frame: ${e.message}")
                }
            }
        }
    }
    
    /**
     * 🛑 Stop BLE-based continuous streaming
     */
    private fun stopBleBasedContinuousStream() {
        Log.i(TAG, "🛑 Stopping BLE-based continuous streaming...")
        bleContinuousStreamManager?.stopStreaming()
    }
    
    /**
     * 🛑 Stop continuous vision streaming
     */
    private fun stopContinuousVisionStream() {
        if (!isContinuousStreaming) return
        
        Log.i(TAG, "🛑 Stopping continuous vision stream")
        isContinuousStreaming = false
        
        // ✅ Safely cancel streaming job - handle CancellationException
        try {
            streamingJob?.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "Job cancellation exception (expected): ${e.message}")
        }
        streamingJob = null
        isSpeaking = false  // Reset speaking flag
        
        updateStatus("📷 Tap button to capture from glasses")
    }
    
    /**
     * 🛑 Handle stop command (voice or broadcast)
     * Stops streaming and returns to normal Gemini Live conversation
     */
    private fun handleStopCommand() {
        Log.i(TAG, "🛑 Stop command received - stopping streaming and returning to Gemini Live")
        
        // Stop streaming
        stopContinuousVisionStream()
        
        // Return to Gemini Live for normal conversation
        if (shouldResumeGeminiLive) {
            resumeGeminiLiveAndFinish(null)
        } else {
            finish()
        }
    }
    
    private fun setupUI() {
        recyclerView = findViewById(R.id.visionChatRecyclerView)
        statusText = findViewById(R.id.ipAddressText)
        captureButton = findViewById(R.id.btnCaptureFromGlasses)
        val stopButton: Button = findViewById(R.id.btnStopAndReturn)
        val bleStreamButton: Button = findViewById(R.id.btnStartBleStream)
        
        chatAdapter = VisionChatAdapter(messages)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = chatAdapter
        
        statusText.text = "📷 Tap button to capture from glasses"
        
        // ❌ SKIP introduction message if opening directly or from voice command
        // Only show introduction if user needs manual button instructions
        val autoCapture = intent.getBooleanExtra(EXTRA_AUTO_CAPTURE, false)
        if (!autoCapture && !shouldAutoStartStreaming) {
            // Show introduction only for manual button-based workflow
            addMessage(VisionChatMessage(
                text = "👓 Vision Chat Ready!\n\n1. Tap 'Capture from Glasses'\n2. Photo captured on glasses\n3. Auto-connect via WiFi P2P\n4. Image analyzed by AI\n\n📡 Uses WiFi Direct (automatic)",
                isUser = false,
                timestamp = System.currentTimeMillis()
            ))
        } else {
            Log.d(TAG, "📸 Skipping introduction - auto-capture mode active")
        }
        
        captureButton.setOnClickListener {
            if (!isProcessing) {
                // 🆕 Start tuning immediately when capture button clicked
                startThinkingTune()
                Log.d(TAG, "🎵 Tuning started on capture button click")
                // 📸 An explicit button press ALWAYS captures a brand-new photo. Clear the
                // last image (and the stale voice query) so startCaptureProcess() goes down
                // the real capture path instead of reusing the previous image.
                userVisionQuery = null
                clearLastCapturedImage()
                startCaptureProcess()
            }
        }
        
        // 🆕 BLE Live Stream button
        var isBleStreamActive = false
        bleStreamButton.setOnClickListener {
            if (!isBleStreamActive) {
                Log.i(TAG, "🎥 Starting BLE live stream...")
                startBleBasedContinuousStream()
                isBleStreamActive = true
                bleStreamButton.text = "🛑 Stop Live Stream"
                bleStreamButton.setBackgroundColor(resources.getColor(android.R.color.holo_red_dark))
            } else {
                Log.i(TAG, "🛑 Stopping BLE live stream...")
                stopBleBasedContinuousStream()
                isBleStreamActive = false
                bleStreamButton.text = "🎥 Start Live Stream (BLE)"
                bleStreamButton.setBackgroundColor(resources.getColor(android.R.color.holo_blue_dark))
            }
        }
        
        stopButton.setOnClickListener {
            Log.i(TAG, "🛑 Manual stop button pressed")
            handleStopCommand()
        }
    }
    
    /**
     * Main capture process with SMART CONNECTION logic:
     * 1. Check if follow-up question (use last image)
     * 2. Trigger camera → 3. Check if already connected → 4. Skip P2P if connected → 5. Download → 6. Analyze
     */
    private fun startCaptureProcess() {
        // During continuous streaming, allow overlapping captures
        if (isProcessing && !isContinuousStreaming) {
            Toast.makeText(this, "Already processing...", Toast.LENGTH_SHORT).show()
            return
        }

        // 🆕 Wake word OFF for the whole capture → analysis → answer window.
        // Set here (not only in MainActivity.triggerVisionChatFromLive) so it also
        // covers VisionChat opened directly by the user rather than by voice.
        engageVisionWakeSuppression()
        
        // 🆕 Check if this is a follow-up question on the same image
        // Skip this check during continuous streaming (we always want new captures)
        if (canUseLastImage() && userVisionQuery != null && !isContinuousStreaming) {
            Log.i(TAG, "🔄 Follow-up question detected - reusing last image")
            isProcessing = true
            captureButton.isEnabled = false
            
            mainScope.launch {
                analyzeImageWithLastCapture(userVisionQuery!!)
            }
            return
        }
        
        // Check permissions before starting
        if (!hasNearbyPermission()) {
            Log.w(TAG, "⚠️ Missing nearby permission - requesting...")
            checkNearbyDevicesPermission()
            Toast.makeText(this, "Please grant nearby devices permission", Toast.LENGTH_LONG).show()
            return
        }
        
        isProcessing = true
        captureButton.isEnabled = false

        // Reset snapshot state for this fresh capture. snapshotExistingPhotos() (persistent
        // path) sets it valid again; paths that can't snapshot fall back to the
        // processed-set heuristic instead of trusting a stale snapshot.
        preCaptureSnapshotValid = false
        preCaptureFileNames = mutableSetOf()

        // Technical message hidden from user
        Log.d(TAG, "📸 Capture started...")
        
        // TTS disabled - using Gemini Live voice instead
        // speakOut("Photo le raha hun.")
        
        mainScope.launch {
            try {
                // ══════════════════════════════════════════════════════════════════
                // 🔗 ONE-TIME PAIRING LOGIC
                //
                // RULE: Once the Glass WiFi is connected (SSID match) OR we have
                //       a cached IP from a previous capture, NEVER disconnect WiFi,
                //       NEVER start P2P discovery again.
                //
                //  • alreadyOnGlassWifi = phone is currently on Glass WiFi SSID
                //  • cachedGlassIp      = IP saved from previous successful download
                //
                //  If EITHER is true → trigger camera + find IP (if needed) + download.
                //  Only fall through to P2P when we have NEVER connected before.
                // ══════════════════════════════════════════════════════════════════

                val alreadyOnGlassWifi = isAlreadyConnectedToGlass()

                // ── Step A: probe cached IP (ALWAYS verify before trusting it) ──
                // The probe must use the SAME network HTTP will use. A plain socket goes
                // out the default (home WiFi) route and can never reach the Glass, so we
                // first try to (re)bind a network that routes to the Glass, then probe
                // through it. This keeps the fast persistent path working across captures.
                val cachedIpReachable: Boolean = if (cachedGlassIp != null) {
                    // ensureGlassNetworkBound probes reachability (direct route, bound
                    // network, or WifiNetworkSpecifier) and binds HTTP accordingly.
                    val reachable = ensureGlassNetworkBound(cachedGlassIp!!)
                    if (reachable) {
                        Log.i(TAG, "🔗 Cached IP ${cachedGlassIp} is REACHABLE ✅")
                    } else {
                        Log.w(TAG, "🔗 Cached IP ${cachedGlassIp} UNREACHABLE — no Glass network available yet")
                    }
                    reachable
                } else false

                // If cached IP is stale, clear it so we don't use a wrong address
                if (cachedGlassIp != null && !cachedIpReachable) {
                    Log.w(TAG, "🗑️ Clearing stale cached IP: $cachedGlassIp")
                    cachedGlassIp = null
                    persistentConnectionReady = false
                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().remove(KEY_GLASS_IP).apply()
                }

                if (alreadyOnGlassWifi || cachedIpReachable) {
                    // ── PERSISTENT PATH: Glass WiFi is live — just take photo + download ──
                    Log.i(TAG, "🔗 PERSISTENT: alreadyOnGlass=$alreadyOnGlassWifi cachedReachable=$cachedIpReachable — skipping P2P entirely")
                    updateStatus("⚡ Connected. Taking photo...")

                    // Resolve IP FIRST (before camera) so we can snapshot the existing
                    // photos and reliably detect the brand-new one after capture.
                    var ip: String? = if (cachedIpReachable) cachedGlassIp else null
                    if (ip == null) {
                        updateStatus("🔍 Finding Glass IP...")
                        ip = withContext(Dispatchers.IO) {
                            val knownIps = listOf(
                                "192.168.49.1", "192.168.43.1",
                                "192.168.42.129", "192.168.43.129",
                                "192.168.6.1"
                            )
                            var found: String? = null
                            for (candidate in knownIps) {
                                try {
                                    val sock = java.net.Socket()
                                    sock.connect(java.net.InetSocketAddress(candidate, 80), 1200)
                                    sock.close()
                                    found = candidate
                                    break
                                } catch (_: Exception) {}
                            }
                            found ?: albumDownloader.discoverGlassesIP()
                        }
                    }

                    if (ip == null) {
                        showError("Glass connected but IP not found. Make sure Glass hotspot is active.")
                        return@launch
                    }

                    // Save verified IP persistently for next time
                    if (cachedGlassIp != ip) {
                        cachedGlassIp = ip
                        persistentConnectionReady = true
                        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit().putString(KEY_GLASS_IP, ip).apply()
                        Log.i(TAG, "🔗 Glass IP cached: $ip")
                    }
                    glassIpAddress = ip

                    // 📸 Snapshot photos that already exist BEFORE we take the new one,
                    // so downloadAndAnalyzeImage() can pick out the freshly-captured file.
                    snapshotExistingPhotos(ip)

                    // Trigger camera
                    val maxRetries = if (isContinuousStreaming) 3 else 1
                    var camOk = false
                    for (attempt in 1..maxRetries) {
                        camOk = triggerGlassCamera()
                        if (camOk) break
                        if (attempt < maxRetries) delay(2000L)
                    }
                    if (!camOk) { showError("Camera trigger failed"); return@launch }

                    val waitTime = if (isContinuousStreaming) 3500L else 2000L
                    delay(waitTime)

                    downloadAndAnalyzeImage(ip)
                    return@launch  // ✅ Done — pairing page never shown again
                }

                // ── FIRST-TIME PAIRING PATH (runs only once, ever) ──
                // Phone is NOT on Glass WiFi and has no cached IP yet.
                Log.i(TAG, "📡 First-time pairing: starting P2P discovery...")
                updateStatus("📡 Connecting to Glass WiFi (one-time setup)...")

                // Step 1: Camera (take the photo now, before pairing)
                var cameraSuccess = false
                val maxCameraRetries = if (isContinuousStreaming) 3 else 1
                for (attempt in 1..maxCameraRetries) {
                    cameraSuccess = triggerGlassCamera()
                    if (cameraSuccess) break
                    if (attempt < maxCameraRetries && isContinuousStreaming) delay(2000L)
                }
                if (!cameraSuccess) { showError("Camera trigger failed"); return@launch }

                val waitTime = if (isContinuousStreaming) 3500L else 2000L
                delay(waitTime)
                Log.d(TAG, "⏳ Waited ${waitTime}ms for photo save")

                // Step 2: Disconnect non-Glass WiFi, then start P2P
                updateStatus("📡 Starting WiFi pairing...")
                withContext(Dispatchers.IO) { disconnectCurrentWifi() }

                triggerGlassP2P()

                Log.i(TAG, "📡 P2P discovery started — waiting for first connection...")
                isWaitingForConnection = true
                wifiP2pHelper.startDiscovery()

                // P2P / BLE callback will call downloadAndAnalyzeImage() automatically.
                // Once that succeeds, cachedGlassIp will be set so this branch
                // never runs again.

            } catch (e: Exception) {
                Log.e(TAG, "Process failed: ${e.message}", e)
                showError("Error: ${e.message}")
            }
        }
    }
    
    /**
     * Step 1: Trigger camera on glasses via BLE
     */
    private suspend fun triggerGlassCamera(): Boolean = withContext(Dispatchers.IO) {
        var success = false
        val latch = java.util.concurrent.CountDownLatch(1)
        
        SafeBleCommandHelper.takePhoto { result, error ->
            success = result
            Log.d(TAG, "📷 Camera trigger: success=$result, error=$error")
            latch.countDown()
        }
        
        latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
        
        // Technical message hidden from user
        if (success) {
            Log.d(TAG, "✅ Photo taken on glasses")
        }
        
        // ✅ Critical: 500ms delay after camera to ensure capture completes
        delay(500)
        
        success
    }
    
    /**
     * Step 2: Trigger WiFi P2P on glasses via BLE
     */
    private fun triggerGlassP2P() {
        Log.i(TAG, "📡 Triggering Glass P2P via BLE...")
        
        try {
            // Reset P2P state first
            val resetCommand = byteArrayOf(2, 1, 15)
            LargeDataHandler.getInstance().glassesControl(resetCommand) { code, _ ->
                Log.i(TAG, "🔄 P2P Reset: code=$code")
            }
            
            // Start transfer mode (triggers P2P)
            val startTransferCommand = byteArrayOf(2, 1, 4)
            LargeDataHandler.getInstance().glassesControl(startTransferCommand) { code, error ->
                Log.i(TAG, "📤 Transfer mode: code=$code")
                // Technical message hidden from user
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error triggering P2P: ${e.message}")
        }
    }
    
    /**
     * Download image and analyze with AI
     * Uses AlbumDownloader for HTTP-based transfer
     * NOW: Accepts specific IP to avoid re-scanning failure
     */
    /**
     * 📸 Record the set of photo filenames that already exist on the Glass BEFORE
     * triggering a new capture. downloadAndAnalyzeImage() then waits for a filename
     * that is NOT in this set — guaranteeing we download the freshly-captured image
     * and never an old one that happened to be the "newest" at fetch time.
     *
     * Returns true only if the list was actually read from the Glass (so the snapshot is
     * trustworthy). If the Glass wasn't reachable we return false and the caller falls
     * back to the processed-set heuristic — but at least it KNOWS the snapshot is invalid.
     */
    private suspend fun snapshotExistingPhotos(ip: String): Boolean {
        // The Glass must be reachable over the network HTTP uses, or the list comes back
        // empty and we'd wrongly think "no photos existed" → grab a random old image.
        ensureGlassNetworkBound(ip)
        var attempt = 0
        while (attempt < 4) {
            if (albumDownloader.isReachable(ip)) {
                val items = withContext(Dispatchers.IO) { albumDownloader.fetchConfig(ip) }
                preCaptureFileNames = items.map { it.fileName }.toMutableSet()
                preCaptureSnapshotValid = true
                Log.i(TAG, "📸 Pre-capture snapshot: ${preCaptureFileNames.size} existing photos on Glass")
                return true
            }
            attempt++
            Log.w(TAG, "📸 Snapshot: Glass not reachable yet (try $attempt/4) — rebinding...")
            ensureGlassNetworkBound(ip)
            delay(800)
        }
        preCaptureFileNames = mutableSetOf()
        preCaptureSnapshotValid = false
        Log.w(TAG, "📸 Snapshot INVALID — Glass unreachable; will fall back to processed-set dedupe")
        return false
    }

    private suspend fun downloadAndAnalyzeImage(connectedIp: String? = null) {
        // 🔒 LOCK: Prevent parallel processing
        if (isImageAnalysisInProgress) {
            Log.w(TAG, "🚫 Analysis already in progress, ignoring duplicate call")
            return
        }
        isImageAnalysisInProgress = true
        // Tracks whether we handed off to analyzeImage() (which resets isProcessing itself).
        // If we return early before that, the finally block MUST reset isProcessing so a
        // failed capture never permanently blocks "take another picture".
        var analysisStarted = false

        try {
            // 1. IP Address Finalize - use passed IP or discover
            var resolvedIp = connectedIp

            if (resolvedIp == null) {
                updateStatus("⬇️ Finding Glass server...")
                resolvedIp = withContext(Dispatchers.IO) {
                    albumDownloader.discoverGlassesIP()
                }
            }

            if (resolvedIp == null) {
                showError("Could not find Glass server. Check WiFi connection.")
                return
            }

            // Non-null working IP. This may be RE-RESOLVED below if it turns out to be
            // unreachable — e.g. the BLE type-8 notification hands us the Glass SoftAP IP
            // (192.168.6.1) but the phone is actually on the WiFi-Direct group where the
            // Glass is the group owner (192.168.49.1).
            var ip: String = resolvedIp
            glassIpAddress = ip
            Log.i(TAG, "✅ Target Glass IP: $ip")

            // 🌐 Make HTTP route to the Glass and not out the phone's home WiFi. This is
            // BEST-EFFORT: the Glass link (WiFi Direct group / hotspot) can take a few
            // seconds to come up, so we don't abort here — we re-check on every retry below.
            updateStatus("📶 Connecting to Glass network...")
            ensureGlassNetworkBound(ip)

            // 🔗 PERSISTENT CONNECTION: Save this IP so next capture is instant
            if (cachedGlassIp != ip) {
                cachedGlassIp = ip
                persistentConnectionReady = true
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putString(KEY_GLASS_IP, ip).apply()
                Log.i(TAG, "🔗 Glass IP cached persistently: $ip")
            } else {
                persistentConnectionReady = true
            }

            // 2. File List Fetch with Retry (IMPORTANT for Fresh Photos)
            updateStatus("📋 Fetching photo list...")

            var latestImage: com.sdk.glassessdksample.ui.wifi.MediaItem? = null
            var retries = 0
            var everReachable = false
            // 🔧 Increased retries: 6 for streaming, 5 for normal (was 4)
            // Gives more time for Glass to save new photo
            val maxRetries = if (isContinuousStreaming) 6 else 5

            // Retry if list is empty or no new photos (photo may still be saving)
            while (latestImage == null && retries < maxRetries) {
                // 🌐 Re-establish the Glass route each loop — the WiFi Direct group / hotspot
                // may only finish forming a few seconds after the camera trigger.
                if (!everReachable) {
                    everReachable = ensureGlassNetworkBound(ip)

                    // 🔁 If the supplied IP isn't reachable, it's very likely the WRONG IP
                    // for the CURRENT connection mode (BLE reports the SoftAP 192.168.6.1
                    // but we're on the WiFi-Direct group where the Glass = 192.168.49.1).
                    // Re-discover whichever known Glass IP actually answers right now —
                    // this is the robustness the normal chat gets for free via
                    // groupOwnerAddress, and it's what fixes "Couldn't reach Glass at …".
                    if (!everReachable) {
                        val rediscovered = withContext(Dispatchers.IO) {
                            albumDownloader.discoverGlassesIP()
                        }
                        if (rediscovered != null) {
                            if (rediscovered != ip) {
                                Log.i(TAG, "🔁 Re-resolved Glass IP: $ip → $rediscovered (original unreachable)")
                                ip = rediscovered
                                glassIpAddress = ip
                                cachedGlassIp = ip
                                persistentConnectionReady = true
                                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                    .edit().putString(KEY_GLASS_IP, ip).apply()
                            }
                            everReachable = true
                        }
                    }
                }

                val mediaItems = withContext(Dispatchers.IO) {
                    albumDownloader.fetchConfig(ip)
                }
                
                if (mediaItems.isNotEmpty()) {
                    // ✅ Keep the Glass media.config ORDER (oldest → newest). The newest
                    // photo is the LAST image entry. This MATCHES the proven normal-chat
                    // path (imageFiles.last()) which always downloads the most recent
                    // capture. We deliberately DON'T sort by filename: different glass
                    // firmwares use prefixes/formats that do NOT sort chronologically,
                    // which is exactly what caused a random/old image to be picked here.
                    val photoList = mediaItems
                        .filter { it.fileName.endsWith(".jpg", true) || it.fileName.endsWith(".jpeg", true) || it.fileName.endsWith(".png", true) }

                    if (photoList.isNotEmpty()) {
                        // 🆕 CRITICAL FIX: Pick the freshly-captured image.
                        // The new photo is whatever file did NOT exist before this capture
                        // (snapshot diff) — this is independent of filename ordering. Among
                        // those candidates we take the LAST one (= newest in config order),
                        // exactly like the normal chat. When the snapshot is INVALID
                        // (Glass was unreachable before capture), fall back to the newest
                        // not-yet-analyzed file.
                        val candidates = if (preCaptureSnapshotValid) {
                            photoList.filter { it.fileName !in preCaptureFileNames }
                        } else {
                            photoList.filter { it.fileName !in processedPhotos }
                        }
                        val freshPhoto = candidates.lastOrNull()

                        if (freshPhoto != null) {
                            latestImage = freshPhoto
                            Log.d(TAG, "🆕 Found freshly-captured photo: ${freshPhoto.fileName} (snapshotValid=$preCaptureSnapshotValid, candidates=${candidates.size})")
                        } else {
                            Log.d(TAG, "⏸️ New photo not saved yet (newest=${photoList.last().fileName}) - waiting for capture to finish...")
                            // latestImage remains null, loop continues
                        }
                    }
                }
                
                if (latestImage == null) {
                    retries++
                    val retryDelay = if (isContinuousStreaming) 2000L else 1500L
                    Log.w(TAG, "⚠️ Photo not ready yet, retry $retries/$maxRetries in ${retryDelay}ms...")
                    updateStatus("⌛ Processing photo... ($retries/$maxRetries)")
                    delay(retryDelay)
                }
            }
            
            if (latestImage == null) {
                if (!everReachable) {
                    // We never got a route to the Glass — this is a connectivity problem,
                    // not a "no new photo" problem. Give an actionable message.
                    showError("Couldn't reach Glass at $ip. Make sure the Glass WiFi/hotspot is on and your phone is connected to it, then try again.")
                } else {
                    showError("No NEW photo found. All photos already analyzed.")
                }
                return
            }
            
            // ✅ New photo - add to processed Set AND save name
            processedPhotos.add(latestImage.fileName)
            lastProcessedPhotoName = latestImage.fileName
            Log.i(TAG, "🆕 Processing new photo: ${latestImage.fileName} (Total processed: ${processedPhotos.size})")
            
            // Technical message hidden from user - don't show filename
            
            // 3. Download the actual file
            updateStatus("⬇️ Downloading ${latestImage.fileName}...")
            
            val outputDir = File(filesDir, "vision_images")
            if (!outputDir.exists()) outputDir.mkdirs()
            
            val downloadedFile = withContext(Dispatchers.IO) {
                albumDownloader.downloadFile(ip, latestImage.fileName, outputDir) { progress ->
                    Log.d(TAG, "📊 Download: $progress%")
                }
            }
            
            if (downloadedFile == null || !downloadedFile.exists()) {
                showError("Failed to download file from Glass.")
                return
            }
            
            Log.i(TAG, "✅ Download Success: ${downloadedFile.absolutePath}")

            // ── Live Gallery: save a copy so it appears in the gallery page ─────
            LiveGalleryManager.copyToGallery(this@VisionChatActivity, downloadedFile, "WiFi")
            Log.d(TAG, "🖼️ Saved WiFi capture to Live Gallery")

            // Scan file for gallery
            android.media.MediaScannerConnection.scanFile(
                this@VisionChatActivity,
                arrayOf(downloadedFile.absolutePath),
                null, null
            )
            
            // Read image bytes
            val imageBytes = downloadedFile.readBytes()
            
            // Show image on user side (as if user sent the photo)
            runOnUiThread {
                addMessage(VisionChatMessage(
                    text = "📸 Captured Image",
                    isUser = true, // Show on user side
                    timestamp = System.currentTimeMillis(),
                    imagePath = downloadedFile.absolutePath
                ))
            }
            // TTS disabled - using Gemini Live voice instead
            // speakOut("Photo mil gaya. Analyzing...")
            
            // 4. Send to AI for analysis
            updateStatus("🔍 Analyzing with AI...")
            analysisStarted = true
            analyzeImage(imageBytes)
            
            // 🎥 Auto-start continuous streaming after first analysis (if enabled)
            if (shouldAutoStartStreaming && !isContinuousStreaming) {
                Log.i(TAG, "🎥 Starting continuous frame capture after first analysis")
                startContinuousVisionStream()
                shouldAutoStartStreaming = false  // Only trigger once
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in download flow: ${e.message}", e)
            showError("Process Error: ${e.message}")
        } finally {
            // 🔓 Release analysis lock
            isImageAnalysisInProgress = false

            // 🔧 CRITICAL: If we returned early (no photo, camera fail, unreachable, etc.)
            // without handing off to analyzeImage(), reset isProcessing HERE. Otherwise a
            // failed capture leaves isProcessing=true forever and silently blocks the next
            // command (including "take another picture").
            if (!analysisStarted) {
                Log.d(TAG, "🔧 Resetting isProcessing (capture ended before analysis)")
                isProcessing = false
                captureButton.isEnabled = true
                stopThinkingTune()
                if (!isContinuousStreaming) {
                    updateStatus("📷 Tap button to capture from glasses")
                }
            }
        }
    }
    
    /**
     * Analyze image with Gemini Vision API
     * 🆕 Uses user's question to customize response (action-oriented if asked "kya kar sakte hai")
     * 🆕 Stores image for follow-up questions without re-capture
     */
    private suspend fun analyzeImage(imageBytes: ByteArray) {
        try {
            // 🆕 Play thinking tune (WAV) while analyzing - loops until description ready
            // Note: If from Gemini Live, tuning already started in onCreate - this ensures it's playing
            if (thinkingPlayer == null || thinkingPlayer?.isPlaying == false) {
                startThinkingTune()
            }
            
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            
            if (bitmap == null) {
                stopThinkingTune()
                showError("Failed to decode image")
                return
            }
            
            // 🆕 Store image for follow-up questions
            lastCapturedImageBytes = imageBytes
            lastCapturedBitmap = bitmap
            lastImageAnalysisTime = System.currentTimeMillis()
            
            // 🆕 Build prompt based on user's question
            val prompt = buildVisionPrompt(userVisionQuery)

            if (!UsageLimitManager.tryConsume(this@VisionChatActivity, TokenUsageTracker.Mode.SEEING)) {
                stopThinkingTune()
                UsageLimitManager.promptUpgradeIfPossible(this@VisionChatActivity, TokenUsageTracker.Mode.SEEING)
                showError(UsageLimitManager.limitReachedMessage(TokenUsageTracker.Mode.SEEING))
                return
            }
            
            val response = withContext(Dispatchers.IO) {
                generativeModel.generateContent(
                    content {
                        image(bitmap)
                        text(prompt)
                    }
                )
            }

            response.usageMetadata?.let {
                TokenUsageTracker.track(this@VisionChatActivity, TokenUsageTracker.Mode.SEEING, it)
            }
            
            // 🆕 Stop thinking tune before speaking result
            stopThinkingTune()
            
            val explanation = response.text ?: "Unable to analyze image"
            
            // 🆕 Store vision description for Gemini Live memory
            lastVisionDescription = explanation
            
            withContext(Dispatchers.Main) {
                addMessage(VisionChatMessage(
                    text = explanation,  // Clean output - just the description
                    isUser = false,
                    timestamp = System.currentTimeMillis()
                ))
                
                // 🆕 Immediately speak result via Gemini Live (no delay)
                speakVisionResultImmediately(explanation)
            }
            
            Log.d(TAG, "✅ Analysis complete: $explanation")
            
            // Signal complete to glasses
            signalDownloadComplete()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing image: ${e.message}", e)
            stopThinkingTune()  // Stop tune on error
            showError("Analysis failed: ${e.message}")
        } finally {
            // Reset processing flags after AI analysis complete
            isProcessing = false
            isWaitingForConnection = false
            captureButton.isEnabled = true
            
            // Update status based on streaming mode
            if (isContinuousStreaming) {
                updateStatus("🎥 Live vision active (capturing every ${STREAMING_INTERVAL_MS / 1000}s)")
            } else {
                updateStatus("📷 Tap button to capture from glasses")
            }
        }
    }
    
    /**
     * 🆕 Build vision prompt based on user's question
     * - If user asks "kya kar sakte hai" → suggest actions/uses
     * - If user asks "ye kya hai" → describe what it is
     * - Default → DETAILED description with TEXT READING
     * Read ALL text visible on objects, boxes, diaries, walls, etc.
     */
    private fun buildVisionPrompt(userQuery: String?): String {
        val query = userQuery?.lowercase() ?: ""
        
        // Check if user is asking about WHAT CAN BE DONE with objects
        val isActionQuery = query.contains("kar sakte") || 
                           query.contains("kya kar") ||
                           query.contains("bana sakte") ||
                           query.contains("use kar") ||
                           query.contains("what can") ||
                           query.contains("can i do") ||
                           query.contains("can we do") ||
                           query.contains("make with") ||
                           query.contains("cook") ||
                           query.contains("recipe") ||
                           query.contains("isse kya") ||
                           query.contains("iska use")
        
        // Check if user wants to READ text specifically
        val isReadQuery = query.contains("padh") ||
                         query.contains("read") ||
                         query.contains("likha") ||
                         query.contains("written") ||
                         query.contains("naam") ||
                         query.contains("name") ||
                         query.contains("text") ||
                         query.contains("kya likha")
        
        // Check if user wants to COUNT objects
        val isCountQuery = query.contains("kitne") ||
                          query.contains("kitni") ||
                          query.contains("count") ||
                          query.contains("how many") ||
                          query.contains("total") ||
                          query.contains("number of")
        
        return when {
            isCountQuery -> {
                // COUNT prompt - count objects precisely
                """COUNT and LIST all objects visible in this image.
                  |
                  |FORMAT YOUR RESPONSE EXACTLY LIKE THIS:
                  |"I can see [TOTAL NUMBER] objects:
                  | - [COUNT] [OBJECT NAME]
                  | - [COUNT] [OBJECT NAME]
                  | ..."
                  |
                  |EXAMPLE: "I can see 7 objects:
                  | - 2 people
                  | - 3 chairs
                  | - 1 table
                  | - 1 laptop"
                  |
                  |Be PRECISE. COUNT carefully. List EACH type separately.
                  |READ any text/labels visible too.
                  |ENGLISH ONLY.""".trimMargin()
            }
            isActionQuery -> {
                // ACTION-ORIENTED prompt with use cases
                """Look at this image and tell me:
                  |1. WHAT objects you see (with COUNT)
                  |2. WHAT CAN BE DONE with these objects
                  |3. Practical USE CASES
                  |
                  |FORMAT:
                  |"I see [X] objects: [list them].
                  |You can: [practical actions/uses]"
                  |
                  |READ any text/labels on products.
                  |Be helpful and practical.
                  |ENGLISH ONLY.""".trimMargin()
            }
            isReadQuery -> {
                // TEXT READING prompt
                """READ and tell me ALL TEXT visible in this image.
                  |
                  |Focus on:
                  | - Names on diaries, books, notebooks
                  | - Labels on boxes, bottles, products
                  | - Text on walls, signs, posters
                  | - Brand names, product names
                  | - Numbers, dates, prices
                  |
                  |Spell out EXACTLY what is written.
                  |If text is in Hindi/other language, transliterate it.
                  |ENGLISH ONLY.""".trimMargin()
            }
            else -> {
                // DEFAULT: DETAILED description with COUNT + TEXT + USE CASE
                // 🆕 UPDATED: Make it conversational and natural for Gemini Live
                """Describe what you see in this image concisely for a voice conversation.
                  |
                  |Focus on the MOST IMPORTANT elements.
                  |READ any prominent text or labels.
                  |Keep it brief and conversational (1-2 sentences max).
                  |
                  |EXAMPLE: "I see a blue water bottle on a wooden desk next to a laptop."
                  |
                  |Avoid lists or bullet points. Speak naturally. ENGLISH ONLY.""".trimMargin()
            }
        }
    }
    
    /**
     * 🆕 Check if we can reuse the last captured image for follow-up questions.
     *
     * BEHAVIOR: Once a photo is captured it stays "active" — every follow-up question is
     * answered from THIS picture. It is only replaced when the user explicitly says
     * "take another picture". So there is intentionally NO time-based expiry here.
     */
    private fun canUseLastImage(): Boolean {
        return lastCapturedBitmap != null && lastCapturedImageBytes != null
    }
    
    /**
     * 🆕 Clear the last captured image to force a new capture
     * Used when user says "click another", "naya photo", etc.
     * 🔧 FIXED: Also clears lastProcessedPhotoName so new capture isn't skipped
     */
    private fun clearLastCapturedImage() {
        Log.d(TAG, "🗑️ Clearing last captured image - will capture NEW frame")
        lastCapturedImageBytes = null
        lastCapturedBitmap = null
        lastImageAnalysisTime = 0
        lastVisionDescription = null
        lastSpokenDescription = null  // Also clear spoken description to allow re-describing
        
        // 🔧 CRITICAL FIX: Also reset lastProcessedPhotoName so new capture isn't skipped!
        // This was causing "click another" to fail because the new photo was considered "already processed"
        lastProcessedPhotoName = null
        Log.d(TAG, "🔧 Reset lastProcessedPhotoName - ready for fresh capture")
    }
    
    // 🆕 Track last spoken description to prevent duplicate voice output
    private var lastSpokenDescription: String? = null
    
    /**
     * 🆕 Analyze the last captured image with a new follow-up question
     * Used when user asks related question without saying "photo le"
     */
    private suspend fun analyzeImageWithLastCapture(followUpQuery: String) {
        try {
            Log.i(TAG, "🔄 Re-analyzing last image with query: $followUpQuery")
            
            // Play thinking tune
            startThinkingTune()
            
            val bitmap = lastCapturedBitmap ?: run {
                stopThinkingTune()
                showError("No previous image available")
                return
            }
            
            // Build prompt with follow-up query
            val prompt = buildVisionPrompt(followUpQuery)

            if (!UsageLimitManager.tryConsume(this@VisionChatActivity, TokenUsageTracker.Mode.SEEING)) {
                stopThinkingTune()
                UsageLimitManager.promptUpgradeIfPossible(this@VisionChatActivity, TokenUsageTracker.Mode.SEEING)
                showError(UsageLimitManager.limitReachedMessage(TokenUsageTracker.Mode.SEEING))
                return
            }
            
            val response = withContext(Dispatchers.IO) {
                generativeModel.generateContent(
                    content {
                        image(bitmap)
                        text(prompt)
                    }
                )
            }

            response.usageMetadata?.let {
                TokenUsageTracker.track(this@VisionChatActivity, TokenUsageTracker.Mode.SEEING, it)
            }
            
            stopThinkingTune()
            
            val explanation = response.text ?: "Unable to analyze image"
            
            // 🆕 Store for context memory
            lastVisionDescription = explanation
            
            withContext(Dispatchers.Main) {
                addMessage(VisionChatMessage(
                    text = explanation,
                    isUser = false,
                    timestamp = System.currentTimeMillis()
                ))
                
                // 🆕 Immediately speak result via Gemini Live
                speakVisionResultImmediately(explanation)
            }
            
            Log.d(TAG, "✅ Follow-up analysis complete: $explanation")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in follow-up analysis: ${e.message}", e)
            stopThinkingTune()
            showError("Analysis failed: ${e.message}")
        } finally {
            isProcessing = false
            captureButton.isEnabled = true
            updateStatus("📷 Tap button to capture from glasses")
        }
    }
    
    /**
     * 🆕 Start playing thinking tune (loops until stopped)
     */
    private fun startThinkingTune() {
        try {
            stopThinkingTune() // Stop any existing playback first
            
            thinkingPlayer = MediaPlayer.create(this, R.raw.thinking_tune)
            thinkingPlayer?.apply {
                isLooping = true // Loop until description arrives
                setVolume(0.5f, 0.5f) // 50% volume so it's not too loud
                start()
            }
            Log.d(TAG, "🎵 Thinking tune started")
        } catch (e: Exception) {
            Log.e(TAG, "Error playing thinking tune: ${e.message}")
        }
    }
    
    /**
     * 🆕 Stop thinking tune when description is ready
     */
    private fun stopThinkingTune() {
        try {
            thinkingPlayer?.apply {
                if (isPlaying) {
                    stop()
                }
                release()
            }
            thinkingPlayer = null
            Log.d(TAG, "🎵 Thinking tune stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping thinking tune: ${e.message}")
        }
    }
    
    private fun signalDownloadComplete() {
        try {
            val completeCommand = byteArrayOf(2, 1, 9)
            LargeDataHandler.getInstance().glassesControl(completeCommand) { _, _ ->
                Log.i(TAG, "✅ Download complete signal sent")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error signaling complete: ${e.message}")
        }
    }
    
    /**
     * Check if phone is already connected to Glass WiFi
     * This avoids unnecessary P2P triggers that cause "Framework busy" errors
     */
    private fun isAlreadyConnectedToGlass(): Boolean {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        val info = wifiManager.connectionInfo
        
        if (info != null && info.networkId != -1) {
            // SSID usually in quotes like "Glass_WIFI", remove quotes
            val ssid = info.ssid.replace("\"", "")
            Log.d(TAG, "Current WiFi SSID: $ssid")
            
            // Check if SSID contains Glass identifiers (comprehensive patterns)
            return ssid.contains("Glass", true) || 
                   ssid.contains("M01", true) || 
                   ssid.contains("Cyan", true) ||
                   ssid.contains("HeyCyan", true) ||
                   ssid.contains("CY01", true) || // CY01 Glass
                   ssid.contains("DIRECT-nH", true) || // WiFi Direct from phone
                   ssid.contains("DIRECT-SR", true) || // WiFi Direct variations
                   ssid.contains("DIRECT-Bv", true) ||
                   ssid.contains("Android", true) // Default P2P name
        }
        return false
    }
    
    /**
     * ✅ AUTO-CONNECT: Scan and connect to Glass WiFi (CY01_*) hotspot
     * Android 10+ opens WiFi picker automatically for user to select
     * Android 9 and below: Shows notification to connect
     */
    private suspend fun tryAutoConnectToGlassWifi(): Boolean = withContext(Dispatchers.IO) {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            
            // Check if WiFi is enabled
            if (!wifiManager.isWifiEnabled) {
                Log.w(TAG, "⚠️ WiFi is disabled, requesting enable...")
                withContext(Dispatchers.Main) {
                    // Show user to enable WiFi
                    Toast.makeText(this@VisionChatActivity, "Please enable WiFi", Toast.LENGTH_SHORT).show()
                }
                return@withContext false
            }
            
            // For Android 10+ (API 29+): Use ConnectivityManager to request network
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Log.i(TAG, "📡 Android 10+: Opening WiFi picker for Glass network...")
                
                withContext(Dispatchers.Main) {
                    // Android will show WiFi picker to user with Glass networks
                    val intent = Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                    startActivity(intent)
                    
                    Toast.makeText(
                        this@VisionChatActivity, 
                        "Please select CY01_... network from WiFi list",
                        Toast.LENGTH_LONG
                    ).show()
                }
                
                // Give user time to connect (will auto-proceed when HTTP scan finds IP)
                return@withContext true // Return success - user will manually connect
            } else {
                // Android 9 and below: Scan for networks
                Log.i(TAG, "📡 Android 9: Scanning for Glass WiFi...")
                
                // Trigger WiFi scan
                val scanStarted = wifiManager.startScan()
                if (!scanStarted) {
                    Log.w(TAG, "⚠️ WiFi scan failed to start")
                    return@withContext false
                }
                
                // Wait for scan results
                delay(3000)
                
                val scanResults = wifiManager.scanResults
                Log.d(TAG, "📡 Found ${scanResults.size} WiFi networks")
                
                // Log all available networks for debugging
                scanResults.forEach { result ->
                    Log.d(TAG, "  - ${result.SSID}")
                }
                
                val glassNetwork = scanResults.firstOrNull { result ->
                    val ssid = result.SSID
                    ssid.contains("CY01", ignoreCase = true) || 
                    ssid.contains("M01", ignoreCase = true) ||
                    ssid.contains("Glass", ignoreCase = true) ||
                    ssid.contains("HeyCyan", ignoreCase = true) ||
                    ssid.contains("DIRECT-nH", ignoreCase = true) ||
                    ssid.contains("DIRECT-SR", ignoreCase = true) ||
                    ssid.contains("DIRECT-Bv", ignoreCase = true)
                }
                
                if (glassNetwork != null) {
                    Log.i(TAG, "✅ Found Glass network: ${glassNetwork.SSID}")
                    
                    withContext(Dispatchers.Main) {
                        // Show user to connect manually (can't auto-connect without password on older Android)
                        Toast.makeText(
                            this@VisionChatActivity,
                            "Glass WiFi found: ${glassNetwork.SSID}\nPlease connect manually",
                            Toast.LENGTH_LONG
                        ).show()
                        
                        // Open WiFi settings
                        val intent = Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                        startActivity(intent)
                    }
                    
                    return@withContext true
                } else {
                    Log.w(TAG, "⚠️ Glass network not found in scan results")
                    return@withContext false
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Auto-connect error: ${e.message}", e)
            return@withContext false
        }
    }
    
    /**
     * Disconnect from current WiFi network to allow P2P connection
     * This solves "Framework busy" error when phone is connected to home/office WiFi
     */
    private fun disconnectCurrentWifi() {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val info = wifiManager.connectionInfo
            
            if (info != null && info.networkId != -1) {
                val currentSsid = info.ssid.replace("\"", "")
                
                // Don't disconnect if already on Glass WiFi
                if (!isAlreadyConnectedToGlass()) {
                    Log.i(TAG, "🔌 Disconnecting from: $currentSsid")
                    wifiManager.disconnect()
                    
                    // Technical message hidden from user
                    
                    // Wait for disconnect to complete
                    Thread.sleep(1000)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting WiFi: ${e.message}")
        }
    }

    // 🌐 Active WifiNetworkSpecifier callback for the Glass hotspot (kept alive for the
    // duration of the transfer; releasing it drops the connection).
    private var glassNetworkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * 🌐 Make sure HTTP to the Glass actually reaches it.
     *
     * The phone is almost always ALSO connected to home/office WiFi (which has internet),
     * so Android's default route prefers that — every request to 192.168.6.1 then leaks
     * out the wrong interface and times out. This binds AlbumDownloader's HTTP to the
     * Glass network (only the HTTP, not the whole process — Gemini Live keeps internet).
     *
     * Strategy (cheapest → most aggressive):
     *  0. If [targetIp] is ALREADY reachable on current routing — e.g. an active WiFi
     *     Direct group installs a connected route that ConnectivityManager doesn't even
     *     surface as a Network — just use default routing. No binding needed. This is the
     *     normal working case and must NOT be blocked.
     *  1. Else, if a connected Network has a route to [targetIp]'s subnet (manually-joined
     *     Glass hotspot, etc.) → bind HTTP to it and verify.
     *  2. Else, request the Glass hotspot via WifiNetworkSpecifier (API 29+) and bind to
     *     the network the system hands back (works even while home WiFi stays up).
     *
     * @return true if the Glass is reachable (bound or via existing routes).
     */
    private suspend fun ensureGlassNetworkBound(targetIp: String): Boolean {
        val cm = connectivityManager
            ?: (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
            ?: return false

        // ── 0. Already reachable with the current binding? (WiFi Direct route, manual
        //       hotspot join, or an earlier successful bind) → done, nothing to do. ──
        if (albumDownloader.isReachable(targetIp)) {
            Log.i(TAG, "🌐 $targetIp reachable with current binding — OK")
            return true
        }

        // ── 1. Try binding to each connected Network that routes to the Glass subnet ──
        for (net in cm.allNetworks) {
            val lp = cm.getLinkProperties(net) ?: continue
            val matches = lp.linkAddresses.any { la ->
                val a = la.address
                a is java.net.Inet4Address && inSameSubnet(a, java.net.InetAddress.getByName(targetIp), la.prefixLength)
            }
            if (!matches) continue
            Log.i(TAG, "🌐 Binding HTTP to candidate network for $targetIp: $net")
            albumDownloader.bindToNetwork(net)
            if (albumDownloader.isReachable(targetIp)) return true
            albumDownloader.bindToNetwork(null)
        }

        // ── 2. Request the Glass hotspot via WifiNetworkSpecifier (API 29+), but only
        //       fire ONE request — subsequent retries reuse the in-flight/active one. ──
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(TAG, "🌐 No route to $targetIp and pre-Android-10 — cannot auto-bind")
            return false
        }
        if (glassNetworkCallback != null) {
            // A specifier request is already active/bound; just report current reachability.
            return albumDownloader.isReachable(targetIp)
        }

        val ssid = withContext(Dispatchers.IO) { findGlassSsid() }
        if (ssid == null) {
            Log.w(TAG, "🌐 Glass hotspot SSID not visible in WiFi scan — cannot auto-connect")
            return false
        }

        Log.i(TAG, "🌐 Requesting Glass hotspot '$ssid' via WifiNetworkSpecifier...")
        updateStatus("📶 Connecting to Glass WiFi ($ssid)...")
        // On success AlbumDownloader is bound to the new network; fetchConfig() will use it.
        if (!requestGlassHotspot(cm, ssid, targetIp)) return false
        return albumDownloader.isReachable(targetIp)
    }

    private fun inSameSubnet(a: java.net.InetAddress, b: java.net.InetAddress, prefixLen: Int): Boolean {
        val ab = a.address; val bb = b.address
        if (ab.size != bb.size) return false
        var bits = prefixLen
        for (i in ab.indices) {
            if (bits <= 0) break
            val mask = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
            if ((ab[i].toInt() and mask) != (bb[i].toInt() and mask)) return false
            bits -= 8
        }
        return true
    }

    /** Scan visible WiFi for an SSID that looks like a Glass transfer hotspot. */
    @Suppress("MissingPermission")
    private fun findGlassSsid(): String? {
        return try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            wifi.startScan()
            val patterns = listOf("CY01", "M01", "Glass", "HeyCyan", "Cyan", "SANVNET", "GS4", "IMI")
            wifi.scanResults
                ?.mapNotNull { it.SSID?.takeIf { s -> s.isNotBlank() } }
                ?.firstOrNull { ssid -> patterns.any { ssid.contains(it, ignoreCase = true) } }
                ?.also { Log.i(TAG, "🌐 Glass hotspot SSID found in scan: $it") }
        } catch (e: Exception) {
            Log.w(TAG, "findGlassSsid error: ${e.message}")
            null
        }
    }

    /** Connect to [ssid] via WifiNetworkSpecifier and bind HTTP to it. Suspends until ready. */
    @android.annotation.SuppressLint("NewApi")
    private suspend fun requestGlassHotspot(cm: ConnectivityManager, ssid: String, targetIp: String): Boolean {
        // Release any previous request first
        glassNetworkCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        glassNetworkCallback = null

        // These transfer hotspots are typically OPEN (no passphrase); if secured, the
        // system surfaces a one-time approval dialog.
        val specifier = android.net.wifi.WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) // Glass AP has no internet
            .setNetworkSpecifier(specifier)
            .build()

        return withTimeoutOrNull(20_000L) {
            kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: android.net.Network) {
                        Log.i(TAG, "🌐 Glass hotspot connected via specifier: $network")
                        albumDownloader.bindToNetwork(network)
                        if (cont.isActive) cont.resumeWith(Result.success(true))
                    }
                    override fun onUnavailable() {
                        Log.w(TAG, "🌐 Glass hotspot request unavailable (declined/not found)")
                        if (cont.isActive) cont.resumeWith(Result.success(false))
                    }
                }
                glassNetworkCallback = cb
                try {
                    cm.requestNetwork(request, cb)
                } catch (e: Exception) {
                    Log.e(TAG, "requestNetwork error: ${e.message}")
                    if (cont.isActive) cont.resumeWith(Result.success(false))
                }
                cont.invokeOnCancellation { runCatching { cm.unregisterNetworkCallback(cb) } }
            }
        } ?: run {
            Log.w(TAG, "🌐 Glass hotspot connect timed out")
            false
        }
    }

    /** Drop the bound Glass network so normal traffic resumes. */
    private fun releaseGlassNetwork() {
        glassNetworkCallback?.let { cb ->
            runCatching { connectivityManager?.unregisterNetworkCallback(cb) }
        }
        glassNetworkCallback = null
        albumDownloader.bindToNetwork(null)
    }

    private fun updateStatus(text: String) {
        runOnUiThread {
            statusText.text = text
        }
        // ── Mirror download/transfer status to Live Gallery in real time ────
        // Only broadcast messages that are about connecting, downloading or saving.
        // Skip AI-analysis states — those are irrelevant to the gallery.
        val isTransferStatus = text.contains("⬇️") || text.contains("📋") ||
            text.contains("⌛") || text.contains("연결") || text.contains("connect", ignoreCase = true) ||
            text.contains("download", ignoreCase = true) || text.contains("Fetching") ||
            text.contains("Finding") || text.contains("Processing photo") ||
            text.contains("photo", ignoreCase = true) && (text.contains("⬇️") || text.contains("⌛"))
        if (isTransferStatus) {
            val intent = android.content.Intent(LiveGalleryManager.ACTION_PHOTO_SAVING).apply {
                putExtra(LiveGalleryManager.EXTRA_STATUS_MSG, text)
                putExtra(LiveGalleryManager.EXTRA_SOURCE, "WiFi")
            }
            androidx.localbroadcastmanager.content.LocalBroadcastManager
                .getInstance(this).sendBroadcast(intent)
        }
    }
    
    private fun showError(message: String) {
        runOnUiThread {
            addMessage(VisionChatMessage(
                text = "❌ $message",
                isUser = false,
                timestamp = System.currentTimeMillis()
            ))
            // TTS disabled - using Gemini Live voice instead
            // speakOut("Error ho gaya. $message")
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            isProcessing = false
            isWaitingForConnection = false
            captureButton.isEnabled = true
            updateStatus("📷 Tap button to capture from glasses")
        }
    }
    
    private fun addMessage(message: VisionChatMessage) {
        messages.add(message)
        chatAdapter.notifyItemInserted(messages.size - 1)
        recyclerView.scrollToPosition(messages.size - 1)
    }
    
    // ========================================================================
    // 🆕 VOICE COMMAND LISTENER - Detect "who is in front of me" while VisionChat is open
    // ========================================================================
    
    /**
     * 🆕 Vision trigger phrases that should capture a new photo
     * Same as MainActivity's vision triggers
     */
    private val visionTriggerPhrases = listOf(
        // English triggers
        "what is in front of me",
        "what's in front of me", 
        "who is in front of me",
        "who's in front of me",
        "what do you see",
        "what can you see",
        "describe what you see",
        "analyze this",
        "what is this",
        "what's this",
        "what am i looking at",
        "describe this",
        "tell me what you see",
        // Hindi triggers (romanized)
        "mere samne kya hai",
        "mere samne kaun hai",
        "samne kya hai",
        "samne kaun hai",
        "yeh kya hai",
        "ye kya hai",
        "kya dekh rahe ho",
        "dekho yeh",
        "dekho ye",
        "camera se dekho",
        "batao kya hai",
        "batao kaun hai",
        "mujhe batao",
        "kya dikh raha hai",
        "phir se dekho",
        "fir se dekho",
        "again",
        "ek aur",
        "aur ek",
        "next",
        "agle"
        // REMOVED: "photo lo", "click karo", "picture lo" 
        // These are confusing normal photo capture with vision analysis
    )
    
    /**
     * 🆕 Check if text contains a vision trigger phrase
     * Uses flexible matching similar to MainActivity
     */
    private fun isVisionTrigger(text: String): Boolean {
        val lowerText = text.lowercase().trim()
        
        // 🛑 Explicitly BLOCK simple photo commands from triggering VISION analysis
        // The user wants "take photo" to be separate from "analyze this"
        if (lowerText == "take photo" || 
            lowerText == "take picture" || 
            lowerText == "capture photo" || 
            lowerText == "click photo" ||
            lowerText == "photo lo" ||
            lowerText == "picture lo" ||
            lowerText == "click karo") {
            return false
        }
        
        // Check exact trigger phrases first
        if (visionTriggerPhrases.any { lowerText.contains(it) }) {
            return true
        }
        
        // 🆕 Also use flexible pattern matching (like MainActivity)
        // "who is" patterns  
        if (lowerText.contains("who") && lowerText.contains("front")) return true
        // "what is" patterns
        if (lowerText.contains("what") && (lowerText.contains("this") || lowerText.contains("front") || lowerText.contains("see"))) return true
        // "see/look" patterns
        if ((lowerText.contains("see") || lowerText.contains("look")) && lowerText.contains("front")) return true
        // Hindi patterns
        if (lowerText.contains("samne") && (lowerText.contains("kya") || lowerText.contains("kaun"))) return true
        if (lowerText.contains("dekh") && (lowerText.contains("kya") || lowerText.contains("batao"))) return true
        
        return false
    }
    
    /**
     * 🛑 Detect a "stop vision chat" command → exit back to the home/Gemini Live chat.
     */
    private fun isStopCommand(text: String): Boolean {
        val t = text.lowercase().trim()
        return t == "stop" ||
               t.contains("stop vision") ||
               t.contains("stop chat") ||
               t.contains("stop it") ||
               t.contains("close vision") ||
               t.contains("exit vision") ||
               t.contains("band karo") ||
               t.contains("bas karo") ||
               t.contains("ruk jao") ||
               t.contains("ruk jaao")
    }

    /**
     * 📸 Detect a "take another picture" command → capture a FRESH photo.
     * Only these explicit phrases cause a new capture; every other vision question is
     * answered from the picture already taken.
     */
    private fun isTakeAnotherCommand(text: String): Boolean {
        val t = text.lowercase().trim()
        return t.contains("take another") ||
               t.contains("another picture") ||
               t.contains("another photo") ||
               t.contains("another image") ||
               t.contains("click another") ||
               t.contains("capture another") ||
               t.contains("one more") ||
               t.contains("next photo") ||
               t.contains("next picture") ||
               t.contains("next image") ||
               // 🆕 "X again" / re-capture phrasings — these were previously falling through
               // to the reuse branch, so "capture again"/"again" re-analysed the SAME image
               // instead of clicking a new one.
               t.contains("again") ||           // take/capture/click/photo again, look again, etc.
               t.contains("recapture") ||
               t.contains("re-capture") ||
               // 🆕 Plain capture commands → in Vision Chat these mean "grab a fresh photo"
               t.contains("take photo") ||
               t.contains("take a photo") ||
               t.contains("take picture") ||
               t.contains("take a picture") ||
               t.contains("capture photo") ||
               t.contains("capture picture") ||
               t.contains("click photo") ||
               t.contains("click picture") ||
               t.contains("snap") ||
               // Hindi (romanized) re-capture phrasings
               t.contains("naya photo") ||
               t.contains("naya picture") ||
               t.contains("nayi photo") ||
               t.contains("nayi tasveer") ||
               t.contains("ek aur photo") ||
               t.contains("ek aur tasveer") ||
               t.contains("ek aur") ||
               t.contains("aur ek") ||
               t.contains("dusri photo") ||
               t.contains("dusri picture") ||
               t.contains("phir se") ||          // phir se dekho / phir se lo
               t.contains("fir se") ||
               t.contains("dobara") ||
               t.contains("dubara") ||
               t.contains("photo lo") ||
               t.contains("picture lo") ||
               t.contains("photo le") ||
               t.contains("click karo") ||
               t == "another" ||
               t == "next" ||
               t == "capture" ||
               t == "click" ||
               t == "naya" ||
               t == "agle"
    }

    /**
     * 🆕 Register as secondary listener to GeminiLiveService
     * This allows VisionChat to intercept voice commands while open
     */
    private fun registerVisionTranscriptionListener() {
        val geminiLive = GeminiLiveService.getInstance()
        if (geminiLive != null) {
            geminiLive.setVisionTranscriptionListener(visionTranscriptionListener)
            Log.i(TAG, "👁️✅ VisionChat REGISTERED as transcription listener - will intercept vision commands!")
        } else {
            Log.e(TAG, "❌ GeminiLiveService NOT available - voice re-capture WON'T work!")
        }
    }
    
    /**
     * 🆕 Unregister from GeminiLiveService
     */
    private fun unregisterVisionTranscriptionListener() {
        val geminiLive = GeminiLiveService.getInstance()
        geminiLive?.setVisionTranscriptionListener(null)
        Log.i(TAG, "👁️ VisionChat unregistered from transcription listener")
    }
    
    /**
     * 🆕 Secondary transcription listener - receives user's voice input
     * When user says "who is in front of me" (or similar), capture a new photo!
     */
    private val visionTranscriptionListener = object : GeminiLiveService.VisionTranscriptionListener {
        override fun onUserTranscription(text: String, isFinal: Boolean) {
            // Only process FINAL transcriptions to avoid false triggers
            if (!isFinal) return

            Log.d(TAG, "👂 VisionChat received user transcription: '$text' (final=$isFinal)")

            // ── 1. STOP → leave Vision Chat, return to home/Gemini Live chat ──
            if (isStopCommand(text)) {
                Log.i(TAG, "🛑 STOP command detected in VisionChat: '$text'")
                runOnUiThread { handleStopCommand() }
                return
            }

            // ── 2. TAKE ANOTHER PICTURE → capture a FRESH photo ──
            if (isTakeAnotherCommand(text)) {
                Log.i(TAG, "📸 'Take another picture' command detected: '$text'")
                GeminiLiveService.getInstance()?.muteOutput()
                runOnUiThread {
                    if (isProcessing) {
                        Log.w(TAG, "⚠️ Already processing, ignoring 'take another'")
                        return@runOnUiThread
                    }
                    userVisionQuery = "What do you see now?"
                    Toast.makeText(this@VisionChatActivity, "📸 Capturing new photo...", Toast.LENGTH_SHORT).show()
                    clearLastCapturedImage()   // force a brand-new capture
                    startThinkingTune()
                    startCaptureProcess()
                }
                return
            }

            // ── 3. Any other vision question → answer from the CURRENT picture ──
            //     (no new capture). startCaptureProcess() reuses the last image when one
            //     exists; if none has been taken yet it captures the first one.
            if (isVisionTrigger(text)) {
                Log.i(TAG, "💬 Vision question — answering from current picture: '$text'")
                GeminiLiveService.getInstance()?.muteOutput()
                runOnUiThread {
                    if (isProcessing) {
                        Log.w(TAG, "⚠️ Already processing, ignoring question")
                        return@runOnUiThread
                    }
                    userVisionQuery = text
                    // NOTE: intentionally do NOT clear the last image — we want to reuse it.
                    startThinkingTune()
                    startCaptureProcess()   // reuses last image if available, else first capture
                }
                return
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()

        // 🆕 Clean up the vision-result-spoken wait, if one was still pending
        visionResultSpokenTimeoutRunnable?.let {
            android.os.Handler(android.os.Looper.getMainLooper()).removeCallbacks(it)
        }
        visionResultSpokenTimeoutRunnable = null
        visionResultSpokenReceiver?.let {
            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this).unregisterReceiver(it)
        }
        visionResultSpokenReceiver = null

        // 🆕 Reset MainActivity's visionChatOpenFlag
        MainActivity.visionChatOpenFlag = false
        Log.d(TAG, "🔄 VisionChat closed - reset visionChatOpenFlag")

        // 🆕 Safety net: if the user backed out mid-analysis, the vision window
        // never completed - make sure wake word detection isn't left suppressed.
        // (MainActivity re-arms the detector in onResume.)
        releaseVisionWakeSuppression("vision-chat-destroyed")
        
        // 🔊 UNMUTE Gemini Live in case activity closes without completing
        // This ensures Gemini Live is responsive even if user backs out
        GeminiLiveService.getInstance()?.unmuteOutput()
        Log.d(TAG, "🔊 Gemini Live unmuted on VisionChat destroy")
        
        // 🆕 Unregister vision transcription listener
        unregisterVisionTranscriptionListener()
        
        // 🛑 Stop continuous streaming
        stopContinuousVisionStream()
        
        // 🛑 Stop BLE-based continuous streaming
        stopBleBasedContinuousStream()
        
        // 🛑 Unregister stop streaming receiver
        try {
            unregisterReceiver(stopStreamingReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Stop receiver already unregistered")
        }
        
        // ✅ Safely cancel mainScope - handle CancellationException
        try {
            mainScope.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "MainScope cancellation (expected): ${e.message}")
        }
        
        // Stop thinking tune if playing
        stopThinkingTune()
        
        // 🆕 Remove BLE notification listener
        try {
            LargeDataHandler.getInstance().removeOutDeviceListener(3)
        } catch (e: Exception) {
            Log.w(TAG, "Error removing BLE listener: ${e.message}")
        }
        
        // Cleanup WiFi monitoring
        networkCallback?.let {
            try {
                connectivityManager?.unregisterNetworkCallback(it)
            } catch (e: Exception) { }
        }

        // 🌐 Release the Glass hotspot network binding
        releaseGlassNetwork()

        // Cleanup WiFi P2P
        wifiP2pHelper.cleanup()
        
        // Cleanup TTS
        tts?.stop()
        tts?.shutdown()
        tts = null
        
        Log.d(TAG, "🔊 VisionChatActivity destroyed")
    }
}

// Data class for chat messages
data class VisionChatMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long,
    val imagePath: String? = null
)

// RecyclerView Adapter for vision chat
class VisionChatAdapter(private val messages: List<VisionChatMessage>) :
    RecyclerView.Adapter<VisionChatAdapter.MessageViewHolder>() {

    class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val messageText: TextView = view.findViewById(R.id.messageText)
        val timestampText: TextView = view.findViewById(R.id.timestampText)
        val messageImage: android.widget.ImageView = view.findViewById(R.id.messageImage)
        val searchWebRow: android.view.View? = view.findViewById(R.id.searchWebRow)
        val btnSearchWebVision: TextView? = view.findViewById(R.id.btnSearchWebVision)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_vision_chat_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = messages[position]
        holder.messageText.text = message.text

        val timeFormat = SimpleDateFormat("HH:mm", Locale.US)
        holder.timestampText.text = timeFormat.format(Date(message.timestamp))

        if (message.imagePath != null) {
            holder.messageImage.visibility = View.VISIBLE
            val bitmap = BitmapFactory.decodeFile(message.imagePath)
            holder.messageImage.setImageBitmap(bitmap)
        } else {
            holder.messageImage.visibility = View.GONE
        }

        // Show "Search on Web" button only for non-empty AI messages
        if (!message.isUser && message.text.isNotBlank()) {
            holder.searchWebRow?.visibility = View.VISIBLE
            holder.searchWebRow?.setOnClickListener {
                val query = Uri.encode(message.text.take(500))
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$query"))
                it.context.startActivity(intent)
            }
        } else {
            holder.searchWebRow?.visibility = View.GONE
        }
    }

    override fun getItemViewType(position: Int): Int {
        return if (messages[position].isUser) 0 else 1
    }

    override fun getItemCount() = messages.size
}
