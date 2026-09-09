package com.sdk.glassessdksample.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.sdk.glassessdksample.R

/**
 * Lists recent conversation topics. Each row shows a topic title; tapping it opens
 * the corresponding bubble thread.
 */
class HistoryRecentsAdapter(
    private var topics: List<HistoryTopic>,
    private val onClick: (HistoryTopic) -> Unit
) : RecyclerView.Adapter<HistoryRecentsAdapter.RecentViewHolder>() {

    class RecentViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val title: TextView = itemView.findViewById(R.id.tv_recent_title)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecentViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history_recent, parent, false)
        return RecentViewHolder(view)
    }

    override fun onBindViewHolder(holder: RecentViewHolder, position: Int) {
        val topic = topics[position]
        holder.title.text = topic.title
        holder.itemView.setOnClickListener { onClick(topic) }
    }

    override fun getItemCount(): Int = topics.size

    fun update(newTopics: List<HistoryTopic>) {
        topics = newTopics
        notifyDataSetChanged()
    }
}

/**
 * A recent conversation topic: a title plus the bubble messages that belong to it.
 * [messages] is a list of (isUser, text). [sessionId] identifies the underlying
 * [ConversationSession] so a single thread can be deleted without affecting others.
 */
data class HistoryTopic(
    val sessionId: String,
    val title: String,
    val messages: List<Pair<Boolean, String>>
)
