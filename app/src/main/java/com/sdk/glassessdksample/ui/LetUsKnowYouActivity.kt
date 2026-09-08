package com.sdk.glassessdksample.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import com.sdk.glassessdksample.R
import com.sdk.glassessdksample.utils.SystemBarsInsets

class LetUsKnowYouActivity : AppCompatActivity() {

    companion object {
        // Kept in sync with `questions` below so callers (e.g. the home screen's
        // "Let Us Know You" subtitle) never have to hardcode this count themselves.
        const val QUESTION_COUNT = 7
    }

    private data class ProfileQuestion(val prompt: String)

    private val questions = listOf(
        ProfileQuestion("What name should I call you, and where are you currently based?"),
        ProfileQuestion("What do you do now, and what are your main goals for the next few months?"),
        ProfileQuestion("Which language should I use most, and do you prefer brief, balanced, or detailed answers?"),
        ProfileQuestion("What topics do you care about most these days, and what should I proactively help you with?"),
        ProfileQuestion("Tell me the things you really like, including hobbies, food, and routines that make your day better."),
        ProfileQuestion("Tell me what you dislike or want me to avoid, including topics, tone, or habits."),
        ProfileQuestion("Anything important I should always remember so I can support you better every day?")
    )

    private lateinit var questionProgress: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var voiceState: TextView
    private lateinit var lastAnswerText: TextView
    private lateinit var btnBack: Button
    private lateinit var btnSkip: Button
    private lateinit var btnRepeat: Button
    private lateinit var btnSpeakAnswer: Button
    private lateinit var btnStartConversation: Button
    private lateinit var btnFinishProfile: Button

    private val answers = mutableMapOf<Int, String>()
    private var currentIndex = 0

    private val uiHandler = Handler(Looper.getMainLooper())
    private var liveService: GeminiLiveService? = null
    private var liveConnected = false
    private var awaitingAnswerForIndex: Int? = null
    private var lastCapturedTranscript: String = ""
    private var previousModelProvider: ModelProvider = ModelProvider.GPT_REALTIME

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startWhisperInterview()
        } else {
            voiceState.text = "Microphone permission denied. Enable it to use Whisper voice interview."
            Toast.makeText(this, "Microphone permission is required for voice answers", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_let_us_know_you)
        SystemBarsInsets.apply(this)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Let Us Know You"

        questionProgress = findViewById(R.id.tvQuestionProgress)
        progressBar = findViewById(R.id.progressQuestions)
        voiceState = findViewById(R.id.tvVoiceState)
        lastAnswerText = findViewById(R.id.tvLastAnswer)
        btnBack = findViewById(R.id.btnQuestionBack)
        btnSkip = findViewById(R.id.btnQuestionSkip)
        btnRepeat = findViewById(R.id.btnRepeatQuestion)
        btnSpeakAnswer = findViewById(R.id.btnSpeakAnswer)
        btnStartConversation = findViewById(R.id.btnStartVoiceMode)
        btnFinishProfile = findViewById(R.id.btnFinishProfile)

        progressBar.max = questions.size

        preloadFromSavedProfile()
        renderQuestionState()
        updateLastAnswerView()

        btnBack.setOnClickListener {
            if (currentIndex > 0) {
                currentIndex--
                renderQuestionState()
                askCurrentQuestion()
            }
        }

        btnSkip.setOnClickListener {
            answers.remove(currentIndex)
            if (currentIndex < questions.lastIndex) {
                currentIndex++
                renderQuestionState()
                askCurrentQuestion()
            } else {
                saveProfileAndFinish()
            }
        }

        btnRepeat.setOnClickListener {
            askCurrentQuestion()
        }

        btnSpeakAnswer.setOnClickListener {
            beginAnswerWindow()
        }

        btnStartConversation.setOnClickListener {
            ensureMicAndStart()
        }

        btnFinishProfile.setOnClickListener {
            saveProfileAndFinish()
        }

        voiceState.text = "Whisper voice interview is ready. Tap Start Voice Mode."
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            liveService?.setVisionTranscriptionListener(null)
            liveService?.stopLiveConversation()
        } catch (_: Exception) {
        }
        GeminiLiveService.saveModelProvider(this, previousModelProvider)
    }

    private fun preloadFromSavedProfile() {
        val memory = UserMemoryManager(this).getUserMemory()

        if (memory.userName.isNotBlank()) answers[0] = memory.userName
        if (memory.location.isNotBlank()) {
            answers[0] = listOf(memory.userName, memory.location).filter { it.isNotBlank() }.joinToString(", ")
        }
        if (memory.occupation.isNotBlank() || memory.importantNotes.isNotEmpty()) {
            answers[1] = listOf(memory.occupation, memory.importantNotes.firstOrNull().orEmpty())
                .filter { it.isNotBlank() }
                .joinToString(". ")
        }
        if (memory.preferredLanguage.isNotBlank() || memory.preferredResponseStyle != UserMemory.ResponseStyle.BALANCED) {
            answers[2] = "Language ${memory.preferredLanguage}, style ${memory.preferredResponseStyle.name.lowercase()}"
        }
        if (memory.interests.isNotEmpty() || memory.importantNotes.size > 1) {
            answers[3] = (memory.interests + memory.importantNotes.drop(1)).take(4).joinToString(", ")
        }
        if (memory.likes.isNotEmpty()) answers[4] = memory.likes.joinToString(", ")
        if (memory.dislikes.isNotEmpty()) answers[5] = memory.dislikes.joinToString(", ")
        if (memory.importantNotes.isNotEmpty()) answers[6] = memory.importantNotes.joinToString(" | ")
    }

    private fun renderQuestionState() {
        questionProgress.text = "Question ${currentIndex + 1} of ${questions.size}"
        progressBar.progress = currentIndex + 1
        updateLastAnswerView()
        btnBack.isEnabled = currentIndex > 0
    }

    private fun updateLastAnswerView() {
        val current = answers[currentIndex]
        lastAnswerText.text = if (current.isNullOrBlank()) {
            "Your answer: waiting for Whisper input"
        } else {
            "Your answer: $current"
        }
    }

    private fun ensureMicAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startWhisperInterview()
        } else {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startWhisperInterview() {
        if (liveConnected) {
            askCurrentQuestion()
            return
        }

        previousModelProvider = GeminiLiveService.getSavedModelProvider(this)
        GeminiLiveService.saveModelProvider(this, ModelProvider.GPT_REALTIME)

        val callbacks = object : GeminiLiveService.GeminiLiveCallbacks {
            override fun onTranscriptionUpdate(input: String, output: String, isFinal: Boolean) {
                if (isFinal && input.isNotBlank()) {
                    processTranscription(input)
                }
            }

            override fun onTurnComplete(fullInput: String, fullOutput: String) {
                if (fullInput.isNotBlank()) {
                    processTranscription(fullInput)
                }
            }

            override fun onToolCall(toolName: String, args: Map<String, Any>): String = "Tool disabled in profile interview mode"

            override fun onAudioPlaybackStart() {
                runOnUiThread {
                    voiceState.text = "AI is speaking question ${currentIndex + 1}..."
                }
            }

            override fun onAudioPlaybackEnd() {
                runOnUiThread {
                    liveService?.muteOutput()
                    beginAnswerWindow()
                }
            }

            override fun onError(error: String) {
                runOnUiThread {
                    voiceState.text = "Voice session error: $error"
                }
            }

            override fun onConnectionStatusChanged(isConnected: Boolean) {
                runOnUiThread {
                    liveConnected = isConnected
                    voiceState.text = if (isConnected) {
                        "Whisper voice session connected."
                    } else {
                        "Whisper voice session disconnected."
                    }

                    if (isConnected) {
                        liveService?.setVisionTranscriptionListener(object : GeminiLiveService.VisionTranscriptionListener {
                            override fun onUserTranscription(text: String, isFinal: Boolean) {
                                if (isFinal && text.isNotBlank()) {
                                    processTranscription(text)
                                }
                            }
                        })
                        uiHandler.postDelayed({ askCurrentQuestion() }, 250)
                    }
                }
            }
        }

        liveService = GeminiLiveService(this, callbacks).also {
            it.startLiveConversation(
                """
                You are running a profile interview in Whisper mode.
                Speak only when asked by injected prompts.
                Keep spoken output concise and clear.
                Do not ask your own follow-up questions.
                """.trimIndent()
            )
        }

        voiceState.text = "Connecting Whisper voice session..."
    }

    private fun askCurrentQuestion() {
        val service = liveService
        if (service == null || !liveConnected) {
            voiceState.text = "Voice session not ready. Tap Start Voice Mode."
            return
        }

        awaitingAnswerForIndex = currentIndex
        lastCapturedTranscript = ""
        service.unmuteOutput()

        val q = questions[currentIndex].prompt
        service.speakText("Question ${currentIndex + 1} of ${questions.size}. $q", speakDirectly = true)
    }

    private fun beginAnswerWindow() {
        awaitingAnswerForIndex = currentIndex
        voiceState.text = "Listening with Whisper... please answer now."
    }

    private fun processTranscription(rawText: String) {
        val targetIndex = awaitingAnswerForIndex ?: return
        val spoken = rawText.trim()
        if (spoken.isBlank()) return
        if (spoken.equals(lastCapturedTranscript, ignoreCase = true)) return

        lastCapturedTranscript = spoken
        answers[targetIndex] = spoken

        runOnUiThread {
            updateLastAnswerView()
            voiceState.text = "Captured answer for question ${targetIndex + 1}."

            if (targetIndex < questions.lastIndex) {
                currentIndex = targetIndex + 1
                renderQuestionState()
                uiHandler.postDelayed({ askCurrentQuestion() }, 280)
            } else {
                saveProfileAndFinish()
            }
        }
    }

    private fun saveProfileAndFinish() {
        val manager = UserMemoryManager(this)
        val existing = manager.getUserMemory()

        val mergedInterests = mergeLists(existing.interests, parseListAnswer(answers[3]))
        val mergedLikes = mergeLists(existing.likes, parseListAnswer(answers[4]))
        val mergedDislikes = mergeLists(existing.dislikes, parseListAnswer(answers[5]))

        val notesFromQuestions = listOf(1, 3, 6)
            .mapNotNull { answers[it]?.trim() }
            .filter { it.isNotBlank() }

        val mergedNotes = mergeLists(existing.importantNotes, notesFromQuestions)

        val updatedMemory = existing.copy(
            userName = extractName(answers[0], existing.userName),
            occupation = firstNonBlank(answers[1], existing.occupation),
            location = extractLocation(answers[0], existing.location),
            interests = mergedInterests,
            likes = mergedLikes,
            dislikes = mergedDislikes,
            preferredLanguage = parsePreferredLanguage(answers[2], existing.preferredLanguage),
            preferredResponseStyle = parseResponseStyle(answers[2], existing.preferredResponseStyle),
            importantNotes = mergedNotes,
            lastUpdated = System.currentTimeMillis()
        )

        manager.saveUserMemory(updatedMemory)

        saveAiSummary(updatedMemory)

        Toast.makeText(this, "Voice profile completed. AI will now personalize your conversations.", Toast.LENGTH_LONG).show()
        finish()
    }

    private fun saveAiSummary(memory: UserMemory) {
        val profileSummary = buildString {
            append("Personalization profile built from 7 merged voice onboarding questions.\n")
            if (memory.userName.isNotBlank()) append("Name: ${memory.userName}\n")
            if (memory.occupation.isNotBlank()) append("Work/Study: ${memory.occupation}\n")
            if (memory.location.isNotBlank()) append("Location: ${memory.location}\n")
            if (memory.interests.isNotEmpty()) append("Interests: ${memory.interests.joinToString(", ")}\n")
            if (memory.likes.isNotEmpty()) append("Likes: ${memory.likes.joinToString(", ")}\n")
            if (memory.dislikes.isNotEmpty()) append("Dislikes: ${memory.dislikes.joinToString(", ")}\n")
            append("Preferred language: ${memory.preferredLanguage}\n")
            append("Preferred response style: ${memory.preferredResponseStyle.description}\n")
            if (memory.importantNotes.isNotEmpty()) {
                append("Support notes: ${memory.importantNotes.joinToString(" | ")}\n")
            }
        }.trim()

        getSharedPreferences("imi_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("user_profile_summary_snapshot", profileSummary)
            .putString("user_profile_summary_for_ai", profileSummary)
            .putLong("user_profile_summary_timestamp", System.currentTimeMillis())
            .apply()
    }

    private fun parsePreferredLanguage(input: String?, fallback: String): String {
        val value = input.orEmpty().trim().lowercase()
        return when {
            value.contains("hindi") -> "Hindi"
            value.contains("english") -> "English"
            value.isBlank() -> fallback
            else -> input!!.trim()
        }
    }

    private fun parseResponseStyle(input: String?, fallback: UserMemory.ResponseStyle): UserMemory.ResponseStyle {
        val value = input.orEmpty().trim().lowercase()
        return when {
            value.contains("brief") || value.contains("short") || value.contains("quick") -> UserMemory.ResponseStyle.BRIEF
            value.contains("detail") || value.contains("long") || value.contains("deep") -> UserMemory.ResponseStyle.DETAILED
            value.contains("balance") || value.contains("normal") -> UserMemory.ResponseStyle.BALANCED
            value.isBlank() -> fallback
            else -> fallback
        }
    }

    private fun parseListAnswer(input: String?): List<String> {
        if (input.isNullOrBlank()) return emptyList()
        return input
            .split(',', '\n', ';', '|')
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private fun mergeLists(existing: List<String>, incoming: List<String>): List<String> {
        if (incoming.isEmpty()) return existing
        val result = existing.toMutableList()
        incoming.forEach { item ->
            if (result.none { it.equals(item, ignoreCase = true) }) {
                result.add(item)
            }
        }
        return result
    }

    private fun firstNonBlank(primary: String?, fallback: String): String {
        return primary?.trim().takeUnless { it.isNullOrBlank() } ?: fallback
    }

    private fun extractName(input: String?, fallback: String): String {
        val text = input.orEmpty().trim()
        if (text.isBlank()) return fallback
        val marker = " and "
        return if (text.contains(marker, ignoreCase = true)) {
            text.substringBefore(marker).trim().ifBlank { fallback }
        } else {
            text
        }
    }

    private fun extractLocation(input: String?, fallback: String): String {
        val text = input.orEmpty().trim()
        if (text.isBlank()) return fallback
        val marker = " and "
        return if (text.contains(marker, ignoreCase = true)) {
            text.substringAfter(marker).trim().ifBlank { fallback }
        } else {
            fallback
        }
    }
}
