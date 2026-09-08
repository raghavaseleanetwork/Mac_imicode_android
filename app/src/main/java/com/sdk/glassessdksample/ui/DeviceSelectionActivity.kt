package com.sdk.glassessdksample.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.sdk.glassessdksample.R
import com.sdk.glassessdksample.utils.SystemBarsInsets

class DeviceSelectionActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_selection)
        SystemBarsInsets.apply(this)

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

        val selectMark2 = View.OnClickListener {
            DevicePreferenceManager.setDeviceType(this, DeviceType.MARK2)
            startActivity(Intent(this, com.sdk.glassessdksample.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
        }

        btnMark2.setOnClickListener(selectMark2)
        btnMark2Action.setOnClickListener(selectMark2)
    }
}
