package com.sdk.glassessdksample.ui.gallery

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pDevice
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.sdk.glassessdksample.R
import com.sdk.glassessdksample.ui.wifi.GlassMediaTransfer
import com.sdk.glassessdksample.ui.wifi.WifiP2pHelper
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyListener
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyRsp
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.net.InetSocketAddress
import java.net.Socket


/**
 * Glass Media Gallery - View Photos/Videos from Glass
 * 
 * Features:
 * - Trigger Glass Hotspot
 * - Connect to Glass WiFi
 * - Download photos/videos
 * - Display in grid view
 * - Play videos / View photos
 */
class GlassMediaGalleryActivity : AppCompatActivity(), 
    GlassMediaTransfer.TransferListener,
    WifiP2pHelper.WifiP2pCallback {
    
    companion object {
        private const val TAG = "GlassMediaGallery"
        private const val LOCATION_PERMISSION_REQUEST = 1001
        private const val NEARBY_PERMISSION_REQUEST = 1002
        private const val MENU_SELECT = 2001
        private const val MENU_DELETE = 2002
        /** The Glass acts as Wi-Fi Direct Group Owner at this fixed address. */
        private const val GLASS_IP = "192.168.6.1"
        /** Any interface holding an address in this range is the Glass link. */
        private const val GLASS_SUBNET_PREFIX = "192.168.6."
        
        fun launch(context: Context) {
            context.startActivity(Intent(context, GlassMediaGalleryActivity::class.java))
        }
    }
    
    private lateinit var glassMediaTransfer: GlassMediaTransfer
    private lateinit var wifiP2pHelper: WifiP2pHelper
    // HTTP-based album downloader for Glass HTTP server (port 80)
    private val albumDownloader by lazy { com.sdk.glassessdksample.ui.wifi.AlbumDownloader(this) }
    // 🌐 Callback holding the Wi-Fi Direct network. Android routes every socket over
    // the DEFAULT network (home WiFi, which has internet) unless a socket is explicitly
    // bound elsewhere. The Glass lives on a P2P-only subnet with no internet, so
    // unbound sockets were being routed out wlan0 to the home router and dropped:
    //     failed to connect to /192.168.6.1 (port 80) from /192.168.1.36
    // Holding this callback both keeps the P2P network alive and gives us the Network
    // object whose socketFactory pins traffic to the p2p interface.
    private var p2pNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private val mediaFiles = mutableListOf<GlassMediaTransfer.MediaFileInfo>()
    private var adapter: MediaAdapter? = null
    private val selectedFileNames = mutableSetOf<String>()
    private var isSelectionMode = false
    
    private var recyclerView: RecyclerView? = null
    private var progressBar: ProgressBar? = null
    private var tvStatus: TextView? = null
    private var tvProgress: TextView? = null
    private var btnConnect: Button? = null
    private var btnRefresh: Button? = null
    private var btnDiscover: Button? = null
    private var layoutProgress: View? = null
    
    // Track if we went to WiFi settings
    private var wentToWifiSettings = false
    private var isConnectedToGlass = false
    private var isDownloadComplete = false  // true once a download session finishes
    private var discoveredDevices = mutableListOf<WifiP2pDevice>()
    private var discoveryRetryJob: Job? = null
    private var connectionProgressDialog: android.app.AlertDialog? = null
    private var connectionProgressTextView: TextView? = null
    private var connectionProgressBar: ProgressBar? = null
    private var connectionProgressPercentTextView: TextView? = null
    private var connectionProgressValue: Int = 0
    private var lastProgressLine: String = ""
    private val connectionStepCircles = mutableListOf<TextView>()
    private val connectionStepTitles = mutableListOf<TextView>()
    private val connectionStepConnectors = mutableListOf<View>()
    private val stepTitles = listOf("Start", "Discover", "Connect", "Download")
    
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    // BLE notification listener for Glass IP (notification type 8)
    private val bleDeviceNotifyListener = object : GlassesDeviceNotifyListener() {
        override fun parseData(cmdType: Int, rsp: GlassesDeviceNotifyRsp?) {
            try {
                if (rsp == null) return
                
                val loadData = rsp.loadData ?: return
                if (loadData.size < 11) return
                
                // loadData[6] contains the notification type
                val notifyType = loadData[6].toInt() and 0xFF
                
                Log.d(TAG, "📡 BLE Notification type: $notifyType")
                
                when (notifyType) {
                    // Type 8: Glass IP Address after P2P connect
                    8 -> {
                        val ip = "${loadData[7].toInt() and 0xFF}.${loadData[8].toInt() and 0xFF}.${loadData[9].toInt() and 0xFF}.${loadData[10].toInt() and 0xFF}"
                        Log.d(TAG, "🌐 BLE returned Glass IP: $ip")

                        runOnUiThread {
                            // Guard: ignore duplicate BLE notifications during an active download
                            if (isConnectedToGlass || isDownloadComplete) {
                                Log.d(TAG, "Ignoring duplicate BLE IP notification (already connected/done)")
                                return@runOnUiThread
                            }

                            updateStatus("📡 Glass IP from BLE: $ip")
                            isConnectedToGlass = true
                            discoveryRetryJob?.cancel()

                            // Stop P2P discovery — BLE gave us the IP, no need to scan
                            wifiP2pHelper.stopDiscovery()

                            // BLE confirmed the IP — Glass HTTP server is on port 80.
                            // But BLE only proves the Glass is READY, not that the phone
                            // has a route to it: this notification arrives BEFORE the
                            // Wi-Fi Direct link finishes forming. Downloading straight
                            // away therefore ran over home WiFi and timed out with
                            // "Config fetch error ... after 5000ms" → "No files found".
                            // Bind to the P2P network first, then fetch.
                            appendConnectionStep("Device connected via BLE")
                            appendConnectionStep("Preparing download")
                            updateConnectionProgress(55)
                            bindToP2pNetworkThen {
                                mainScope.launch { downloadViaHttp(ip) }
                            }
                        }
                    }
                    
                    // Type 9: Error codes
                    9 -> {
                        val errorCode = loadData[7].toInt() and 0xFF
                        Log.w(TAG, "⚠️ BLE Error notification: $errorCode")
                        if (errorCode == 255) {
                            // Need to reset P2P and try again
                            runOnUiThread {
                                updateStatus("⚠️ P2P Error - Retrying...")
                                glassMediaTransfer.triggerGlassHotspot() // Reset and retry
                            }
                        }
                    }
                    
                    // Type 1: Media count update
                    1 -> {
                        if (loadData.size >= 13) {
                            val imageCount = ((loadData[7].toInt() and 0xFF) or ((loadData[8].toInt() and 0xFF) shl 8))
                            val videoCount = ((loadData[9].toInt() and 0xFF) or ((loadData[10].toInt() and 0xFF) shl 8))
                            val recordCount = ((loadData[11].toInt() and 0xFF) or ((loadData[12].toInt() and 0xFF) shl 8))
                            val total = imageCount + videoCount + recordCount
                            Log.d(TAG, "📷 Media count: $imageCount images, $videoCount videos, $recordCount records")
                            runOnUiThread {
                                updateStatus("📷 Glass has $total files")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing BLE notification: ${e.message}")
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Use XML layout instead of programmatic creation
        setContentView(R.layout.activity_glass_media_gallery)
        
        // Initialize views from layout
        recyclerView = findViewById(R.id.recyclerView)
        progressBar = findViewById(R.id.progressBar)
        tvStatus = findViewById(R.id.tvStatus)
        tvProgress = findViewById(R.id.tvProgress)
        btnConnect = findViewById(R.id.btnConnect)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnDiscover = findViewById(R.id.btnDiscover)
        layoutProgress = findViewById(R.id.layoutProgress)
        
        // Setup back button
        findViewById<ImageView>(R.id.btnBack).setOnClickListener {
            finish()
        }
        
        // Setup menu button
        findViewById<ImageView>(R.id.btnMenu).setOnClickListener {
            showOverflowMenu(it)
        }
        
        // Setup button click listeners
        btnConnect?.setOnClickListener { startConnection() }
        btnRefresh?.setOnClickListener { refreshMediaList() }
        btnDiscover?.setOnClickListener { 
            updateStatus("🔍 Searching for Glass devices...")
            wifiP2pHelper.startDiscovery()
        }
        
        // Check and request location permission for WiFi SSID
        checkLocationPermission()
        
        // Initialize WiFi P2P helper
        wifiP2pHelper = WifiP2pHelper(this)
        wifiP2pHelper.setCallback(this)
        wifiP2pHelper.initialize()
        wifiP2pHelper.registerReceiver()
        
        // Initialize transfer manager
        glassMediaTransfer = GlassMediaTransfer(this)
        glassMediaTransfer.setListener(this)
        
        // Widen the BLE pipe. The bulk media download here runs over WiFi, but the
        // BLE link still carries control commands and thumbnail data, so the larger
        // MTU and faster interval shorten the setup phase before WiFi takes over.
        com.sdk.glassessdksample.ui.BleSpeedTuner.tune(this, "MediaGallery")

        // Register BLE notification listener for Glass IP (type 8)
        // Using listener ID 2 like the original app
        LargeDataHandler.getInstance().addOutDeviceListener(2, bleDeviceNotifyListener)
        Log.d(TAG, "📡 BLE notification listener registered for Glass IP")
        
        // Setup adapter with new grid layout
        adapter = MediaAdapter(
            context = this,
            items = mediaFiles,
            onTap = { fileInfo ->
                if (isSelectionMode) {
                    toggleSelection(fileInfo)
                } else {
                    openMedia(fileInfo)
                }
            },
            onLongPress = { fileInfo ->
                if (!isSelectionMode) {
                    enterSelectionMode()
                }
                toggleSelection(fileInfo)
            },
            isSelectionMode = { isSelectionMode },
            isSelected = { fileInfo -> selectedFileNames.contains(fileInfo.fileName) }
        )
        recyclerView?.layoutManager = GridLayoutManager(this, 3)  // 3-column grid
        recyclerView?.adapter = adapter
        
        // Load any existing downloaded files
        loadLocalFiles()
        
        updateStatus("Press 'Connect to Glass' to start")
    }
    
    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume - wentToWifiSettings: $wentToWifiSettings, isConnectedToGlass: $isConnectedToGlass")
        
        // Re-register P2P receiver
        wifiP2pHelper.registerReceiver()
        
        // If we returned from WiFi settings, auto-check connection
        if (wentToWifiSettings && !isConnectedToGlass) {
            wentToWifiSettings = false
            updateStatus("🔍 Checking Glass WiFi connection...")
            
            mainScope.launch {
                // Wait for WiFi Direct connection to be fully established
                delay(2000)
                tryCurrentConnection()
            }
        }
    }
    
    override fun onPause() {
        super.onPause()
        // Unregister to avoid leaks but will re-register in onResume
        // Don't cleanup - just unregister receiver
        try {
            wifiP2pHelper.unregisterReceiver()
        } catch (e: IllegalArgumentException) {
            // On some Oplus/Realme devices this throws when already unregistered
            Log.d(TAG, "Receiver already unregistered (Oplus safety catch)")
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering: ${e.message}")
        }
    }
    
    /**
     * Auto-check and connect to Glass when returning from WiFi settings
     * Uses WifiP2pHelper for proper P2P IP detection
     */
    private fun checkAndConnectToGlass() {
        mainScope.launch {
            updateStatus("🔍 Searching for Glass on WiFi...")
            
            // Log phone's IP for debugging
            logPhoneNetworkInfo()
            
            // First try P2P helper to detect Glass IP
            val glassIp = withContext(Dispatchers.IO) {
                wifiP2pHelper.tryGetGlassIpFromCurrentConnection()
            }
            
            if (glassIp != null) {
                onGlassConnected(glassIp)
                return@launch
            }
            
            // Fallback: Try GlassMediaTransfer's scan method
            val connected = glassMediaTransfer.connectToGlass()
            if (connected) {
                isConnectedToGlass = true
                updateStatus("✅ Connected! Getting media list...")
                btnConnect?.text = "✅ Connected"
                btnConnect?.isEnabled = false
                glassMediaTransfer.getMediaList()
                return@launch
            }
            
            // Connection failed - show detailed error
            val currentSSID = getCurrentWifiSSID()
            Log.d(TAG, "Current SSID: $currentSSID")
            
            if (currentSSID != null && (currentSSID.contains("M01") || currentSSID.contains("Glass") || currentSSID.contains("DIRECT"))) {
                // Connected to Glass WiFi but can't reach socket server
                updateStatus("⚠️ WiFi connected but Glass server not responding")
                showRetryDialog()
            } else {
                updateStatus("❌ Cannot connect - please check WiFi")
                startAutoDiscovery()
            }
        }
    }
    
    /**
     * Log phone's network info for debugging
     */
    private fun logPhoneNetworkInfo() {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isUp && !iface.isLoopback) {
                    val addresses = iface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val addr = addresses.nextElement()
                        if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                            Log.i(TAG, "📱 Network: ${iface.name} = ${addr.hostAddress}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error logging network info: ${e.message}")
        }
    }
    
    /**
     * Show retry dialog when connection fails
     */
    private fun showRetryDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle("⚠️ Connection Failed")
            .setMessage(
                "Glass WiFi se connect hai lekin server respond nahi kar raha.\n\n" +
                "Possible issues:\n" +
                "• Glass ka hotspot abhi start ho raha hai\n" +
                "• Glass restart karo aur phir try karo\n" +
                "• WiFi Direct properly connect nahi hua\n\n" +
                "Retry karna chahte ho?"
            )
            .setPositiveButton("🔄 Retry") { _, _ ->
                mainScope.launch {
                    updateStatus("🔄 Retrying connection...")
                    delay(2000)
                    checkAndConnectToGlass()
                }
            }
            .setNegativeButton("WiFi Settings") { _, _ ->
                wentToWifiSettings = true
                openWifiDirectSettings()
            }
            .setNeutralButton("Cancel", null)
            .show()
    }
    
    /**
     * Show dialog when permission is required
     */
    private fun showPermissionRequiredDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle("📍 Permission Required")
            .setMessage(
                "WiFi SSID detect karne ke liye Location permission chahiye.\n\n" +
                "Please permission allow karo."
            )
            .setPositiveButton("Grant Permission") { _, _ ->
                checkLocationPermission()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    /**
     * Get current WiFi SSID
     */
    private fun getCurrentWifiSSID(): String? {
        // Check location permission first
        if (!hasLocationPermission()) {
            Log.w(TAG, "Location permission not granted - cannot get SSID")
            return null
        }
        
        // Check if location is enabled
        if (!isLocationEnabled()) {
            Log.w(TAG, "Location services disabled - cannot get SSID")
            return null
        }
        
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ use ConnectivityManager
                getSSIDFromConnectivityManager()
            } else {
                // Legacy method
                getSSIDFromWifiManager()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting SSID: ${e.message}")
            null
        }
    }
    
    /**
     * Get SSID using ConnectivityManager (Android 10+)
     */
    private fun getSSIDFromConnectivityManager(): String? {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return null
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return null
        
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            val ssid = wifiInfo?.ssid?.replace("\"", "")
            Log.d(TAG, "SSID from ConnectivityManager: $ssid")
            return if (ssid == "<unknown ssid>" || ssid == null) null else ssid
        }
        return null
    }
    
    /**
     * Get SSID using WifiManager (legacy)
     */
    private fun getSSIDFromWifiManager(): String? {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiInfo = wifiManager.connectionInfo
        val ssid = wifiInfo?.ssid?.replace("\"", "")
        Log.d(TAG, "SSID from WifiManager: $ssid")
        return if (ssid == "<unknown ssid>" || ssid == null) null else ssid
    }
    
    /**
     * Check if location permission is granted
     */
    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, 
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Check if location services are enabled
     */
    private fun isLocationEnabled(): Boolean {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
               locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }
    
    /**
     * Check and request location permission
     */
    private fun checkLocationPermission() {
        val permissionsNeeded = mutableListOf<String>()
        
        // Location permissions
        if (!hasLocationPermission()) {
            permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissionsNeeded.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        
        // Android 12+ needs NEARBY_WIFI_DEVICES permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) 
                != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }
        
        if (permissionsNeeded.isNotEmpty()) {
            Log.d(TAG, "Requesting permissions: $permissionsNeeded")
            ActivityCompat.requestPermissions(
                this,
                permissionsNeeded.toTypedArray(),
                LOCATION_PERMISSION_REQUEST
            )
        } else {
            Log.d(TAG, "All permissions already granted")
            // Check if location is enabled
            if (!isLocationEnabled()) {
                showLocationEnableDialog()
            }
        }
    }
    
    /**
     * Check if NEARBY_WIFI_DEVICES permission is granted (Android 12+)
     */
    private fun hasNearbyPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) == 
                PackageManager.PERMISSION_GRANTED
        } else {
            true // Not needed for older Android versions
        }
    }
    
    /**
     * Show dialog to enable location services
     */
    private fun showLocationEnableDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle("📍 Location Required")
            .setMessage(
                "WiFi SSID padhne ke liye Location ON hona chahiye.\n\n" +
                "Yeh Android ka requirement hai - Location ON karo to Glass WiFi detect ho payega."
            )
            .setPositiveButton("📍 Enable Location") { _, _ ->
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            
            if (allGranted) {
                Log.d(TAG, "All permissions granted: ${permissions.toList()}")
                Toast.makeText(this, "✅ All permissions granted", Toast.LENGTH_SHORT).show()
                
                // Check if location is enabled
                if (!isLocationEnabled()) {
                    showLocationEnableDialog()
                }
            } else {
                // Check which permissions were denied
                val deniedPermissions = permissions.filterIndexed { index, _ -> 
                    grantResults.getOrNull(index) != PackageManager.PERMISSION_GRANTED 
                }
                Log.w(TAG, "Permissions denied: $deniedPermissions")
                
                if (deniedPermissions.any { it.contains("NEARBY") }) {
                    Toast.makeText(this, "⚠️ Nearby Devices permission needed for WiFi P2P", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "⚠️ Location permission needed for WiFi detection", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    
    /**
     * Start connection process
     */
    private fun startConnection() {
        isDownloadComplete = false  // Reset for new session
        isConnectedToGlass = false
        showConnectionProgressDialog()
        appendConnectionStep("Connect request started")
        updateConnectionProgress(5)
        updateStatus("📡 Sending BLE command to Glass...")
        btnConnect?.isEnabled = false

        // Step 1: Trigger Glass hotspot/P2P mode
        glassMediaTransfer.triggerGlassHotspot()
        
        // Always use auto discovery (no method selection popup)
        mainScope.launch {
            delay(2000) // Wait for Glass P2P to start
            startAutoDiscovery()
        }
    }
    
    /**
     * Start Auto Discovery directly (default flow)
     */
    private fun startAutoDiscovery() {
        btnConnect?.isEnabled = true
        appendConnectionStep("Searching for Glass device")
        updateConnectionProgress(25)
        updateStatus("🔍 Searching for Glass devices...")
        wifiP2pHelper.startDiscovery()
    }
    
    /**
     * Try to get Glass IP from current WiFi/P2P connection
     */
    private fun tryCurrentConnection() {
        updateStatus("🔍 Checking current connection...")
        
        mainScope.launch {
            // First try WifiP2pHelper method
            val glassIp = withContext(Dispatchers.IO) {
                wifiP2pHelper.tryGetGlassIpFromCurrentConnection()
            }
            
            if (glassIp != null) {
                onGlassConnected(glassIp)
            } else {
                // Fallback to GlassMediaTransfer scan
                updateStatus("🔍 Scanning for Glass server...")
                val connected = glassMediaTransfer.connectToGlass()
                if (!connected) {
                    startAutoDiscovery()
                }
            }
        }
    }
    
    /**
     * Show dialog to guide user to connect WiFi
     */
    private fun showWifiConnectionDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle("📶 Connect to Glass WiFi")
            .setMessage(
                "Glass ka WiFi hotspot ON ho gaya hai!\n\n" +
                "Ab yeh karo:\n" +
                "1️⃣ Phone ki WiFi Settings kholo\n" +
                "2️⃣ WiFi Direct / P2P section mein jao\n" +
                "3️⃣ \"M01_F736...\" network dhundo\n" +
                "4️⃣ Us network se connect karo\n" +
                "5️⃣ Wapas yahan aao - AUTO CONNECT hoga!\n\n" +
                "Note: Glass WiFi SSID usually starts with M01_"
            )
            .setPositiveButton("📶 WiFi Direct Settings") { _, _ ->
                wentToWifiSettings = true
                openWifiDirectSettings()
            }
            .setNeutralButton("🔄 Retry Connection") { _, _ ->
                checkAndConnectToGlass()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    /**
     * Open WiFi Direct settings
     * Tries multiple intents since different Android versions use different paths
     */
    private fun openWifiDirectSettings() {
        val intents = listOf(
            // Android WiFi Direct P2P settings
            android.content.Intent("android.settings.WIFI_P2P_SETTINGS"),
            android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS),
            android.content.Intent().apply {
                action = android.provider.Settings.ACTION_WIRELESS_SETTINGS
            }
        )
        
        for (intent in intents) {
            try {
                if (intent.resolveActivity(packageManager) != null) {
                    startActivity(intent)
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "Intent failed: ${e.message}")
            }
        }
        
        // Fallback to regular WiFi settings
        try {
            startActivity(android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open WiFi settings", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * Refresh media list from Glass
     */
    private fun refreshMediaList() {
        if (!glassMediaTransfer.isConnectedToGlass()) {
            Toast.makeText(this, "Not connected to Glass", Toast.LENGTH_SHORT).show()
            return
        }
        
        mainScope.launch {
            updateStatus("Refreshing media list...")
            glassMediaTransfer.getMediaList()
        }
    }

    private fun showOverflowMenu(anchor: View) {
        val popupMenu = PopupMenu(this, anchor)
        popupMenu.menu.add(
            0,
            MENU_SELECT,
            0,
            if (isSelectionMode) "Cancel selection" else "Select"
        )
        popupMenu.menu.add(
            0,
            MENU_DELETE,
            1,
            if (selectedFileNames.isEmpty()) "Delete selected" else "Delete selected (${selectedFileNames.size})"
        )
        popupMenu.menu.findItem(MENU_DELETE)?.isEnabled = selectedFileNames.isNotEmpty()

        popupMenu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_SELECT -> {
                    if (isSelectionMode) {
                        exitSelectionMode(showMessage = true)
                    } else {
                        enterSelectionMode()
                    }
                    true
                }

                MENU_DELETE -> {
                    deleteSelectedImages()
                    true
                }

                else -> false
            }
        }

        popupMenu.show()
    }

    private fun enterSelectionMode() {
        if (isSelectionMode) return
        isSelectionMode = true
        selectedFileNames.clear()
        adapter?.notifyDataSetChanged()
        updateStatus("Selection mode enabled")
        Toast.makeText(this, "Tap images to select", Toast.LENGTH_SHORT).show()
    }

    private fun exitSelectionMode(showMessage: Boolean) {
        if (!isSelectionMode) return
        isSelectionMode = false
        selectedFileNames.clear()
        adapter?.notifyDataSetChanged()
        if (showMessage) {
            Toast.makeText(this, "Selection canceled", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleSelection(item: GlassMediaTransfer.MediaFileInfo) {
        if (!isSelectionMode) return

        if (selectedFileNames.contains(item.fileName)) {
            selectedFileNames.remove(item.fileName)
        } else {
            selectedFileNames.add(item.fileName)
        }

        if (selectedFileNames.isEmpty()) {
            updateStatus("Selection mode enabled")
        } else {
            updateStatus("${selectedFileNames.size} selected")
        }
        adapter?.notifyDataSetChanged()
    }
    
    /**
     * Delete selected images from gallery
     */
    private fun deleteSelectedImages() {
        if (selectedFileNames.isEmpty()) {
            Toast.makeText(this, "Select items first", Toast.LENGTH_SHORT).show()
            return
        }

        val selectedCount = selectedFileNames.size
        android.app.AlertDialog.Builder(this)
            .setTitle("Delete selected")
            .setMessage("Delete $selectedCount selected item${if (selectedCount == 1) "" else "s"}?")
            .setPositiveButton("Delete") { _, _ ->
                var deletedCount = 0
                val iterator = mediaFiles.iterator()

                while (iterator.hasNext()) {
                    val item = iterator.next()
                    if (!selectedFileNames.contains(item.fileName)) continue

                    val deleted = if (!item.localPath.isNullOrBlank()) {
                        val file = File(item.localPath!!)
                        !file.exists() || file.delete()
                    } else {
                        true
                    }

                    if (deleted) {
                        iterator.remove()
                        deletedCount++
                    }
                }

                isSelectionMode = false
                selectedFileNames.clear()
                adapter?.notifyDataSetChanged()
                updateStatus("Deleted $deletedCount item${if (deletedCount == 1) "" else "s"}")
                Toast.makeText(
                    this,
                    "Deleted $deletedCount item${if (deletedCount == 1) "" else "s"}",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    /**
     * Fetch file list from Glass and start download
     * Called when BLE returns Glass IP (notification type 8)
     */
    private fun fetchAndDownloadFromGlass() {
        appendConnectionStep("Fetching media list from device")
        updateStatus("📋 Fetching file list from Glass...")
        layoutProgress?.visibility = View.VISIBLE
        
        mainScope.launch {
            try {
                // Get file list from Glass via socket
                val files = glassMediaTransfer.getMediaList()
                
                if (files.isNotEmpty()) {
                    mediaFiles.clear()
                    mediaFiles.addAll(files)
                    adapter?.notifyDataSetChanged()
                    
                    updateStatus("📥 Found ${files.size} files. Starting download...")
                    appendConnectionStep("Downloading data from Glass")
                    
                    // Start downloading all files
                    val toDownload = files.filter { !it.isDownloaded }
                    if (toDownload.isNotEmpty()) {
                        glassMediaTransfer.downloadAllFiles(toDownload)
                    } else {
                        appendConnectionStep("Download completed")
                        closeConnectionProgressDialog("Connected. All files already downloaded")
                        updateStatus("✅ All ${files.size} files already downloaded")
                        layoutProgress?.visibility = View.GONE
                    }
                } else {
                    // No files from socket - Glass may need different protocol
                    appendConnectionStep("Device connected")
                    closeConnectionProgressDialog("Connected to Glass")
                    updateStatus("📷 Connected! Press 'Download All' to fetch files")
                    layoutProgress?.visibility = View.GONE
                    Toast.makeText(this@GlassMediaGalleryActivity, 
                        "Connected to Glass at ${glassMediaTransfer.isConnectedToGlass()}", 
                        Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching files: ${e.message}")
                appendConnectionStep("Failed: ${e.message}")
                closeConnectionProgressDialog("Connection failed")
                updateStatus("⚠️ Error: ${e.message}")
                layoutProgress?.visibility = View.GONE
            }
        }
    }
    
    /**
     * Download all media from Glass
     */
    private fun downloadAllMedia() {
        if (mediaFiles.isEmpty()) {
            Toast.makeText(this, "No media to download", Toast.LENGTH_SHORT).show()
            return
        }
        
        val toDownload = mediaFiles.filter { !it.isDownloaded }
        if (toDownload.isEmpty()) {
            Toast.makeText(this, "All files already downloaded", Toast.LENGTH_SHORT).show()
            return
        }
        
        layoutProgress?.visibility = View.VISIBLE
        updateStatus("Downloading ${toDownload.size} files...")
        
        glassMediaTransfer.downloadAllFiles(toDownload)
    }
    
    /**
     * Load already downloaded files
     */
    private fun loadLocalFiles() {
        val photosDir = glassMediaTransfer.getPhotosDirectory()
        val videosDir = glassMediaTransfer.getVideosDirectory()
        
        val existingFiles = mutableListOf<GlassMediaTransfer.MediaFileInfo>()
        
        // Load photos
        photosDir.listFiles()?.forEach { file ->
            existingFiles.add(GlassMediaTransfer.MediaFileInfo(
                fileName = file.name,
                fileType = "photo",
                fileSize = file.length(),
                timestamp = file.lastModified(),
                localPath = file.absolutePath,
                isDownloaded = true
            ))
        }
        
        // Load videos
        videosDir.listFiles()?.forEach { file ->
            existingFiles.add(GlassMediaTransfer.MediaFileInfo(
                fileName = file.name,
                fileType = "video",
                fileSize = file.length(),
                timestamp = file.lastModified(),
                localPath = file.absolutePath,
                isDownloaded = true
            ))
        }
        
        if (existingFiles.isNotEmpty()) {
            mediaFiles.clear()
            mediaFiles.addAll(existingFiles.sortedByDescending { it.timestamp })
            adapter?.notifyDataSetChanged()
            updateStatus("${existingFiles.size} local files found")
        }
    }
    
    /**
     * Open media file
     */
    private fun openMedia(fileInfo: GlassMediaTransfer.MediaFileInfo) {
        val localPath = fileInfo.localPath
        if (localPath == null) {
            // Need to download first
            mainScope.launch {
                updateStatus("Downloading ${fileInfo.fileName}...")
                layoutProgress?.visibility = View.VISIBLE
                
                val path = glassMediaTransfer.downloadFile(fileInfo)
                if (path != null) {
                    fileInfo.localPath = path
                    fileInfo.isDownloaded = true
                    adapter?.notifyDataSetChanged()
                    openLocalFile(path, fileInfo.fileType)
                }
                
                layoutProgress?.visibility = View.GONE
            }
            return
        }
        
        openLocalFile(localPath, fileInfo.fileType)
    }
    
    private fun openLocalFile(path: String, fileType: String) {
        try {
            val file = File(path)
            
            if (!file.exists()) {
                Log.e(TAG, "File does not exist: $path")
                Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show()
                return
            }
            
            // Determine file type from extension if fileType is not clear
            val extension = file.extension.lowercase()
            val isImage = fileType.equals("image", ignoreCase = true) || 
                          extension in listOf("jpg", "jpeg", "png", "gif", "bmp", "webp")
            val isVideo = fileType.equals("video", ignoreCase = true) || 
                          extension in listOf("mp4", "mkv", "avi", "mov", "3gp", "webm")
            
            Log.d(TAG, "Opening file: $path (type: $fileType, ext: $extension, isImage: $isImage, isVideo: $isVideo)")
            
            when {
                isImage -> {
                    // Get all image paths for swipe navigation
                    val allImagePaths = mediaFiles
                        .filter { f -> 
                            val fExt = f.fileName.substringAfterLast('.').lowercase()
                            f.fileType.equals("image", ignoreCase = true) || 
                            fExt in listOf("jpg", "jpeg", "png", "gif", "bmp", "webp")
                        }
                        .mapNotNull { it.localPath }
                    val currentIndex = allImagePaths.indexOf(path).coerceAtLeast(0)
                    
                    // Open image in our in-app full-screen viewer with swipe navigation
                    ImageViewerActivity.open(this, path, file.name, ArrayList(allImagePaths), currentIndex)
                }
                isVideo -> {
                    // Open video in our in-app video player
                    VideoPlayerActivity.open(this, path, file.name)
                }
                else -> {
                    Toast.makeText(this, "Unsupported file type: $extension", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening file: ${e.message}", e)
            Toast.makeText(this, "Cannot open file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun updateStatus(status: String) {
        tvStatus?.text = "Status: $status"
        Log.d(TAG, status)
    }

    private fun showConnectionProgressDialog() {
        if (connectionProgressDialog?.isShowing == true) return

        lastProgressLine = ""
        connectionProgressValue = 0
        connectionStepCircles.clear()
        connectionStepTitles.clear()
        connectionStepConnectors.clear()
        tvStatus?.visibility = View.INVISIBLE
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }

        val subtitle = TextView(this).apply {
            textSize = 14f
            text = "Please wait while we connect and download data"
            alpha = 0.85f
        }
        content.addView(subtitle)

        val stepperRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.topMargin = 20
            layoutParams = params
        }

        for (i in stepTitles.indices) {
            val stepContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val circle = TextView(this).apply {
                text = "${i + 1}"
                gravity = android.view.Gravity.CENTER
                setTextColor(Color.parseColor("#9CA3AF"))
                textSize = 16f
                background = createStepCircleDrawable("pending")
                val size = (40 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size)
            }
            stepContainer.addView(circle)

            val stepLabel = TextView(this).apply {
                text = "Step ${i + 1}"
                textSize = 11f
                setTextColor(Color.parseColor("#9CA3AF"))
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.topMargin = 6
                layoutParams = params
            }
            stepContainer.addView(stepLabel)

            val stepTitle = TextView(this).apply {
                text = stepTitles[i]
                textSize = 12f
                setTextColor(Color.parseColor("#D1D5DB"))
            }
            stepContainer.addView(stepTitle)

            connectionStepCircles.add(circle)
            connectionStepTitles.add(stepTitle)
            stepperRow.addView(stepContainer)

            if (i < stepTitles.lastIndex) {
                val connector = View(this).apply {
                    setBackgroundColor(Color.parseColor("#4B5563"))
                    layoutParams = LinearLayout.LayoutParams(26, 4).apply {
                        topMargin = (20 * resources.displayMetrics.density).toInt()
                    }
                }
                connectionStepConnectors.add(connector)
                stepperRow.addView(connector)
            }
        }
        content.addView(stepperRow)

        connectionProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progress = 0
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.topMargin = 20
            layoutParams = params
        }
        content.addView(connectionProgressBar)

        connectionProgressPercentTextView = TextView(this).apply {
            textSize = 13f
            text = "0%"
            gravity = android.view.Gravity.END
        }
        content.addView(connectionProgressPercentTextView)

        connectionProgressTextView = TextView(this).apply {
            textSize = 15f
            text = ""
            setLineSpacing(10f, 1.0f)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.topMargin = 18
            layoutParams = params
        }
        content.addView(connectionProgressTextView)

        connectionProgressDialog = android.app.AlertDialog.Builder(this)
            .setTitle("Downloading from Glass")
            .setView(content)
            .setCancelable(false)
            .create()

        connectionProgressDialog?.show()
        updateConnectionStepper(0)
    }

    private fun appendConnectionStep(step: String) {
        if (connectionProgressDialog?.isShowing != true) return
        if (lastProgressLine == step) return

        val current = connectionProgressTextView?.text?.toString().orEmpty()
        val nextLine = "• $step"
        connectionProgressTextView?.text = if (current.isBlank()) nextLine else "$current\n$nextLine"
        lastProgressLine = step
    }

    private fun updateConnectionProgress(value: Int) {
        if (connectionProgressDialog?.isShowing != true) return

        val clamped = value.coerceIn(0, 100)
        if (clamped < connectionProgressValue) return

        connectionProgressValue = clamped
        connectionProgressBar?.progress = connectionProgressValue
        connectionProgressPercentTextView?.text = "$connectionProgressValue%"
        updateConnectionStepper(connectionProgressValue)
    }

    private fun updateConnectionStepper(progress: Int) {
        if (connectionStepCircles.isEmpty()) return

        val activeIndex = when {
            progress < 25 -> 0
            progress < 50 -> 1
            progress < 75 -> 2
            else -> 3
        }

        connectionStepCircles.forEachIndexed { index, circle ->
            when {
                progress >= 100 || index < activeIndex -> {
                    circle.text = "✓"
                    circle.setTextColor(Color.WHITE)
                    circle.background = createStepCircleDrawable("done")
                    connectionStepTitles[index].setTextColor(Color.WHITE)
                }
                index == activeIndex -> {
                    circle.text = "${index + 1}"
                    circle.setTextColor(Color.WHITE)
                    circle.background = createStepCircleDrawable("active")
                    connectionStepTitles[index].setTextColor(Color.parseColor("#93C5FD"))
                }
                else -> {
                    circle.text = "${index + 1}"
                    circle.setTextColor(Color.parseColor("#9CA3AF"))
                    circle.background = createStepCircleDrawable("pending")
                    connectionStepTitles[index].setTextColor(Color.parseColor("#D1D5DB"))
                }
            }
        }

        connectionStepConnectors.forEachIndexed { index, connector ->
            val done = progress >= 100 || index < activeIndex
            connector.setBackgroundColor(
                if (done) Color.parseColor("#3B82F6") else Color.parseColor("#4B5563")
            )
        }
    }

    private fun createStepCircleDrawable(state: String): GradientDrawable {
        val drawable = GradientDrawable()
        drawable.shape = GradientDrawable.OVAL

        when (state) {
            "done" -> {
                drawable.setColor(Color.parseColor("#3B82F6"))
                drawable.setStroke(3, Color.parseColor("#93C5FD"))
            }
            "active" -> {
                drawable.setColor(Color.parseColor("#2563EB"))
                drawable.setStroke(4, Color.parseColor("#60A5FA"))
            }
            else -> {
                drawable.setColor(Color.parseColor("#1F2937"))
                drawable.setStroke(3, Color.parseColor("#6B7280"))
            }
        }
        return drawable
    }

    private fun closeConnectionProgressDialog(finalStatus: String) {
        updateConnectionProgress(100)
        if (connectionProgressDialog?.isShowing == true) {
            connectionProgressDialog?.dismiss()
        }
        connectionProgressDialog = null
        connectionProgressTextView = null
        connectionProgressBar = null
        connectionProgressPercentTextView = null
        connectionProgressValue = 0
        connectionStepCircles.clear()
        connectionStepTitles.clear()
        connectionStepConnectors.clear()
        lastProgressLine = ""
        tvStatus?.visibility = View.VISIBLE
        updateStatus(finalStatus)
        btnConnect?.isEnabled = true
    }

    /**
     * 🌐 Acquire the Wi-Fi Direct network and route Glass traffic over it.
     *
     * Without this, sockets follow Android's default network. That default is the
     * phone's home WiFi (it has internet, so the OS prefers it), and the Glass subnet
     * is only reachable over the p2p interface — so every connection attempt was
     * routed to the home router and refused. Confirmed from the device routing table:
     *     192.168.6.1 via 192.168.1.1 dev wlan0 src 192.168.1.36
     *
     * requestNetwork() with NET_CAPABILITY_NOT_INTERNET is the supported way to ask
     * for such a network: the P2P link is deliberately internet-less, so the normal
     * "give me WiFi" request would never match it.
     *
     * [onReady] runs once the network is available and HTTP has been pinned to it.
     * It is invoked at most once per call, on the main thread.
     */
    private fun bindToP2pNetworkThen(onReady: () -> Unit) {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            Log.w(TAG, "🌐 No ConnectivityManager — proceeding on default route")
            onReady()
            return
        }

        // Releasing any previous request first: a stale callback from an earlier
        // attempt would otherwise keep an old (possibly dead) P2P network alive.
        releaseP2pNetwork()

        // Run off the main thread: isReachable() does real TCP connects.
        Thread {
            val bound = tryBindToGlass(cm, GLASS_IP)
            runOnUiThread {
                if (bound) {
                    appendConnectionStep("Network route locked to Glass")
                } else {
                    // Say what actually happened. The previous build claimed
                    // "Network route locked to Glass" even when it had bound to the
                    // home WiFi, which made a failure look like a success.
                    appendConnectionStep("No route to Glass yet — trying anyway")
                }
                onReady()
            }
        }.start()
    }

    /**
     * Bind HTTP to whatever network can actually reach [targetIp], if any.
     *
     * Mirrors the strategy already proven in VisionChatActivity.ensureGlassNetworkBound().
     * Order matters, cheapest first:
     *
     *  0. Already reachable on current routing? An active Wi-Fi Direct group installs a
     *     connected route that ConnectivityManager never surfaces as a Network object,
     *     so this case CANNOT be detected by inspecting networks — only by trying it.
     *     This is the normal working case and must not be blocked.
     *  1. Otherwise bind to a connected Network holding an address in the Glass subnet
     *     (a manually-joined Glass hotspot), and verify it really reaches the Glass.
     *
     * Returns true only when the Glass is genuinely reachable — never on a guess.
     */
    private fun tryBindToGlass(cm: ConnectivityManager, targetIp: String): Boolean {
        // 0. Cheapest: does the current routing already work?
        if (runBlocking { albumDownloader.isReachable(targetIp) }) {
            Log.i(TAG, "🌐 $targetIp already reachable on current routing — no binding needed")
            return true
        }

        // 1. Look for a network whose own address sits in the Glass subnet. Matching on
        //    the address (not just "is WiFi") is what stops us binding to the home
        //    network: plain WiFi matches a TRANSPORT_WIFI request, and binding to it
        //    sent every request out wlan0 to the home router.
        for (net in cm.allNetworks) {
            val lp = cm.getLinkProperties(net) ?: continue
            val iface = lp.interfaceName ?: "?"
            val matches = lp.linkAddresses.any { la ->
                la.address?.hostAddress?.startsWith(GLASS_SUBNET_PREFIX) == true
            }
            if (!matches) continue

            Log.i(TAG, "🌐 Trying candidate network $net (iface=$iface) for $targetIp")
            albumDownloader.bindToNetwork(net)
            if (runBlocking { albumDownloader.isReachable(targetIp) }) {
                Log.i(TAG, "🌐 Bound Glass HTTP to $net (iface=$iface)")
                return true
            }
            albumDownloader.bindToNetwork(null)
        }

        Log.w(TAG, "🌐 No network can reach $targetIp — the Wi-Fi Direct group has no " +
                "interface on this phone. Proceeding on the default route (will likely fail).")
        return false
    }

    /** Release the P2P network request and stop forcing HTTP through it. */
    private fun releaseP2pNetwork() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        p2pNetworkCallback?.let {
            try {
                cm?.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Log.w(TAG, "🌐 unregisterNetworkCallback: ${e.message}")
            }
        }
        p2pNetworkCallback = null
        albumDownloader.bindToNetwork(null)
    }

    // Probes common ports on the given Glass IP; if open port found, set server ip/port and proceed
    private fun testPortsAndProceed(glassIp: String) {
        Thread {
            // Put 80 first as logs showed it working for some devices
            val commonPorts = listOf(80, 8888, 8899, 8080, 9000, 5000)
            var openPort: Int? = null

            // Probe over the SAME network the download will use. A bare Socket() follows
            // the default route (home WiFi) and reported every port closed even though
            // the Glass server was up — that was the "Glass server failed to respond".
            val p2p = albumDownloader.currentBoundNetwork()
            for (port in commonPorts) {
                try {
                    val sock = p2p?.socketFactory?.createSocket() ?: Socket()
                    sock.use {
                        it.connect(InetSocketAddress(glassIp, port), 1500)
                    }
                    Log.e("PORT_TEST", "✅ OPEN PORT FOUND: $port (bound=${p2p != null})")
                    openPort = port
                    break
                } catch (e: Exception) {
                    Log.d("PORT_TEST", "❌ closed $port (bound=${p2p != null})")
                }
            }

            runOnUiThread {
                if (openPort == 80) {
                    // HTTP path
                    appendConnectionStep("Device connected")
                    appendConnectionStep("Preparing download")
                    updateConnectionProgress(55)
                    updateStatus("✅ Connected via HTTP (Port 80)")
                    isConnectedToGlass = true
                    try {
                        val m = wifiP2pHelper::class.java.getMethod("stopDiscovery")
                        m.invoke(wifiP2pHelper)
                    } catch (e: Exception) {
                        Log.w(TAG, "Error stopping scanner: ${e.message}")
                    }

                    mainScope.launch {
                        downloadViaHttp(glassIp)
                    }

                } else if (openPort != null) {
                    // Raw socket path
                    appendConnectionStep("Device connected")
                    appendConnectionStep("Preparing download")
                    updateConnectionProgress(55)
                    glassMediaTransfer.setServerIp(glassIp)
                    glassMediaTransfer.setServerPort(openPort)
                    isConnectedToGlass = true
                    updateStatus("✅ Glass socket ready (port $openPort)")

                    try {
                        val m = wifiP2pHelper::class.java.getMethod("stopDiscovery")
                        m.invoke(wifiP2pHelper)
                    } catch (e: Exception) {
                        Log.w(TAG, "Error stopping scanner: ${e.message}")
                    }

                    mainScope.launch {
                        delay(500)
                        fetchAndDownloadFromGlass()
                    }

                } else {
                    isConnectedToGlass = false
                    appendConnectionStep("Failed to reach Glass server")
                    closeConnectionProgressDialog("Glass server did not respond")
                    updateStatus("❌ Glass server failed to respond")
                }
            }
        }.start()
    }

    // HTTP download flow using AlbumDownloader (for port 80)
    private suspend fun downloadViaHttp(ip: String) {
        layoutProgress?.visibility = View.VISIBLE
        appendConnectionStep("Fetching media list")
        updateConnectionProgress(65)
        updateStatus("📋 Fetching HTTP file list...")

        // 1. List Fetch karo
        val files = try {
            withContext(Dispatchers.IO) { albumDownloader.fetchConfig(ip) }
        } catch (e: Exception) {
            Log.e(TAG, "HTTP fetchConfig failed: ${e.message}")
            layoutProgress?.visibility = View.GONE
            appendConnectionStep("Failed: ${e.message}")
            closeConnectionProgressDialog("HTTP fetch failed")
            updateStatus("⚠️ HTTP fetch failed: ${e.message}")
            return
        }

        if (files.isNotEmpty()) {
            val totalFiles = files.size
            appendConnectionStep("Downloading data ($totalFiles files)")
            updateConnectionProgress(72)
            updateStatus("📥 Found $totalFiles files. Checking local storage...")

            // 2. Progress Bar Setup (Fixed 0% issue)
            runOnUiThread {
                progressBar?.isIndeterminate = false
                progressBar?.max = totalFiles
                progressBar?.progress = 0
                tvProgress?.text = "0/$totalFiles"
            }

            // UI List Setup
            val galleryFiles = files.map { item ->
                GlassMediaTransfer.MediaFileInfo(
                    fileName = item.fileName,
                    fileType = if (item.type == 2) "video" else "photo",
                    fileSize = 0L,
                    timestamp = System.currentTimeMillis(),
                    localPath = null,
                    isDownloaded = false
                )
            }

            mediaFiles.clear()
            mediaFiles.addAll(galleryFiles)
            adapter?.notifyDataSetChanged()

            var downloadedCount = 0
            var skippedCount = 0
            val outputDir = glassMediaTransfer.getPhotosDirectory()

            // 3. Ek-ek file check aur download karo
            files.forEachIndexed { index, item ->
                val currentFileNum = index + 1
                val localFile = File(outputDir, item.fileName)

                // --- CHECK: Kya file pehle se hai? ---
                if (localFile.exists() && localFile.length() > 0) {
                    // Haan hai -> SKIP DOWNLOAD
                    skippedCount++
                    
                    // UI update (Dikhao ki downloaded hai)
                    mediaFiles.find { it.fileName == item.fileName }?.apply {
                        localPath = localFile.absolutePath
                        isDownloaded = true
                    }
                    
                    // Progress Bar update (Silent)
                    runOnUiThread {
                        progressBar?.progress = currentFileNum
                        tvProgress?.text = "$currentFileNum/$totalFiles"
                        val popupProgress = 72 + ((currentFileNum * 26) / totalFiles)
                        updateConnectionProgress(popupProgress)
                    }
                } else {
                    // Nahi hai -> DOWNLOAD KARO
                    runOnUiThread {
                        progressBar?.progress = currentFileNum
                        tvProgress?.text = "$currentFileNum/$totalFiles"
                        val popupProgress = 72 + ((currentFileNum * 26) / totalFiles)
                        updateConnectionProgress(popupProgress)
                        updateStatus("Downloading $currentFileNum/$totalFiles: ${item.fileName}")
                    }

                    val file = withContext(Dispatchers.IO) { 
                        albumDownloader.downloadFile(ip, item.fileName, outputDir) 
                    }
                    
                    if (file != null) {
                        downloadedCount++
                        mediaFiles.find { it.fileName == item.fileName }?.apply {
                            localPath = file.absolutePath
                            isDownloaded = true
                        }
                        // Thode thode der mein UI refresh karo
                        if (downloadedCount % 5 == 0) {
                            runOnUiThread { adapter?.notifyDataSetChanged() }
                        }
                    }
                }
            }
            
            // 4. Final Result Message
            runOnUiThread {
                adapter?.notifyDataSetChanged()
                layoutProgress?.visibility = View.GONE
                
                isDownloadComplete = true
                if (downloadedCount == 0 && skippedCount > 0) {
                    // Agar sab pehle se tha
                    updateStatus("✅ All files already downloaded!")
                    appendConnectionStep("Download completed")
                    updateConnectionProgress(100)
                    closeConnectionProgressDialog("Connected. Files already downloaded")
                    Toast.makeText(this@GlassMediaGalleryActivity, "All files already exist", Toast.LENGTH_SHORT).show()
                } else {
                    // Agar kuch naya download hua
                    updateStatus("✅ Complete: $downloadedCount new, $skippedCount skipped")
                    appendConnectionStep("Download completed")
                    updateConnectionProgress(100)
                    closeConnectionProgressDialog("Download complete: $downloadedCount new files")
                    Toast.makeText(this@GlassMediaGalleryActivity, "Downloaded $downloadedCount new files", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            appendConnectionStep("No files found")
            updateConnectionProgress(100)
            closeConnectionProgressDialog("Connected. No files found")
            updateStatus("⚠️ No files found via HTTP")
            layoutProgress?.visibility = View.GONE
        }
    }
    
    // Transfer Listener callbacks
    
    override fun onHotspotTriggered() {
        runOnUiThread {
            appendConnectionStep("Device switched to transfer mode")
            updateConnectionProgress(15)
            updateStatus("Glass Hotspot triggered. Connect to Glass WiFi...")
        }
    }
    
    override fun onConnected(ip: String) {
        runOnUiThread {
            appendConnectionStep("Device connected at $ip")
            updateConnectionProgress(50)
            updateStatus("Connected to Glass at $ip")
        }
    }
    
    override fun onDisconnected() {
        runOnUiThread {
            updateStatus("Disconnected from Glass")
        }
    }
    
    override fun onMediaListReceived(files: List<GlassMediaTransfer.MediaFileInfo>) {
        runOnUiThread {
            mediaFiles.clear()
            mediaFiles.addAll(files.sortedByDescending { it.timestamp })
            adapter?.notifyDataSetChanged()
            updateStatus("${files.size} files found on Glass")
        }
    }
    
    override fun onDownloadProgress(fileName: String, progress: Int) {
        runOnUiThread {
            progressBar?.progress = progress
            tvProgress?.text = "$progress%"
            // Socket downloads report per-file percentage; map to final stage band.
            val popupProgress = 72 + ((progress.coerceIn(0, 100) * 26) / 100)
            updateConnectionProgress(popupProgress)
        }
    }
    
    override fun onDownloadComplete(fileName: String, localPath: String) {
        runOnUiThread {
            // Update the file info
            mediaFiles.find { it.fileName == fileName }?.apply {
                this.localPath = localPath
                this.isDownloaded = true
            }
            adapter?.notifyDataSetChanged()
            updateStatus("Downloaded: $fileName")

            if (mediaFiles.isNotEmpty() && mediaFiles.all { it.isDownloaded }) {
                appendConnectionStep("Download completed")
                updateConnectionProgress(100)
                closeConnectionProgressDialog("Download complete")
            }
        }
    }
    
    override fun onDownloadError(fileName: String, error: String) {
        runOnUiThread {
            updateStatus("Error: $fileName - $error")
            Toast.makeText(this, "Download failed: $fileName", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onError(error: String) {
        runOnUiThread {
            appendConnectionStep("Failed: $error")
            closeConnectionProgressDialog("Connection failed")
            updateStatus("Error: $error")
            Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            btnConnect?.isEnabled = true
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        mainScope.cancel()
        // Give the P2P network back before tearing down, so the phone returns to its
        // normal route and we don't leak the network request.
        releaseP2pNetwork()
        glassMediaTransfer.disconnect()
        wifiP2pHelper.cleanup()
        
        // Remove BLE notification listener
        try {
            LargeDataHandler.getInstance().removeOutDeviceListener(2)
            Log.d(TAG, "📡 BLE notification listener removed")
        } catch (e: Exception) {
            Log.w(TAG, "Error removing BLE listener: ${e.message}")
        }
    }
    
    // ===============================
    // WifiP2pHelper.WifiP2pCallback implementations
    // ===============================
    
    override fun onP2pStateChanged(enabled: Boolean) {
        runOnUiThread {
            if (enabled) {
                Log.i(TAG, "✅ WiFi P2P enabled")
            } else {
                Log.w(TAG, "⚠️ WiFi P2P disabled")
                updateStatus("⚠️ WiFi Direct is disabled")
            }
        }
    }
    
    override fun onPeersDiscovered(peers: List<WifiP2pDevice>) {
        runOnUiThread {
            discoveredDevices.clear()
            discoveredDevices.addAll(peers)
            
            if (peers.isNotEmpty()) {
                // WifiP2pHelper already auto-connects to a Glass device.
                appendConnectionStep("Device found. Connecting...")
                updateConnectionProgress(35)
                updateStatus("Found ${peers.size} devices. Auto-connecting...")
                discoveryRetryJob?.cancel()
                discoveryRetryJob = null
            } else {
                appendConnectionStep("No device found. Retrying...")
                updateConnectionProgress(20)
                updateStatus("No devices found. Retrying...")
                scheduleDiscoveryRetry(3000)
            }
        }
    }
    
    override fun onConnecting(deviceName: String) {
        runOnUiThread {
            appendConnectionStep("Connecting to $deviceName")
            updateConnectionProgress(45)
            updateStatus("🔗 Connecting to Glass device...")
        }
    }

    override fun onGlassConnected(glassIp: String) {
        runOnUiThread {
            discoveryRetryJob?.cancel()
            discoveryRetryJob = null
            btnConnect?.text = "⏳ Checking server..."
            btnConnect?.isEnabled = false
            appendConnectionStep("Device connected")
            appendConnectionStep("Verifying transfer server")
            updateConnectionProgress(50)
            updateStatus("📡 Glass IP detected: $glassIp")

            // Pin traffic to the Wi-Fi Direct network BEFORE probing. Probing first
            // tested the home-WiFi route, which can never reach the Glass subnet.
            bindToP2pNetworkThen {
                testPortsAndProceed(glassIp)
            }
        }
    }
    
    override fun onP2pDisconnected() {
        runOnUiThread {
            isConnectedToGlass = false
            if (isDownloadComplete) {
                // Normal teardown after a successful download — don't restart
                updateStatus("✅ Download complete. P2P disconnected.")
                btnConnect?.text = "🔗 Connect to Glass"
                btnConnect?.isEnabled = true
                return@runOnUiThread
            }
            closeConnectionProgressDialog("Disconnected from Glass")
            updateStatus("P2P Disconnected from Glass")
            btnConnect?.text = "🔗 Connect to Glass"
            btnConnect?.isEnabled = true
        }
    }
    
    override fun onP2pError(message: String) {
        // --- CHANGE: If we're already connected (or downloading), ignore transient scanner errors ---
        if (isConnectedToGlass) {
            Log.w(TAG, "Ignored P2P Error because already connected: $message")
            return
        }

        runOnUiThread {
            appendConnectionStep("P2P error: $message")
            updateStatus("P2P Error: $message. Retrying...")
            btnConnect?.isEnabled = true
            scheduleDiscoveryRetry(1500)
        }
    }
    
    /**
     * Debounced discovery retry to avoid rapid reconnect loops.
     */
    private fun scheduleDiscoveryRetry(delayMs: Long) {
        if (isConnectedToGlass || isDownloadComplete) return
        if (discoveryRetryJob?.isActive == true) return

        discoveryRetryJob = mainScope.launch {
            delay(delayMs)
            if (!isConnectedToGlass) {
                updateStatus("Retrying discovery...")
                wifiP2pHelper.startDiscovery()
            }
        }
    }
    
    /**
     * Media Adapter for RecyclerView
     */
    class MediaAdapter(
        private val context: Context,
        private val items: List<GlassMediaTransfer.MediaFileInfo>,
        private val onTap: (GlassMediaTransfer.MediaFileInfo) -> Unit,
        private val onLongPress: (GlassMediaTransfer.MediaFileInfo) -> Unit,
        private val isSelectionMode: () -> Boolean,
        private val isSelected: (GlassMediaTransfer.MediaFileInfo) -> Boolean
    ) : RecyclerView.Adapter<MediaAdapter.ViewHolder>() {
        
        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val cardRoot: MaterialCardView = view.findViewById(R.id.cardRoot)
            val imageView: ImageView = view.findViewById(R.id.imageView)
            val tvFileName: TextView = view.findViewById(R.id.tvFileName)
            val videoIcon: ImageView = view.findViewById(R.id.videoIcon)
            val videoIconContainer: FrameLayout = view.findViewById(R.id.videoIconContainer)
        }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(context).inflate(R.layout.item_gallery_photo, parent, false)
            return ViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val selected = isSelected(item)

            holder.tvFileName.text = item.fileName
            holder.cardRoot.strokeWidth = if (selected) dpToPx(2) else dpToPx(1)
            holder.cardRoot.strokeColor = if (selected) {
                Color.parseColor("#FFFA5A2E")
            } else {
                Color.parseColor("#FF202734")
            }

            // Video icon logic
            holder.videoIconContainer.visibility = if (item.fileType == "video") View.VISIBLE else View.GONE

            // Clear previous image to free memory immediately
            holder.imageView.setImageBitmap(null)

            val localPath = item.localPath
            if (localPath != null && File(localPath).exists()) {
                try {
                    if (item.fileType == "video") {
                        // Video Thumbnail (Already optimized by Android)
                        val bitmap = android.media.ThumbnailUtils.createVideoThumbnail(
                            localPath,
                            android.provider.MediaStore.Images.Thumbnails.MINI_KIND
                        )
                        if (bitmap != null) {
                            holder.imageView.setImageBitmap(bitmap)
                        } else {
                            holder.imageView.setImageResource(android.R.drawable.ic_menu_gallery)
                        }
                    } else {
                        // --- PHOTO MEMORY FIX: Load smaller version ---
                        val options = BitmapFactory.Options()
                        options.inJustDecodeBounds = false
                        options.inSampleSize = 8 // Load 1/8th size (Small Thumbnail)

                        val bitmap = BitmapFactory.decodeFile(localPath, options)

                        if (bitmap != null) {
                            holder.imageView.setImageBitmap(bitmap)
                        } else {
                            holder.imageView.setImageResource(android.R.drawable.ic_menu_gallery)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("GalleryAdapter", "Error loading thumbnail: ${e.message}")
                    holder.imageView.setImageResource(android.R.drawable.ic_menu_gallery)
                }
            } else {
                holder.imageView.setImageResource(android.R.drawable.ic_menu_gallery)
            }

            holder.itemView.setOnClickListener { 
                onTap(item)
            }

            holder.itemView.setOnLongClickListener {
                onLongPress(item)
                true
            }
        }

        private fun dpToPx(dp: Int): Int {
            return (dp * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        }
        
        override fun getItemCount() = items.size
    }
}
