package com.sdk.glassessdksample.ui

import android.content.Context

enum class DeviceType { MARK1, MARK2 }

object DevicePreferenceManager {
    private const val PREFS_NAME = "device_prefs"
    private const val KEY_DEVICE_TYPE = "device_type"

    fun setDeviceType(context: Context, type: DeviceType) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_DEVICE_TYPE, type.name).apply()
    }

    fun clearDeviceType(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_DEVICE_TYPE).apply()
    }

    fun getDeviceType(context: Context): DeviceType? {
        val name = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DEVICE_TYPE, null) ?: return null
        return try { DeviceType.valueOf(name) } catch (e: IllegalArgumentException) { null }
    }
}
