package com.sdk.glassessdksample.ui

import com.sdk.glassessdksample.RemoteConfigManager
import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.sdk.glassessdksample.BuildConfig
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import okhttp3.*
import java.util.concurrent.TimeUnit
import kotlin.coroutines.suspendCoroutine
import kotlin.coroutines.resume

class GeminiLiveApiClient(private val context: Context? = null) {
    
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    // System prompt.
    //
    // A getter, not a val: the answer-length and tone lines come from the user's
    // AI Preferences and must be re-read per request so a change in Settings
    // applies to the next reply. This previously hardcoded "Keep responses concise
    // (1-2 sentences)", which overrode the user's choice on every reply this
    // client produced. Falls back to that wording only when no context is
    // available to read the preference from.
    private val systemPrompt: String
        get() {
            val style = context?.let {
                runCatching { AiResponsePrefs.buildResponseStyleInstruction(it) }.getOrNull()
            } ?: "Keep responses concise (1-2 sentences)."
            return """
                You are Imi Glass, an intelligent AI assistant.
                $style
                Respond in the same language the user speaks.
            """.trimIndent()
        }

    suspend fun chat(prompt: String, history: List<Pair<String, String>>): String {
        // ✅ Don't include history in Live API - it confuses the model
        // Live API works best with simple, direct prompts
        // Filter out error messages from history
        val cleanHistory = history.filter { 
            !it.second.contains("AI service error") && 
            !it.second.contains("Error:") &&
            !it.second.isEmpty()
        }
        
        // For now, just use the current prompt without history
        // History context is maintained by the app, not needed in each API call
        return chat(prompt)
    }

    suspend fun chat(prompt: String): String {
        Log.d("GeminiLiveApiClient", "🎯 Starting chat with prompt: '$prompt'")
        
        // ⚡ LIVE API DISABLED - Using REST API only (more reliable)
        Log.d("GeminiLiveApiClient", "⚡ Using REST API (production mode)")
        return try {
            val restResponse = GeminiAIClient(context).chat(
                prompt,
                TokenUsageTracker.Mode.VOICE_CHAT
            )
            Log.d("GeminiLiveApiClient", "✅ REST API success")
            restResponse
        } catch (restError: Exception) {
            Log.e("GeminiLiveApiClient", "❌ REST API failed: ${restError.message}")
            "Sorry, I am unable to connect to the server right now. Please check your network."
        }
        
        /* LIVE API CODE - DISABLED (too complex, timeout issues)
         * To enable Live API: Uncomment code below
         */
        /*
        Log.d("GeminiLiveApiClient", "🔴 Trying LIVE API (WebSocket mode) first...")
        try {
            val liveResponse = withTimeout(10000L) {
                val response = connectAndSendMessage(prompt)
                if (response.startsWith("Error") || response.contains("not available")) {
                    throw Exception("Live API Error: $response")
                }
                response
            }
            Log.d("GeminiLiveApiClient", "✅ Live API success: '$liveResponse'")
            return liveResponse
        } catch (e: TimeoutCancellationException) {
            Log.w("GeminiLiveApiClient", "⏱️ Live API TIMEOUT after 10s - falling back to REST API")
        } catch (e: Exception) {
            Log.w("GeminiLiveApiClient", "⚠️ Live API failed: ${e.message} - falling back to REST API")
        }
        */
    }

    private suspend fun connectAndSendMessage(prompt: String): String = suspendCoroutine { continuation ->
        try {
            val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=${RemoteConfigManager.geminiApiKey}"
            val request = Request.Builder().url(url).build()
            
            val listener = object : WebSocketListener() {
                private var isResumed = false
                private var webSocketRef: WebSocket? = null
                
                // Timeout handler - force resume after 8 seconds if no response
                private val timeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
                private val timeoutRunnable = Runnable {
                    if (!isResumed) {
                        Log.w("GeminiLiveApiClient", "⏱️ Manual timeout triggered - no response in 8s")
                        resumeWith("Error: Timeout - no response")
                        webSocketRef?.close(1000, "Timeout")
                    }
                }
                
                private fun resumeWith(result: String) {
                    synchronized(this) {
                        if (!isResumed) {
                            isResumed = true
                            timeoutHandler.removeCallbacks(timeoutRunnable)
                            try { continuation.resume(result) } catch (_: Exception) { }
                        }
                    }
                }

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocketRef = webSocket
                    Log.d("GeminiLiveApiClient", "✅ WebSocket Connected")
                    
                    // Start 8-second timeout
                    timeoutHandler.postDelayed(timeoutRunnable, 8000)
                    
                    try {
                        // Send ONLY user message - no separate setup needed
                        // Use system prompt inline with user message
                        val fullPrompt = "$systemPrompt\n\nUser: $prompt"
                        val userMessage = mapOf(
                            "client_content" to mapOf(
                                "turns" to listOf(
                                    mapOf(
                                        "role" to "user", 
                                        "parts" to listOf(mapOf("text" to fullPrompt))
                                    )
                                ),
                                "turn_complete" to true
                            )
                        )
                        val messageJson = gson.toJson(userMessage)
                        Log.d("GeminiLiveApiClient", "📤 Sending message: $messageJson")
                        webSocket.send(messageJson)
                        Log.d("GeminiLiveApiClient", "🚀 Message Sent (system+user combined)")
                    } catch (e: Exception) {
                        Log.e("GeminiLiveApiClient", "❌ Error sending message", e)
                        resumeWith("Error: ${e.message}")
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.d("GeminiLiveApiClient", "📨 RAW MESSAGE RECEIVED: $text")
                    Log.d("GeminiLiveApiClient", "📨 Message length: ${text.length} chars")
                    
                    try {
                        val responseMap = gson.fromJson(text, Map::class.java) as Map<*, *>
                        Log.d("GeminiLiveApiClient", "📦 Parsed keys: ${responseMap.keys}")
                        Log.d("GeminiLiveApiClient", "📦 Full response: $responseMap")
                        
                        // Check for setup acknowledgment
                        if (responseMap.containsKey("setupComplete")) {
                            Log.d("GeminiLiveApiClient", "✅ Setup acknowledged by server")
                            return
                        }
                        
                        // Check for any error
                        if (responseMap.containsKey("error")) {
                            val error = responseMap["error"] as? Map<*, *>
                            val errorMessage = error?.get("message") as? String ?: "Unknown error"
                            val errorCode = error?.get("code") as? Any
                            Log.e("GeminiLiveApiClient", "❌ Server error code=$errorCode: $errorMessage")
                            Log.e("GeminiLiveApiClient", "❌ Full error object: $error")
                            resumeWith("Error: $errorMessage")
                            webSocket.close(1000, "Error received")
                            return
                        }
                        
                        // Check for Text response
                        val serverContent = responseMap["serverContent"] as? Map<*, *>
                        if (serverContent != null) {
                            Log.d("GeminiLiveApiClient", "📥 serverContent found: $serverContent")
                            val modelTurn = serverContent["modelTurn"] as? Map<*, *>
                            Log.d("GeminiLiveApiClient", "📥 modelTurn: $modelTurn")
                            val parts = modelTurn?.get("parts") as? List<*>
                            Log.d("GeminiLiveApiClient", "📥 parts: $parts")
                            val firstPart = parts?.firstOrNull() as? Map<*, *>
                            val responseText = firstPart?.get("text") as? String
                            
                            if (!responseText.isNullOrBlank()) {
                                Log.d("GeminiLiveApiClient", "🗣️ Received text: $responseText")
                                resumeWith(responseText)
                                webSocket.close(1000, "Done")
                                return
                            }
                        }
                        
                        // Log any other message types
                        Log.d("GeminiLiveApiClient", "ℹ️ Other message type received (not error, not text)")
                        
                    } catch (e: Exception) {
                        Log.e("GeminiLiveApiClient", "❌ Error parsing message: ${e.message}", e)
                        Log.e("GeminiLiveApiClient", "❌ Raw text was: $text")
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e("GeminiLiveApiClient", "❌ WebSocket FAILURE")
                    Log.e("GeminiLiveApiClient", "❌ Error: ${t.message}", t)
                    Log.e("GeminiLiveApiClient", "❌ Error class: ${t.javaClass.name}")
                    Log.e("GeminiLiveApiClient", "❌ Response code: ${response?.code}")
                    Log.e("GeminiLiveApiClient", "❌ Response message: ${response?.message}")
                    Log.e("GeminiLiveApiClient", "❌ Response body: ${response?.body?.string()}")
                    timeoutHandler.removeCallbacks(timeoutRunnable)
                    resumeWith("Error: Connection failed - ${t.message}")
                }
                
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.w("GeminiLiveApiClient", "⚠️ WebSocket CLOSING: code=$code, reason='$reason'")
                    timeoutHandler.removeCallbacks(timeoutRunnable)
                    if(!isResumed) {
                        resumeWith("Error: Closed $code - $reason")
                    }
                }
                
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d("GeminiLiveApiClient", "🔒 WebSocket CLOSED: code=$code, reason='$reason'")
                    timeoutHandler.removeCallbacks(timeoutRunnable)
                }
            }
            
            client.newWebSocket(request, listener)
            
        } catch (e: Exception) {
            continuation.resume("Error: Setup failed ${e.message}")
        }
    }
}