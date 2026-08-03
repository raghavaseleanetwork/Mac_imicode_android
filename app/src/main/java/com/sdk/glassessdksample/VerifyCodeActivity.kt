package com.sdk.glassessdksample

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.sdk.glassessdksample.databinding.ActivityVerifyCodeBinding

class VerifyCodeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVerifyCodeBinding
    private lateinit var otpFields: List<EditText>
    private var phoneNumber: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityVerifyCodeBinding.inflate(layoutInflater)
            setContentView(binding.root)

            phoneNumber = intent.getStringExtra("PHONE_NUMBER") ?: ""

            otpFields = listOf(
                binding.etOtp1,
                binding.etOtp2,
                binding.etOtp3,
                binding.etOtp4
            )

            setupOtpFields()
            setupClickListeners()

            // Auto-focus first field
            binding.etOtp1.requestFocus()
        } catch (e: Exception) {
            e.printStackTrace()
            // If binding fails, go directly to MainActivity
            finish()
        }
    }

    private fun setupOtpFields() {
        otpFields.forEachIndexed { index, editText ->
            editText.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (s?.length == 1 && index < otpFields.size - 1) {
                        // Move to next field
                        otpFields[index + 1].requestFocus()
                    }
                }

                override fun afterTextChanged(s: Editable?) {}
            })

            // Handle backspace
            editText.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DEL && event.action == KeyEvent.ACTION_DOWN) {
                    if (editText.text.isEmpty() && index > 0) {
                        otpFields[index - 1].requestFocus()
                        otpFields[index - 1].setText("")
                        return@setOnKeyListener true
                    }
                }
                false
            }
        }
    }

    private fun setupClickListeners() {
        // Verify button click
        binding.btnVerify.setOnClickListener {
            val otp = getOtpCode()
            
            if (otp.length != 4) {
                Toast.makeText(this, "Please enter complete OTP", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            // Here you would verify the OTP with your backend
            // For now, we'll just navigate to MainActivity
            verifyOtp(otp)
        }

        // Resend code click
        binding.tvResendCode.setOnClickListener {
            Toast.makeText(this, "OTP resent to $phoneNumber", Toast.LENGTH_SHORT).show()
            // Here you would call your backend to resend OTP
            clearOtpFields()
        }
    }

    private fun getOtpCode(): String {
        return otpFields.joinToString("") { it.text.toString() }
    }

    private fun clearOtpFields() {
        otpFields.forEach { it.setText("") }
        binding.etOtp1.requestFocus()
    }

    private fun verifyOtp(otp: String) {
        // Here you would verify OTP with your backend
        // For demo purposes, accepting any 4-digit OTP
        
        Toast.makeText(this, "OTP Verified Successfully", Toast.LENGTH_SHORT).show()
        
        // Save login state
        val sharedPreferences = getSharedPreferences("IMI_PREFS", MODE_PRIVATE)
        sharedPreferences.edit().putBoolean("is_logged_in", true).apply()
        
        // After login the user must ALWAYS pick their device first — never drop
        // them straight onto a device home screen (this used to jump to Mark 2).
        val intent = Intent(this, com.sdk.glassessdksample.ui.DeviceSelectionActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    override fun onBackPressed() {
        super.onBackPressed()
        finish()
    }
}
