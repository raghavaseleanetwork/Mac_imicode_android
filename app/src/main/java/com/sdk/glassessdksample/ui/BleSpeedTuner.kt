package com.sdk.glassessdksample.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Speeds up BLE transfers (photos, vision frames) on the SDK's own GATT link.
 *
 * Image transfer time over BLE is dominated by two per-connection properties:
 *
 *  - **MTU.** The default is 23 bytes (20 usable), so a ~15 KB JPEG needs 750+
 *    round trips. At 517 the same image takes ~30.
 *  - **Connection interval.** The default is ~30-50 ms; CONNECTION_PRIORITY_HIGH
 *    drops it to ~7.5-15 ms, which multiplies throughput independently of MTU.
 *
 * Both are properties of a *specific* GATT connection, so they must be applied to
 * the connection the SDK actually streams over. The SDK exposes it through the
 * public [com.oudmon.ble.base.bluetooth.BleBaseControl.getGatt] accessor — no
 * reflection and no second connection required.
 *
 * Scoped to Mark 2: Mark 1 has no camera, so nothing on that path benefits, and
 * leaving its BLE link untouched keeps a working configuration working.
 *
 * ### Why this is centralised
 * Several screens capture images (vision chat, text chat, galleries, the main
 * activity). Each needs the same tuning, and an earlier per-screen copy in
 * VisionChatActivity had bugs that were invisible precisely because the logic was
 * buried in one screen: it raised MTU on a newly opened connection (which does
 * nothing for the SDK's stream) and only recognised devices named
 * "Glass"/"IMI"/"Cyan", so it silently did nothing for SANVNET-branded glasses.
 */
object BleSpeedTuner {

    private const val TAG = "BleSpeedTuner"

    /** ATT maximum. The peripheral negotiates down to whatever it supports. */
    private const val MAX_BLE_MTU = 517

    /**
     * Minimum gap between tuning attempts.
     *
     * Callers invoke this from screen entry and from connection callbacks, so the
     * same link can be tuned several times in quick succession. Renegotiating MTU
     * mid-transfer is wasteful and can disrupt an in-flight image, so repeated
     * calls inside this window are ignored.
     */
    private const val MIN_RETUNE_INTERVAL_MS = 10_000L

    @Volatile private var lastTuneAt = 0L

    /**
     * Apply high connection priority and a large MTU to the SDK's GATT link.
     *
     * Safe to call from anywhere, repeatedly: it is rate-limited, no-ops on Mark 1,
     * and never throws. [reason] is logged so it is clear which screen asked.
     */
    @SuppressLint("MissingPermission")
    fun tune(context: Context, reason: String) {
        try {
            if (DevicePreferenceManager.getDeviceType(context) != DeviceType.MARK2) return

            if (!hasConnectPermission(context)) {
                Log.w(TAG, "BLUETOOTH_CONNECT not granted — cannot tune BLE link ($reason)")
                return
            }

            val now = SystemClock.uptimeMillis()
            if (now - lastTuneAt < MIN_RETUNE_INTERVAL_MS) {
                Log.d(TAG, "↩️ Skipping BLE tune ($reason) — tuned ${now - lastTuneAt}ms ago")
                return
            }

            val gatts = resolveSdkGatts(context)
            if (gatts.isEmpty()) {
                Log.w(TAG, "⚠️ No SDK GATT connection found ($reason) — BLE stays at default speed")
                return
            }
            lastTuneAt = now

            for (gatt in gatts) {
                val label = gatt.device?.name ?: gatt.device?.address ?: "unknown"

                val fast = gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                Log.i(TAG, "⚡ connectionPriority(HIGH) on $label → $fast ($reason)")

                // The negotiated size is delivered to the SDK's own
                // BluetoothGattCallback, which this app does not own, so only the
                // request can be logged here. Confirm the result in GLASSES_LOG.
                val asked = gatt.requestMtu(MAX_BLE_MTU)
                Log.i(TAG, "🚀 requestMtu($MAX_BLE_MTU) on $label → $asked ($reason)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "tune($reason) failed: ${e.message}")
        }
    }

    /**
     * Forget the rate-limit so the next [tune] call always runs.
     *
     * Call on a fresh connection: a new link starts at default MTU and interval, so
     * the previous tuning no longer applies and must not be suppressed.
     */
    fun resetThrottle() {
        lastTuneAt = 0L
    }

    /**
     * The SDK's live GATT connection(s).
     *
     * Addresses come from the system's connected-GATT list rather than from
     * device-name or MAC-prefix guesses, so any branding works; getGatt() returns
     * null for addresses the SDK does not manage, which filters everything else out.
     */
    @SuppressLint("MissingPermission")
    private fun resolveSdkGatts(context: Context): List<BluetoothGatt> {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: return emptyList()
        val control = try {
            com.oudmon.ble.base.bluetooth.BleBaseControl.getInstance()
        } catch (e: Exception) {
            Log.w(TAG, "Could not obtain BleBaseControl: ${e.message}")
            return emptyList()
        }

        return manager.getConnectedDevices(BluetoothProfile.GATT).mapNotNull { device ->
            try {
                control.getGatt(device.address)
            } catch (e: Exception) {
                Log.w(TAG, "getGatt(${device.address}) failed: ${e.message}")
                null
            }
        }
    }

    private fun hasConnectPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    }
}
