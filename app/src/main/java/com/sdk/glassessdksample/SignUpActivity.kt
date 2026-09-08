package com.sdk.glassessdksample

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.sdk.glassessdksample.auth.AuthApi
import com.sdk.glassessdksample.databinding.ActivitySignUpBinding
import com.sdk.glassessdksample.utils.SystemBarsInsets

class SignUpActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySignUpBinding
    private val authApi by lazy { AuthApi(this) }
    private var isSubmitting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignUpBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBarsInsets.apply(this)

        setupUI()
    }

    private fun setupUI() {
        // Sign Up button
        binding.signUpButton.setOnClickListener {
            performSignUp()
        }

        // Login link
        binding.loginLink.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    private fun performSignUp() {
        if (isSubmitting) return

        val fullName = binding.fullNameInput.text.toString().trim()
        val email = binding.emailInput.text.toString().trim()
        val password = binding.passwordInput.text.toString().trim()
        val confirmPassword = binding.confirmPasswordInput.text.toString().trim()

        // Validation
        if (fullName.isEmpty()) {
            showError("Please enter your full name")
            return
        }

        if (email.isEmpty() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            showError("Please enter a valid email address")
            return
        }

        if (password.isEmpty() || password.length < 6) {
            showError("Password must be at least 6 characters")
            return
        }

        if (password != confirmPassword) {
            showError("Passwords do not match")
            return
        }

        setSubmitting(true)
        authApi.register(fullName, email, password, confirmPassword) { result ->
            when (result) {
                is AuthApi.Result.Success -> {
                    // Registration succeeds but returns no token — log in to obtain one.
                    loginAfterRegister(email, password)
                }
                is AuthApi.Result.Error -> {
                    setSubmitting(false)
                    showError(result.message)
                }
            }
        }
    }

    private fun loginAfterRegister(email: String, password: String) {
        authApi.login(email, password) { result ->
            setSubmitting(false)
            when (result) {
                is AuthApi.Result.Success -> {
                    showSuccess("Account created successfully!")
                    goToNextScreen()
                }
                is AuthApi.Result.Error -> {
                    // Account exists now; send them to login to finish signing in.
                    showError("Account created. Please log in. (${result.message})")
                    startActivity(Intent(this, LoginActivity::class.java))
                    finish()
                }
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
        binding.signUpButton.isEnabled = !submitting
        binding.signUpButton.text = if (submitting) "Creating account…" else "Create Account"
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun showSuccess(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
