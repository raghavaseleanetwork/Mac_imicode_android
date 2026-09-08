package com.sdk.glassessdksample

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.sdk.glassessdksample.databinding.ActivityProfileBinding
import com.sdk.glassessdksample.ui.DevicePreferenceManager
import com.sdk.glassessdksample.ui.DeviceType
import com.sdk.glassessdksample.ui.Mark1BottomNavManager
import com.sdk.glassessdksample.ui.UsageLimitManager
import com.sdk.glassessdksample.ui.UserMemoryActivity
import com.sdk.glassessdksample.utils.SystemBarsInsets

class ProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileBinding
    private lateinit var sharedPref: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBarsInsets.apply(this)

        sharedPref = getSharedPreferences("user_prefs", MODE_PRIVATE)

        setupUI()
        loadUserData()
        refreshUpgradeCard()
        updateCurrentDeviceLabel()
    }

    override fun onResume() {
        super.onResume()
        refreshUpgradeCard()
        updateCurrentDeviceLabel()
    }

    private fun updateCurrentDeviceLabel() {
        val type = DevicePreferenceManager.getDeviceType(this)
        binding.tvCurrentDevice.text = when (type) {
            DeviceType.MARK1 -> "Mark 1"
            DeviceType.MARK2 -> "Mark 2"
            else -> "Not selected"
        }
    }

    private fun showSwitchDeviceDialog() {
        val current = DevicePreferenceManager.getDeviceType(this) ?: DeviceType.MARK1
        var selected = current

        val sheet = BottomSheetDialog(this, R.style.SwitchDeviceBottomSheetStyle)
        val view = layoutInflater.inflate(R.layout.dialog_switch_device, null)
        sheet.setContentView(view)

        val cardMark1 = view.findViewById<LinearLayout>(R.id.cardMark1)
        val cardMark2 = view.findViewById<LinearLayout>(R.id.cardMark2)

        fun updateSelection(type: DeviceType) {
            selected = type
            cardMark1.setBackgroundResource(
                if (type == DeviceType.MARK1) R.drawable.bg_switch_device_card_selected
                else R.drawable.bg_switch_device_card_plain
            )
            cardMark2.setBackgroundResource(
                if (type == DeviceType.MARK2) R.drawable.bg_switch_device_card_selected
                else R.drawable.bg_switch_device_card_plain
            )
        }

        updateSelection(current)

        cardMark1.setOnClickListener { updateSelection(DeviceType.MARK1) }
        cardMark2.setOnClickListener { updateSelection(DeviceType.MARK2) }

        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSwitchConfirm).setOnClickListener {
            sheet.dismiss()
            DevicePreferenceManager.setDeviceType(this, selected)
            val target = if (selected == DeviceType.MARK2)
                MainActivity::class.java
            else
                com.sdk.glassessdksample.ui.Mark1MainActivity::class.java
            startActivity(Intent(this, target).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
        }

        view.findViewById<TextView>(R.id.btnNotNow).setOnClickListener {
            sheet.dismiss()
        }

        sheet.show()
    }

    private fun setupUI() {
        Mark1BottomNavManager.setup(this, binding.bottomNavigation, R.id.nav_profile)

        // Back button
        binding.backButton.setOnClickListener {
            finish()
        }

        // Edit profile button
        binding.editProfileButton.setOnClickListener {
            // Open user memory activity for editing
            startActivity(Intent(this, UserMemoryActivity::class.java))
        }

        // Switch Device (Mark 1 / Mark 2)
        binding.switchDeviceButton.setOnClickListener {
            showSwitchDeviceDialog()
        }

        // Manage Data button
        binding.manageDataButton.setOnClickListener {
            showManageDataDialog()
        }

        // Permission button
        binding.permissionButton.setOnClickListener {
            // Open app settings
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.fromParts("package", packageName, null)
            startActivity(intent)
        }

        // Settings button
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Dark Mode toggle
        binding.darkModeToggle.setOnCheckedChangeListener { _, isChecked ->
            sharedPref.edit().putBoolean("dark_mode", isChecked).apply()
            // In a real app, you'd apply the theme change here
        }

        // Rate Us button
        binding.rateUsButton.setOnClickListener {
            showRateUsDialog()
        }

        // Logout button
        binding.logoutButton.setOnClickListener {
            showLogoutConfirmation()
        }

        // Support link
        binding.supportLink.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.imi.glass/support"))
            startActivity(intent)
        }

        // Privacy Policy link
        binding.privacyLink.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.imi.glass/privacy"))
            startActivity(intent)
        }

        // About Us link
        binding.aboutLink.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.imi.glass/about"))
            startActivity(intent)
        }

        // Sign Up button
        binding.signUpButton.setOnClickListener {
            startActivity(Intent(this, SignUpActivity::class.java))
        }

        // Upgrade plan button on profile page
        binding.upgradePlanButton.setOnClickListener {
            UsageLimitManager.showUpgradeDialog(this) {
                refreshUpgradeCard()
            }
        }
    }

    private fun loadUserData() {
        // Prefer the API session (IMI_PREFS), fall back to legacy user_prefs values.
        val session = com.sdk.glassessdksample.auth.SessionManager(this)
        val userName = session.userName?.takeIf { it.isNotBlank() }
            ?: sharedPref.getString("user_name", "User") ?: "User"
        val userEmail = session.userEmail?.takeIf { it.isNotBlank() }
            ?: sharedPref.getString("user_email", "")?.takeIf { it.isNotBlank() }
            ?: "No email on file"
        val profileImageUrl = session.profileImageUrl?.takeIf { it.isNotBlank() }
            ?: sharedPref.getString("profile_image_url", "") ?: ""

        // Set user name and email
        binding.userName.text = userName
        binding.userEmail.text = userEmail

        // Load profile image if available
        if (profileImageUrl.isNotEmpty()) {
            Glide.with(this)
                .load(profileImageUrl)
                .circleCrop()
                .into(binding.profileImage)
        }

        // Load dark mode setting
        val isDarkMode = sharedPref.getBoolean("dark_mode", false)
        binding.darkModeToggle.isChecked = isDarkMode

        // Load version info
        binding.versionText.text = "v${getVersionName()}"
    }

    private fun refreshUpgradeCard() {
        val currentPlan = UsageLimitManager.getCurrentPlan(this)
        binding.upgradeCardPlanStatus.text = "Current plan: ${currentPlan.title} (${currentPlan.priceLabel})"

        when (currentPlan) {
            UsageLimitManager.Plan.FREE -> {
                binding.upgradeCardTitle.text = "Upgrade to Premium"
                binding.upgradeCardSubtitle.text = "Unlock advanced Glasses AI features"
                binding.upgradePlanButton.text = "Upgrade"
            }
            UsageLimitManager.Plan.PRO -> {
                binding.upgradeCardTitle.text = "Upgrade to Ultra"
                binding.upgradeCardSubtitle.text = "Boost your limits for voice, vision, and chat"
                binding.upgradePlanButton.text = "Upgrade"
            }
            UsageLimitManager.Plan.ULTRA -> {
                binding.upgradeCardTitle.text = "Ultra Plan Active"
                binding.upgradeCardSubtitle.text = "You are on the highest plan with maximum limits"
                binding.upgradePlanButton.text = "Manage"
            }
        }
    }

    private fun getVersionName(): String {
        return try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun showManageDataDialog() {
        val sheet = BottomSheetDialog(this, R.style.BottomSheetStyle)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_manage_data, null)
        sheet.setContentView(view)

        view.findViewById<ImageView>(R.id.btnCloseManageData).setOnClickListener {
            sheet.dismiss()
        }
        view.findViewById<Button>(R.id.btnDownloadData).setOnClickListener {
            showToast("Data export feature coming soon")
            sheet.dismiss()
        }
        view.findViewById<Button>(R.id.btnClearData).setOnClickListener {
            sheet.dismiss()
            clearAppData()
        }

        sheet.show()
    }

    private fun showRateUsDialog() {
        val sheet = BottomSheetDialog(this, R.style.BottomSheetStyle)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_rate_us, null)
        sheet.setContentView(view)

        view.findViewById<ImageView>(R.id.btnCloseRateUs).setOnClickListener {
            sheet.dismiss()
        }

        val stars = listOf(
            view.findViewById<ImageView>(R.id.star1),
            view.findViewById<ImageView>(R.id.star2),
            view.findViewById<ImageView>(R.id.star3),
            view.findViewById<ImageView>(R.id.star4),
            view.findViewById<ImageView>(R.id.star5)
        )
        stars.forEach { star ->
            star.setOnClickListener {
                // Any star tap sends the user to the Play Store to leave a rating.
                sheet.dismiss()
                openPlayStore()
            }
        }

        sheet.show()
    }

    private fun clearAppData() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Clear All Data?")
            .setMessage("This will delete all your saved preferences, conversation history, and settings. This action cannot be undone.")
            .setNegativeButton("DELETE") { dialog, which ->
                // Clear all SharedPreferences
                sharedPref.edit().clear().apply()
                getSharedPreferences("conversation_history", MODE_PRIVATE).edit().clear().apply()
                getSharedPreferences("user_memory_prefs", MODE_PRIVATE).edit().clear().apply()
                
                showToast("All data cleared successfully")
                finish()
            }
            .setPositiveButton("Cancel") { dialog, which ->
                dialog.dismiss()
            }
            .show()
    }

    private fun showLogoutConfirmation() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Logout")
            .setMessage("Are you sure you want to logout?")
            .setPositiveButton("YES, LOGOUT") { dialog, which ->
                performLogout()
            }
            .setNegativeButton("Cancel") { dialog, which ->
                dialog.dismiss()
            }
            .show()
    }

    private fun performLogout() {
        // Invalidate the server session and clear the local session (IMI_PREFS).
        // AuthApi.logout() clears the session regardless of the server outcome.
        com.sdk.glassessdksample.auth.AuthApi(this).logout {
            // Also clear any legacy profile fields kept in the user_prefs file.
            sharedPref.edit()
                .remove("user_name")
                .remove("user_email")
                .remove("auth_token")
                .remove("is_logged_in")
                .apply()

            // Go to login screen
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            finish()
        }
    }

    private fun openPlayStore() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")))
        } catch (e: Exception) {
            showToast("Play Store not available")
        }
    }

    private fun showToast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }
}
