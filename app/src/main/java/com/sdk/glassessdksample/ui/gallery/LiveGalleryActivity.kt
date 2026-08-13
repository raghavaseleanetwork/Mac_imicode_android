package com.sdk.glassessdksample.ui.gallery

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.net.wifi.p2p.WifiP2pDevice
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sdk.glassessdksample.MainActivity
import com.sdk.glassessdksample.ProfileActivity
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyListener
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyRsp
import com.sdk.glassessdksample.R
import com.sdk.glassessdksample.ui.ChatActivity
import com.sdk.glassessdksample.ui.GeminiAIClient
import com.sdk.glassessdksample.ui.wifi.AlbumDownloader
import com.sdk.glassessdksample.ui.wifi.WifiP2pHelper
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Live Gallery shows every photo captured from the glasses.
 *
 * WiFi sync flow (same as VisionChatActivity):
 *  1. Try cached IP from VisionChat prefs  (instant if already used VisionChat)
 *  2. Register BLE type-8 IP listener      (glass sends its IP via BLE)
 *  3. WiFi P2P / Direct discovery          (WifiP2pHelper)
 *  4. AlbumDownloader parallel IP scan     (fallback subnet scan)
 *  5. Manual IP dialog                     (last resort for user to type IP)
 *
 * BLE photos are still saved automatically via LiveGalleryManager broadcasts.
 */
class LiveGalleryActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LiveGalleryActivity"
        private const val PREFS_VISION = "vision_chat_prefs"
        private const val KEY_GLASS_IP  = "cached_glass_ip"    // same key as VisionChatActivity

        fun launch(context: Context) {
            context.startActivity(Intent(context, LiveGalleryActivity::class.java))
        }
    }

    // Views
    private lateinit var recyclerView: RecyclerView
    private lateinit var tvPhotoCount: TextView
    private lateinit var tvStatus: TextView
    private lateinit var progressBar: android.widget.ProgressBar
    private lateinit var tvEmpty: View
    private lateinit var tvEmptyTitle: TextView
    private lateinit var tvEmptySubtitle: TextView
    private lateinit var btnSync: android.widget.ImageView
    private lateinit var btnAnalyzeAI: android.widget.ImageView
    private lateinit var chipAll: TextView
    private lateinit var chipImage: TextView
    private lateinit var chipVideo: TextView
    private lateinit var chipRecording: TextView
    private lateinit var chipCollection: TextView

    private val photos = mutableListOf<File>()
    private val visiblePhotos = mutableListOf<File>()
    private val galleryRows = mutableListOf<GalleryRow>()
    private var adapter: GalleryAdapter? = null
    private var currentFilter = GalleryFilter.ALL

    private val uiHandler = Handler(Looper.getMainLooper())
    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private enum class GalleryFilter {
        ALL,
        IMAGE,
        VIDEO,
        RECORDING,
        COLLECTION
    }

    private sealed class GalleryRow {
        data class SectionHeader(val title: String) : GalleryRow()
        data class PhotoTile(val file: File) : GalleryRow()
    }

    // AI Analysis
    private lateinit var geminiClient: GeminiAIClient

    // WiFi
    private lateinit var albumDownloader: AlbumDownloader
    private lateinit var wifiP2pHelper: WifiP2pHelper
    private var isSyncing = false
    private var cachedGlassIp: String? = null
    private var pendingGlassIp: String? = null

    // BLE IP listener (type-8 notification, same as VisionChatActivity)
    private val bleIpListener = object : GlassesDeviceNotifyListener() {
        override fun parseData(cmdType: Int, rsp: GlassesDeviceNotifyRsp?) {
            try {
                val data = extractNotifyBytes(rsp) ?: return
                if (data.size < 11) return
                val notifyType = data[6].toInt() and 0xFF
                if (notifyType == 8) {
                    val ip = "${data[7].toInt() and 0xFF}.${data[8].toInt() and 0xFF}" +
                             ".${data[9].toInt() and 0xFF}.${data[10].toInt() and 0xFF}"
                    Log.i(TAG, "BLE Glass IP received: $ip")
                    runOnUiThread {
                        if (isSyncing && pendingGlassIp == null) {
                            pendingGlassIp = ip
                            startDownloadWithIp(ip)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "BLE IP parse error: ${e.message}")
            }
        }
    }

    private fun extractNotifyBytes(rsp: Any?): ByteArray? {
        if (rsp == null) return null
        for (fieldName in listOf("loadData", "data", "bytes")) {
            try {
                val f = rsp.javaClass.getDeclaredField(fieldName)
                f.isAccessible = true
                val v = f.get(rsp)
                if (v is ByteArray) return v
            } catch (_: Exception) {}
        }
        return null
    }

    // WiFi P2P callback (same as VisionChatActivity)
    private val p2pCallback = object : WifiP2pHelper.WifiP2pCallback {
        override fun onP2pStateChanged(enabled: Boolean) {
            Log.d(TAG, "P2P state: $enabled")
        }
        override fun onPeersDiscovered(peers: List<WifiP2pDevice>) {
            if (!isSyncing || peers.isEmpty()) return
            val glass = peers.find { d ->
                val n = d.deviceName.lowercase()
                n.contains("m01") || n.contains("glass") || n.contains("heycyan") ||
                n.contains("cyan") || n.contains("cy01") || n.contains("bond")
            } ?: peers[0]
            Log.i(TAG, "Auto-connecting via P2P to: ${glass.deviceName}")
            wifiP2pHelper.connectToDevice(glass)
        }
        override fun onGlassConnected(glassIp: String) {
            Log.i(TAG, "P2P connected at: $glassIp")
            runOnUiThread {
                if (pendingGlassIp == null) {
                    pendingGlassIp = glassIp
                    startDownloadWithIp(glassIp)
                }
            }
        }
        override fun onP2pDisconnected() { Log.w(TAG, "P2P disconnected") }
        override fun onP2pError(message: String) { Log.w(TAG, "P2P error: $message") }
    }

    // LocalBroadcast for BLE photo auto-save notifications
    private val galleryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val msg = intent?.getStringExtra(LiveGalleryManager.EXTRA_STATUS_MSG) ?: return
            when (intent.action) {
                LiveGalleryManager.ACTION_PHOTO_SAVING -> {
                    tvStatus.text = msg
                    progressBar.visibility = View.VISIBLE
                }
                LiveGalleryManager.ACTION_PHOTO_SAVED -> {
                    refreshGallery()
                    tvStatus.text = msg
                    // Auto-analyze any new BLE photo
                    val filePath = intent.getStringExtra(LiveGalleryManager.EXTRA_FILE_PATH)
                    if (filePath != null) {
                        val photoFile = File(filePath)
                        activityScope.launch { autoAnalyzePhoto(photoFile) }
                    }
                    uiHandler.postDelayed({
                        if (!isSyncing) {
                            progressBar.visibility = View.GONE
                            updateHeaderStatus()
                        }
                    }, 2500L)
                }
            }
        }
    }

    // Lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live_gallery)

        albumDownloader = AlbumDownloader(this)
        wifiP2pHelper   = WifiP2pHelper(this)
        geminiClient    = GeminiAIClient(this)

        recyclerView = findViewById(R.id.rvGallery)
        tvPhotoCount = findViewById(R.id.tvPhotoCount)
        tvStatus     = findViewById(R.id.tvGalleryStatus)
        progressBar  = findViewById(R.id.progressBarGallery)
        tvEmpty      = findViewById(R.id.layoutEmpty)
        tvEmptyTitle = findViewById(R.id.tvEmptyTitle)
        tvEmptySubtitle = findViewById(R.id.tvEmptySubtitle)
        btnSync      = findViewById(R.id.btnSyncGlasses)
        chipAll = findViewById(R.id.chipAll)
        chipImage = findViewById(R.id.chipImage)
        chipVideo = findViewById(R.id.chipVideo)
        chipRecording = findViewById(R.id.chipRecording)
        chipCollection = findViewById(R.id.chipCollection)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnClearAll).setOnClickListener { confirmClearAll() }
        btnSync.setOnClickListener { syncFromGlasses(userTriggered = true) }

        findViewById<View>(R.id.navHome)?.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
        findViewById<View>(R.id.navAi)?.setOnClickListener {
            startActivity(Intent(this, ChatActivity::class.java))
        }
        findViewById<View>(R.id.navMine)?.setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }

        // Long-press on the photo count label → open hidden descriptions vault
        tvPhotoCount.setOnLongClickListener {
            ImageDescriptionVaultActivity.launch(this)
            true
        }

        setupFilterChips()

        // Vault button → open descriptions vault
        try {
            findViewById<ImageView>(R.id.btnVault).setOnClickListener {
                ImageDescriptionVaultActivity.launch(this)
            }
        } catch (e: Exception) {
            Log.w(TAG, "btnVault not found in layout")
        }

        // Add AI Analysis button (reuse same row as sync button)
        try {
            btnAnalyzeAI = findViewById(R.id.btnAnalyzeAI)
            btnAnalyzeAI.setOnClickListener { analyzeAllImagesWithAI() }
        } catch (e: Exception) {
            Log.w(TAG, "btnAnalyzeAI not found in layout, skipping AI analysis button")
        }

        adapter = GalleryAdapter(
            galleryRows,
            onTap = { file ->
                val allPaths = ArrayList(visiblePhotos.map { it.absolutePath })
                val index = visiblePhotos.indexOfFirst { it.absolutePath == file.absolutePath }
                    .coerceAtLeast(0)
                ImageViewerActivity.open(this, file.absolutePath, file.name, allPaths, index)
            },
            onLongPress = { file ->
                deletePhoto(file)
            }
        )

        val gridLayoutManager = GridLayoutManager(this, 4).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    return when (galleryRows.getOrNull(position)) {
                        is GalleryRow.SectionHeader -> 4
                        is GalleryRow.PhotoTile -> 1
                        null -> 1
                    }
                }
            }
        }

        recyclerView.layoutManager = gridLayoutManager
        recyclerView.adapter = adapter

        // Empty-state sync button
        try {
            findViewById<View>(R.id.btnSyncGlassesEmpty).setOnClickListener {
                syncFromGlasses(userTriggered = true)
            }
        } catch (e: Exception) { Log.w(TAG, "btnSyncGlassesEmpty not found") }

        val filter = IntentFilter().apply {
            addAction(LiveGalleryManager.ACTION_PHOTO_SAVING)
            addAction(LiveGalleryManager.ACTION_PHOTO_SAVED)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(galleryReceiver, filter)

        wifiP2pHelper.initialize()
        wifiP2pHelper.registerReceiver()
        wifiP2pHelper.setCallback(p2pCallback)

        // Widen the BLE pipe before any glasses media moves. See BleSpeedTuner.
        com.sdk.glassessdksample.ui.BleSpeedTuner.tune(this, "LiveGallery")

        try {
            LargeDataHandler.getInstance().addOutDeviceListener(3, bleIpListener)
            Log.i(TAG, "BLE IP listener registered")
        } catch (e: Exception) {
            Log.w(TAG, "BLE IP listener register failed: ${e.message}")
        }

        cachedGlassIp = getSharedPreferences(PREFS_VISION, Context.MODE_PRIVATE)
            .getString(KEY_GLASS_IP, null)
        Log.i(TAG, "Cached glass IP from prefs: $cachedGlassIp")

        // Import any .desc.txt sidecar files into the central vault (one-time migration)
        activityScope.launch(Dispatchers.IO) {
            ImageDescriptionStore.importLegacySidecarFiles(this@LiveGalleryActivity)
        }

        syncFromGlasses(userTriggered = false)
    }

    override fun onResume() {
        super.onResume()
        refreshGallery()
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
        try { wifiP2pHelper.unregisterReceiver() } catch (_: Exception) {}
        try { LargeDataHandler.getInstance().removeOutDeviceListener(3) } catch (_: Exception) {}
        LocalBroadcastManager.getInstance(this).unregisterReceiver(galleryReceiver)
        uiHandler.removeCallbacksAndMessages(null)
    }

    // WiFi Sync

    private fun syncFromGlasses(userTriggered: Boolean = false) {
        if (isSyncing) {
            if (userTriggered) Toast.makeText(this, "Sync already in progress...", Toast.LENGTH_SHORT).show()
            return
        }
        isSyncing = true
        pendingGlassIp = null
        startSyncAnimation()
        setStatus("Searching for glasses...", showProgress = true)

        activityScope.launch {
            // Trigger glass WiFi (same as VisionChat - sends 0x0f reset then transfer mode)
            setStatus("Waking glass WiFi...", showProgress = true)
            triggerGlassP2P()
            delay(800) // give glass time to start WiFi hotspot

            // Path 1: Cached IP
            val cached = cachedGlassIp
            if (cached != null) {
                setStatus("Testing cached connection ($cached)...", showProgress = true)
                val reachable = withContext(Dispatchers.IO) { testIp(cached) }
                if (reachable) {
                    Log.i(TAG, "Cached IP reachable: $cached")
                    startDownloadWithIp(cached)
                    return@launch
                } else {
                    Log.w(TAG, "Cached IP $cached unreachable, clearing")
                    cachedGlassIp = null
                    getSharedPreferences(PREFS_VISION, Context.MODE_PRIVATE).edit().remove(KEY_GLASS_IP).apply()
                }
            }

            // Path 2+3: BLE + WiFi P2P - wait up to 12 seconds
            setStatus("Searching via BLE + WiFi P2P...", showProgress = true)
            try { wifiP2pHelper.startDiscovery() } catch (e: Exception) {
                Log.w(TAG, "P2P discovery start failed: ${e.message}")
            }

            var waited = 0
            while (waited < 12000 && pendingGlassIp == null && isSyncing) {
                delay(500)
                waited += 500
                if (waited % 3000 == 0) setStatus("Still searching... (${waited/1000}s)", showProgress = true)
            }
            if (pendingGlassIp != null) return@launch

            // Path 4: AlbumDownloader scan
            setStatus("Scanning WiFi for glasses...", showProgress = true)
            val scanned = withContext(Dispatchers.IO) { albumDownloader.discoverGlassesIP() }
            if (scanned != null) {
                Log.i(TAG, "Found via scan: $scanned")
                startDownloadWithIp(scanned)
                return@launch
            }

            // Path 5: Manual entry
            Log.w(TAG, "All auto-discovery failed")
            withContext(Dispatchers.Main) {
                finishSync()
                showManualIpDialog()
            }
        }
    }

    /**
     * Trigger WiFi P2P on glasses via BLE (mirrors VisionChatActivity.triggerGlassP2P)
     * Sends two commands: reset P2P (0x0f=15) then start transfer mode (0x04=4)
     * This makes the glass turn on its WiFi hotspot and broadcast its IP via BLE type-8
     */
    private fun triggerGlassP2P() {
        Log.i(TAG, "📡 Triggering Glass P2P via BLE...")
        try {
            // Reset P2P state first (0x0f)
            val resetCommand = byteArrayOf(2, 1, 15)
            LargeDataHandler.getInstance().glassesControl(resetCommand) { code, _ ->
                Log.i(TAG, "🔄 P2P Reset: code=$code")
            }
            // Start transfer mode (triggers P2P / WiFi hotspot)
            val startTransferCommand = byteArrayOf(2, 1, 4)
            LargeDataHandler.getInstance().glassesControl(startTransferCommand) { code, _ ->
                Log.i(TAG, "📤 Transfer mode started: code=$code")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error triggering P2P on glass: ${e.message}")
        }
    }

    /**
     * Auto-analyze a single photo silently in the background
     * Skips photos that already have a description
     */
    private suspend fun autoAnalyzePhoto(photoFile: File) {
        if (!photoFile.exists()) return
        if (getImageDescription(photoFile).isNotEmpty()) return // already analyzed
        try {
            Log.d(TAG, "🤖 Auto-analyzing: ${photoFile.name}")
            val description = processImageWithAI(photoFile)
            if (description != null) {
                saveImageDescription(photoFile, description)
                withContext(Dispatchers.Main) {
                    adapter?.notifyDataSetChanged()
                    Log.i(TAG, "✅ AI note saved for ${photoFile.name}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Auto-analysis failed for ${photoFile.name}: ${e.message}")
        }
    }

    /**
     * Analyze all photos with AI (for photos not yet analyzed) - triggered by AI button
     */
    private fun analyzeAllImagesWithAI() {
        if (photos.isEmpty()) {
            Toast.makeText(this, "No photos to analyze", Toast.LENGTH_SHORT).show()
            return
        }
        val unanalyzed = photos.filter { getImageDescription(it).isEmpty() }
        if (unanalyzed.isEmpty()) {
            Toast.makeText(this, "All photos already have AI notes", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Analyzing ${unanalyzed.size} photos...", Toast.LENGTH_SHORT).show()
        activityScope.launch {
            analyzePhotosWithGemini()
        }
    }

    /**
     * Process all photos: send to Gemini Vision, save descriptions
     * AI gets access to ALL image + description pairs
     */
    private suspend fun analyzePhotosWithGemini() {
        setStatus("🤖 Analyzing ${photos.size} photos with AI...", showProgress = true)
        val toAnalyze = photos.filter { getImageDescription(it).isEmpty() }
        var analyzed = 0
        val failed = mutableListOf<String>()

        for ((idx, photo) in toAnalyze.withIndex()) {
            try {
                setStatus("🤖 Analyzing photo ${idx + 1}/${toAnalyze.size}...", showProgress = true)
                val description = processImageWithAI(photo)
                if (description != null) {
                    saveImageDescription(photo, description)
                    analyzed++
                    withContext(Dispatchers.Main) { adapter?.notifyDataSetChanged() }
                    Log.i(TAG, "✅ Analyzed: ${photo.name}")
                } else {
                    failed.add(photo.name)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error analyzing ${photo.name}: ${e.message}")
                failed.add(photo.name)
            }
            delay(100)
        }

        withContext(Dispatchers.Main) {
            val msg = if (failed.isEmpty()) {
                "✅ Analyzed all ${analyzed} photos!\nAI now has context for each image."
            } else {
                "✅ Analyzed ${analyzed}/${photos.size} photos\n❌ Failed: ${failed.joinToString(", ")}"
            }
            setStatus(msg, showProgress = false)
            Toast.makeText(this@LiveGalleryActivity, msg, Toast.LENGTH_LONG).show()
            adapter?.notifyDataSetChanged()
        }
    }

    /**
     * Send a single photo to Gemini Vision API for analysis
     * Returns AI-generated description or null on failure
     */
    private suspend fun processImageWithAI(photoFile: File): String? {
        return try {
            val imageBytes = photoFile.readBytes()
            val prompt = """Describe EXACTLY what you see in this photo in 1-2 sentences. 
                |Be specific about objects, colors, text, people, and context. 
                |Focus on what's important and visible.""".trimMargin()
            geminiClient.analyzeImage(imageBytes, prompt)
        } catch (e: Exception) {
            Log.e(TAG, "Gemini analysis failed for ${photoFile.name}: ${e.message}")
            null
        }
    }

    /**
     * Save AI description as .desc.txt file next to image
     * When AI needs context, these descriptions are available alongside images
     */
    private fun saveImageDescription(photoFile: File, description: String) {
        try {
            // Keep legacy .desc.txt sidecar for backwards compat
            val descFile = File(photoFile.parent, "${photoFile.nameWithoutExtension}.desc.txt")
            descFile.writeText(description)
            Log.d(TAG, "Saved description: ${descFile.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save description: ${e.message}")
        }
        // Also persist to central hidden vault (used by AI + VaultActivity)
        try {
            ImageDescriptionStore.save(this, photoFile.name, description)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save to vault: ${e.message}")
        }
    }

    /**
     * Load AI description for a photo if it exists
     * Returns description or empty string if not found
     */
    private fun getImageDescription(photoFile: File): String {
        return try {
            val descFile = File(photoFile.parent, "${photoFile.nameWithoutExtension}.desc.txt")
            if (descFile.exists()) descFile.readText() else ""
        } catch (e: Exception) {
            Log.w(TAG, "Could not load description for ${photoFile.name}")
            ""
        }
    }

    private fun startDownloadWithIp(ip: String) {
        if (!isSyncing) return
        pendingGlassIp = ip
        cachedGlassIp  = ip
        getSharedPreferences(PREFS_VISION, Context.MODE_PRIVATE).edit().putString(KEY_GLASS_IP, ip).apply()
        activityScope.launch { runDownload(ip) }
    }

    private suspend fun runDownload(ip: String) {
        try {
            setStatus("Fetching photo list from $ip...", showProgress = true)
            val mediaItems = withContext(Dispatchers.IO) { albumDownloader.fetchConfig(ip) }

            val imageItems = mediaItems.filter {
                it.fileName.endsWith(".jpg", true) ||
                it.fileName.endsWith(".jpeg", true) ||
                it.fileName.endsWith(".png", true)
            }.sortedByDescending { it.fileName }

            if (imageItems.isEmpty()) {
                setStatus("No photos found on glasses", showProgress = false)
                finishSync()
                return
            }

            val toDownload = imageItems.filter { !LiveGalleryManager.isAlreadyDownloaded(this@LiveGalleryActivity, it.fileName) }

            if (toDownload.isEmpty()) {
                setStatus("All ${imageItems.size} photos already synced", showProgress = false)
                finishSync()
                refreshGallery()
                return
            }

            val outputDir = File(filesDir, "live_gallery_temp")
            if (!outputDir.exists()) outputDir.mkdirs()

            var downloaded = 0
            var failed = 0

            for ((idx, item) in toDownload.withIndex()) {
                setStatus("Downloading ${idx+1}/${toDownload.size}: ${item.fileName}", showProgress = true)
                try {
                    val file = withContext(Dispatchers.IO) {
                        albumDownloader.downloadFile(ip, item.fileName, outputDir)
                    }
                    if (file != null && file.exists() && file.length() > 0) {
                        val savedPhoto = LiveGalleryManager.savePhoto(this@LiveGalleryActivity, file.readBytes(), "WiFi")
                        LiveGalleryManager.markFileDownloaded(this@LiveGalleryActivity, item.fileName)
                        file.delete()
                        downloaded++
                        refreshGallery()
                        // Auto-analyze immediately after saving
                        if (savedPhoto != null) autoAnalyzePhoto(savedPhoto)
                    } else { failed++ }
                } catch (e: Exception) {
                    failed++
                    Log.e(TAG, "Download error for ${item.fileName}: ${e.message}")
                }
            }

            val msg = "✅ Synced $downloaded photo${if (downloaded != 1) "s" else ""}" +
                      if (failed > 0) " ($failed failed)" else ""
            setStatus(msg, showProgress = false)
            refreshGallery()
            finishSync()

        } catch (e: Exception) {
            Log.e(TAG, "Download error: ${e.message}", e)
            setStatus("Download failed: ${e.message}", showProgress = false)
            finishSync()
        }
    }

    // Manual IP Dialog

    private fun showManualIpDialog() {
        val editText = EditText(this).apply {
            hint = "e.g. 192.168.49.1"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setPadding(40, 20, 40, 20)
            setText(cachedGlassIp ?: "")
        }
        AlertDialog.Builder(this)
            .setTitle("Connect to Glasses")
            .setMessage("Could not find glasses automatically.\n\nMake sure your phone WiFi is connected to the glasses hotspot (e.g. 'HEYCYAN-...' or 'M01-...').\n\nEnter glasses IP manually:")
            .setView(editText)
            .setPositiveButton("Connect") { _, _ ->
                val ip = editText.text.toString().trim()
                if (ip.isNotEmpty()) {
                    isSyncing = true
                    pendingGlassIp = ip
                    startSyncAnimation()
                    activityScope.launch { runDownload(ip) }
                } else {
                    setStatus("No IP entered", showProgress = false)
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                setStatus("Tap sync button. Make sure WiFi is on glasses hotspot.", showProgress = false)
            }
            .show()
    }

    // Helpers

    private fun testIp(ip: String): Boolean {
        return try {
            val sock = java.net.Socket()
            sock.connect(java.net.InetSocketAddress(ip, 80), 2000)
            sock.close()
            true
        } catch (_: Exception) { false }
    }

    private fun startSyncAnimation() {
        runOnUiThread {
            btnSync.isEnabled = false
            btnSync.alpha = 0.4f
            val anim = android.view.animation.RotateAnimation(
                0f, 360f,
                android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
                android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f
            ).apply {
                duration = 1000
                repeatCount = android.view.animation.Animation.INFINITE
                interpolator = android.view.animation.LinearInterpolator()
            }
            btnSync.startAnimation(anim)
        }
    }

    private fun finishSync() {
        isSyncing = false
        runOnUiThread {
            btnSync.clearAnimation()
            btnSync.isEnabled = true
            btnSync.alpha = 1.0f
        }
    }

    private fun setStatus(text: String, showProgress: Boolean) {
        runOnUiThread {
            tvStatus.text = text
            progressBar.visibility = if (showProgress) View.VISIBLE else View.GONE
        }
    }

    // Gallery data

    private fun refreshGallery() {
        runOnUiThread {
            val fresh = LiveGalleryManager.getAllPhotos(this)
            photos.clear()
            photos.addAll(fresh)
            applyFilterAndRender()
        }
    }

    private fun setupFilterChips() {
        chipAll.setOnClickListener { selectFilter(GalleryFilter.ALL) }
        chipImage.setOnClickListener { selectFilter(GalleryFilter.IMAGE) }
        chipVideo.setOnClickListener { selectFilter(GalleryFilter.VIDEO) }
        chipRecording.setOnClickListener { selectFilter(GalleryFilter.RECORDING) }
        chipCollection.setOnClickListener { selectFilter(GalleryFilter.COLLECTION) }
        updateFilterChipStyles()
    }

    private fun selectFilter(filter: GalleryFilter) {
        if (currentFilter == filter) return
        currentFilter = filter
        updateFilterChipStyles()
        applyFilterAndRender()
    }

    private fun applyFilterAndRender() {
        visiblePhotos.clear()
        visiblePhotos.addAll(filterPhotosByCurrentChip(photos))

        galleryRows.clear()
        galleryRows.addAll(buildSectionedRows(visiblePhotos))

        adapter?.notifyDataSetChanged()
        updateHeader()
    }

    private fun filterPhotosByCurrentChip(source: List<File>): List<File> {
        return when (currentFilter) {
            GalleryFilter.ALL,
            GalleryFilter.IMAGE,
            GalleryFilter.COLLECTION -> source

            GalleryFilter.VIDEO,
            GalleryFilter.RECORDING -> emptyList()
        }
    }

    private fun buildSectionedRows(source: List<File>): List<GalleryRow> {
        if (source.isEmpty()) return emptyList()

        val grouped = linkedMapOf<String, MutableList<File>>()
        source.sortedByDescending { it.lastModified() }.forEach { file ->
            val label = sectionLabelFor(file.lastModified())
            grouped.getOrPut(label) { mutableListOf() }.add(file)
        }

        val result = mutableListOf<GalleryRow>()
        grouped.forEach { (sectionTitle, files) ->
            result.add(GalleryRow.SectionHeader(sectionTitle))
            files.forEach { result.add(GalleryRow.PhotoTile(it)) }
        }
        return result
    }

    private fun sectionLabelFor(timeMillis: Long): String {
        val now = Calendar.getInstance()
        val item = Calendar.getInstance().apply { timeInMillis = timeMillis }
        if (isSameDate(now, item)) return "Today"

        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        if (isSameDate(yesterday, item)) return "Yesterday"

        return SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(timeMillis))
    }

    private fun isSameDate(first: Calendar, second: Calendar): Boolean {
        return first.get(Calendar.YEAR) == second.get(Calendar.YEAR) &&
            first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR)
    }

    private fun updateFilterChipStyles() {
        setChipSelected(chipAll, currentFilter == GalleryFilter.ALL)
        setChipSelected(chipImage, currentFilter == GalleryFilter.IMAGE)
        setChipSelected(chipVideo, currentFilter == GalleryFilter.VIDEO)
        setChipSelected(chipRecording, currentFilter == GalleryFilter.RECORDING)
        setChipSelected(chipCollection, currentFilter == GalleryFilter.COLLECTION)
    }

    private fun setChipSelected(chip: TextView, selected: Boolean) {
        chip.setBackgroundResource(
            if (selected) R.drawable.bg_gallery_chip_active
            else R.drawable.bg_gallery_chip_inactive
        )
        chip.setTextColor(
            if (selected) ContextCompat.getColor(this, R.color.white)
            else ContextCompat.getColor(this, R.color.text_secondary)
        )
    }

    private fun updateHeader() {
        val visibleCount = visiblePhotos.size
        val totalCount = photos.size

        tvPhotoCount.text = if (currentFilter == GalleryFilter.ALL || currentFilter == GalleryFilter.IMAGE) {
            "$visibleCount ${if (visibleCount == 1) "item" else "items"}"
        } else {
            "$visibleCount ${if (visibleCount == 1) "item" else "items"} • total $totalCount"
        }

        val hasVisibleItems = galleryRows.isNotEmpty()
        tvEmpty.visibility = if (hasVisibleItems) View.GONE else View.VISIBLE
        recyclerView.visibility = if (hasVisibleItems) View.VISIBLE else View.GONE

        if (!hasVisibleItems) {
            tvEmptyTitle.text = when (currentFilter) {
                GalleryFilter.VIDEO -> "No videos yet"
                GalleryFilter.RECORDING -> "No recordings yet"
                GalleryFilter.COLLECTION -> "No collection items"
                GalleryFilter.IMAGE -> "No images yet"
                GalleryFilter.ALL -> "No media yet"
            }
            tvEmptySubtitle.text = when (currentFilter) {
                GalleryFilter.ALL, GalleryFilter.IMAGE ->
                    "Capture from your glasses, then tap Sync to import photos."
                else ->
                    "Switch filter or sync more media from your glasses."
            }
        }

        if (!isSyncing) updateHeaderStatus()
    }

    private fun updateHeaderStatus() {
        val count = visiblePhotos.size
        tvStatus.text = if (count > 0) {
            "$count item${if (count == 1) "" else "s"} • tap to open • long-press to delete"
        } else {
            "Tap sync to download media from glasses"
        }
    }

    // Delete helpers

    private fun confirmClearAll() {
        if (photos.isEmpty()) {
            Toast.makeText(this, "Gallery is already empty", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Clear Gallery")
            .setMessage("Delete all ${photos.size} photos? This cannot be undone.")
            .setPositiveButton("Delete All") { _, _ ->
                val deleted = LiveGalleryManager.deleteAll(this)
                Toast.makeText(this, "Deleted $deleted photos", Toast.LENGTH_SHORT).show()
                refreshGallery()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deletePhoto(file: File) {
        AlertDialog.Builder(this)
            .setTitle("Delete Photo")
            .setMessage("Delete ${file.name}?")
            .setPositiveButton("Delete") { _, _ ->
                if (LiveGalleryManager.deletePhoto(file)) {
                    refreshGallery()
                    Toast.makeText(this, "Photo deleted", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Adapter

    private inner class GalleryAdapter(
        private val items: List<GalleryRow>,
        private val onTap: (File) -> Unit,
        private val onLongPress: (File) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val typeSection = 0
        private val typePhoto = 1

        private var lastItemClickAt = 0L
        private val itemClickDebounceMs = 500L

        inner class SectionVH(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.tvSectionHeader)
        }

        inner class PhotoVH(view: View) : RecyclerView.ViewHolder(view) {
            val image: ImageView = view.findViewById(R.id.ivGalleryThumb)
            val duration: TextView = view.findViewById(R.id.tvMediaDuration)
        }

        override fun getItemViewType(position: Int): Int {
            return when (items.getOrNull(position)) {
                is GalleryRow.SectionHeader -> typeSection
                is GalleryRow.PhotoTile -> typePhoto
                null -> typePhoto
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == typeSection) {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_live_gallery_section_header, parent, false)
                SectionVH(view)
            } else {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_live_gallery_grid_photo, parent, false)
                PhotoVH(view)
            }
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is GalleryRow.SectionHeader -> {
                    (holder as SectionVH).title.text = item.title
                }

                is GalleryRow.PhotoTile -> {
                    val photoHolder = holder as PhotoVH
                    val file = item.file

                    try {
                        val opts = BitmapFactory.Options().apply { inSampleSize = 3 }
                        val bmp = BitmapFactory.decodeFile(file.absolutePath, opts)
                        if (bmp != null) {
                            photoHolder.image.setImageBitmap(bmp)
                        } else {
                            photoHolder.image.setImageResource(R.drawable.ic_gallery)
                        }
                    } catch (_: Exception) {
                        photoHolder.image.setImageResource(R.drawable.ic_gallery)
                    }

                    photoHolder.duration.visibility = View.GONE

                    holder.itemView.setOnClickListener {
                        val now = System.currentTimeMillis()
                        if (now - lastItemClickAt < itemClickDebounceMs) return@setOnClickListener
                        lastItemClickAt = now
                        onTap(file)
                    }

                    holder.itemView.setOnLongClickListener {
                        onLongPress(file)
                        true
                    }
                }
            }
        }
    }
}