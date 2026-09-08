package com.sdk.glassessdksample

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.sdk.glassessdksample.auth.AuthApi
import com.sdk.glassessdksample.databinding.ActivityLoginBinding
import com.sdk.glassessdksample.utils.SystemBarsInsets

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val authApi by lazy { AuthApi(this) }
    private var isSubmitting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBarsInsets.apply(this)

        binding.btnLogin.setOnClickListener {
            performLogin()
        }

        binding.signUpLink.setOnClickListener {
            startActivity(Intent(this, SignUpActivity::class.java))
        }
    }

    private fun performLogin() {
        if (isSubmitting) return

        val email = binding.etUserId.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()

        if (email.isEmpty() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            Toast.makeText(this, "Please enter a valid email address", Toast.LENGTH_SHORT).show()
            return
        }

        if (password.isEmpty()) {
            Toast.makeText(this, "Please enter your password", Toast.LENGTH_SHORT).show()
            return
        }

        setSubmitting(true)
        authApi.login(email, password) { result ->
            setSubmitting(false)
            when (result) {
                is AuthApi.Result.Success -> goToNextScreen()
                is AuthApi.Result.Error -> Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun goToNextScreen() {
        // Onboarding screen hidden: go straight to device selection.
        val intent = Intent(this, com.sdk.glassessdksample.ui.DeviceSelectionActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun setSubmitting(submitting: Boolean) {
        isSubmitting = submitting
        binding.btnLogin.isEnabled = !submitting
        binding.btnLogin.text = if (submitting) "Logging in…" else "Login"
    }
}
