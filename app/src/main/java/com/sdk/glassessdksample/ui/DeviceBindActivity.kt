package com.sdk.glassessdksample.ui

import android.Manifest
import android.annotation.SuppressLint
import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Dialog
import android.bluetooth.*
import android.bluetooth.le.ScanResult
import android.content.*
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.*
import android.graphics.drawable.ColorDrawable
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.text.SimpleDateFormat
import java.util.Date
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.XXPermissions
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.scan.BleScannerHelper
import com.oudmon.ble.base.scan.ScanRecord
import com.oudmon.ble.base.scan.ScanWrapperCallback
import com.sdk.glassessdksample.R
import com.sdk.glassessdksample.databinding.ActivityDeviceBindBinding
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class DeviceBindActivity : BaseActivity() {

    companion object {
        /**
         * Per-attempt HFP window (Mark 2). The observed refusal surfaced as
         * CONNECTING → DISCONNECTED at ~12s, so this must sit comfortably past that
         * to distinguish a slow link from a rejected one.
         */
        private const val HFP_ATTEMPT_TIMEOUT_MS = 15000L

        /** Pause before re-issuing a refused HFP connect (Mark 2). */
        private const val HFP_RETRY_BACKOFF_MS = 1500L

        /** Retries after the initial attempt before reporting failure (Mark 2). */
        private const val HFP_MAX_ATTEMPTS = 2
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    private lateinit var binding: ActivityDeviceBindBinding
    private val deviceList = mutableListOf<SmartWatch>()
    private var isScanning = false
    private lateinit var bleAdapter: BleDeviceAdapter

    private var scanSize = 0
    private var autoConnectDialog: AlertDialog? = null
    private var autoConnectBottomSheet: BottomSheetDialog? = null
    private var connectingDialog: Dialog? = null
    private var connectingStatusText: TextView? = null
    private val connectingAnimators = mutableListOf<Animator>()
    private var connectingDotCount = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    private var connectedDeviceAddress: String? = null
    private var headsetProxy: BluetoothHeadset? = null
    private var hfpReceiverRegistered = false
    private var bondStateReceiverRegistered = false

    /**
     * True when binding Mark 2 glasses. The HFP hardening below is scoped to Mark 2
     * so Mark 1's bind flow keeps its existing behaviour exactly.
     */
    private val isMark2: Boolean
        get() = DevicePreferenceManager.getDeviceType(this) == DeviceType.MARK2

    /**
     * Guards the classic (HFP) connection flow so it runs once per bind.
     *
     * BLE emits CONNECTED repeatedly (initial connect, then again on each service
     * re-discovery), and every event used to schedule another startClassicConnectionFlow.
     * That produced overlapping passes — observed three in one bind — each opening its
     * own headset proxy and its own timeout, which then cancelled each other through
     * the shared handler. See HFP_TOKEN.
     */
    private var classicFlowStarted = false

    /** Retry bookkeeping for the HFP connect attempt (Mark 2 only). */
    private var hfpAttempt = 0

    /**
     * Token for HFP-related handler callbacks.
     *
     * cleanUpReceivers() used to call removeCallbacksAndMessages(null), which clears
     * EVERY pending callback on mainHandler — including unrelated UI work such as the
     * scanning dot animation. Posting HFP work under its own token lets us cancel just
     * that work.
     */
    private val hfpToken = Any()

    /**
     * Token for the BLE auto-connect timeout that raises "Connection Timeout".
     *
     * This timeout has no success path of its own — it used to be cancelled only as a
     * side effect of removeCallbacksAndMessages(null) elsewhere. Giving it a token
     * lets cancelBleConnectTimeout() retire it precisely once the link is up, so the
     * dialog can no longer appear over an already-connected device.
     */
    private val bleConnectToken = Any()

    private val connectingDotAnimRunnable = object : Runnable {
        override fun run() {
            val textView = connectingStatusText ?: return
            val dialog = connectingDialog
            if (dialog?.isShowing != true) return

            connectingDotCount = (connectingDotCount + 1) % 4
            val dots = ".".repeat(connectingDotCount)
            textView.text = "Establishing secure link$dots"
            mainHandler.postDelayed(this, 480)
        }
    }

    // Dot animation for scanning text
    private var dotCount = 0
    private val dotAnimRunnable = object : Runnable {
        override fun run() {
            if (!isScanning) return
            dotCount = (dotCount + 1) % 4
            val dots = ".".repeat(dotCount)
            try { binding.scanStatus.text = "Searching$dots" } catch (e: Exception) {}
            mainHandler.postDelayed(this, 500)
        }
    }

    private val scanStopRunnable = Runnable {
        BleScannerHelper.getInstance().stopScan(this)
        isScanning = false
        updateScanUi()
    }

    private val bleScanCallback = BleCallback()

    private val profileListener = object : BluetoothProfile.ServiceListener {
        @SuppressLint("MissingPermission")
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            Log.d("DeviceBindActivity", "Profile proxy connected for profile: $profile")
            if (profile == BluetoothProfile.HEADSET) {
                headsetProxy = proxy as BluetoothHeadset
                if (!hasBluetoothConnectPermission()) {
                    Log.e("DeviceBindActivity", "Cannot attempt HFP connection, BLUETOOTH_CONNECT not granted.")
                    return
                }
                val classicDevice = connectedDeviceAddress?.let { BluetoothAdapter.getDefaultAdapter().getRemoteDevice(it) }
                if (classicDevice != null) {
                    mainHandler.post { attemptHfpConnection(classicDevice) }
                } else {
                    Log.e("DeviceBindActivity", "Cannot attempt HFP connection, connectedDeviceAddress is null.")
                }
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HEADSET) {
                headsetProxy = null
                Log.w("DeviceBindActivity", "Headset profile proxy disconnected.")
            }
        }
    }

    private val headsetConnectionReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)

                Log.d("DeviceBindActivity", "HFP connection state changed to $state for device ${device?.address}")

                if (device?.address == connectedDeviceAddress && state == BluetoothProfile.STATE_CONNECTED) {
                    Log.d("DeviceBindActivity", "✅ Headset Profile Connected via BroadcastReceiver")
                    handleHfpConnectionSuccess()
                }
            }
        }
    }

    private val bondStateReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)

                if (device != null && device.address == connectedDeviceAddress && bondState == BluetoothDevice.BOND_BONDED) {
                    Log.d("DeviceBindActivity", "✅ Device bonded. Now getting HFP proxy.")
                    cleanUpReceivers() // Clean up bond receiver
                    getHeadsetProxyAndConnect(device) // Proceed to connect HFP
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceBindBinding.inflate(layoutInflater)
        setContentView(binding.root)
    }

    override fun setupViews() {
        binding.btnBack.setOnClickListener { finish() }

        // RecyclerView with card-per-device + Connect button
        bleAdapter = BleDeviceAdapter(emptyList()) { tappedDevice ->
            mainHandler.removeCallbacks(scanStopRunnable)
            showPairConfirmationDialog(tappedDevice)
        }
        binding.rvDevices.layoutManager = LinearLayoutManager(this)
        binding.rvDevices.adapter = bleAdapter

        binding.startScan.setOnClickListener {
            if (isScanning) stopScanning() else startScanning()
        }
        updateScanUi()

        // Radar kept for SDK compat (lives in scanning layout, visible there)
        binding.radarView.onDeviceClick = { tappedDevice ->
            mainHandler.removeCallbacks(scanStopRunnable)
            showPairConfirmationDialog(tappedDevice)
        }

        // Auto-connect check
        mainHandler.postDelayed({ checkAutoConnect() }, 500)
    }

    override fun onResume() {
        super.onResume()
        EventBus.getDefault().register(this)
        requestPermissions()
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(scanStopRunnable)
        mainHandler.removeCallbacks(dotAnimRunnable)
        binding.radarView.setScanning(false)
        EventBus.getDefault().unregister(this)
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onMessageEvent(event: BluetoothEvent) {
        if (event.type == BluetoothEvent.EventType.CONNECTED) {
            // The link is up: retire the 30s "Connection Timeout" watchdog before it
            // can fire over a device that connected successfully.
            cancelBleConnectTimeout()
            dismissConnectingDialog()
            // MARK 2 ONLY: BLE re-emits CONNECTED on every service re-discovery, so
            // without this guard the classic flow starts several times over and the
            // concurrent passes tear down each other's proxies and timeouts.
            if (isMark2 && classicFlowStarted) {
                Log.d("DeviceBindActivity", "↩️ Classic flow already running — ignoring duplicate BLE CONNECTED")
                return
            }
            if (isMark2) classicFlowStarted = true
            Log.d("DeviceBindActivity", "✅ BLE Connected. Starting classic connection flow...")
            mainHandler.postDelayed({ connectedDeviceAddress?.let { startClassicConnectionFlow(it) } }, 500)
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleHfpConnectionSuccess() {
        if (isMark2) {
            hfpAttempt = 0
            mainHandler.removeCallbacksAndMessages(hfpToken)
        } else {
            mainHandler.removeCallbacksAndMessages(null)
        }
        Log.d("DeviceBindActivity", "🎧 HFP Connected (no forced SCO in bind flow)")
        Log.d("DeviceBindActivity", "✅ Both BLE and Audio connections active")
        setDeviceAliasIfSupported()
        cleanUpReceivers()
        mainHandler.postDelayed({ finish() }, 500)
    }

    @SuppressLint("MissingPermission")
    private fun setDeviceAliasIfSupported() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (!hasBluetoothConnectPermission()) return
        val address = connectedDeviceAddress ?: return
        try {
            val device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address)
            device.setAlias("IMI Glasses")
            Log.d("DeviceBindActivity", "✅ Device alias set to 'IMI Glasses'")
        } catch (e: Exception) {
            Log.w("DeviceBindActivity", "Could not set device alias: ${e.message}")
        }
    }

    private fun cleanUpReceivers() {
        // MARK 2: cancel only the HFP callbacks. removeCallbacksAndMessages(null)
        // wipes EVERY pending callback on this handler — including other in-flight
        // passes of this same flow and unrelated UI work like the dot animation —
        // which is how concurrent passes used to cancel each other.
        if (isMark2) {
            mainHandler.removeCallbacksAndMessages(hfpToken)
        } else {
            mainHandler.removeCallbacksAndMessages(null)
        }
        if (hfpReceiverRegistered) {
            try { unregisterReceiver(headsetConnectionReceiver) } catch (e: Exception) {}
            hfpReceiverRegistered = false
        }
        if (bondStateReceiverRegistered) {
            try { unregisterReceiver(bondStateReceiver) } catch (e: Exception) {}
            bondStateReceiverRegistered = false
        }
    }
    
    /**
     * 🆕 Show confirmation dialog before pairing with glasses
     * This ensures pairing ONLY happens through app, not system settings
     */
    private fun showPairConfirmationDialog(device: SmartWatch) {
        AlertDialog.Builder(this)
            .setTitle("Pair with Glasses?")
            .setMessage("Do you want to connect to ${device.deviceName}?\n\nThis will pair the glasses with your phone for:\n• Voice AI Assistant\n• Camera & Media\n• Smart Features")
            .setPositiveButton("Pair") { dialog, _ ->
                dialog.dismiss()
                proceedWithPairing(device)
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
                Toast.makeText(this, "Pairing cancelled", Toast.LENGTH_SHORT).show()
            }
            .setCancelable(false)
            .show()
    }
    
    /**
     * 🆕 Proceed with pairing after user confirmation
     */
    private fun proceedWithPairing(device: SmartWatch) {
        // ✅ Check Bluetooth state first
        if (!isBluetoothEnabled()) {
            Log.w("DeviceBindActivity", "⚠️ Bluetooth is OFF, cannot pair")
            showBluetoothOffDialog(device.deviceAddress, device.deviceName)
            return
        }
        
        connectedDeviceAddress = device.deviceAddress
        
        // Save device info for auto-connect next time
        savePairedDevice(device)
        
        // Start BLE connection
        BleOperateManager.getInstance().connectDirectly(device.deviceAddress)
        showConnectingDialog(device.deviceName)
        
        Log.d("DeviceBindActivity", "✅ User confirmed pairing with ${device.deviceName}")
    }
    
    /**
     * 🆕 Save paired device for auto-connect
     */
    private fun savePairedDevice(device: SmartWatch) {
        val prefs = getSharedPreferences("glass_pairing", MODE_PRIVATE)
        prefs.edit().apply {
            putString("paired_device_name", device.deviceName)
            putString("paired_device_address", device.deviceAddress)
            putLong("paired_timestamp", System.currentTimeMillis())
            apply()
        }
        Log.d("DeviceBindActivity", "💾 Saved device for auto-connect: ${device.deviceName}")
    }
    
    /**
     * 🆕 Check if there's a previously paired device and show bottom sheet popup
     */
    private fun checkAutoConnect() {
        val prefs = getSharedPreferences("glass_pairing", MODE_PRIVATE)
        val pairedAddress = prefs.getString("paired_device_address", null)
        val pairedName = prefs.getString("paired_device_name", null)
        val pairedTime = prefs.getLong("paired_timestamp", 0L)

        if (pairedAddress != null && pairedName != null) {
            Log.d("DeviceBindActivity", "📱 Found saved device: $pairedName")
            if (isFinishing || isDestroyed) return
            showAutoConnectBottomSheet(pairedAddress, pairedName, pairedTime)
        }
    }

    private fun showAutoConnectBottomSheet(address: String, name: String, timestamp: Long) {
        if (isFinishing || isDestroyed) return
        val sheet = BottomSheetDialog(this, R.style.BottomSheetStyle)
        val view = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_auto_connect, null)
        sheet.setContentView(view)
        sheet.setCancelable(true)

        // Populate name and address
        view.findViewById<TextView>(R.id.tvAutoConnectName).text = name
        view.findViewById<TextView>(R.id.tvAutoConnectAddress).text = address

        // Format last connected time
        val timeText = if (timestamp > 0) {
            val diff = System.currentTimeMillis() - timestamp
            when {
                diff < 60_000 -> "Just now"
                diff < 3_600_000 -> "${diff / 60_000}m ago"
                diff < 86_400_000 -> "${diff / 3_600_000}h ago"
                else -> SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(Date(timestamp))
            }
        } else "Unknown"
        view.findViewById<TextView>(R.id.tvLastConnected).text = timeText

        view.findViewById<Button>(R.id.btnAutoConnect).setOnClickListener {
            sheet.dismiss()
            attemptAutoConnect(address, name)
        }
        view.findViewById<Button>(R.id.btnScanNew).setOnClickListener {
            sheet.dismiss()
        }

        autoConnectBottomSheet = sheet
        sheet.show()
    }
    
    /**
     * 🆕 Attempt auto-connect to saved device
     */
    private fun attemptAutoConnect(address: String, name: String) {
        // ✅ Check if activity is still valid
        if (isFinishing || isDestroyed) return
        
        // ✅ Check Bluetooth state first
        if (!isBluetoothEnabled()) {
            Log.w("DeviceBindActivity", "⚠️ Bluetooth is OFF, cannot connect")
            showBluetoothOffDialog(address, name)
            return
        }
        
        connectedDeviceAddress = address
        BleOperateManager.getInstance().connectDirectly(address)
        showConnectingDialog(name)
        
        // ⏱️ Set timeout for connection (increased from 10s to 30s for slower devices).
        // Tagged with bleConnectToken so cancelBleConnectTimeout() can retire it the
        // moment the link comes up — otherwise it fires 30s later and shows
        // "Connection Timeout" over glasses that are already connected.
        mainHandler.postDelayed({
            if (!isFinishing && !isDestroyed) {
                dismissConnectingDialog()
                showConnectionTimeoutDialog(address, name)
            }
        }, bleConnectToken, 30000L)
        
        Log.d("DeviceBindActivity", "🔄 Auto-connecting to: $name")
    }

    @SuppressLint("MissingPermission")
    private fun startClassicConnectionFlow(deviceMac: String) {
        if (!hasBluetoothConnectPermission()) {
            Log.e("DeviceBindActivity", "Cannot start classic connection flow, BLUETOOTH_CONNECT not granted.")
            return
        }
        val classicDevice = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(deviceMac)
        Log.d("DeviceBindActivity", "Starting classic flow. Bond state: ${classicDevice.bondState}")
        when (classicDevice.bondState) {
            BluetoothDevice.BOND_BONDED -> {
                Log.d("DeviceBindActivity", "Device already bonded.")
                getHeadsetProxyAndConnect(classicDevice)
            }
            BluetoothDevice.BOND_NONE -> {
                Log.d("DeviceBindActivity", "Device not bonded. Starting pairing...")
                Toast.makeText(this, "Pairing with glasses... Please confirm.", Toast.LENGTH_LONG).show()
                registerBondStateReceiver()
                if (!classicDevice.createBond()) {
                    Log.e("DeviceBindActivity", "createBond() failed.")
                    cleanUpReceivers()
                }
            }
            BluetoothDevice.BOND_BONDING -> {
                Log.d("DeviceBindActivity", "Device is bonding. Waiting for result...")
                registerBondStateReceiver()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun getHeadsetProxyAndConnect(classicDevice: BluetoothDevice) {
        if (!hasBluetoothConnectPermission()) {
            Log.e("DeviceBindActivity", "Cannot get headset proxy, BLUETOOTH_CONNECT not granted.")
            return
        }
        Log.d("DeviceBindActivity", "Getting Headset profile proxy for ${classicDevice.address}")
        BluetoothAdapter.getDefaultAdapter().getProfileProxy(this, profileListener, BluetoothProfile.HEADSET)
    }

    @SuppressLint("MissingPermission")
    private fun attemptHfpConnection(classicDevice: BluetoothDevice) {
        val headset = headsetProxy ?: run {
            Log.e("DeviceBindActivity", "Headset proxy is null, cannot attempt connection.")
            return
        }
        val state = headset.getConnectionState(classicDevice)
        Log.d("DeviceBindActivity", "Attempting HFP connection. Current state: $state")
        when (state) {
            BluetoothProfile.STATE_CONNECTED -> handleHfpConnectionSuccess()
            BluetoothProfile.STATE_CONNECTING -> {
                Log.d("DeviceBindActivity", "HFP is connecting. Waiting...")
                registerHfpReceiverAndSetTimeout()
            }
            else -> {
                registerHfpReceiverAndSetTimeout()
                try {
                    val connectMethod = headset.javaClass.getMethod("connect", BluetoothDevice::class.java)
                    val success = connectMethod.invoke(headset, classicDevice) as? Boolean ?: false
                    if (success) Log.d("DeviceBindActivity", "HFP connect command issued successfully.")
                    else {
                        Log.e("DeviceBindActivity", "HFP connect command failed immediately.")
                        cleanUpReceivers()
                        finish()
                    }
                } catch (e: Exception) {
                    Log.e("DeviceBindActivity", "Error invoking HFP connect method", e)
                    cleanUpReceivers()
                    finish()
                }
            }
        }
    }

    private fun registerHfpReceiverAndSetTimeout() {
        if (!hfpReceiverRegistered) {
            registerReceiver(headsetConnectionReceiver, IntentFilter(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED))
            hfpReceiverRegistered = true
        }
        // MARK 2 ONLY: retry instead of giving up on the first timeout, and never
        // silently finish() back to home — a failed HFP link means all audio would
        // come out of the phone, which the user cannot diagnose from the home screen.
        if (isMark2) {
            mainHandler.postDelayed(
                { onHfpAttemptTimedOut() },
                hfpToken,
                HFP_ATTEMPT_TIMEOUT_MS
            )
            return
        }
        mainHandler.postDelayed({
            if (hfpReceiverRegistered) {
                Log.w("DeviceBindActivity", "HFP connection timed out.")
                cleanUpReceivers()
                finish()
            }
        }, 15000)
    }

    /**
     * MARK 2 ONLY. One HFP attempt ran out of time.
     *
     * Bluetooth stacks commonly refuse an HFP connect issued immediately after GATT
     * service discovery — the observed failure was state CONNECTING → DISCONNECTED
     * about 12s in, followed by an immediate finish() that dropped the user on the
     * home screen with audio still routed to the phone. Retrying after a short
     * backoff clears that transient refusal; only after the retries are exhausted do
     * we surface a real error, and even then we stay on this screen so the user can
     * retry rather than silently landing somewhere that looks connected.
     */
    @SuppressLint("MissingPermission")
    private fun onHfpAttemptTimedOut() {
        if (!hfpReceiverRegistered) return // already resolved

        val address = connectedDeviceAddress
        val device = if (address != null && hasBluetoothConnectPermission()) {
            try { BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address) } catch (e: Exception) { null }
        } else null

        // The broadcast can be missed if the link settled while we were not listening,
        // so confirm against the live profile state before treating this as a failure.
        val liveState = device?.let { d -> headsetProxy?.getConnectionState(d) }
        if (liveState == BluetoothProfile.STATE_CONNECTED) {
            Log.d("DeviceBindActivity", "✅ HFP is connected (observed on timeout check)")
            handleHfpConnectionSuccess()
            return
        }

        if (hfpAttempt < HFP_MAX_ATTEMPTS && device != null) {
            hfpAttempt++
            Log.w("DeviceBindActivity", "⚠️ HFP attempt $hfpAttempt/$HFP_MAX_ATTEMPTS timed out — retrying in ${HFP_RETRY_BACKOFF_MS}ms")
            connectingStatusText?.text = "Connecting glasses audio… (attempt ${hfpAttempt + 1})"
            mainHandler.postDelayed(
                { retryHfpConnect(device) },
                hfpToken,
                HFP_RETRY_BACKOFF_MS
            )
            return
        }

        Log.e("DeviceBindActivity", "❌ HFP connection failed after ${hfpAttempt + 1} attempts")
        cleanUpReceivers()
        showHfpFailureDialog()
    }

    /** MARK 2 ONLY. Re-issue the HFP connect for a retry attempt. */
    @SuppressLint("MissingPermission")
    private fun retryHfpConnect(device: BluetoothDevice) {
        val headset = headsetProxy
        if (headset == null) {
            // Proxy went away between attempts — re-acquire it; the profile listener
            // routes back into attemptHfpConnection() once it reconnects.
            Log.d("DeviceBindActivity", "Headset proxy gone — re-acquiring for retry")
            BluetoothAdapter.getDefaultAdapter().getProfileProxy(this, profileListener, BluetoothProfile.HEADSET)
            return
        }
        try {
            val connectMethod = headset.javaClass.getMethod("connect", BluetoothDevice::class.java)
            val success = connectMethod.invoke(headset, device) as? Boolean ?: false
            Log.d("DeviceBindActivity", "HFP retry connect issued, success=$success")
        } catch (e: Exception) {
            Log.e("DeviceBindActivity", "Error invoking HFP connect on retry", e)
        }
        // Arm the next timeout regardless: a refused connect still needs to fall
        // through to the next attempt or to the failure dialog.
        mainHandler.postDelayed(
            { onHfpAttemptTimedOut() },
            hfpToken,
            HFP_ATTEMPT_TIMEOUT_MS
        )
    }

    /**
     * MARK 2 ONLY. Report a genuine HFP failure instead of finishing to the home
     * screen, where the glasses would look connected but all audio would play on the
     * phone with no explanation.
     */
    private fun showHfpFailureDialog() {
        if (isFinishing || isDestroyed) return
        try {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Glasses audio not connected")
                .setMessage(
                    "The glasses are connected for data, but the audio (hands-free) " +
                    "connection could not be established, so AI voice would play " +
                    "through the phone.\n\nTry again, or forget and re-pair the " +
                    "glasses in system Bluetooth settings."
                )
                .setPositiveButton("Retry") { d, _ ->
                    d.dismiss()
                    hfpAttempt = 0
                    classicFlowStarted = false
                    connectedDeviceAddress?.let { startClassicConnectionFlow(it) }
                }
                .setNegativeButton("Continue anyway") { d, _ ->
                    d.dismiss()
                    finish()
                }
                .setCancelable(false)
                .show()
        } catch (e: Exception) {
            Log.w("DeviceBindActivity", "Could not show HFP failure dialog: ${e.message}")
        }
    }

    private fun registerBondStateReceiver() {
        if (!bondStateReceiverRegistered) {
            registerReceiver(bondStateReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
            bondStateReceiverRegistered = true
        }
    }

    private fun playNotificationSound() {
        try {
            RingtoneManager.getRingtone(applicationContext, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)).play()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun requestPermissions() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        XXPermissions.with(this).permission(perms).request(object : OnPermissionCallback {
            override fun onGranted(list: MutableList<String>, all: Boolean) { if (all) setupViews() }
            override fun onDenied(list: MutableList<String>, never: Boolean) {
                if (never) XXPermissions.startPermissionActivity(this@DeviceBindActivity, list)
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        if (!isBluetoothEnabled()) {
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        deviceList.clear()
        binding.radarView.updateDevices(deviceList)
        scanSize = 0
        isScanning = true
        BleScannerHelper.getInstance().reSetCallback()
        BleScannerHelper.getInstance().scanDevice(this, null, bleScanCallback)
        updateScanUi()
        dotCount = 0
        mainHandler.post(dotAnimRunnable)
        mainHandler.postDelayed(scanStopRunnable, 15000)
    }

    private fun stopScanning() {
        BleScannerHelper.getInstance().stopScan(this)
        mainHandler.removeCallbacks(scanStopRunnable)
        mainHandler.removeCallbacks(dotAnimRunnable)
        isScanning = false
        updateScanUi()
    }

    private fun updateScanUi() {
        val hasDevices = deviceList.isNotEmpty()
        val count = deviceList.size

        binding.startScan.text = if (isScanning) "Stop Scan" else "Scan for Glasses"

        // Show devices as soon as any are found (even while still scanning)
        if (hasDevices) {
            // Transition to device list if not already visible
            if (binding.layoutDevicesFound.visibility != android.view.View.VISIBLE) {
                binding.layoutScanning.animate().alpha(0f).setDuration(300).withEndAction {
                    binding.layoutScanning.visibility = android.view.View.GONE
                }.start()
                binding.layoutDevicesFound.alpha = 0f
                binding.layoutDevicesFound.visibility = android.view.View.VISIBLE
                binding.layoutDevicesFound.animate().alpha(1f).setDuration(400).start()
            }
            binding.deviceCount.text = "$count device${if (count == 1) "" else "s"} found"
            bleAdapter.submitList(deviceList.toList())
            // Show "still scanning" indicator while scan is active
            try {
                binding.tvScanningIndicator.visibility =
                    if (isScanning) android.view.View.VISIBLE else android.view.View.GONE
            } catch (e: Exception) {}
            binding.startScan.visibility = if (isScanning) android.view.View.GONE else android.view.View.VISIBLE
        } else {
            // No devices yet — show scanning layout
            if (binding.layoutScanning.visibility != android.view.View.VISIBLE) {
                binding.layoutDevicesFound.visibility = android.view.View.GONE
                binding.layoutScanning.alpha = 1f
                binding.layoutScanning.visibility = android.view.View.VISIBLE
            }
            // Update the found-count chip in scanning view
            try {
                if (isScanning) {
                    binding.tvFoundCount.visibility = android.view.View.GONE
                }
            } catch (e: Exception) {}
            binding.startScan.visibility = android.view.View.VISIBLE
        }

        // Radar drive (it sits inside scanning layout)
        binding.radarView.setScanning(isScanning)
        binding.radarView.updateDevices(deviceList)
    }
    
    private fun isBluetoothEnabled(): Boolean = BluetoothAdapter.getDefaultAdapter()?.isEnabled ?: false

    private fun showConnectingDialog(deviceName: String) {
        if (isFinishing || isDestroyed) return

        dismissConnectingDialog()

        val content = LayoutInflater.from(this).inflate(R.layout.dialog_connecting_glasses, null)
        val titleText = content.findViewById<TextView>(R.id.tvConnectingTitle)
        val subtitleText = content.findViewById<TextView>(R.id.tvConnectingSubtitle)
        val pulseRingOne = content.findViewById<View>(R.id.pulseRingOne)
        val pulseRingTwo = content.findViewById<View>(R.id.pulseRingTwo)
        val iconHolder = content.findViewById<View>(R.id.connectingIconHolder)
        val spinner = content.findViewById<ProgressBar>(R.id.progressConnecting)

        titleText.text = "Connecting to $deviceName"
        subtitleText.text = "Pairing BLE and audio profile"

        connectingStatusText = content.findViewById(R.id.tvConnectingState)
        connectingStatusText?.text = "Establishing secure link"

        val dialog = Dialog(this, R.style.ConnectingDialogStyle)
        dialog.setContentView(content)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            val maxWidth = (resources.displayMetrics.density * 360f).toInt()
            val preferredWidth = (resources.displayMetrics.widthPixels * 0.84f).toInt()
            setLayout(preferredWidth.coerceAtMost(maxWidth), WindowManager.LayoutParams.WRAP_CONTENT)
            attributes = attributes.apply { dimAmount = 0.62f }
        }

        dialog.show()
        connectingDialog = dialog

        startConnectingAnimations(pulseRingOne, pulseRingTwo, iconHolder, spinner)
        mainHandler.removeCallbacks(connectingDotAnimRunnable)
        connectingDotCount = 0
        mainHandler.post(connectingDotAnimRunnable)
    }

    private fun startConnectingAnimations(
        pulseRingOne: View,
        pulseRingTwo: View,
        iconHolder: View,
        spinner: ProgressBar
    ) {
        val pulseOneScaleX = ObjectAnimator.ofFloat(pulseRingOne, View.SCALE_X, 0.72f, 1.28f).apply {
            duration = 1650L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = DecelerateInterpolator()
        }
        val pulseOneScaleY = ObjectAnimator.ofFloat(pulseRingOne, View.SCALE_Y, 0.72f, 1.28f).apply {
            duration = 1650L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = DecelerateInterpolator()
        }
        val pulseOneAlpha = ObjectAnimator.ofFloat(pulseRingOne, View.ALPHA, 0.58f, 0f).apply {
            duration = 1650L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = DecelerateInterpolator()
        }

        val pulseTwoScaleX = ObjectAnimator.ofFloat(pulseRingTwo, View.SCALE_X, 0.72f, 1.28f).apply {
            duration = 1650L
            startDelay = 760L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = DecelerateInterpolator()
        }
        val pulseTwoScaleY = ObjectAnimator.ofFloat(pulseRingTwo, View.SCALE_Y, 0.72f, 1.28f).apply {
            duration = 1650L
            startDelay = 760L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = DecelerateInterpolator()
        }
        val pulseTwoAlpha = ObjectAnimator.ofFloat(pulseRingTwo, View.ALPHA, 0.52f, 0f).apply {
            duration = 1650L
            startDelay = 760L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = DecelerateInterpolator()
        }

        val iconFloat = ObjectAnimator.ofFloat(iconHolder, View.TRANSLATION_Y, 0f, -10f, 0f).apply {
            duration = 1700L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = AccelerateDecelerateInterpolator()
        }
        val iconScaleX = ObjectAnimator.ofFloat(iconHolder, View.SCALE_X, 1f, 1.06f, 1f).apply {
            duration = 1700L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = AccelerateDecelerateInterpolator()
        }
        val iconScaleY = ObjectAnimator.ofFloat(iconHolder, View.SCALE_Y, 1f, 1.06f, 1f).apply {
            duration = 1700L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = AccelerateDecelerateInterpolator()
        }

        val spinnerBreath = ObjectAnimator.ofFloat(spinner, View.ALPHA, 0.66f, 1f, 0.66f).apply {
            duration = 1200L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = AccelerateDecelerateInterpolator()
        }

        connectingAnimators.clear()
        connectingAnimators.addAll(
            listOf(
                pulseOneScaleX,
                pulseOneScaleY,
                pulseOneAlpha,
                pulseTwoScaleX,
                pulseTwoScaleY,
                pulseTwoAlpha,
                iconFloat,
                iconScaleX,
                iconScaleY,
                spinnerBreath
            )
        )

        connectingAnimators.forEach { it.start() }
    }

    private fun dismissConnectingDialog() {
        mainHandler.removeCallbacks(connectingDotAnimRunnable)

        connectingAnimators.forEach {
            try {
                it.cancel()
            } catch (e: Exception) {
                Log.w("DeviceBindActivity", "Failed to cancel connecting animation", e)
            }
        }
        connectingAnimators.clear()

        try {
            if (connectingDialog?.isShowing == true) {
                connectingDialog?.dismiss()
            }
        } catch (e: Exception) {
            Log.w("DeviceBindActivity", "Failed to dismiss connecting dialog", e)
        }

        connectingDialog = null
        connectingStatusText = null
    }
    
    /**
     * 🆕 Show dialog when Bluetooth is OFF
     */
    private fun showBluetoothOffDialog(address: String, name: String) {
        // Check if activity is finishing to prevent window leak
        if (isFinishing || isDestroyed) return
        
        AlertDialog.Builder(this)
            .setTitle("Bluetooth is OFF")
            .setMessage("Please turn ON Bluetooth to connect to $name")
            .setPositiveButton("Turn ON") { dialog, _ ->
                dialog.dismiss()
                // Open Bluetooth settings
                startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                
                // Retry connection after delay
                mainHandler.postDelayed({
                    // Check if activity is still valid before attempting connection
                    if (!isFinishing && !isDestroyed && isBluetoothEnabled()) {
                        attemptAutoConnect(address, name)
                    } else if (!isFinishing && !isDestroyed) {
                        Toast.makeText(this, "Please enable Bluetooth manually", Toast.LENGTH_LONG).show()
                    }
                }, 2000)
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }
    
    /**
     * 🆕 Show dialog when connection times out
     */
    /**
     * Retire the BLE auto-connect watchdog. Safe to call more than once.
     */
    private fun cancelBleConnectTimeout() {
        mainHandler.removeCallbacksAndMessages(bleConnectToken)
    }

    /**
     * Whether [address] currently has a live GATT link, asked of the system rather
     * than of our own bookkeeping so a missed callback cannot make a connected
     * device look disconnected.
     */
    @SuppressLint("MissingPermission")
    private fun isDeviceCurrentlyConnected(address: String): Boolean {
        if (!hasBluetoothConnectPermission()) return false
        return try {
            val manager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return false
            manager.getConnectedDevices(BluetoothProfile.GATT)
                .any { it.address.equals(address, ignoreCase = true) }
        } catch (e: Exception) {
            Log.w("DeviceBindActivity", "Could not query GATT connection state: ${e.message}")
            false
        }
    }

    private fun showConnectionTimeoutDialog(address: String, name: String) {
        if (isFinishing || isDestroyed) return

        // Last-ditch guard: never claim a timeout for a device that is actually
        // connected. The watchdog is cancelled on BLE CONNECTED, but if it was
        // already queued on the main thread that cancellation can lose the race.
        if (isDeviceCurrentlyConnected(address)) {
            Log.d("DeviceBindActivity", "↩️ Suppressing Connection Timeout — $name is connected")
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Connection Timeout")
            .setMessage("Could not connect to $name.\n\nMake sure glasses are:\n• Powered ON\n• In range\n• Not connected to another device")
            .setPositiveButton("Retry") { dialog, _ ->
                dialog.dismiss()
                attemptAutoConnect(address, name)
            }
            .setNegativeButton("Scan New") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    inner class BleCallback : ScanWrapperCallback {
        @SuppressLint("MissingPermission")
        override fun onLeScan(device: BluetoothDevice?, rssi: Int, scanRecord: ByteArray?) {
            if (device == null || device.name.isNullOrEmpty()) return
            val deviceName = device.name
            val isAllowedDevice = deviceName.startsWith("Cyber", ignoreCase = true) ||
                deviceName.startsWith("SANVNET", ignoreCase = true)
            if (!isAllowedDevice) return
            val newDevice = SmartWatch(device.name, device.address, rssi = rssi)
            if (deviceList.any { it.deviceAddress == newDevice.deviceAddress }) return
            deviceList.add(newDevice)
            deviceList.sortByDescending { it.rssi }
            updateScanUi()
            if (++scanSize > 30) BleScannerHelper.getInstance().stopScan(this@DeviceBindActivity)
        }
        override fun onStart() {
            isScanning = true
            dotCount = 0
            mainHandler.post(dotAnimRunnable)
            updateScanUi()
        }
        override fun onStop() {
            isScanning = false
            mainHandler.removeCallbacks(dotAnimRunnable)
            updateScanUi()
        }
        override fun onScanFailed(errorCode: Int) {}
        override fun onParsedData(device: BluetoothDevice?, scanRecord: ScanRecord?) {}
        override fun onBatchScanResults(results: MutableList<ScanResult>?) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        // Dismiss connection dialog to prevent window leak
        dismissConnectingDialog()
        if (autoConnectDialog?.isShowing == true) {
            autoConnectDialog?.dismiss()
        }
        autoConnectDialog = null
        autoConnectBottomSheet?.dismiss()
        autoConnectBottomSheet = null

        stopScanning()
        cleanUpReceivers()
        EventBus.getDefault().unregister(this)
        headsetProxy?.let { BluetoothAdapter.getDefaultAdapter().closeProfileProxy(BluetoothProfile.HEADSET, it) }
    }
}
