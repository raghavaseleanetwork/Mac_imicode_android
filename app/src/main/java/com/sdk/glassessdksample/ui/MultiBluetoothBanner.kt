package com.sdk.glassessdksample.ui

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import android.view.View
import android.widget.TextView
import com.sdk.glassessdksample.R

/**
 * Drives the in-app "Two Bluetooth devices connected" banner (view_multi_bluetooth_banner.xml).
 *
 * Android gives an app no public API to force audio onto one specific Bluetooth
 * device, or to disconnect a *different* device it doesn't own — only the system
 * Bluetooth settings screen can do the latter. So this banner's job is purely to
 * make the ambiguous state visible and point the user at the one thing that
 * actually fixes it: turning off/disconnecting the other device themselves.
 *
 * Callers show/hide this alongside gating the actual wake-word/conversation start
 * (see HotHelper.start(), MainActivity.proceedWithGeminiLive(),
 * Mark1MainActivity.startInlineGeminiLive()) so the UI state and the lock always
 * agree with each other.
 */
class MultiBluetoothBanner(private val activity: Activity, private val root: View) {

    private val otherDeviceView: TextView = root.findViewById(R.id.tvMultiBluetoothOtherDevice)
    private val disconnectButton: View = root.findViewById(R.id.btnDisconnectOtherDevice)

    init {
        disconnectButton.setOnClickListener {
            try {
                activity.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            } catch (e: Exception) {
                try {
                    activity.startActivity(Intent(Settings.ACTION_SETTINGS))
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Refreshes visibility/content from the current Bluetooth state. Call this
     * anywhere the lock is (re-)evaluated: onResume, after a Bluetooth
     * connect/disconnect broadcast, and right before attempting to start
     * wake-word listening or a conversation.
     *
     * @return true if multiple Bluetooth audio devices are connected (i.e. the
     *         caller should treat wake-word/quick-start as locked right now).
     */
    fun refresh(): Boolean {
        val devices = PreferredAudioDeviceResolver.connectedBluetoothAudioDevices(activity)
        val locked = devices.size > 1

        if (!locked) {
            root.visibility = View.GONE
            return false
        }

        val otherDevices = devices.filterNot { PreferredAudioDeviceResolver.isPairedGlasses(activity, it) }
        val otherNames = otherDevices.map { it.productName?.toString() ?: "Unknown device" }
            .ifEmpty {
                // Glasses weren't identified among the connected devices (e.g. no
                // paired address recorded yet) — fall back to naming everything
                // that isn't the first device, so the message still makes sense.
                devices.drop(1).map { it.productName?.toString() ?: "Unknown device" }
            }

        otherDeviceView.text = "Other device: ${otherNames.joinToString(", ")}"
        root.visibility = View.VISIBLE
        return true
    }

    fun hide() {
        root.visibility = View.GONE
    }
}
