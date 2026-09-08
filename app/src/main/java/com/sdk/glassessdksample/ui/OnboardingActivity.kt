package com.sdk.glassessdksample.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.sdk.glassessdksample.R
import com.sdk.glassessdksample.databinding.ActivityOnboardingBinding
import com.sdk.glassessdksample.utils.SystemBarsInsets

class OnboardingActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityOnboardingBinding
    private lateinit var adapter: OnboardingAdapter
    
    private val onboardingItems = listOf(
        OnboardingItem(
            "onboarding1.png",
            "Welcome to IMI Glasses AI",
            "Your smart companion for everyday life, right through your glasses."
        ),
        OnboardingItem(
            "onboarding2.png",
            "Find Things Easily",
            "Just ask, and IMI Glasses AI will help you locate keys, wallet, phone, and more."
        ),
        OnboardingItem(
            "onboarding3.png",
            "Hands-Free AI Help",
            "Voice commands make it easy to take notes, set reminders, and get instant answers on the go."
        )
    )
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBarsInsets.apply(this)
        
        setupViewPager()
        setupButtons()
        setupIndicators()
    }
    
    private fun setupViewPager() {
        adapter = OnboardingAdapter(onboardingItems)
        binding.viewPager.adapter = adapter
        
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                updateUI(position)
            }
        })
    }
    
    private fun setupButtons() {
        binding.btnSkip.setOnClickListener {
            completeOnboarding()
        }
        
        binding.btnNext.setOnClickListener {
            if (binding.viewPager.currentItem < onboardingItems.size - 1) {
                binding.viewPager.currentItem += 1
            } else {
                completeOnboarding()
            }
        }
        
        binding.btnPrevious.setOnClickListener {
            if (binding.viewPager.currentItem > 0) {
                binding.viewPager.currentItem -= 1
            }
        }
    }
    
    private fun setupIndicators() {
        updateUI(0)
    }
    
    private fun updateUI(position: Int) {
        // Update page counter
        binding.tvPageIndicator.text = "${position + 1}/${onboardingItems.size}"
        
        // Show/hide previous button
        binding.btnPrevious.visibility = if (position > 0) View.VISIBLE else View.INVISIBLE
        
        // Update navigation dots
        binding.indicator1.alpha = if (position == 0) 1.0f else 0.3f
        binding.indicator2.alpha = if (position == 1) 1.0f else 0.3f
        binding.indicator3.alpha = if (position == 2) 1.0f else 0.3f
    }
    
    private fun completeOnboarding() {
        // Save onboarding completion status
        val sharedPreferences = getSharedPreferences("IMI_PREFS", MODE_PRIVATE)
        sharedPreferences.edit().putBoolean("onboarding_completed", true).apply()
        
        // Navigate to device selection ("Choose Your Device")
        startActivity(Intent(this, DeviceSelectionActivity::class.java))
        finish()
    }
}

data class OnboardingItem(
    val imagePath: String,
    val title: String,
    val description: String
)
