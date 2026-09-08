package com.sdk.glassessdksample

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.sdk.glassessdksample.databinding.ActivityCameraBinding
import com.sdk.glassessdksample.ui.BottomNavManager
import com.sdk.glassessdksample.ui.DeviceBindActivity
import com.sdk.glassessdksample.ui.gallery.GlassMediaGalleryActivity
import com.sdk.glassessdksample.utils.SafeBleCommandHelper
import com.sdk.glassessdksample.utils.SystemBarsInsets

class CameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBarsInsets.apply(this)

        setupUi()
        BottomNavManager.setup(binding.bottomNavigation, R.id.nav_more, this)
    }

    private fun setupUi() {
        binding.btnConnectGlasses.setOnClickListener {
            startActivity(Intent(this, DeviceBindActivity::class.java))
        }

        binding.btnCapturePhoto.setOnClickListener {
            if (!isBleConnected()) {
                showToast("Glasses not connected. Connect first.")
                return@setOnClickListener
            }
            SafeBleCommandHelper.takePhoto(
                onSuccess = {
                    binding.tvCameraStatus.text = "Photo command sent successfully"
                    showToast("Photo command sent")
                },
                onError = { error ->
                    binding.tvCameraStatus.text = "Photo failed: $error"
                    showToast("Photo failed: $error")
                }
            )
        }

        binding.btnStartVideo.setOnClickListener {
            if (!isBleConnected()) {
                showToast("Glasses not connected. Connect first.")
                return@setOnClickListener
            }
            SafeBleCommandHelper.startRecording(
                onSuccess = {
                    binding.tvCameraStatus.text = "Video recording started"
                    showToast("Recording started")
                },
                onError = { error ->
                    binding.tvCameraStatus.text = "Start failed: $error"
                    showToast("Start failed: $error")
                }
            )
        }

        binding.btnStopVideo.setOnClickListener {
            if (!isBleConnected()) {
                showToast("Glasses not connected. Connect first.")
                return@setOnClickListener
            }
            SafeBleCommandHelper.stopRecording(
                onSuccess = {
                    binding.tvCameraStatus.text = "Video recording stopped"
                    showToast("Recording stopped")
                },
                onError = { error ->
                    binding.tvCameraStatus.text = "Stop failed: $error"
                    showToast("Stop failed: $error")
                }
            )
        }

        binding.btnOpenGallery.setOnClickListener {
            startActivity(Intent(this, GlassMediaGalleryActivity::class.java))
        }

        binding.btnOpenVisionChat.setOnClickListener {
            startActivity(Intent(this, VisionChatActivity::class.java))
        }

        binding.btnAppPermissions.setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        }
    }

    private fun isBleConnected(): Boolean {
        return try {
            BleOperateManager.getInstance()?.isConnected ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
