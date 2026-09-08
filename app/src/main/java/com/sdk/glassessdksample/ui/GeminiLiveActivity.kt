package com.sdk.glassessdksample.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sdk.glassessdksample.R
import com.sdk.glassessdksample.utils.SystemBarsInsets

/**
 * Example Activity demonstrating how to use GeminiLiveService
 * for real-time bidirectional audio conversations with Gemini AI
 */
class GeminiLiveActivity : AppCompatActivity(), GeminiLiveService.GeminiLiveCallbacks {

    companion object {
        private const val TAG = "GeminiLiveActivity"
        private const val REQUEST_RECORD_AUDIO = 200
    }

    private lateinit var geminiLiveService: GeminiLiveService
    
    // UI Components
    private lateinit var btnStartConversation: Button
    private lateinit var btnStopConversation: Button
    private lateinit var tvConnectionStatus: TextView
    private lateinit var tvUserTranscript: TextView
    private lateinit var tvModelTranscript: TextView
    private lateinit var tvAudioStatus: TextView
    
    // Chat overlay components
    private lateinit var recyclerChat: RecyclerView
    private lateinit var chatAdapter: ChatAdapter
    private val chatMessages = mutableListOf<ChatMessage>()

    private var isConnected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gemini_live)
        SystemBarsInsets.apply(this)
        
        // Initialize the service
        geminiLiveService = GeminiLiveService(this, this)
        
        setupUI()
        checkPermissions()
    }

    private fun setupUI() {
        // Initialize UI components
        btnStartConversation = findViewById(R.id.btn_start_conversation)
        btnStopConversation = findViewById(R.id.btn_stop_conversation)
        tvConnectionStatus = findViewById(R.id.tv_connection_status)
        tvUserTranscript = findViewById(R.id.tv_user_transcript)
        tvModelTranscript = findViewById(R.id.tv_model_transcript)
        tvAudioStatus = findViewById(R.id.tv_audio_status)
        recyclerChat = findViewById(R.id.recycler_chat)

        // Setup chat RecyclerView
        chatAdapter = ChatAdapter(chatMessages)
        recyclerChat.apply {
            layoutManager = LinearLayoutManager(this@GeminiLiveActivity)
            adapter = chatAdapter
        }

        btnStartConversation.setOnClickListener {
            if (!isConnected) {
                startLiveConversation()
            }
        }

        btnStopConversation.setOnClickListener {
            if (isConnected) {
                stopLiveConversation()
            }
        }

        btnStopConversation.isEnabled = false
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO
            )
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
                Toast.makeText(this, "Microphone permission granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Microphone permission required for live conversation", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startLiveConversation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Please grant microphone permission first", Toast.LENGTH_SHORT).show()
            return
        }

        val systemInstruction = """
            You are a helpful AI assistant integrated into smart glasses.
            Keep your responses c👤 User: ..."
        tvModelTranscript.text = "🤖 AI: ..."
        
        // Clear chat history
        chatMessages.clear()
        chatAdapter.notifyDataSetChanged()guage the user speaks.
        """.trimIndent()

        geminiLiveService.startLiveConversation(systemInstruction)
        
        // UI updates
        btnStartConversation.isEnabled = false
        tvUserTranscript.text = ""
        tvModelTranscript.text = ""
        
        Log.d(TAG, "Starting live conversation...")
    }

    private fun stopLiveConversation() {
        geminiLiveService.stopLiveConversation()
        
        // UI updates
        btnStartConversation.isEnabled = true
        btnStopConversation.isEnabled = false
        
        Log.d(TAG, "Stopping live conversation...")
    }

    // ========== GeminiLiveCallbacks Implementation ==========

    override fun onTranscriptionUpdate(input: String, output: String, isFinal: Boolean) {
        runOnUiThread {
            // Update compact transcript display
            tvUserTranscript.text = if (input.isEmpty()) "👤 User: ..." else "👤 User: $input"
            tvModelTranscript.text = if (output.isEmpty()) "🤖 AI: ..." else "🤖 AI: $output"
            
            // Update chat overlay for user input
            if (input.isNotEmpty()) {
                val updated = chatAdapter.updateLastMessage(input, isFinal, isFromUser = true)
                if (!updated) {
                    // No existing message to update, add new one
                    val userMessage = ChatMessage(text = input, isFromUser = true, isFinal = isFinal)
                    chatAdapter.addMessage(userMessage)
                    recyclerChat.scrollToPosition(chatMessages.size - 1)
                }
            }
            
            // Update chat overlay for AI output
            if (output.isNotEmpty()) {
                val updated = chatAdapter.updateLastMessage(output, isFinal, isFromUser = false)
                if (!updated) {
                    // No existing message to update, add new one
                    val aiMessage = ChatMessage(text = output, isFromUser = false, isFinal = isFinal)
                    chatAdapter.addMessage(aiMessage)
                    recyclerChat.scrollToPosition(chatMessages.size - 1)
                }
            }
            
            Log.d(TAG, "Transcription - User: '$input', AI: '$output', Final: $isFinal")
        }
    }

    override fun onTurnComplete(fullInput: String, fullOutput: String) {
        runOnUiThread {
            Log.d(TAG, "Turn Complete - User said: '$fullInput'")
            Log.d(TAG, "Turn Complete - AI responded: '$fullOutput'")
            
            // You can save this to conversation history, display it, etc.
            Toast.makeText(this, "Turn complete", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onToolCall(toolName: String, args: Map<String, Any>): String {
        Log.d(TAG, "Tool call received: $toolName with args: $args")
        // This activity is for demo purposes - tool calls should be handled in MainActivity
        return "Tool $toolName executed (demo mode)"
    }

    override fun onAudioPlaybackStart() {
        runOnUiThread {
            tvAudioStatus.text = "🔊 AI is speaking..."
            Log.d(TAG, "Audio playback started")
        }
    }

    override fun onAudioPlaybackEnd() {
        runOnUiThread {
            tvAudioStatus.text = "🎤 Listening..."
            Log.d(TAG, "Audio playback ended")
        }
    }

    override fun onError(error: String) {
        runOnUiThread {
            Toast.makeText(this, "Error: $error", Toast.LENGTH_LONG).show()
            Log.e(TAG, "Error: $error")
            
            // Reset UI on error
            btnStartConversation.isEnabled = true
            btnStopConversation.isEnabled = false
        }
    }

    override fun onConnectionStatusChanged(isConnected: Boolean) {
        this.isConnected = isConnected
        
        runOnUiThread {
            val modelName = if (GeminiLiveService.getSavedModelProvider(this) == ModelProvider.GPT_REALTIME)
                "GPT Realtime" else "Gemini Live"
            tvConnectionStatus.text = if (isConnected) "🟢 Connected" else "🔴 Disconnected"
            btnStopConversation.isEnabled = isConnected
            btnStartConversation.isEnabled = !isConnected
            
            val status = if (isConnected) "Connected to $modelName" else "Disconnected"
            Toast.makeText(this, status, Toast.LENGTH_SHORT).show()
            Log.d(TAG, "Connection status: $status")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        geminiLiveService.destroy()
    }
}
