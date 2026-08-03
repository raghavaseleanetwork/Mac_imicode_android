package com.sdk.glassessdksample

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Gmail Service for reading and sending emails.
 * Uses Gmail REST API, authenticated with a real OAuth 2.0 ACCESS TOKEN
 * (via GoogleAuthUtil), not the ID token. The ID token authenticates
 * "who the user is" to our own backend; it is not accepted by Google's
 * own REST APIs, which need an access token carrying the granted scopes.
 */
class GmailService(private val context: Context) {

    private val TAG = "GmailService"
    private val httpClient = OkHttpClient()
    private val gmailApiUrl = "https://www.googleapis.com/gmail/v1"

    companion object {
        const val SCOPE_READONLY = "https://www.googleapis.com/auth/gmail.readonly"
        const val SCOPE_SEND = "https://www.googleapis.com/auth/gmail.send"

        // "oauth2:<scopes>" is the format GoogleAuthUtil.getToken expects.
        private const val OAUTH_SCOPES = "oauth2:$SCOPE_READONLY $SCOPE_SEND"
    }

    fun signInOptions(): GoogleSignInOptions =
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(SCOPE_SEND), Scope(SCOPE_READONLY))
            .build()

    fun signInClient(): GoogleSignInClient =
        GoogleSignIn.getClient(context, signInOptions())

    fun isGmailReady(): Boolean {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return false
        return GoogleSignIn.hasPermissions(account, Scope(SCOPE_SEND), Scope(SCOPE_READONLY))
    }

    /**
     * Lightweight init hook used by MainActivity. There is no heavyweight setup to
     * perform (tokens are fetched lazily per request), so this just reports whether
     * the signed-in Google account already has the Gmail permissions we need.
     */
    fun initializeGmail(callback: (success: Boolean) -> Unit) {
        callback(isGmailReady())
    }

    /**
     * Fetch a fresh OAuth access token for the signed-in account. Must run off the main thread
     * (GoogleAuthUtil.getToken makes a blocking network call).
     */
    private suspend fun getAccessToken(account: GoogleSignInAccount): String? = withContext(Dispatchers.IO) {
        try {
            GoogleAuthUtil.getToken(context, account.account!!, OAUTH_SCOPES)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to obtain access token: ${e.message}", e)
            null
        }
    }

    fun getUnreadEmailCount(callback: (String) -> Unit) {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        if (account == null) {
            callback("You're not signed in to Gmail yet. Please connect your Google account in Settings first.")
            return
        }

        GlobalScope.launch(Dispatchers.Main) {
            try {
                val token = getAccessToken(account) ?: return@launch callback(
                    "Couldn't access Gmail right now. Please reconnect your Google account in Settings."
                )
                val response = withContext(Dispatchers.IO) {
                    fetchGmailAPI("$gmailApiUrl/users/me/messages?q=is:unread&maxResults=1", token)
                }
                val jsonResponse = JSONObject(response)
                val unreadCount = if (jsonResponse.has("resultSizeEstimate")) {
                    jsonResponse.getInt("resultSizeEstimate")
                } else 0

                val message = if (unreadCount > 0) {
                    "You have $unreadCount unread email${if (unreadCount != 1) "s" else ""}"
                } else {
                    "You have no unread emails"
                }
                Log.d(TAG, "📧 Unread count: $unreadCount")
                callback(message)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error getting unread count: ${e.message}")
                callback("Unable to fetch unread emails")
            }
        }
    }

    fun getRecentEmails(maxResults: Int = 5, callback: (String) -> Unit) {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        if (account == null) {
            callback("You're not signed in to Gmail yet. Please connect your Google account in Settings first.")
            return
        }

        GlobalScope.launch(Dispatchers.Main) {
            try {
                val token = getAccessToken(account) ?: return@launch callback(
                    "Couldn't access Gmail right now. Please reconnect your Google account in Settings."
                )

                val messageListResponse = withContext(Dispatchers.IO) {
                    fetchGmailAPI("$gmailApiUrl/users/me/messages?maxResults=$maxResults", token)
                }

                val jsonResponse = JSONObject(messageListResponse)
                val messages = if (jsonResponse.has("messages")) jsonResponse.getJSONArray("messages") else null

                if (messages == null || messages.length() == 0) {
                    callback("No emails in inbox")
                    return@launch
                }

                val emailSummary = StringBuilder("Recent emails: ")
                for (i in 0 until messages.length()) {
                    val msgId = messages.getJSONObject(i).getString("id")
                    try {
                        val msgResponse = withContext(Dispatchers.IO) {
                            fetchGmailAPI(
                                "$gmailApiUrl/users/me/messages/$msgId?format=metadata&metadataHeaders=Subject&metadataHeaders=From",
                                token
                            )
                        }
                        val msgJson = JSONObject(msgResponse)
                        val headers = msgJson.getJSONObject("payload").getJSONArray("headers")

                        var subject = "no subject"
                        var from = "unknown sender"
                        for (j in 0 until headers.length()) {
                            val header = headers.getJSONObject(j)
                            when (header.getString("name")) {
                                "Subject" -> subject = header.getString("value")
                                "From" -> from = header.getString("value").substringBefore("<").trim()
                            }
                        }
                        emailSummary.append("${i + 1}. From $from, subject $subject. ")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing message: ${e.message}")
                    }
                }
                callback(emailSummary.toString().trim())
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error getting emails: ${e.message}")
                callback("Unable to fetch emails")
            }
        }
    }

    /**
     * Search the inbox for messages matching [query] using Gmail's search syntax
     * (the same `q=` parameter the Gmail app uses), then summarise the matches for
     * voice/glass output. Mirrors [getRecentEmails] but adds the search filter.
     */
    fun searchEmails(query: String, maxResults: Int = 5, callback: (String) -> Unit) {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        if (account == null) {
            callback("You're not signed in to Gmail yet. Please connect your Google account in Settings first.")
            return
        }

        GlobalScope.launch(Dispatchers.Main) {
            try {
                val token = getAccessToken(account) ?: return@launch callback(
                    "Couldn't access Gmail right now. Please reconnect your Google account in Settings."
                )

                val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
                val messageListResponse = withContext(Dispatchers.IO) {
                    fetchGmailAPI("$gmailApiUrl/users/me/messages?maxResults=$maxResults&q=$encodedQuery", token)
                }

                val jsonResponse = JSONObject(messageListResponse)
                val messages = if (jsonResponse.has("messages")) jsonResponse.getJSONArray("messages") else null

                if (messages == null || messages.length() == 0) {
                    callback("No emails found matching \"$query\"")
                    return@launch
                }

                val emailSummary = StringBuilder("Found ${messages.length()} emails matching \"$query\": ")
                for (i in 0 until messages.length()) {
                    val msgId = messages.getJSONObject(i).getString("id")
                    try {
                        val msgResponse = withContext(Dispatchers.IO) {
                            fetchGmailAPI(
                                "$gmailApiUrl/users/me/messages/$msgId?format=metadata&metadataHeaders=Subject&metadataHeaders=From",
                                token
                            )
                        }
                        val msgJson = JSONObject(msgResponse)
                        val headers = msgJson.getJSONObject("payload").getJSONArray("headers")

                        var subject = "no subject"
                        var from = "unknown sender"
                        for (j in 0 until headers.length()) {
                            val header = headers.getJSONObject(j)
                            when (header.getString("name")) {
                                "Subject" -> subject = header.getString("value")
                                "From" -> from = header.getString("value").substringBefore("<").trim()
                            }
                        }
                        emailSummary.append("${i + 1}. From $from, subject $subject. ")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing search result: ${e.message}")
                    }
                }
                callback(emailSummary.toString().trim())
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error searching emails: ${e.message}")
                callback("Unable to search emails")
            }
        }
    }

    /**
     * Send an email via the Gmail API. Builds an RFC 2822 message, base64url-encodes it,
     * and POSTs it as a "raw" Gmail message.
     */
    fun sendEmail(to: String, subject: String, body: String, callback: (success: Boolean, message: String) -> Unit) {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        if (account == null) {
            callback(false, "You're not signed in to Gmail yet. Please connect your Google account in Settings first.")
            return
        }

        GlobalScope.launch(Dispatchers.Main) {
            try {
                val token = getAccessToken(account) ?: return@launch callback(
                    false, "Couldn't access Gmail right now. Please reconnect your Google account in Settings."
                )

                val fromEmail = account.email ?: ""
                val rawMessage = buildRfc2822Message(fromEmail, to, subject, body)
                val encoded = Base64.encodeToString(
                    rawMessage.toByteArray(Charsets.UTF_8),
                    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
                )

                val result = withContext(Dispatchers.IO) {
                    val json = JSONObject().put("raw", encoded)
                    val requestBody = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                    val request = Request.Builder()
                        .url("$gmailApiUrl/users/me/messages/send")
                        .addHeader("Authorization", "Bearer $token")
                        .post(requestBody)
                        .build()

                    httpClient.newCall(request).execute().use { resp ->
                        val respBody = resp.body?.string() ?: ""
                        resp.isSuccessful to respBody
                    }
                }

                if (result.first) {
                    Log.d(TAG, "✅ Email sent to $to")
                    callback(true, "Email sent to $to")
                } else {
                    Log.e(TAG, "❌ Gmail send failed: ${result.second}")
                    callback(false, "Gmail rejected the message. Please try again.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error sending email: ${e.message}", e)
                callback(false, "Failed to send email: ${e.message}")
            }
        }
    }

    private fun buildRfc2822Message(from: String, to: String, subject: String, body: String): String {
        return buildString {
            append("From: $from\r\n")
            append("To: $to\r\n")
            append("Subject: $subject\r\n")
            append("Content-Type: text/plain; charset=UTF-8\r\n")
            append("\r\n")
            append(body)
        }
    }

    private fun fetchGmailAPI(url: String, accessToken: String): String {
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $accessToken")
            .build()
        httpClient.newCall(request).execute().use { response ->
            return response.body?.string() ?: ""
        }
    }
}
