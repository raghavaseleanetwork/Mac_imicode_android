package com.sdk.glassessdksample.ui

import android.content.Context
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.sdk.glassessdksample.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.sdk.glassessdksample.utils.SystemBarsInsets

class UserProfileSummaryActivity : AppCompatActivity() {

    private lateinit var tvSnapshot: TextView
    private lateinit var tvSummary: TextView
    private lateinit var tvUpdatedAt: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_user_profile_summary)
        SystemBarsInsets.apply(this)

        tvSnapshot = findViewById(R.id.tvSnapshot)
        tvSummary = findViewById(R.id.tvSummary)
        tvUpdatedAt = findViewById(R.id.tvUpdatedAt)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btnRefreshSummary).setOnClickListener {
            buildAndPersistSummary(showToast = true)
        }

        buildAndPersistSummary(showToast = false)
    }

    override fun onResume() {
        super.onResume()
        buildAndPersistSummary(showToast = false)
    }

    private fun buildAndPersistSummary(showToast: Boolean) {
        val prefs = getSharedPreferences("imi_prefs", Context.MODE_PRIVATE)
        val memoryManager = UserMemoryManager(this)
        val userMemory = memoryManager.getUserMemory()

        val historyJson = prefs.getString("conversation_history", null)
        val parsed = parseHistory(historyJson)

        val userMessages = parsed.filter { it.first == "User" }.map { it.second }
        val aiMessages = parsed.filter { it.first == "AI" }.map { it.second }

        val topTopics = extractTopTopics(userMessages)
        val recentUserPrompts = userMessages.takeLast(5)
        val tone = estimateTone(userMessages)

        val snapshot = buildString {
            appendLine("Name: ${userMemory.userName.ifBlank { "Not set" }}")
            appendLine("Occupation: ${userMemory.occupation.ifBlank { "Not set" }}")
            appendLine("Location: ${userMemory.location.ifBlank { "Not set" }}")
            appendLine("Preferred Language: ${userMemory.preferredLanguage}")
            appendLine("Response Style: ${userMemory.preferredResponseStyle.name}")
            appendLine("Total User Messages: ${userMessages.size}")
            appendLine("Total AI Messages: ${aiMessages.size}")
            appendLine("Auto-Learned Facts: ${userMemory.autoLearnedFacts.size}")
            appendLine("Interests: ${if (userMemory.interests.isEmpty()) "Not set" else userMemory.interests.joinToString(", ")}")
            appendLine("Likes: ${if (userMemory.likes.isEmpty()) "Not set" else userMemory.likes.joinToString(", ")}")
            appendLine("Dislikes: ${if (userMemory.dislikes.isEmpty()) "Not set" else userMemory.dislikes.joinToString(", ")}")
            appendLine("Top Topics From Chat: ${if (topTopics.isEmpty()) "Not enough chat data" else topTopics.joinToString(", ")}")
            if (recentUserPrompts.isNotEmpty()) {
                appendLine("Recent User Requests:")
                recentUserPrompts.forEachIndexed { index, msg ->
                    appendLine("${index + 1}. ${msg.take(120)}")
                }
            }
        }.trim()

        val aiSummary = buildString {
            append("This user")
            if (userMemory.userName.isNotBlank()) {
                append(" (${userMemory.userName})")
            }
            append(" usually interacts in ${userMemory.preferredLanguage} and prefers ${userMemory.preferredResponseStyle.name.lowercase()} responses. ")

            if (userMemory.interests.isNotEmpty()) {
                append("Main interests include ${userMemory.interests.take(4).joinToString(", ")}. ")
            }

            if (topTopics.isNotEmpty()) {
                append("Frequent conversation topics are ${topTopics.take(5).joinToString(", ")}. ")
            }

            append("Conversation tone appears ${tone}. ")
            append("Use this profile to personalize suggestions, reminders, and concise responses for the glasses experience.")
        }

        val now = System.currentTimeMillis()
        prefs.edit()
            .putString("user_profile_summary_snapshot", snapshot)
            .putString("user_profile_summary_for_ai", aiSummary)
            .putLong("user_profile_summary_timestamp", now)
            .apply()

        tvSnapshot.text = snapshot
        tvSummary.text = aiSummary

        val formatted = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(now))
        tvUpdatedAt.text = "Updated: $formatted"

        if (showToast) {
            Toast.makeText(this, "User AI summary refreshed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun parseHistory(historyJson: String?): List<Pair<String, String>> {
        if (historyJson.isNullOrBlank()) return emptyList()

        val normalized = mutableListOf<Pair<String, String>>()

        try {
            val gson = Gson()
            val type = object : TypeToken<MutableList<Pair<String, String>>>() {}.type
            val raw: MutableList<Pair<String, String>> = gson.fromJson(historyJson, type) ?: mutableListOf()
            raw.forEach { pair -> appendNormalizedPair(normalized, pair.first, pair.second) }
        } catch (_: Exception) {
            // Try generic parser fallback.
        }

        if (normalized.isNotEmpty()) return normalized

        try {
            val root = JsonParser.parseString(historyJson)
            if (!root.isJsonArray) return emptyList()

            root.asJsonArray.forEach { item ->
                if (item.isJsonArray) {
                    val arr = item.asJsonArray
                    if (arr.size() >= 2) {
                        appendNormalizedPair(normalized, arr[0].asStringOrNull(), arr[1].asStringOrNull())
                    }
                    return@forEach
                }

                if (!item.isJsonObject) return@forEach
                val obj = item.asJsonObject

                val first = obj.stringOrNull("first")
                val second = obj.stringOrNull("second")
                if (!first.isNullOrBlank() || !second.isNullOrBlank()) {
                    appendNormalizedPair(normalized, first, second)
                    return@forEach
                }

                val role = obj.stringOrNull("role") ?: obj.stringOrNull("speaker")
                val content = obj.stringOrNull("content") ?: obj.stringOrNull("message") ?: obj.stringOrNull("text")
                if (!role.isNullOrBlank() || !content.isNullOrBlank()) {
                    appendNormalizedPair(normalized, role, content)
                    return@forEach
                }

                appendNormalizedPair(
                    normalized,
                    obj.stringOrNull("input") ?: obj.stringOrNull("fullInput") ?: obj.stringOrNull("prompt"),
                    obj.stringOrNull("output") ?: obj.stringOrNull("fullOutput") ?: obj.stringOrNull("response")
                )
            }
        } catch (_: Exception) {
            return emptyList()
        }

        return normalized
    }

    private fun appendNormalizedPair(result: MutableList<Pair<String, String>>, leftRaw: String?, rightRaw: String?) {
        val left = leftRaw?.trim().orEmpty()
        val right = rightRaw?.trim().orEmpty()
        if (left.isEmpty() && right.isEmpty()) return

        val roleLike = left.lowercase() in setOf("user", "you", "model", "assistant", "ai", "imi", "system")
        if (roleLike) {
            val role = when (left.lowercase()) {
                "user", "you" -> "User"
                "model", "assistant", "ai", "imi" -> "AI"
                else -> "System"
            }
            if (right.isNotEmpty()) result.add(role to right)
            return
        }

        if (left.isNotEmpty()) result.add("User" to left)
        if (right.isNotEmpty()) result.add("AI" to right)
    }

    private fun extractTopTopics(userMessages: List<String>): List<String> {
        if (userMessages.isEmpty()) return emptyList()

        val stopWords = setOf(
            "the", "and", "for", "with", "that", "this", "from", "what", "when", "where", "your", "have", "please",
            "about", "want", "need", "help", "tell", "give", "show", "make", "open", "start", "stop", "latest", "today"
        )

        val counts = linkedMapOf<String, Int>()
        userMessages.forEach { message ->
            Regex("[A-Za-z]{4,}").findAll(message.lowercase()).forEach { match ->
                val token = match.value
                if (token !in stopWords) {
                    counts[token] = (counts[token] ?: 0) + 1
                }
            }
        }

        return counts.entries
            .sortedByDescending { it.value }
            .take(8)
            .map { it.key.replaceFirstChar { ch -> ch.uppercase() } }
    }

    private fun estimateTone(userMessages: List<String>): String {
        if (userMessages.isEmpty()) return "neutral"

        val positive = listOf("thanks", "thank", "great", "good", "awesome", "love", "nice", "cool")
        val urgent = listOf("urgent", "quick", "fast", "now", "asap", "immediately")

        val allText = userMessages.joinToString(" ").lowercase()
        val positiveScore = positive.count { allText.contains(it) }
        val urgentScore = urgent.count { allText.contains(it) }

        return when {
            urgentScore > positiveScore -> "task-focused and urgent"
            positiveScore > 0 -> "friendly and collaborative"
            else -> "neutral and practical"
        }
    }

    private fun JsonObject.stringOrNull(key: String): String? {
        if (!has(key)) return null
        return get(key).asStringOrNull()
    }

    private fun com.google.gson.JsonElement?.asStringOrNull(): String? {
        if (this == null || isJsonNull) return null
        return try {
            asString
        } catch (_: Exception) {
            toString()
        }
    }
}
