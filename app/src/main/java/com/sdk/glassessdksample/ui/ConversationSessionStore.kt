package com.sdk.glassessdksample.ui

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * One message inside a conversation session.
 * [role] is "User" or "AI"; [text] is the spoken/typed content.
 */
data class SessionMessage(
    val role: String,
    val text: String
)

/**
 * A complete conversation session: every turn between the user and the AI for
 * one continuous conversation, stamped with when it started. This is what the
 * History screen lists (one row per session, labelled by date/time) and renders
 * in full when opened — replacing the old per-utterance "topic" grouping.
 */
data class ConversationSession(
    val id: String,
    val startedAt: Long,
    var lastUpdatedAt: Long,
    val messages: MutableList<SessionMessage> = mutableListOf()
) {
    /** Title shown in the Recents list, e.g. "16 Jun 2026, 1:07 PM". */
    fun displayTitle(): String {
        val fmt = SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault())
        return fmt.format(java.util.Date(startedAt))
    }

    /** Short preview of the first user message, for a subtitle if needed. */
    fun preview(): String {
        val firstUser = messages.firstOrNull { it.role.equals("User", true) }?.text
            ?: messages.firstOrNull()?.text
            ?: ""
        return firstUser.take(60)
    }
}

/**
 * Durable store of conversation sessions, shared across the app. Sessions are
 * kept in "imi_conversation_sessions" as a JSON list, newest last. The Mark 1
 * voice assistant appends turns to the current session; the History screen and
 * profile summary read them back.
 */
object ConversationSessionStore {

    private const val TAG = "ConvSessionStore"
    private const val PREFS = "imi_conversation_sessions"
    private const val KEY_SESSIONS = "sessions"
    private const val MAX_SESSIONS = 100
    private const val MAX_MESSAGES_PER_SESSION = 400

    private val gson = Gson()
    private val listType = object : TypeToken<MutableList<ConversationSession>>() {}.type

    fun getSessions(context: Context): MutableList<ConversationSession> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SESSIONS, null) ?: return mutableListOf()
        return try {
            gson.fromJson(json, listType) ?: mutableListOf()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse sessions: ${e.message}")
            mutableListOf()
        }
    }

    /** Sessions sorted newest-first, for display. */
    fun getSessionsNewestFirst(context: Context): List<ConversationSession> =
        getSessions(context).sortedByDescending { it.startedAt }

    private fun save(context: Context, sessions: MutableList<ConversationSession>) {
        // Trim oldest sessions beyond the cap.
        while (sessions.size > MAX_SESSIONS) {
            val oldest = sessions.minByOrNull { it.startedAt } ?: break
            sessions.remove(oldest)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SESSIONS, gson.toJson(sessions))
            .apply()
    }

    /** Start a fresh session and return its id. */
    fun startSession(context: Context): String {
        val now = System.currentTimeMillis()
        val session = ConversationSession(
            id = "sess_$now",
            startedAt = now,
            lastUpdatedAt = now
        )
        val sessions = getSessions(context)
        sessions.add(session)
        save(context, sessions)
        com.sdk.glassessdksample.ui.sync.ConversationSync.pushStartSession(context, session.id, now)
        return session.id
    }

    /**
     * Append a completed turn (user text + AI text) to the given session.
     * If [sessionId] is unknown (e.g. process was restarted), a new session is
     * created so the turn is never lost.
     */
    fun appendTurn(context: Context, sessionId: String?, userText: String, aiText: String) {
        if (userText.isBlank() && aiText.isBlank()) return
        try {
            val sessions = getSessions(context)
            var session = sessions.firstOrNull { it.id == sessionId }
            if (session == null) {
                val now = System.currentTimeMillis()
                session = ConversationSession(id = sessionId ?: "sess_$now", startedAt = now, lastUpdatedAt = now)
                sessions.add(session)
            }
            if (userText.isNotBlank()) session.messages.add(SessionMessage("User", userText.trim()))
            if (aiText.isNotBlank()) session.messages.add(SessionMessage("AI", aiText.trim()))
            session.lastUpdatedAt = System.currentTimeMillis()
            while (session.messages.size > MAX_MESSAGES_PER_SESSION) session.messages.removeAt(0)
            save(context, sessions)
            com.sdk.glassessdksample.ui.sync.ConversationSync.pushTurn(
                context, session.id, userText, aiText
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to append turn: ${e.message}")
        }
    }

    fun clearAll(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_SESSIONS).apply()
        com.sdk.glassessdksample.ui.sync.ConversationSync.pushClearAll(context)
    }

    /** Delete one session (a single conversation thread) without touching the rest. */
    fun deleteSession(context: Context, sessionId: String) {
        val sessions = getSessions(context)
        if (sessions.removeAll { it.id == sessionId }) {
            save(context, sessions)
        }
    }
}
