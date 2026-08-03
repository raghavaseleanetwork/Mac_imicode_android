package com.sdk.glassessdksample.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.sdk.glassessdksample.R

class DeviceSelectionActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_selection)

        val btnMark1 = findViewById<LinearLayout>(R.id.btnSelectMark1)
        val btnMark1Action = findViewById<Button>(R.id.btnSelectMark1Action)
        val btnMark2 = findViewById<LinearLayout>(R.id.btnSelectMark2)
        val btnMark2Action = findViewById<Button>(R.id.btnSelectMark2Action)

        val selectMark1 = View.OnClickListener {
            DevicePreferenceManager.setDeviceType(this, DeviceType.MARK1)
            startActivity(Intent(this, Mark1MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
        }

        btnMark1.setOnClickListener(selectMark1)
        btnMark1Action.setOnClickListener(selectMark1)

        // ── Mark II temporarily disabled ──────────────────────────────────────
        // The card stays VISIBLE (so users can see the model exists) but is not
        // selectable: only Mark 1 is supported in this build. To re-enable, delete
        // this block and restore the selectMark2 click listeners below.
        // Dim to read as unavailable, but keep the views clickable so a tap can
        // explain why (a disabled View swallows clicks and would feel broken).
        btnMark2.alpha = 0.45f
        btnMark2Action.alpha = 0.45f
        btnMark2Action.text = "Coming Soon"
        val notAvailable = View.OnClickListener {
            Toast.makeText(this, "Mark II isn't available yet — please select Mark I.", Toast.LENGTH_SHORT).show()
        }
        btnMark2.setOnClickListener(notAvailable)
        btnMark2Action.setOnClickListener(notAvailable)
    }
}
