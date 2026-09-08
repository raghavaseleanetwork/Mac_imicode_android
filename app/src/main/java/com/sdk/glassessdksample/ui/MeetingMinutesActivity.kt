package com.sdk.glassessdksample.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
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
 * Activity for viewing and managing Meeting Minutes
 */
class MeetingMinutesActivity : AppCompatActivity() {

    private lateinit var meetingManager: MeetingMinutesManager
    private lateinit var meetingsAdapter: MeetingMinutesAdapter
    private lateinit var rvMeetings: RecyclerView
    private lateinit var btnStartMic: ImageView
    private lateinit var btnBack: ImageView
    private lateinit var btnViewAll: TextView
    private lateinit var layoutEmptyState: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_meeting_minutes)
        SystemBarsInsets.apply(this)

        meetingManager = MeetingMinutesManager(this)

        setupUI()
        loadMeetings()
    }

    private fun setupUI() {
        rvMeetings = findViewById(R.id.rv_meetings)
        btnStartMic = findViewById(R.id.btn_start_mic)
        btnBack = findViewById(R.id.btn_back)
        btnViewAll = findViewById(R.id.btn_view_all)
        layoutEmptyState = findViewById(R.id.layout_empty_state)

        btnBack.setOnClickListener { finish() }

        btnViewAll.setOnClickListener {
            startActivity(Intent(this, AllMeetingMinutesActivity::class.java))
        }

        // Setup RecyclerView
        meetingsAdapter = MeetingMinutesAdapter(
            meetings = emptyList(),
            onView = { meeting -> showMeetingDetails(meeting) },
            onDelete = { meeting -> confirmDeleteMeeting(meeting) }
        )

        rvMeetings.apply {
            layoutManager = LinearLayoutManager(this@MeetingMinutesActivity)
            adapter = meetingsAdapter
        }

        // Mic button to start a new meeting
        btnStartMic.setOnClickListener {
            promptMeetingTitle()
        }
    }

    private fun loadMeetings() {
        val meetings = meetingManager.getAllMeetings().filter { isToday(it.startTime) }
        meetingsAdapter.updateMeetings(meetings)
        
        // Show/hide empty state
        if (meetings.isEmpty()) {
            layoutEmptyState.visibility = View.VISIBLE
            rvMeetings.visibility = View.GONE
        } else {
            layoutEmptyState.visibility = View.GONE
            rvMeetings.visibility = View.VISIBLE
        }
    }

    private fun promptMeetingTitle() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_meeting_title, null)
        val input = dialogView.findViewById<EditText>(R.id.et_meeting_title)
        val btnCancel = dialogView.findViewById<TextView>(R.id.btn_cancel)
        val btnStart = dialogView.findViewById<TextView>(R.id.btn_start)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnStart.setOnClickListener {
            val title = input.text.toString().trim()
            dialog.dismiss()
            showLiveTranscriptInfo(title)
        }

        dialog.show()
    }

    /** "Live Transcript" speaker-detection info screen shown before recording begins */
    private fun showLiveTranscriptInfo(title: String) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_live_transcript_info, null)
        val btnContinue = dialogView.findViewById<TextView>(R.id.btn_continue)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnContinue.setOnClickListener {
            dialog.dismiss()
            startNewMeeting(title)
        }

        dialog.show()
    }

    private fun startNewMeeting(title: String) {
        val intent = Intent(this, ActiveMeetingActivity::class.java)
        intent.putExtra(ActiveMeetingActivity.EXTRA_MEETING_TITLE, title)
        startActivity(intent)
    }

    private fun showMeetingDetails(meeting: MeetingMinute) {
        MeetingDetailsDialog.show(this, meeting) { confirmDeleteMeeting(it) }
    }

    /** True if the given epoch millis falls on the current calendar day. */
    private fun isToday(timeMillis: Long): Boolean {
        val now = java.util.Calendar.getInstance()
        val then = java.util.Calendar.getInstance().apply { timeInMillis = timeMillis }
        return now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
            now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
    }

    private fun confirmDeleteMeeting(meeting: MeetingMinute) {
        AlertDialog.Builder(this)
            .setTitle("Delete Meeting?")
            .setMessage("Are you sure you want to delete \"${meeting.title}\"?")
            .setPositiveButton("Delete") { _, _ ->
                meetingManager.deleteMeeting(meeting.id)
                Toast.makeText(this, "Meeting deleted", Toast.LENGTH_SHORT).show()
                loadMeetings()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        loadMeetings() // Refresh meetings when returning to this screen
    }
}
