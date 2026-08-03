package com.sdk.glassessdksample.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.sdk.glassessdksample.R
import java.io.IOException
import com.sdk.glassessdksample.ui.DevicePreferenceManager
import com.sdk.glassessdksample.ui.DeviceType
import com.sdk.glassessdksample.ui.DeviceSelectionActivity
import com.sdk.glassessdksample.ui.Mark1MainActivity

class SplashActivity : AppCompatActivity() {
    
    private val SPLASH_DISPLAY_LENGTH = 2000L // 2 seconds
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        
        // Load logo from assets
        val logoImageView = findViewById<ImageView>(R.id.ivLogo)
        try {
            val inputStream = assets.open("logo.png")
            val bitmap = BitmapFactory.decodeStream(inputStream)
            logoImageView.setImageBitmap(bitmap)
            inputStream.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
        
        // Check authentication and onboarding status
        val sharedPreferences = getSharedPreferences("IMI_PREFS", MODE_PRIVATE)
        
        // Temporarily bypass login for testing - set to true to skip auth
        val skipAuth = false // Change to false to enable login flow
        
        val isLoggedIn = sharedPreferences.getBoolean("is_logged_in", false) || skipAuth
        val hasCompletedOnboarding = true // onboarding screen hidden

        // Kick off the one-time migration + pull of Quick Notes / Meeting Minutes
        // from the backend. Runs on a background thread; no-op if not signed in.
        if (isLoggedIn) {
            com.sdk.glassessdksample.ui.sync.BackendSync.syncOnLaunch(applicationContext)
        }
        
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val intent = when {
                    !isLoggedIn -> {
                        // User not logged in, go to login screen
                        Intent(this, com.sdk.glassessdksample.LoginActivity::class.java)
                    }
                    !hasCompletedOnboarding -> {
                        // User logged in but hasn't completed onboarding
                        Intent(this, OnboardingActivity::class.java)
                    }
                    else -> {
                        // Always show device selection so user can switch models
                        Intent(this, DeviceSelectionActivity::class.java)
                    }
                }
                // Clear any restored task (e.g. a Mark 2 MainActivity left over from a
                // previous run) so launching the app ALWAYS lands on the select-device
                // screen instead of resuming the last-used device's home screen.
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            } catch (e: Exception) {
                e.printStackTrace()
                // Fall back to device selection — NEVER to a device home screen.
                // (This previously fell back to Mark 2's MainActivity, which meant any
                // stray exception here silently skipped the select-device screen.)
                val fallbackIntent = Intent(this, DeviceSelectionActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(fallbackIntent)
                finish()
            }
        }, SPLASH_DISPLAY_LENGTH)
    }
}
