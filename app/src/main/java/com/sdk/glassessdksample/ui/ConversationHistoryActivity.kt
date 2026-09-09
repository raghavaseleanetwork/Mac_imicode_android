package com.sdk.glassessdksample.ui

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sdk.glassessdksample.R
import com.sdk.glassessdksample.utils.SystemBarsInsets

/**
 * History screen with two states:
 *  - Recents list: one row per conversation SESSION, labelled with the date/time
 *    the conversation started (screen 1).
 *  - Thread view:  the selected session rendered in full as chat bubbles (screen 2).
 *
 * Backing data is [ConversationSessionStore] — a list of sessions, each holding
 * the complete turn-by-turn history of one conversation. This replaces the older
 * per-utterance "topic" grouping that only surfaced fragments.
 */
class ConversationHistoryActivity : AppCompatActivity() {

    private lateinit var viewRecents: View
    private lateinit var viewThread: View
    private lateinit var tvEmptyRecents: TextView

    private lateinit var rvRecents: RecyclerView
    private lateinit var rvThread: RecyclerView
    private lateinit var recentsAdapter: HistoryRecentsAdapter
    private lateinit var bubbleAdapter: HistoryBubbleAdapter
    private lateinit var btnClearHistory: TextView

    private var showingThread = false
    private var openTopic: HistoryTopic? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_conversation_history)
        SystemBarsInsets.apply(this)

        viewRecents = findViewById(R.id.view_recents)
        viewThread = findViewById(R.id.view_thread)
        tvEmptyRecents = findViewById(R.id.tvEmptyRecents)
        rvRecents = findViewById(R.id.rvRecents)
        rvThread = findViewById(R.id.rvThread)

        recentsAdapter = HistoryRecentsAdapter(emptyList()) { topic -> openThread(topic) }
        rvRecents.layoutManager = LinearLayoutManager(this)
        rvRecents.adapter = recentsAdapter

        bubbleAdapter = HistoryBubbleAdapter(emptyList())
        rvThread.layoutManager = LinearLayoutManager(this)
        rvThread.adapter = bubbleAdapter

        btnClearHistory = findViewById(R.id.btnClearHistory)
        findViewById<ImageView>(R.id.btnBack).setOnClickListener { handleBack() }
        btnClearHistory.setOnClickListener { onClearOrDeleteClicked() }

        loadAndRenderHistory()
    }

    override fun onResume() {
        super.onResume()
        // Refresh in case a conversation was recorded while this screen was open.
        if (!showingThread) loadAndRenderHistory()
    }

    private fun openThread(topic: HistoryTopic) {
        openTopic = topic
        bubbleAdapter.update(topic.messages)
        showingThread = true
        viewRecents.visibility = View.GONE
        viewThread.visibility = View.VISIBLE
        btnClearHistory.text = "Delete"
    }

    private fun showRecents() {
        openTopic = null
        showingThread = false
        viewThread.visibility = View.GONE
        viewRecents.visibility = View.VISIBLE
        btnClearHistory.text = "Clear"
        loadAndRenderHistory()
    }

    private fun handleBack() {
        if (showingThread) showRecents() else finish()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (showingThread) showRecents() else super.onBackPressed()
    }

    private fun loadAndRenderHistory() {
        val sessions = ConversationSessionStore.getSessionsNewestFirst(this)

        // Map each session to a History row carrying its full message thread.
        val topics = sessions
            .filter { it.messages.isNotEmpty() }
            .map { session ->
                HistoryTopic(
                    sessionId = session.id,
                    title = session.displayTitle(),
                    messages = session.messages.map { msg ->
                        // true = user bubble (right/orange), false = AI bubble (left/dark)
                        msg.role.equals("User", ignoreCase = true) to msg.text
                    }
                )
            }

        recentsAdapter.update(topics)
        tvEmptyRecents.visibility = if (topics.isEmpty()) View.VISIBLE else View.GONE
        rvRecents.visibility = if (topics.isEmpty()) View.GONE else View.VISIBLE
    }

    /**
     * "Clear" (Recents screen) wipes every conversation. "Delete" (an open single
     * thread) only removes that one session - the button's label and behavior
     * switch depending on which screen is showing (see [openThread]/[showRecents]).
     */
    private fun onClearOrDeleteClicked() {
        if (showingThread) deleteOpenThread() else clearAllHistory()
    }

    private fun clearAllHistory() {
        ConversationSessionStore.clearAll(this)
        recentsAdapter.update(emptyList())
        bubbleAdapter.update(emptyList())
        showRecents()
        tvEmptyRecents.visibility = View.VISIBLE
        rvRecents.visibility = View.GONE
        Toast.makeText(this, "Conversation history cleared", Toast.LENGTH_SHORT).show()
    }

    private fun deleteOpenThread() {
        val topic = openTopic ?: return
        ConversationSessionStore.deleteSession(this, topic.sessionId)
        Toast.makeText(this, "Conversation deleted", Toast.LENGTH_SHORT).show()
        showRecents()
    }
}
