package com.sdk.glassessdksample.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Picks the paired glasses out of Android's list of currently connected Bluetooth
 * audio devices, instead of blindly taking whichever one the OS reports first.
 *
 * When the phone has only the glasses connected as a Bluetooth audio device, "first
 * device in the list" always happens to be the glasses, so every call site that used
 * `firstOrNull { it.type == TYPE_BLUETOOTH_SCO/A2DP }` worked by coincidence. The
 * moment a second Bluetooth audio accessory (headset, car kit, earbuds) is connected
 * at the same time, Android's own ordering can put that other device first, and
 * audio silently goes there instead of the glasses. This resolver filters candidates
 * by the glasses' known Bluetooth address (persisted in DeviceBindActivity's
 * "glass_pairing" prefs) so every routing decision targets the glasses specifically.
 */
object PreferredAudioDeviceResolver {
    private const val TAG = "PreferredAudioDevice"
    private const val PREFS_NAME = "glass_pairing"
    private const val KEY_PAIRED_ADDRESS = "paired_device_address"
    private const val KEY_PAIRED_NAME = "paired_device_name"

    /** The Bluetooth MAC address of the currently paired glasses, if any. */
    fun pairedGlassesAddress(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PAIRED_ADDRESS, null)

    private fun pairedGlassesName(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PAIRED_NAME, null)

    /**
     * True if [info] is the paired glasses device.
     *
     * Matches by Bluetooth address when available (API 28+; needs BLUETOOTH_CONNECT
     * on API 31+, which the app already requests for BLE). Falls back to a
     * product-name match on older API levels where `AudioDeviceInfo.address` is not
     * exposed for Bluetooth devices.
     */
    fun isPairedGlasses(context: Context, info: AudioDeviceInfo): Boolean {
        val pairedAddress = pairedGlassesAddress(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && pairedAddress != null) {
            val infoAddress = try { info.address } catch (e: Exception) { null }
            if (!infoAddress.isNullOrBlank()) {
                return infoAddress.equals(pairedAddress, ignoreCase = true)
            }
        }
        val pairedName = pairedGlassesName(context) ?: return false
        val productName = info.productName?.toString() ?: return false
        return productName.equals(pairedName, ignoreCase = true) ||
            productName.contains(pairedName, ignoreCase = true) ||
            pairedName.contains(productName, ignoreCase = true)
    }

    /**
     * Finds the paired glasses among [candidates] whose `type` is one of [types].
     * Returns null (never a mismatched device) if the glasses aren't in the list —
     * callers should treat that as "not routable right now", not fall back to
     * picking any other Bluetooth device.
     */
    fun findGlasses(
        context: Context,
        candidates: Array<AudioDeviceInfo>,
        vararg types: Int
    ): AudioDeviceInfo? {
        val match = candidates.firstOrNull { it.type in types && isPairedGlasses(context, it) }
        if (match == null && candidates.any { it.type in types }) {
            Log.w(TAG, "⚠️ Bluetooth audio device(s) present but none match the paired glasses " +
                "(address=${pairedGlassesAddress(context)}) — likely a second connected " +
                "Bluetooth accessory. Refusing to route to it.")
        }
        return match
    }

    fun findGlasses(
        context: Context,
        candidates: List<AudioDeviceInfo>,
        vararg types: Int
    ): AudioDeviceInfo? = findGlasses(context, candidates.toTypedArray(), *types)

    /**
     * Distinct Bluetooth audio devices (SCO and/or A2DP endpoints, de-duplicated by
     * address/name since one physical device often exposes both) that Android
     * currently reports as connected, with any entry that is actually the PHONE
     * ITSELF (a phantom/self route some OEM builds — e.g. OnePlus — surface
     * through AudioManager.getDevices(), whose productName is the phone's own
     * model name rather than a real accessory) filtered out. When there is more
     * than one real device, routing to "the" Bluetooth device is ambiguous —
     * Android's own picker (and every legacy startBluetoothSco()/
     * setBluetoothScoOn() call, which take no device argument at all) can land on
     * the wrong accessory instead of the glasses.
     */
    fun connectedBluetoothAudioDevices(context: Context): List<AudioDeviceInfo> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return emptyList()
        val all = try {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS) + am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        } catch (e: Exception) {
            return emptyList()
        }
        val bluetoothOnly = all.filter {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        }

        val selfNames = selfDeviceNames(context)

        val seen = LinkedHashMap<String, AudioDeviceInfo>()
        for (dev in bluetoothOnly) {
            val address = try { dev.address } catch (e: Exception) { null }
            val productName = dev.productName?.toString()

            // A phantom/self entry reports the PHONE'S OWN model/adapter name
            // here, not a real accessory — strip it so it never counts as a
            // second device.
            if (productName != null && selfNames.any { it.equals(productName, ignoreCase = true) }) {
                continue
            }

            val key = address?.takeIf { it.isNotBlank() } ?: productName ?: dev.id.toString()
            seen.putIfAbsent(key, dev)
        }
        return seen.values.toList()
    }

    /** Names that identify the phone itself, to filter out a self/phantom audio-device entry. */
    private fun selfDeviceNames(context: Context): Set<String> {
        val names = mutableSetOf<String>()
        Build.MODEL?.let { names.add(it) }
        Build.PRODUCT?.let { names.add(it) }
        Build.DEVICE?.let { names.add(it) }
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED
            ) {
                val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
                    ?.adapter ?: BluetoothAdapter.getDefaultAdapter()
                adapter?.name?.let { names.add(it) }
            }
        } catch (e: Exception) { /* ignore */ }
        return names
    }

    /**
     * True when two or more distinct Bluetooth audio devices are connected at once.
     * In that state the app cannot reliably guarantee audio goes to the glasses (see
     * [connectedBluetoothAudioDevices]), so callers should block starting a
     * conversation and tell the user to disconnect the other device instead of
     * silently risking misrouted audio.
     */
    fun hasMultipleBluetoothAudioDevicesConnected(context: Context): Boolean =
        connectedBluetoothAudioDevices(context).size > 1

    /** Human-readable names of the currently connected Bluetooth audio devices. */
    fun connectedBluetoothAudioDeviceNames(context: Context): List<String> =
        connectedBluetoothAudioDevices(context).map { it.productName?.toString() ?: "Unknown device" }
}
