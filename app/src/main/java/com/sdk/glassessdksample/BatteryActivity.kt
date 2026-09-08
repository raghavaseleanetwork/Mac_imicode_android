package com.sdk.glassessdksample

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.sdk.glassessdksample.databinding.ActivityBatteryBinding
import com.sdk.glassessdksample.ui.BatteryStatusStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.sdk.glassessdksample.utils.SystemBarsInsets

class BatteryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBatteryBinding

    private val batteryUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BatteryStatusStore.ACTION_BATTERY_UPDATED) {
                return
            }

            val level = intent.getIntExtra(BatteryStatusStore.EXTRA_BATTERY_LEVEL, -1)
            val updatedAt = intent.getLongExtra(BatteryStatusStore.EXTRA_BATTERY_UPDATED_AT, -1L)

            if (level in 0..100) {
                updateBatteryUi(level, updatedAt.takeIf { it > 0L })
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBatteryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBarsInsets.apply(this)

        setupUi()
        loadInitialBattery()
    }

    override fun onStart() {
        super.onStart()
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(batteryUpdateReceiver, IntentFilter(BatteryStatusStore.ACTION_BATTERY_UPDATED))
    }

    override fun onResume() {
        super.onResume()
        loadLatestBattery(showToastIfUnavailable = false)
    }

    override fun onStop() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(batteryUpdateReceiver)
        super.onStop()
    }

    private fun setupUi() {
        binding.btnBackBattery.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        binding.btnRefreshBattery.setOnClickListener {
            loadLatestBattery(showToastIfUnavailable = true)
        }
    }

    private fun loadInitialBattery() {
        val intentBattery = intent.extras?.let {
            if (it.containsKey(BatteryStatusStore.EXTRA_BATTERY_LEVEL)) {
                it.getInt(BatteryStatusStore.EXTRA_BATTERY_LEVEL)
            } else {
                null
            }
        }

        val intentUpdatedAt = intent.extras?.let {
            if (it.containsKey(BatteryStatusStore.EXTRA_BATTERY_UPDATED_AT)) {
                it.getLong(BatteryStatusStore.EXTRA_BATTERY_UPDATED_AT)
            } else {
                null
            }
        }

        if (intentBattery != null && intentBattery in 0..100) {
            updateBatteryUi(intentBattery, intentUpdatedAt)
        } else {
            loadLatestBattery(showToastIfUnavailable = false)
        }
    }

    private fun loadLatestBattery(showToastIfUnavailable: Boolean) {
        val level = BatteryStatusStore.getBatteryLevel(this)
        val updatedAt = BatteryStatusStore.getBatteryUpdatedAt(this)

        if (level == null) {
            showUnavailableState()
            if (showToastIfUnavailable) {
                Toast.makeText(
                    this,
                    "Battery data is not available yet. Connect glasses and wait for sync.",
                    Toast.LENGTH_SHORT
                ).show()
            }
            return
        }

        updateBatteryUi(level, updatedAt)
    }

    private fun updateBatteryUi(level: Int, updatedAt: Long?) {
        val safeLevel = level.coerceIn(0, 100)
        val tintColor = ContextCompat.getColor(this, getBatteryTintColorRes(safeLevel))

        binding.tvBatteryPercent.text = "$safeLevel%"
        binding.tvBatteryStatus.text = getBatteryStateLabel(safeLevel)
        binding.progressBattery.progress = safeLevel
        binding.progressBattery.progressTintList = ColorStateList.valueOf(tintColor)
        binding.tvBatteryPercent.setTextColor(tintColor)
        binding.ivBatteryIcon.imageTintList = ColorStateList.valueOf(tintColor)
        binding.tvBatteryHint.text = getBatteryHint(safeLevel)
        binding.tvBatteryUpdated.text = formatLastSynced(updatedAt)
    }

    private fun showUnavailableState() {
        val neutralColor = ContextCompat.getColor(this, R.color.text_secondary)

        binding.tvBatteryPercent.text = "--%"
        binding.tvBatteryStatus.text = "Waiting for battery data"
        binding.progressBattery.progress = 0
        binding.progressBattery.progressTintList = ColorStateList.valueOf(neutralColor)
        binding.tvBatteryPercent.setTextColor(neutralColor)
        binding.ivBatteryIcon.imageTintList = ColorStateList.valueOf(neutralColor)
        binding.tvBatteryHint.text = "Battery updates appear here after the glasses send status."
        binding.tvBatteryUpdated.text = "Not synced yet"
    }

    private fun getBatteryStateLabel(level: Int): String {
        return when {
            level >= 80 -> "Excellent"
            level >= 55 -> "Good"
            level >= 30 -> "Medium"
            level >= 15 -> "Low"
            else -> "Critical"
        }
    }

    private fun getBatteryHint(level: Int): String {
        return when {
            level >= 80 -> "Battery is healthy for extended use."
            level >= 55 -> "Battery is stable for regular usage."
            level >= 30 -> "Plan a recharge soon for longer sessions."
            level >= 15 -> "Low battery. Keep charging access nearby."
            else -> "Critical battery. Charge the glasses now."
        }
    }

    private fun getBatteryTintColorRes(level: Int): Int {
        return when {
            level >= 30 -> R.color.glass_primary
            level >= 15 -> R.color.warning_muted
            else -> R.color.error_muted
        }
    }

    private fun formatLastSynced(updatedAt: Long?): String {
        if (updatedAt == null) {
            return "Not synced yet"
        }

        val formatter = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
        return "Last synced: ${formatter.format(Date(updatedAt))}"
    }
}
