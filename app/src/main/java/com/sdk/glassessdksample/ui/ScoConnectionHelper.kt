package com.sdk.glassessdksample.ui

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlin.coroutines.resume

class ScoConnectionHelper(private val context: Context) {
    
    companion object {
        private const val TAG = "ScoConnectionHelper"
        private const val SCO_TIMEOUT_MS = 5000L
        private const val SCO_RETRY_DELAY_MS = 300L
        private const val MAX_SCO_RETRIES = 10
    }
    
    private var audioManager: AudioManager? = null
    private var scoReceiver: BroadcastReceiver? = null
    private var scoConnectionContinuation: CancellableContinuation<Boolean>? = null
    
    init {
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    
    /**
     * Enhanced SCO connection with proper async handling and retries
     */
    suspend fun connectScoWithRetries(): Boolean = withContext(Dispatchers.Main) {
        Log.d(TAG, "🔵 Starting enhanced SCO connection process...")
        
        // Check if Bluetooth is available and enabled
        if (!isBluetoothReady()) {
            Log.e(TAG, "❌ Bluetooth not ready for SCO connection")
            return@withContext false
        }
        
        // Ensure HFP (HEADSET) profile is available before attempting SCO
        val hfpReady = waitForHfpReady(3000L)
        if (!hfpReady) {
            Log.w(TAG, "⚠️ HFP/HEADSET profile not available — aborting SCO attempts")
            return@withContext false
        }
        
        val am = audioManager ?: return@withContext false
        
        // If already connected, return success
        if (am.isBluetoothScoOn) {
            Log.d(TAG, "✅ SCO already connected")
            return@withContext true
        }
        
        // Set up audio mode for SCO
        try {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            Log.d(TAG, "📞 Audio mode set to IN_COMMUNICATION")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set audio mode: ${e.message}")
        }
        
        // Retry SCO connection with backoff
        var attempt = 0
        var connected = false
        
        while (attempt < MAX_SCO_RETRIES && !connected) {
            attempt++
            Log.d(TAG, "🔄 SCO connection attempt $attempt/$MAX_SCO_RETRIES")
            
            connected = try {
                connectScoOnce()
            } catch (e: Exception) {
                Log.e(TAG, "SCO connection attempt $attempt failed: ${e.message}")
                false
            }
            
            if (!connected && attempt < MAX_SCO_RETRIES) {
                Log.d(TAG, "⏳ Waiting ${SCO_RETRY_DELAY_MS}ms before retry...")
                delay(SCO_RETRY_DELAY_MS)
            }
        }
        
        if (connected) {
            Log.d(TAG, "✅ SCO connection successful after $attempt attempts")
            
            // Optimize volume for glass speaker
            try {
                val maxVolume = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVolume, 0)
                Log.d(TAG, "🔊 Volume optimized for glass speaker")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to optimize volume: ${e.message}")
            }
        } else {
            Log.e(TAG, "❌ SCO connection failed after $MAX_SCO_RETRIES attempts")
        }
        
        connected
    }
    
    /**
     * Single SCO connection attempt with timeout
     */
    private suspend fun connectScoOnce(): Boolean = suspendCancellableCoroutine { continuation ->
        val am = audioManager ?: run {
            continuation.resume(false)
            return@suspendCancellableCoroutine
        }
        
        scoConnectionContinuation = continuation
        
        // Register SCO state change receiver
        registerScoReceiver()
        
        // Set timeout
        val timeoutJob = CoroutineScope(Dispatchers.Main).launch {
            delay(SCO_TIMEOUT_MS)
            if (scoConnectionContinuation?.isActive == true) {
                Log.w(TAG, "⏰ SCO connection timeout")
                unregisterScoReceiver()
                scoConnectionContinuation?.resume(false)
                scoConnectionContinuation = null
            }
        }
        
        continuation.invokeOnCancellation {
            timeoutJob.cancel()
            unregisterScoReceiver()
            scoConnectionContinuation = null
        }
        
        // Prefer explicitly targeting the glasses (Android 12+) over the legacy
        // startBluetoothSco(), which just takes whichever Bluetooth device the OS
        // reports first and can misroute audio to a second connected accessory.
        val routedToGlasses = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val bt = PreferredAudioDeviceResolver.findGlasses(
                    context, am.availableCommunicationDevices, AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                )
                bt != null && am.setCommunicationDevice(bt)
            } else false
        } catch (e: Exception) { false }
        if (routedToGlasses) {
            Log.d(TAG, "🎧 SCO routed to glasses via setCommunicationDevice")
            timeoutJob.cancel()
            unregisterScoReceiver()
            scoConnectionContinuation?.resume(true)
            scoConnectionContinuation = null
            return@suspendCancellableCoroutine
        }

        // Start SCO connection
        try {
            Log.d(TAG, "📡 Starting Bluetooth SCO...")
            am.startBluetoothSco()
            am.isBluetoothScoOn = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SCO: ${e.message}")
            timeoutJob.cancel()
            unregisterScoReceiver()
            scoConnectionContinuation?.resume(false)
            scoConnectionContinuation = null
        }
    }
    
    /**
     * Register SCO state change receiver
     */
    private fun registerScoReceiver() {
        unregisterScoReceiver() // Ensure no duplicate receiver
        
        scoReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val state = intent?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                Log.d(TAG, "📻 SCO state changed: $state")
                
                when (state) {
                    AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                        Log.d(TAG, "🎉 SCO connected successfully")
                        unregisterScoReceiver()
                        scoConnectionContinuation?.resume(true)
                        scoConnectionContinuation = null
                    }
                    AudioManager.SCO_AUDIO_STATE_ERROR -> {
                        Log.e(TAG, "💥 SCO connection error")
                        unregisterScoReceiver()
                        scoConnectionContinuation?.resume(false)
                        scoConnectionContinuation = null
                    }
                    AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                        Log.w(TAG, "🔌 SCO disconnected")
                        // Don't immediately fail on disconnect - might reconnect
                    }
                }
            }
        }
        
        context.registerReceiver(
            scoReceiver,
            IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        )
        Log.d(TAG, "📻 SCO receiver registered")
    }
    
    /**
     * Unregister SCO state change receiver
     */
    private fun unregisterScoReceiver() {
        scoReceiver?.let { receiver ->
            try {
                context.unregisterReceiver(receiver)
                Log.d(TAG, "📻 SCO receiver unregistered")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister SCO receiver: ${e.message}")
            }
        }
        scoReceiver = null
    }
    
    /**
     * Disconnect SCO and cleanup
     */
    fun disconnectSco() {
        Log.d(TAG, "🔴 Disconnecting SCO...")
        
        val am = audioManager ?: return
        
        try {
            if (am.isBluetoothScoOn) {
                am.stopBluetoothSco()
                am.isBluetoothScoOn = false
                Log.d(TAG, "📡 Bluetooth SCO stopped")
            }
            
            if (am.mode != AudioManager.MODE_NORMAL) {
                am.mode = AudioManager.MODE_NORMAL
                Log.d(TAG, "📞 Audio mode restored to NORMAL")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disconnect SCO: ${e.message}")
        }
        
        unregisterScoReceiver()
        scoConnectionContinuation?.cancel()
        scoConnectionContinuation = null
    }
    
    /**
     * Check if Bluetooth is ready for SCO connection
     */
    private fun isBluetoothReady(): Boolean {
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.e(TAG, "❌ Bluetooth adapter not available or disabled")
            return false
        }
        
        if (!BluetoothUtils.isBluetoothReady(context)) {
            Log.e(TAG, "❌ Bluetooth not ready (permissions/BLE issues)")
            return false
        }
        
        return true
    }

    /**
     * Poll for HFP/HEADSET profile readiness. Many glasses expose BLE only
     * and do not provide HFP; poll for up to timeoutMs to see if HFP becomes available.
     */
    suspend fun waitForHfpReady(timeoutMs: Long = 3000L): Boolean = withContext(Dispatchers.Main) {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return@withContext false
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            try {
                val state = adapter.getProfileConnectionState(BluetoothProfile.HEADSET)
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    Log.d(TAG, "✅ HFP/HEADSET profile is connected")
                    return@withContext true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error checking HFP state: ${e.message}")
            }
            delay(300)
        }
        Log.w(TAG, "⚠️ HFP/HEADSET profile not available within timeout")
        false
    }
    
    /**
     * Check current SCO connection status
     */
    fun isScoConnected(): Boolean {
        return audioManager?.isBluetoothScoOn == true
    }
    
    /**
     * Clean up resources
     */
    fun cleanup() {
        disconnectSco()
    }
}