package com.sdk.glassessdksample

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.view.LayoutInflater
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.sdk.glassessdksample.auth.AuthApi
import com.sdk.glassessdksample.auth.SessionManager

/**
 * Dialog for configuring automatic photo upload.
 *
 * The destination is no longer user-configurable: photos go to the IMI Glass
 * backend under the signed-in user's account, so this only toggles the feature
 * and reports where uploads land.
 */
class UploadSettingsDialog(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("imi_prefs", AppCompatActivity.MODE_PRIVATE)

    fun show() {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_upload_settings, null)

        val enableSwitch = dialogView.findViewById<Switch>(R.id.uploadEnabledSwitch)
        val destinationLabel = dialogView.findViewById<TextView>(R.id.uploadDestinationLabel)
        val accountLabel = dialogView.findViewById<TextView>(R.id.uploadAccountLabel)

        enableSwitch.isChecked = prefs.getBoolean("auto_upload_enabled", false)
        destinationLabel.text = "${AuthApi.BASE_URL}/v1/upload/image"

        // Uploads authenticate as the logged-in user and no-op when signed out —
        // say so here rather than letting captures fail silently.
        val session = SessionManager(context)
        accountLabel.text = if (session.isLoggedIn) {
            "Signed in as ${session.userEmail ?: "your account"}"
        } else {
            "⚠️ Sign in to enable uploads — photos are saved locally until then."
        }

        AlertDialog.Builder(context)
            .setTitle("Image Upload Settings")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit()
                    .putBoolean("auto_upload_enabled", enableSwitch.isChecked)
                    .apply()

                android.widget.Toast.makeText(
                    context,
                    "Upload settings saved",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
