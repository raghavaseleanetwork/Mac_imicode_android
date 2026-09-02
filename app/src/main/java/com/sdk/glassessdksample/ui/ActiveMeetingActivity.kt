package com.sdk.glassessdksample.ui

import com.sdk.glassessdksample.RemoteConfigManager
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.PowerManager
import android.os.Looper
import android.media.MediaRecorder
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.sdk.glassessdksample.R
import kotlinx.coroutines.*
import java.util.*

/**
 * Activity for recording meeting audio, then converting to text at the end
 */
class ActiveMeetingActivity : AppCompatActivity() {

    private val TAG = "ActiveMeetingActivity"
    
    private lateinit var meetingManager: MeetingMinutesManager
    private var mediaRecorder: MediaRecorder? = null
    private var audioFilePath: String? = null
    
    private lateinit var tvMeetingTitle: TextView
    private lateinit var tvDuration: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvLiveParticipants: TextView
    private lateinit var tvTranscript: TextView
    private lateinit var tvSummaryStatus: TextView
    private lateinit var tvSummaryMeetingTitle: TextView
    private lateinit var tvSummaryDate: TextView
    private lateinit var tvSummaryDuration: TextView
    private lateinit var tvSummaryWords: TextView
    private lateinit var layoutGenerating: View
    private lateinit var cardSummaryResult: View
    private lateinit var timerContainer: View
    private lateinit var summaryContainer: View
    private lateinit var indicatorRecording: View
    private lateinit var blobGlow: MeetingBlobView
    private lateinit var glowBg: MeetingGlowView
    private lateinit var btnPause: ImageView
    private lateinit var btnSwitch: ImageView
    private lateinit var btnEndMeeting: ImageView

    // Polls MediaRecorder amplitude every 100 ms and drives the blob animation.
    private val amplitudeRunnable = object : Runnable {
        override fun run() {
            if (isRecording && !isPaused) {
                try {
                    val raw = mediaRecorder?.maxAmplitude ?: 0
                    // Typical speech peaks at 500-5000; use 6000 as practical max with a boost curve
                    val normalised = ((raw / 6000f) * 1.5f).coerceIn(0f, 1f)
                    blobGlow.setAmplitude(normalised)
                    glowBg.setAmplitude(normalised)
                } catch (_: Exception) {}
            } else {
                blobGlow.setAmplitude(0f)
                glowBg.setAmplitude(0f)
            }
            handler.postDelayed(this, 100)
        }
    }
    
    private var currentMeeting: MeetingMinute? = null
    private var isRecording = false
    private var isPaused = false

    // Total time spent paused, and when the current pause began, so the displayed
    // duration reflects recorded audio rather than raw wall-clock time.
    private var pausedDurationMs = 0L
    private var pauseStartedAtMs = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    // Audio focus management
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    // Keeps the CPU alive so recording survives the screen turning off / doze.
    private var wakeLock: PowerManager.WakeLock? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_active_meeting)
        // Recording must keep running while the user looks away from the phone.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        meetingManager = MeetingMinutesManager(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        setupUI()
        checkPermissionsAndStart()
    }

    private fun setupUI() {
        tvMeetingTitle = findViewById(R.id.tv_meeting_title)
        tvDuration = findViewById(R.id.tv_duration)
        tvStatus = findViewById(R.id.tv_status)
        tvLiveParticipants = findViewById(R.id.tv_live_participants)
        tvTranscript = findViewById(R.id.tv_transcript)
        tvSummaryStatus = findViewById(R.id.tv_summary_status)
        timerContainer = findViewById(R.id.timer_container)
        summaryContainer = findViewById(R.id.summary_container)
        indicatorRecording = findViewById(R.id.indicator_recording)
        blobGlow = findViewById(R.id.blob_glow)
        glowBg = findViewById(R.id.glow_bg)
        tvSummaryMeetingTitle = findViewById(R.id.tv_summary_meeting_title)
        tvSummaryDate = findViewById(R.id.tv_summary_date)
        tvSummaryDuration = findViewById(R.id.tv_summary_duration)
        tvSummaryWords = findViewById(R.id.tv_summary_words)
        layoutGenerating = findViewById(R.id.layout_generating)
        cardSummaryResult = findViewById(R.id.card_summary_result)

        findViewById<android.widget.Button>(R.id.btn_summary_close).setOnClickListener { finish() }
        findViewById<android.widget.Button>(R.id.btn_summary_view_all).setOnClickListener {
            startActivity(Intent(this, MeetingMinutesActivity::class.java))
            finish()
        }
        btnPause = findViewById(R.id.btn_pause)
        btnSwitch = findViewById(R.id.btn_switch)
        btnEndMeeting = findViewById(R.id.btn_end_meeting)

        findViewById<ImageView>(R.id.btn_back).setOnClickListener { confirmEndMeeting() }

        btnPause.setOnClickListener {
            if (isPaused) resumeRecording() else pauseRecording()
        }

        btnEndMeeting.setOnClickListener {
            confirmEndMeeting()
        }
    }
    
    private fun checkPermissionsAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO
            )
        } else {
            startMeeting()
        }
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startMeeting()
            } else {
                Toast.makeText(this, "Microphone permission required", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }
    
    private fun startMeeting() {
        val meetingTitle = intent.getStringExtra(EXTRA_MEETING_TITLE) ?: "Meeting ${getFormattedDateTime()}"
        
        // Start new meeting
        currentMeeting = meetingManager.startMeeting(meetingTitle)
        tvMeetingTitle.text = "Title: $meetingTitle"
        
        // Take the microphone away from wake-word detection for the whole meeting.
        // Without this, ListeningService re-arms the "Hey IMI" detector on a timer
        // and its recorder kills our MediaRecorder mid-meeting.
        acquireMicExclusively()

        // A meeting runs for a long time with the screen possibly off; without a
        // partial wake lock the CPU sleeps and the recorder is torn down.
        acquireWakeLock()

        // Request audio focus
        requestAudioFocus()
        
        Toast.makeText(this, "🎤 Recording audio - AI paused during meeting", Toast.LENGTH_LONG).show()
        
        // Start recording audio
        startRecording()
        
        // Start duration timer
        startDurationTimer()
        
        Log.d(TAG, "Meeting started: $meetingTitle")
    }
    
    /**
     * Claim the mic for the meeting: raise the global meeting flag, stop the
     * wake-word detector and suppress every path that would re-arm it, and stop
     * the foreground listening service that owns the background detector.
     *
     * setSuppressed(true) is the same mechanism vision analysis uses — HotHelper.start()
     * becomes a no-op while it is engaged, so the delayed re-arms already queued by
     * Mark1MainActivity.stopConversation() and ListeningService.rearmWakeWord() fire
     * harmlessly instead of stealing the microphone.
     */
    private fun acquireMicExclusively() {
        meetingActive = true
        try {
            HotHelper.getInstance(applicationContext).apply {
                setSuppressed(true)
                stop()
            }
            Log.d(TAG, "Wake-word detection suppressed for meeting")
        } catch (e: Exception) {
            Log.w(TAG, "Could not suppress wake word: ${e.message}")
        }
        try {
            startService(
                Intent(this, com.sdk.glassessdksample.ListeningService::class.java)
                    .apply { action = com.sdk.glassessdksample.ListeningService.ACTION_STOP }
            )
            Log.d(TAG, "ListeningService stopped for meeting")
        } catch (e: Exception) {
            Log.w(TAG, "Could not stop ListeningService: ${e.message}")
        }
    }

    /**
     * Release the exclusive mic claim taken by [acquireMicExclusively]. Safe to call
     * more than once. Deliberately does NOT restart the detector itself — the host
     * Activity's onResume re-arms it, matching how vision suppression is released.
     */
    private fun releaseMicExclusively() {
        if (!meetingActive) return
        meetingActive = false
        try {
            HotHelper.getInstance(applicationContext).setSuppressed(false)
            Log.d(TAG, "Wake-word suppression released after meeting")
        } catch (e: Exception) {
            Log.w(TAG, "Could not release wake-word suppression: ${e.message}")
        }
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == true) return
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "IMI:MeetingRecording"
            ).apply { setReferenceCounted(false); acquire() }
            Log.d(TAG, "Wake lock acquired for meeting recording")
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire wake lock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        } catch (e: Exception) {
            Log.w(TAG, "Could not release wake lock: ${e.message}")
        }
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener { focusChange ->
                    Log.d(TAG, "Audio focus changed: $focusChange")
                }
                .build()
            audioFocusRequest = focusRequest
            audioManager.requestAudioFocus(focusRequest)
            Log.d(TAG, "Audio focus requested (GAIN_TRANSIENT_EXCLUSIVE)")
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                { Log.d(TAG, "Audio focus changed: $it") },
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            )
        }
    }
    
    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        }
        Log.d(TAG, "Audio focus abandoned")
    }
    
    private fun startRecording() {
        try {
            // Create audio file path
            val audioDir = getExternalFilesDir(null)
            audioFilePath = "${audioDir?.absolutePath}/meeting_${System.currentTimeMillis()}.m4a"

            // Initialize MediaRecorder
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mediaRecorder?.apply {
                // Surface a recorder that dies mid-meeting instead of letting the UI
                // keep ticking against a dead recorder — the user only found out at
                // the end, when the meeting had captured nothing.
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaRecorder error what=$what extra=$extra")
                    runOnUiThread { handleRecorderFailure("Recording error ($what)") }
                }
                setOnInfoListener { _, what, extra ->
                    Log.w(TAG, "MediaRecorder info what=$what extra=$extra")
                }
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(audioFilePath)
                prepare()
                start()
            }
            
            isRecording = true
            isPaused = false
            pausedDurationMs = 0L
            pauseStartedAtMs = 0L

            tvStatus.text = "Recording audio"
            indicatorRecording.visibility = View.VISIBLE
            btnPause.setImageResource(R.drawable.ic_pause_bars)

            // Start driving the blob with mic amplitude.
            handler.removeCallbacks(amplitudeRunnable)
            handler.postDelayed(amplitudeRunnable, 100)

            Log.d(TAG, "Audio recording started: $audioFilePath")
        } catch (e: Exception) {
            // Do NOT leave isRecording true here: the timer would keep counting and
            // the meeting would look like it was recording when it never started.
            isRecording = false
            isPaused = false
            try { mediaRecorder?.release() } catch (_: Exception) {}
            mediaRecorder = null
            audioFilePath = null
            Log.e(TAG, "Error starting audio recording: ${e.message}", e)
            tvStatus.text = "Recording failed"
            indicatorRecording.visibility = View.GONE
            Toast.makeText(this, "Error starting recording: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * The recorder stopped on its own (mic taken by another app, hardware error).
     * Stop the clock, tell the user, and keep whatever audio was written so the
     * meeting can still be transcribed rather than silently lost.
     */
    private fun handleRecorderFailure(reason: String) {
        if (!isRecording) return
        Log.e(TAG, "Recorder failure: $reason")
        isRecording = false
        handler.removeCallbacks(durationRunnable)
        handler.removeCallbacks(amplitudeRunnable)
        try { mediaRecorder?.release() } catch (_: Exception) {}
        mediaRecorder = null
        tvStatus.text = "Recording stopped"
        indicatorRecording.visibility = View.GONE
        blobGlow.setAmplitude(0f)
        glowBg.setAmplitude(0f)
        Toast.makeText(this, "$reason - finishing meeting", Toast.LENGTH_LONG).show()
    }
    
    private fun pauseRecording() {
        // Pause with MediaRecorder supported in API 24+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                mediaRecorder?.pause()
                isPaused = true
                pauseStartedAtMs = System.currentTimeMillis()
                tvStatus.text = "Paused"
                indicatorRecording.visibility = View.GONE
                blobGlow.setAmplitude(0f)
                glowBg.setAmplitude(0f)
                btnPause.setImageResource(R.drawable.ic_play_triangle)
                Log.d(TAG, "Recording paused")
            } catch (e: Exception) {
                Log.e(TAG, "Error pausing: ${e.message}")
                Toast.makeText(this, "Error pausing recording", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Pause not supported on this device", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun resumeRecording() {
        // Resume with MediaRecorder supported in API 24+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                mediaRecorder?.resume()
                if (pauseStartedAtMs > 0L) {
                    pausedDurationMs += System.currentTimeMillis() - pauseStartedAtMs
                    pauseStartedAtMs = 0L
                }
                isPaused = false
                tvStatus.text = "Recording audio"
                indicatorRecording.visibility = View.VISIBLE
                btnPause.setImageResource(R.drawable.ic_pause_bars)
                handler.removeCallbacks(amplitudeRunnable)
                handler.postDelayed(amplitudeRunnable, 100)
                Log.d(TAG, "Recording resumed")
            } catch (e: Exception) {
                Log.e(TAG, "Error resuming: ${e.message}")
                Toast.makeText(this, "Error resuming recording", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Resume not supported on this device", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun confirmEndMeeting() {
        AlertDialog.Builder(this)
            .setTitle("End Meeting?")
            .setMessage("Meeting will be saved and summarized.")
            .setPositiveButton("End Meeting") { _, _ ->
                endMeeting()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun endMeeting() {
        // Stop recording
        isRecording = false
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
            mediaRecorder = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recorder: ${e.message}")
        }
        abandonAudioFocus()
        releaseMicExclusively()
        releaseWakeLock()
        handler.removeCallbacksAndMessages(null)

        // Switch from the recording timer to the summary-generation view (mockup screen 5)
        handler.removeCallbacks(amplitudeRunnable)
        blobGlow.setAmplitude(0f)
        glowBg.setAmplitude(0f)
        glowBg.visibility = View.GONE
        timerContainer.visibility = View.GONE
        summaryContainer.visibility = View.VISIBLE
        indicatorRecording.visibility = View.GONE
        layoutGenerating.visibility = View.VISIBLE
        cardSummaryResult.visibility = View.GONE
        tvStatus.text = "Recording complete"
        tvSummaryStatus.text = "Generating Summary…."

        // Pre-fill the info card with what we know already
        currentMeeting?.let { m ->
            tvSummaryMeetingTitle.text = "Title: ${m.title}"
            tvSummaryDate.text = m.getFormattedStartTime()
            tvSummaryDuration.text = "Duration: ${m.getDuration()}"
            tvSummaryWords.text = ""
        }
        btnPause.isEnabled = false
        btnSwitch.isEnabled = false
        btnEndMeeting.isEnabled = false

        if (audioFilePath == null) {
            Toast.makeText(this, "No audio recorded", Toast.LENGTH_SHORT).show()
            meetingManager.cancelActiveMeeting()
            finish()
            return
        }
        
        // Convert audio to text using Gemini, then generate summary
        scope.launch {
            try {
                // Step 1: Transcribe audio
                runOnUiThread {
                    tvSummaryStatus.text = "Transcribing audio…"
                    tvTranscript.text = "Please wait while your audio is being processed.\nThis may take a few moments."
                }
                
                val transcript = transcribeAudioWithGemini(audioFilePath!!)
                
                if (transcript.isBlank()) {
                    Toast.makeText(this@ActiveMeetingActivity, "No speech detected in recording", Toast.LENGTH_SHORT).show()
                    meetingManager.cancelActiveMeeting()
                    finish()
                    return@launch
                }
                
                // Update meeting with transcript
                meetingManager.updateActiveMeetingTranscript(transcript)
                
                // Step 2: Generate summary
                runOnUiThread {
                    tvSummaryStatus.text = "Generating Summary…"
                }
                
                val summary = generateMeetingSummary(transcript)
                val completedMeeting = meetingManager.endMeeting(summary)
                
                // Clean up audio file
                try {
                    java.io.File(audioFilePath!!).delete()
                } catch (e: Exception) {
                    Log.e(TAG, "Error deleting audio file: ${e.message}")
                }
                
                if (completedMeeting != null) {
                    runOnUiThread {
                        showSummaryInScreen(completedMeeting)
                    }
                } else {
                    Toast.makeText(this@ActiveMeetingActivity, "Error saving meeting", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error ending meeting: ${e.message}", e)
                Toast.makeText(this@ActiveMeetingActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }
    
    private suspend fun transcribeAudioWithGemini(audioFilePath: String): String = withContext(Dispatchers.IO) {
        try {
            val audioFile = java.io.File(audioFilePath)
            if (!audioFile.exists()) return@withContext "Error: Audio file not found"

            val audioBytes = audioFile.readBytes()
            Log.d(TAG, "Transcribing via Gemini SDK (${audioBytes.size} bytes)")

            val model = GenerativeModel(
                modelName = "gemini-2.5-flash",
                apiKey = RemoteConfigManager.geminiApiKey
            )

            val response = model.generateContent(
                content {
                    text("Transcribe this audio recording completely and accurately. Return only the spoken words, nothing else.")
                    blob("audio/mp4", audioBytes)
                }
            )

            val text = response.text?.trim() ?: ""
            Log.d(TAG, "Transcription result: $text")
            if (text.isBlank()) "No speech detected." else text

        } catch (e: Exception) {
            Log.e(TAG, "Transcription error: ${e.message}", e)
            "Audio recorded but transcription failed: ${e.message}"
        }
    }

    private suspend fun generateMeetingSummary(transcript: String): String = withContext(Dispatchers.IO) {
        try {
            val model = GenerativeModel(
                modelName = "gemini-2.5-flash",
                apiKey = RemoteConfigManager.geminiApiKey
            )

            val prompt = """
                Please provide a concise meeting summary based on this transcript:

                $transcript

                Include:
                1. Key Discussion Points (bullet points)
                2. Decisions Made (if any)
                3. Action Items (if any)
                4. Important Notes

                Keep it clear and professional.
            """.trimIndent()

            val response = model.generateContent(
                content { text(prompt) }
            )

            val text = response.text?.trim() ?: ""
            Log.d(TAG, "Summary result: $text")
            if (text.isBlank()) "Summary generation failed. Transcript saved." else text

        } catch (e: Exception) {
            Log.e(TAG, "Summary error: ${e.message}", e)
            "Error generating summary: ${e.message}"
        }
    }
    
    private fun showSummaryInScreen(meeting: MeetingMinute) {
        tvSummaryMeetingTitle.text = "Title: ${meeting.title}"
        tvSummaryDate.text = meeting.getFormattedStartTime()
        tvSummaryDuration.text = "Duration: ${meeting.getDuration()}"
        tvSummaryWords.text = "Words: ${meeting.getWordCount()} words"

        val summaryText = meeting.summary?.takeIf { it.isNotBlank() } ?: "No summary available."
        tvTranscript.text = summaryText

        layoutGenerating.visibility = View.GONE
        cardSummaryResult.visibility = View.VISIBLE
    }
    
    /**
     * Drives the on-screen timer. Uses a single dedicated Runnable reference so the
     * chain can never be scheduled twice and can be cancelled precisely on end.
     */
    private val durationRunnable = object : Runnable {
        override fun run() {
            updateDuration()
            handler.postDelayed(this, 1000) // Update every second
        }
    }

    private fun startDurationTimer() {
        handler.removeCallbacks(durationRunnable)
        handler.postDelayed(durationRunnable, 1000)
    }

    private fun updateDuration() {
        if (!isRecording) return
        currentMeeting?.let { meeting ->
            // Exclude paused time so the clock matches the audio actually captured.
            // This used to be raw wall-clock from startTime, so the timer kept
            // counting while paused and drifted away from the real recording.
            val pausedSoFar = pausedDurationMs +
                if (isPaused && pauseStartedAtMs > 0L) System.currentTimeMillis() - pauseStartedAtMs else 0L
            val durationMs = (System.currentTimeMillis() - meeting.startTime - pausedSoFar)
                .coerceAtLeast(0L)
            val minutes = (durationMs / 1000 / 60).toInt()
            val seconds = ((durationMs / 1000) % 60).toInt()
            tvDuration.text = String.format("%02d :\n%02d", minutes, seconds)
        }
    }

    private fun updateLiveParticipantsDisplay(speakerCount: Int) {
        if (speakerCount > 0) {
            tvLiveParticipants.visibility = View.VISIBLE
            val participantsText = if (speakerCount == 1) {
                "👤 1 person detected in meeting"
            } else {
                "👥 $speakerCount people detected in meeting"
            }
            tvLiveParticipants.text = participantsText
        } else {
            tvLiveParticipants.visibility = View.GONE
        }
    }
    
    private fun getFormattedDateTime(): String {
        val sdf = java.text.SimpleDateFormat("MMM dd HH:mm", Locale.getDefault())
        return sdf.format(Date())
    }
    
    override fun onBackPressed() {
        confirmEndMeeting()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        abandonAudioFocus()
        releaseMicExclusively()
        releaseWakeLock()
        try {
            mediaRecorder?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing recorder: ${e.message}")
        }
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
    }
    
    companion object {
        const val EXTRA_MEETING_TITLE = "meeting_title"
        private const val REQUEST_RECORD_AUDIO = 300

        /**
         * True while a meeting recording is in progress.
         *
         * The meeting recorder owns the microphone exclusively for as long as the
         * meeting runs, which can be hours. Every wake-word re-arm path in the app
         * (ListeningService.rearmWakeWord / onStartCommand, MainActivity.onResume +
         * handOffListeningToService, Mark1MainActivity.startWakeWordListeningDelayed)
         * must check this before starting a detector: arming a second recorder against
         * the live MediaRecorder kills it silently on most devices, which is what made
         * the meeting timer freeze and recording stop part-way through.
         *
         * Mirrors the existing MainActivity.visionBusy suppression flag.
         */
        @Volatile
        var meetingActive = false
    }
}
