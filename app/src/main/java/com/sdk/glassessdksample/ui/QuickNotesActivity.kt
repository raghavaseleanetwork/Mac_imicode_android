package com.sdk.glassessdksample.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sdk.glassessdksample.R
import com.sdk.glassessdksample.utils.SystemBarsInsets

/**
 * Quick Notes - lists user notes grouped by month with two tabs:
 *  - AI Written:   notes created by the AI, shown as rich cards
 *  - Self-Written: notes created by the user, grouped by month
 *
 * Tapping the compose button (or a note) opens the full-screen [NoteEditorActivity].
 */
class QuickNotesActivity : AppCompatActivity() {

    private lateinit var notesManager: QuickNotesManager
    private lateinit var notesAdapter: QuickNotesAdapter
    private lateinit var rvNotes: RecyclerView
    private lateinit var layoutEmptyState: LinearLayout
    private lateinit var tabAi: TextView
    private lateinit var tabSelf: TextView
    private lateinit var tvAiIntro: TextView
    private lateinit var etSearch: EditText

    private var showingAi = false
    private var searchQuery = ""

    // System speech-to-text dialog for the search mic button.
    private val speechLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val spoken = result.data
            ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            .orEmpty()
        if (spoken.isNotBlank()) {
            etSearch.setText(spoken)
            etSearch.setSelection(etSearch.text.length)
        }
    }

    private val recordAudioPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startVoiceSearch()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_quick_notes)
        SystemBarsInsets.apply(this)

        notesManager = QuickNotesManager(this)
        setupUI()
        refreshList()
    }

    private fun setupUI() {
        rvNotes = findViewById(R.id.rv_notes)
        layoutEmptyState = findViewById(R.id.layout_empty_state)
        tabAi = findViewById(R.id.tab_ai)
        tabSelf = findViewById(R.id.tab_self)
        tvAiIntro = findViewById(R.id.tv_ai_intro)
        etSearch = findViewById(R.id.et_search)

        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }

        notesAdapter = QuickNotesAdapter(
            items = emptyList(),
            onClick = { note -> openEditor(note) },
            onEdit = { note -> openEditor(note) },
            onCopy = { note -> copyNote(note) },
            onDelete = { note -> confirmDeleteNote(note) }
        )
        rvNotes.apply {
            layoutManager = LinearLayoutManager(this@QuickNotesActivity)
            adapter = notesAdapter
        }

        tabAi.setOnClickListener { selectTab(ai = true) }
        tabSelf.setOnClickListener { selectTab(ai = false) }

        findViewById<ImageView>(R.id.btn_compose).setOnClickListener { openEditor(null) }

        findViewById<ImageView>(R.id.btn_search_mic).setOnClickListener { onSearchMicClicked() }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString()?.trim().orEmpty()
                refreshList()
            }
        })

        applyTabStyles()
    }

    private fun selectTab(ai: Boolean) {
        if (showingAi == ai) return
        showingAi = ai
        applyTabStyles()
        refreshList()
    }

    private fun applyTabStyles() {
        if (showingAi) {
            tabAi.setBackgroundResource(R.drawable.qn_tab_active)
            tabAi.setTextColor(0xFFFFFFFF.toInt())
            tabSelf.setBackgroundResource(R.drawable.qn_tab_inactive)
            tabSelf.setTextColor(0xFFDDDDDD.toInt())
        } else {
            tabSelf.setBackgroundResource(R.drawable.qn_tab_active)
            tabSelf.setTextColor(0xFFFFFFFF.toInt())
            tabAi.setBackgroundResource(R.drawable.qn_tab_inactive)
            tabAi.setTextColor(0xFFDDDDDD.toInt())
        }
        tvAiIntro.visibility = if (showingAi) View.VISIBLE else View.GONE
    }

    private fun refreshList() {
        val all = notesManager.getAllNotes()
        val source = if (showingAi) {
            all.filter { it.createdBy == QuickNote.CreatedBy.AI }
        } else {
            all.filter { it.createdBy == QuickNote.CreatedBy.USER }
        }

        val filtered = if (searchQuery.isEmpty()) source else source.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
                it.content.contains(searchQuery, ignoreCase = true)
        }

        val items = if (showingAi) buildAiItems(filtered) else buildSelfItems(filtered)
        notesAdapter.updateItems(items)

        val empty = items.isEmpty()
        layoutEmptyState.visibility = if (empty) View.VISIBLE else View.GONE
        rvNotes.visibility = if (empty) View.GONE else View.VISIBLE
    }

    /** AI tab: one rich card per note, newest first. */
    private fun buildAiItems(notes: List<QuickNote>): List<QuickNotesAdapter.ListItem> =
        notes.sortedByDescending { it.timestamp }
            .map { QuickNotesAdapter.ListItem.AiNote(it) }

    /** Self tab: month headers followed by the notes in that month. */
    private fun buildSelfItems(notes: List<QuickNote>): List<QuickNotesAdapter.ListItem> {
        val items = mutableListOf<QuickNotesAdapter.ListItem>()
        notes.sortedByDescending { it.timestamp }
            .groupBy { it.getMonthKey() }
            .toSortedMap(compareByDescending { it })
            .forEach { (_, monthNotes) ->
                items.add(QuickNotesAdapter.ListItem.MonthHeader(monthNotes.first().getMonthLabel()))
                monthNotes.forEachIndexed { index, note ->
                    items.add(
                        QuickNotesAdapter.ListItem.SelfNote(
                            note = note,
                            isLastInGroup = index == monthNotes.lastIndex
                        )
                    )
                }
            }
        return items
    }

    private fun onSearchMicClicked() {
        val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (hasPermission) {
            startVoiceSearch()
        } else {
            recordAudioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startVoiceSearch() {
        val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault().toString())
            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Search notes")
            putExtra(android.speech.RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        try {
            speechLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Voice search not available on this device", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openEditor(note: QuickNote?) {
        val intent = Intent(this, NoteEditorActivity::class.java)
        if (note != null) {
            intent.putExtra(NoteEditorActivity.EXTRA_NOTE_ID, note.id)
            intent.putExtra(NoteEditorActivity.EXTRA_NOTE_TITLE, note.title)
            intent.putExtra(NoteEditorActivity.EXTRA_NOTE_CONTENT, note.content)
        }
        startActivity(intent)
    }

    private fun copyNote(note: QuickNote) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = if (note.title.isBlank()) note.content else "${note.title}\n\n${note.content}"
        clipboard.setPrimaryClip(ClipData.newPlainText(note.title, text))
        Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    /** Long-press on a note in the list -> delete, with a confirmation. */
    private fun confirmDeleteNote(note: QuickNote) {
        AlertDialog.Builder(this)
            .setTitle("Delete this note?")
            .setMessage(note.title.ifBlank { "This note" } + " will be permanently deleted.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                notesManager.deleteNote(note.id)
                Toast.makeText(this, "Note deleted", Toast.LENGTH_SHORT).show()
                refreshList()
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }
}
