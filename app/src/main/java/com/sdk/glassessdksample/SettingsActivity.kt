package com.sdk.glassessdksample

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import com.sdk.glassessdksample.databinding.ActivitySettingsBinding
import com.sdk.glassessdksample.ui.AiResponsePrefs
import com.sdk.glassessdksample.ui.Mark1BottomNavManager
import com.sdk.glassessdksample.ui.GeminiLiveService
import com.sdk.glassessdksample.ui.HotHelper
import com.sdk.glassessdksample.ui.ModelProvider
import com.sdk.glassessdksample.ui.UsageLimitManager
import com.sdk.glassessdksample.wakeword.WakeWordEngine
import com.sdk.glassessdksample.wakeword.WakeWordEngineSettings

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var gmailService: GmailService

    private val gmailSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        try {
            GoogleSignIn.getSignedInAccountFromIntent(result.data).getResult(ApiException::class.java)
            Toast.makeText(this, "Gmail connected", Toast.LENGTH_SHORT).show()
        } catch (e: ApiException) {
            Toast.makeText(this, "Gmail connection failed: ${e.statusCode}", Toast.LENGTH_LONG).show()
        }
        refreshGmailStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        gmailService = GmailService(this)
        setupSettings()
        setupAiPreferences()
        setupGmailConnect()
        Mark1BottomNavManager.setup(this, binding.bottomNavigation, R.id.nav_profile)
    }

    private fun setupGmailConnect() {
        refreshGmailStatus()
        binding.btnConnectGmail.setOnClickListener {
            gmailSignInLauncher.launch(gmailService.signInClient().signInIntent)
        }
    }

    private fun refreshGmailStatus() {
        if (gmailService.isGmailReady()) {
            val email = GoogleSignIn.getLastSignedInAccount(this)?.email ?: ""
            binding.tvGmailStatus.text = "Connected as $email"
            binding.btnConnectGmail.text = "Reconnect Gmail"
        } else {
            binding.tvGmailStatus.text = "Not connected"
            binding.btnConnectGmail.text = "Connect Gmail"
        }
    }

    /**
     * Wire the AI Preferences card: answer length, tone, greeting style (+ custom
     * greeting text) and whether to address the user by name. Selections persist
     * via [AiResponsePrefs] and feed the live system instruction and greeting.
     */
    private fun setupAiPreferences() {
        bindSpinner(
            spinner = binding.spinnerResponseLength,
            labels = AiResponsePrefs.lengthLabels,
            selectedIndex = AiResponsePrefs.getLength(this).ordinal
        ) { index -> AiResponsePrefs.setLength(this, AiResponsePrefs.Length.values()[index]) }

        bindSpinner(
            spinner = binding.spinnerResponseTone,
            labels = AiResponsePrefs.toneLabels,
            selectedIndex = AiResponsePrefs.getTone(this).ordinal
        ) { index -> AiResponsePrefs.setTone(this, AiResponsePrefs.Tone.values()[index]) }

        bindSpinner(
            spinner = binding.spinnerGreetingStyle,
            labels = AiResponsePrefs.greetingLabels,
            selectedIndex = AiResponsePrefs.getGreetingStyle(this).ordinal
        ) { index ->
            val style = AiResponsePrefs.GreetingStyle.values()[index]
            AiResponsePrefs.setGreetingStyle(this, style)
            binding.layoutCustomGreeting.visibility =
                if (style == AiResponsePrefs.GreetingStyle.CUSTOM) View.VISIBLE else View.GONE
        }

        // Custom greeting text.
        binding.layoutCustomGreeting.visibility =
            if (AiResponsePrefs.getGreetingStyle(this) == AiResponsePrefs.GreetingStyle.CUSTOM)
                View.VISIBLE else View.GONE
        binding.etCustomGreeting.setText(AiResponsePrefs.getCustomGreeting(this))
        binding.etCustomGreeting.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                AiResponsePrefs.setCustomGreeting(this, binding.etCustomGreeting.text.toString())
            }
        }

        // Use-my-name switch.
        binding.switchUseName.isChecked = AiResponsePrefs.getUseName(this)
        binding.switchUseName.setOnCheckedChangeListener { _, isChecked ->
            AiResponsePrefs.setUseName(this, isChecked)
        }
    }

    /** Populate a spinner with white-on-dark labels and persist the chosen index. */
    private fun bindSpinner(
        spinner: Spinner,
        labels: List<String>,
        selectedIndex: Int,
        onSelected: (Int) -> Unit
    ) {
        val adapter = ArrayAdapter(this, R.layout.item_spinner_white, labels)
        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown_white)
        spinner.adapter = adapter
        spinner.setSelection(selectedIndex.coerceIn(0, labels.lastIndex), false)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                onSelected(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    override fun onPause() {
        super.onPause()
        // Persist any in-progress custom greeting edit when leaving the screen.
        AiResponsePrefs.setCustomGreeting(this, binding.etCustomGreeting.text.toString())
    }

    override fun onResume() {
        super.onResume()
        if (::gmailService.isInitialized) refreshGmailStatus()
        refreshUsageUi()
        updateWakeEngineUi(WakeWordEngineSettings.getSelectedEngine(this))
    }

    private fun setupSettings() {
        val prefs = getSharedPreferences("imi_prefs", MODE_PRIVATE)

        // Continuous Chat toggle
        binding.switchContinuousChat.isChecked = prefs.getBoolean("continuous_chat", true)
        binding.switchContinuousChat.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("continuous_chat", isChecked).apply()
        }

        // Hidden model/wake engine buttons still wired so existing logic works
        val currentModel = GeminiLiveService.getSavedModelProvider(this)
        updateModelUi(currentModel)

        val currentWakeEngine = WakeWordEngineSettings.getSelectedEngine(this)
        updateWakeEngineUi(currentWakeEngine)

        binding.btnModelGpt.setOnClickListener { setModel(ModelProvider.GPT_REALTIME) }
        binding.btnModelGemini.setOnClickListener { setModel(ModelProvider.GEMINI_LIVE) }
        binding.btnWakeEngineCustom.setOnClickListener { setWakeWordEngine(WakeWordEngine.CUSTOM_ONNX) }
        binding.btnWakeEngineSnowboy.setOnClickListener { setWakeWordEngine(WakeWordEngine.SNOWBOY) }

        binding.switchGeminiAck.isChecked = prefs.getBoolean("useGeminiAck", true)
        binding.switchGeminiAck.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("useGeminiAck", isChecked).apply()
        }

        binding.btnOpenPermissionSettings.setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        }

        binding.btnUpgradePlan.setOnClickListener {
            UsageLimitManager.showUpgradeDialog(this) {
                refreshUsageUi()
            }
        }

        refreshUsageUi()
    }

    private fun setWakeWordEngine(engine: WakeWordEngine) {
        val current = WakeWordEngineSettings.getSelectedEngine(this)
        if (current == engine) return

        WakeWordEngineSettings.setSelectedEngine(this, engine)
        try {
            HotHelper.getInstance(applicationContext).setWakeWordEngine(engine)
        } catch (_: Exception) {
            // HotHelper may not be active yet; preference is already persisted.
        }
        updateWakeEngineUi(engine)

        Toast.makeText(this, "Wake engine set to ${engine.displayName}", Toast.LENGTH_SHORT).show()
    }

    private fun setModel(provider: ModelProvider) {
        val current = GeminiLiveService.getSavedModelProvider(this)
        if (current == provider) return

        GeminiLiveService.saveModelProvider(this, provider)
        updateModelUi(provider)

        val name = if (provider == ModelProvider.GPT_REALTIME) "GPT" else "Gemini"
        Toast.makeText(this, "AI model switched to $name", Toast.LENGTH_SHORT).show()
    }

    private fun updateModelUi(provider: ModelProvider) {
        if (provider == ModelProvider.GPT_REALTIME) {
            binding.btnModelGpt.isEnabled = false
            binding.btnModelGemini.isEnabled = true
            binding.tvCurrentModel.text = "Current model: GPT"
        } else {
            binding.btnModelGpt.isEnabled = true
            binding.btnModelGemini.isEnabled = false
            binding.tvCurrentModel.text = "Current model: Gemini"
        }
    }

    private fun updateWakeEngineUi(engine: WakeWordEngine) {
        binding.btnWakeEngineCustom.isEnabled = engine != WakeWordEngine.CUSTOM_ONNX
        binding.btnWakeEngineSnowboy.isEnabled = engine != WakeWordEngine.SNOWBOY
        binding.tvCurrentWakeEngine.text = "Current engine: ${engine.displayName}"
    }

    private fun refreshUsageUi() {
        val snapshot = UsageLimitManager.getSnapshot(this)

        binding.tvCurrentPlan.text = snapshot.plan.title
        binding.tvPlanPrice.text = "${snapshot.plan.title} – ${snapshot.plan.priceLabel}/month"

        bindUsage(
            usage = snapshot.voice,
            callsView = binding.tvVoiceCalls,
            progressView = binding.progressVoiceCalls,
            remainingView = binding.tvVoiceRemaining
        )

        bindUsage(
            usage = snapshot.seeing,
            callsView = binding.tvSeeingCalls,
            progressView = binding.progressSeeingCalls,
            remainingView = binding.tvSeeingRemaining
        )

        bindUsage(
            usage = snapshot.chat,
            callsView = binding.tvChatCalls,
            progressView = binding.progressChatCalls,
            remainingView = binding.tvChatRemaining
        )
    }

    private fun bindUsage(
        usage: UsageLimitManager.FeatureUsage,
        callsView: TextView,
        progressView: ProgressBar,
        remainingView: TextView
    ) {
        callsView.text = "${usage.used} / ${usage.limit}"
        progressView.progress = usage.progressPercent
        remainingView.text = "Remaining : ${usage.remaining}"
    }
}
