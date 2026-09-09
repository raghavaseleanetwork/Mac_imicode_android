package com.sdk.glassessdksample.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.sdk.glassessdksample.R

/**
 * Adapter for the Quick Notes list. Renders two layouts depending on the active tab:
 *  - Self-Written: month headers + compact note rows grouped by month
 *  - AI Written:   rich AI note cards with edit / copy actions
 *
 * The hosting activity builds a flat list of [ListItem]s (headers + notes) and the
 * adapter renders each by its view type.
 */
class QuickNotesAdapter(
    private var items: List<ListItem>,
    private val onClick: (QuickNote) -> Unit,
    private val onEdit: (QuickNote) -> Unit,
    private val onCopy: (QuickNote) -> Unit,
    private val onDelete: (QuickNote) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /** A row in the list is either a month header or a note (self or AI styled). */
    sealed class ListItem {
        data class MonthHeader(val label: String) : ListItem()
        data class SelfNote(val note: QuickNote, val isLastInGroup: Boolean) : ListItem()
        data class AiNote(val note: QuickNote) : ListItem()
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_SELF = 1
        private const val TYPE_AI = 2
    }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is ListItem.MonthHeader -> TYPE_HEADER
        is ListItem.SelfNote -> TYPE_SELF
        is ListItem.AiNote -> TYPE_AI
    }

    // ---- View holders ----

    inner class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvMonth: TextView = itemView.findViewById(R.id.tv_month)
    }

    inner class SelfViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val card: View = itemView.findViewById(R.id.card_note)
        val tvTitle: TextView = itemView.findViewById(R.id.tv_note_title)
        val tvTimestamp: TextView = itemView.findViewById(R.id.tv_note_timestamp)
        val divider: View = itemView.findViewById(R.id.row_divider)
    }

    inner class AiViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val card: View = itemView.findViewById(R.id.card_note_ai)
        val tvTitle: TextView = itemView.findViewById(R.id.tv_ai_title)
        val tvContent: TextView = itemView.findViewById(R.id.tv_ai_content)
        val btnEdit: ImageView = itemView.findViewById(R.id.btn_ai_edit)
        val btnCopy: ImageView = itemView.findViewById(R.id.btn_ai_copy)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderViewHolder(
                inflater.inflate(R.layout.item_note_month_header, parent, false)
            )
            TYPE_AI -> AiViewHolder(
                inflater.inflate(R.layout.item_quick_note_ai, parent, false)
            )
            else -> SelfViewHolder(
                inflater.inflate(R.layout.item_quick_note, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is ListItem.MonthHeader -> {
                (holder as HeaderViewHolder).tvMonth.text = item.label
            }
            is ListItem.SelfNote -> {
                val h = holder as SelfViewHolder
                h.tvTitle.text = item.note.title
                h.tvTimestamp.text = item.note.getShortDate()
                h.divider.visibility = if (item.isLastInGroup) View.GONE else View.VISIBLE
                h.card.setOnClickListener { onClick(item.note) }
                // Long-press to delete without having to open the note first —
                // matches the standard Android list pattern, and there was
                // previously no delete path anywhere in Quick Notes at all.
                h.card.setOnLongClickListener { onDelete(item.note); true }
            }
            is ListItem.AiNote -> {
                val h = holder as AiViewHolder
                h.tvTitle.text = item.note.title
                h.tvContent.text = item.note.content
                h.card.setOnClickListener { onClick(item.note) }
                h.card.setOnLongClickListener { onDelete(item.note); true }
                h.btnEdit.setOnClickListener { onEdit(item.note) }
                h.btnCopy.setOnClickListener { onCopy(item.note) }
            }
        }
    }

    override fun getItemCount(): Int = items.size

    fun updateItems(newItems: List<ListItem>) {
        items = newItems
        notifyDataSetChanged()
    }
}
