package com.sdk.glassessdksample.ui

import android.content.Context

enum class DeviceType { MARK1, MARK2 }

object DevicePreferenceManager {
    private const val PREFS_NAME = "device_prefs"
    private const val KEY_DEVICE_TYPE = "device_type"

    /**
     * Mark II is temporarily disabled — only Mark 1 is supported in this build.
     * While this is true, MARK2 can neither be saved nor read back, so no code path
     * (bottom nav, profile switch, services, stale preferences from an older
     * install) can route the user to a Mark 2 screen.
     *
     * To re-enable Mark II: set this to false.
     */
    private const val MARK2_DISABLED = true

    fun setDeviceType(context: Context, type: DeviceType) {
        val effective = if (MARK2_DISABLED && type == DeviceType.MARK2) DeviceType.MARK1 else type
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_DEVICE_TYPE, effective.name).apply()
    }

    fun clearDeviceType(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_DEVICE_TYPE).apply()
    }

    fun getDeviceType(context: Context): DeviceType? {
        val name = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DEVICE_TYPE, null) ?: return null
        val stored = try { DeviceType.valueOf(name) } catch (e: IllegalArgumentException) { null }
        // Coerce a stale MARK2 preference (e.g. from a previous install) to MARK1
        // so no screen can route to the disabled Mark 2 experience.
        return if (MARK2_DISABLED && stored == DeviceType.MARK2) DeviceType.MARK1 else stored
    }
}
