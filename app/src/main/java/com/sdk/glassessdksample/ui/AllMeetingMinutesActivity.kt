package com.sdk.glassessdksample.ui

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sdk.glassessdksample.R
import com.sdk.glassessdksample.utils.SystemBarsInsets

/**
 * Shows ALL of the user's recorded meetings (not just today's).
 * Opened via the "View All" action on [MeetingMinutesActivity].
 */
class AllMeetingMinutesActivity : AppCompatActivity() {

    private lateinit var meetingManager: MeetingMinutesManager
    private lateinit var adapter: AllMeetingMinutesAdapter
    private lateinit var rvMeetings: RecyclerView
    private lateinit var btnBack: ImageView
    private lateinit var layoutEmptyState: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_all_meeting_minutes)
        SystemBarsInsets.apply(this)

        meetingManager = MeetingMinutesManager(this)

        rvMeetings = findViewById(R.id.rv_all_meetings)
        btnBack = findViewById(R.id.btn_back)
        layoutEmptyState = findViewById(R.id.layout_empty_state)

        btnBack.setOnClickListener { finish() }

        adapter = AllMeetingMinutesAdapter(
            meetings = emptyList(),
            onView = { meeting -> MeetingDetailsDialog.show(this, meeting) { confirmDeleteMeeting(it) } },
            onDelete = { meeting -> confirmDeleteMeeting(meeting) }
        )
        rvMeetings.apply {
            layoutManager = LinearLayoutManager(this@AllMeetingMinutesActivity)
            adapter = this@AllMeetingMinutesActivity.adapter
        }

        loadMeetings()
    }

    private fun loadMeetings() {
        val meetings = meetingManager.getAllMeetings()
        adapter.updateMeetings(meetings)

        if (meetings.isEmpty()) {
            layoutEmptyState.visibility = View.VISIBLE
            rvMeetings.visibility = View.GONE
        } else {
            layoutEmptyState.visibility = View.GONE
            rvMeetings.visibility = View.VISIBLE
        }
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
        loadMeetings()
    }
}
