package com.sdk.glassessdksample.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.sdk.glassessdksample.R
import com.sdk.glassessdksample.utils.SystemBarsInsets

/**
 * Full-screen editor for creating and editing a Quick Note (mockup screen 1).
 * Text-only: a title field and a multi-line body.
 *
 * Saving is two-layered:
 *  - an explicit Save button in the header, so there is always a visible,
 *    unambiguous way to confirm the note is kept;
 *  - back navigation (arrow tap, system back, or the predictive-back gesture)
 *    also saves, as a safety net for anyone who edits and just leaves.
 *
 * The explicit button used to be missing entirely - saving only ever happened via
 * the deprecated Activity.onBackPressed() override, which is not guaranteed to
 * run under predictive back (the default gesture-nav behaviour once an app
 * targets SDK 33+): the system can complete the back animation and finish the
 * Activity without ever calling that override, silently discarding the note.
 * OnBackPressedCallback is registered with the dispatcher instead, which
 * predictive back is required to invoke.
 */
class NoteEditorActivity : AppCompatActivity() {

    private lateinit var notesManager: QuickNotesManager
    private lateinit var etTitle: EditText
    private lateinit var etContent: EditText

    private var existingNoteId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_note_editor)
        SystemBarsInsets.apply(this)

        notesManager = QuickNotesManager(this)

        etTitle = findViewById(R.id.et_title)
        etContent = findViewById(R.id.et_content)

        existingNoteId = intent.getStringExtra(EXTRA_NOTE_ID)
        if (existingNoteId != null) {
            etTitle.setText(intent.getStringExtra(EXTRA_NOTE_TITLE) ?: "")
            etContent.setText(intent.getStringExtra(EXTRA_NOTE_CONTENT) ?: "")
        }

        findViewById<ImageView>(R.id.btn_back).setOnClickListener { saveAndFinish() }
        findViewById<ImageView>(R.id.btn_share).setOnClickListener { shareNote() }
        findViewById<TextView>(R.id.btn_save).setOnClickListener { saveAndFinish() }

        // Only a note that already exists can be deleted — nothing to delete
        // for a note still being composed for the first time.
        val btnDelete = findViewById<ImageView>(R.id.btn_delete)
        if (existingNoteId != null) {
            btnDelete.visibility = android.view.View.VISIBLE
            btnDelete.setOnClickListener { confirmDelete() }
        }

        // Registered with the dispatcher rather than overriding the deprecated
        // Activity.onBackPressed() — see class doc for why that matters here.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                saveAndFinish()
            }
        })
    }

    private fun shareNote() {
        val title = etTitle.text.toString().trim()
        val content = etContent.text.toString().trim()
        if (title.isEmpty() && content.isEmpty()) {
            Toast.makeText(this, "Nothing to share", Toast.LENGTH_SHORT).show()
            return
        }
        val text = if (title.isEmpty()) content else "$title\n\n$content"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "Share note"))
    }

    /**
     * Manual delete, reachable from the editor's header.
     *
     * There was no way to delete a note anywhere in the app before this —
     * `QuickNotesManager.deleteNote()` existed and even synced the deletion
     * to the backend, but nothing in the UI ever called it, and the voice
     * assistant has no delete_note tool (deleting is deliberately left to the
     * user, since a spoken "delete my note" can't reliably disambiguate which
     * one is meant the way tapping a specific note can).
     */
    private fun confirmDelete() {
        val id = existingNoteId ?: return
        AlertDialog.Builder(this)
            .setTitle("Delete this note?")
            .setMessage("This can't be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                notesManager.deleteNote(id)
                Toast.makeText(this, "Note deleted", Toast.LENGTH_SHORT).show()
                finish()
            }
            .show()
    }

    private fun saveAndFinish() {
        val titleRaw = etTitle.text.toString().trim()
        val content = etContent.text.toString().trim()

        // Nothing entered -> just leave without saving
        if (titleRaw.isEmpty() && content.isEmpty()) {
            finish()
            return
        }

        val title = titleRaw.ifEmpty { content.take(40) }

        if (existingNoteId != null) {
            notesManager.updateNote(existingNoteId!!, title, content)
            Toast.makeText(this, "Note updated", Toast.LENGTH_SHORT).show()
        } else {
            notesManager.createNote(title, content, QuickNote.CreatedBy.USER)
            Toast.makeText(this, "Note saved", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    companion object {
        const val EXTRA_NOTE_ID = "note_id"
        const val EXTRA_NOTE_TITLE = "note_title"
        const val EXTRA_NOTE_CONTENT = "note_content"
    }
}
