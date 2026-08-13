package com.sdk.glassessdksample

import android.util.Log
import com.google.firebase.ktx.Firebase
import com.google.firebase.remoteconfig.ktx.remoteConfig
import com.google.firebase.remoteconfig.ktx.remoteConfigSettings

object RemoteConfigManager {
    private const val TAG = "RemoteConfigManager"

    // Keys matching what you set in Firebase Remote Config
    private const val KEY_GEMINI   = "gemini_api_key"
    private const val KEY_OPENAI   = "openai_api_key"
    private const val KEY_PICOVOICE = "picovoice_key"
    private const val KEY_YOUTUBE  = "youtube_api_key"
    private const val KEY_AUDD     = "audd_api_key"

    private val remoteConfig = Firebase.remoteConfig

    init {
        val settings = remoteConfigSettings {
            minimumFetchIntervalInSeconds = 3600 // 1 hour cache
        }
        remoteConfig.setConfigSettingsAsync(settings)
        // Default fallback values (empty — forces fetch from Firebase)
        remoteConfig.setDefaultsAsync(
            mapOf(
                KEY_GEMINI    to "",
                KEY_OPENAI    to "",
                KEY_PICOVOICE to "",
                KEY_YOUTUBE   to "",
                KEY_AUDD      to ""
            )
        )
    }

    /**
     * Call this once at app startup (e.g., in MainActivity.onCreate)
     * Fetches latest values from Firebase Remote Config.
     */
    fun fetchAndActivate(onComplete: (success: Boolean) -> Unit = {}) {
        remoteConfig.fetchAndActivate()
            .addOnSuccessListener {
                Log.d(TAG, "✅ Remote config fetched and activated")
                onComplete(true)
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "⚠️ Remote config fetch failed, using cached/defaults: ${e.message}")
                onComplete(false)
            }
    }

    val geminiApiKey: String
        get() = remoteConfig.getString(KEY_GEMINI)

    val openAiApiKey: String
        get() = remoteConfig.getString(KEY_OPENAI)

    val picovoiceAccessKey: String
        get() = remoteConfig.getString(KEY_PICOVOICE)

    val youtubeApiKey: String
        get() = remoteConfig.getString(KEY_YOUTUBE)

    /** AudD music recognition token — set this in the Firebase console. */
    val auddApiKey: String
        get() = remoteConfig.getString(KEY_AUDD)
}
