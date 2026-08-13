package com.sdk.glassessdksample.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.bluetooth.DeviceManager
import com.oudmon.ble.base.bluetooth.QCBluetoothCallbackCloneReceiver
import com.oudmon.ble.base.communication.Constants
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.request.WriteRequest
import com.sdk.glassessdksample.BuildConfig
import org.greenrobot.eventbus.EventBus

class MyBluetoothReceiver : QCBluetoothCallbackCloneReceiver() {

    private val TAG = "MyBluetoothReceiver"
    // Use getInstance() instead of constructor since HotHelper is a Singleton
    private val hotHelper by lazy { HotHelper.getInstance(MyApplication.instance) }
    private val mainHandler = Handler(Looper.getMainLooper())
    // When true, prefer the glasses' native/firmware wake-word detection
    // and do not start the in-app wake detector.
    // Set to false to use the selected app wake engine from Settings.
    private var enableNativeWake = false

    @SuppressLint("MissingPermission")
    override fun connectStatue(device: BluetoothDevice?, connected: Boolean) {
        Log.i(TAG, "Connection Status: Device=${device?.name}, Connected=$connected")
        if (device != null && connected) {
            device.name?.let { DeviceManager.getInstance().deviceName = it }
            requestMicPermissionIfNeeded()
            EventBus.getDefault().post(BluetoothEvent(BluetoothEvent.EventType.CONNECTED))
        } else {
            stopCustomWakeWord()
            EventBus.getDefault().post(BluetoothEvent(BluetoothEvent.EventType.DISCONNECTED))
        }
    }

    private fun requestMicPermissionIfNeeded() {
        val micGranted = ContextCompat.checkSelfPermission(
            MyApplication.instance,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!micGranted) {
            Log.w(TAG, "RECORD_AUDIO permission is not granted. Posting request event.")
            EventBus.getDefault().post(BluetoothEvent(BluetoothEvent.EventType.REQUEST_MIC_PERMISSION))
        }
    }

    private fun stopCustomWakeWord() {
        mainHandler.removeCallbacksAndMessages(null)
        hotHelper.stop()
    }

    private fun setNativeWakeWord(enable: Boolean) {
        val command = if (enable) byteArrayOf(0x44, 0x01) else byteArrayOf(0x44, 0x00)
        Log.d(TAG, "${if (enable) "Enabling" else "Disabling"} native Wake Word...")
        try {
            if (Constants.SERIAL_PORT_SERVICE != null && Constants.SERIAL_PORT_CHARACTER_WRITE != null) {
                val writeReq = WriteRequest(Constants.SERIAL_PORT_SERVICE, Constants.SERIAL_PORT_CHARACTER_WRITE)
                writeReq.value = command
                BleOperateManager.getInstance().execute(writeReq)
            } else {
                Log.e(TAG, "Cannot send command: Constants UUIDs are null")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send BLE command: ${e.message}")
        }
    }

    override fun onServiceDiscovered() {
        Log.i(TAG, "BLE Services Discovered - Glass Ready")
        LargeDataHandler.getInstance().initEnable()
        BleOperateManager.getInstance().isReady = true

        // A fresh link starts at the default MTU and connection interval, so any
        // earlier tuning no longer applies. Clear the rate-limit and tune now, while
        // services are up but before any screen asks for an image — that way the
        // first capture of a session is already fast instead of paying for the
        // negotiation itself.
        BleSpeedTuner.resetThrottle()
        BleSpeedTuner.tune(MyApplication.instance, "ServicesDiscovered")

        // Disable firmware wake word - app handles wake-word detection.
        setNativeWakeWord(false)
        requestMicPermissionIfNeeded()

        EventBus.getDefault().post(BluetoothEvent(BluetoothEvent.EventType.CONNECTED))
    }
    


    override fun onCharacteristicChange(address: String?, uuid: String?, data: ByteArray?) {
        // Not used - wake word detected by phone mic Porcupine
    }

    override fun onCharacteristicRead(uuid: String?, data: ByteArray?) {
        if (uuid == null || data == null) return
        val valueStr = String(data, Charsets.UTF_8)
        when (uuid.lowercase()) {
            Constants.CHAR_FIRMWARE_REVISION.toString().lowercase() -> MyApplication.instance.firmwareVersion = valueStr
            Constants.CHAR_HW_REVISION.toString().lowercase() -> MyApplication.instance.hardwareVersion = valueStr
        }
    }
}
