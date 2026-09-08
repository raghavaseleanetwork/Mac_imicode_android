package com.sdk.glassessdksample.ui

import android.app.AlertDialog
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.sdk.glassessdksample.R
import com.sdk.glassessdksample.utils.SystemBarsInsets

/**
 * Activity for managing user memory and AI personalization
 * Allows users to set their preferences, likes/dislikes, and communication style
 */
class UserMemoryActivity : AppCompatActivity() {
    
    private lateinit var memoryManager: UserMemoryManager
    private lateinit var userMemory: UserMemory
    
    // UI elements
    private lateinit var etUserName: EditText
    private lateinit var etOccupation: EditText
    private lateinit var etLocation: EditText
    private lateinit var spinnerResponseStyle: Spinner
    private lateinit var spinnerLanguage: Spinner
    
    // Lists
    private lateinit var lvLikes: ListView
    private lateinit var lvDislikes: ListView
    private lateinit var lvInterests: ListView
    private lateinit var lvNotes: ListView
    
    private lateinit var btnAddLike: Button
    private lateinit var btnAddDislike: Button
    private lateinit var btnAddInterest: Button
    private lateinit var btnAddNote: Button
    private lateinit var btnSave: Button
    private lateinit var btnClear: Button
    
    // List adapters
    private lateinit var likesAdapter: ArrayAdapter<String>
    private lateinit var dislikesAdapter: ArrayAdapter<String>
    private lateinit var interestsAdapter: ArrayAdapter<String>
    private lateinit var notesAdapter: ArrayAdapter<String>
    
    private val likesList = mutableListOf<String>()
    private val dislikesList = mutableListOf<String>()
    private val interestsList = mutableListOf<String>()
    private val notesList = mutableListOf<String>()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_user_memory)
        SystemBarsInsets.apply(this)
        
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "AI Memory Settings"
        
        memoryManager = UserMemoryManager(this)
        
        initViews()
        loadUserMemory()
        setupListeners()
    }
    
    private fun initViews() {
        etUserName = findViewById(R.id.et_user_name)
        etOccupation = findViewById(R.id.et_occupation)
        etLocation = findViewById(R.id.et_location)
        spinnerResponseStyle = findViewById(R.id.spinner_response_style)
        spinnerLanguage = findViewById(R.id.spinner_language)
        
        lvLikes = findViewById(R.id.lv_likes)
        lvDislikes = findViewById(R.id.lv_dislikes)
        lvInterests = findViewById(R.id.lv_interests)
        lvNotes = findViewById(R.id.lv_notes)
        
        btnAddLike = findViewById(R.id.btn_add_like)
        btnAddDislike = findViewById(R.id.btn_add_dislike)
        btnAddInterest = findViewById(R.id.btn_add_interest)
        btnAddNote = findViewById(R.id.btn_add_note)
        btnSave = findViewById(R.id.btn_save)
        btnClear = findViewById(R.id.btn_clear)
        
        // Setup spinners
        ArrayAdapter.createFromResource(
            this,
            R.array.response_styles,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerResponseStyle.adapter = adapter
        }
        
        ArrayAdapter.createFromResource(
            this,
            R.array.languages,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerLanguage.adapter = adapter
        }
        
        // Setup list adapters
        likesAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, likesList)
        lvLikes.adapter = likesAdapter
        
        dislikesAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, dislikesList)
        lvDislikes.adapter = dislikesAdapter
        
        interestsAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, interestsList)
        lvInterests.adapter = interestsAdapter
        
        notesAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, notesList)
        lvNotes.adapter = notesAdapter
    }
    
    private fun loadUserMemory() {
        userMemory = memoryManager.getUserMemory()
        
        // Populate fields
        etUserName.setText(userMemory.userName)
        etOccupation.setText(userMemory.occupation)
        etLocation.setText(userMemory.location)
        
        // Set spinner selections
        val responseStyleIndex = when (userMemory.preferredResponseStyle) {
            UserMemory.ResponseStyle.BRIEF -> 0
            UserMemory.ResponseStyle.BALANCED -> 1
            UserMemory.ResponseStyle.DETAILED -> 2
        }
        spinnerResponseStyle.setSelection(responseStyleIndex)
        
        val languageIndex = when (userMemory.preferredLanguage) {
            "English" -> 0
            "Hindi" -> 1
            else -> 0
        }
        spinnerLanguage.setSelection(languageIndex)
        
        // Populate lists
        likesList.clear()
        likesList.addAll(userMemory.likes)
        likesAdapter.notifyDataSetChanged()
        
        dislikesList.clear()
        dislikesList.addAll(userMemory.dislikes)
        dislikesAdapter.notifyDataSetChanged()
        
        interestsList.clear()
        interestsList.addAll(userMemory.interests)
        interestsAdapter.notifyDataSetChanged()
        
        notesList.clear()
        notesList.addAll(userMemory.importantNotes)
        notesAdapter.notifyDataSetChanged()
    }
    
    private fun setupListeners() {
        btnAddLike.setOnClickListener { showAddDialog("Add Something You Like", likesList, likesAdapter) }
        btnAddDislike.setOnClickListener { showAddDialog("Add Something You Dislike", dislikesList, dislikesAdapter) }
        btnAddInterest.setOnClickListener { showAddDialog("Add an Interest", interestsList, interestsAdapter) }
        btnAddNote.setOnClickListener { showAddDialog("Add an Important Note", notesList, notesAdapter) }
        
        // Long press to remove item
        lvLikes.setOnItemLongClickListener { _, _, position, _ ->
            showRemoveDialog(likesList[position], likesList, likesAdapter, position)
            true
        }
        
        lvDislikes.setOnItemLongClickListener { _, _, position, _ ->
            showRemoveDialog(dislikesList[position], dislikesList, dislikesAdapter, position)
            true
        }
        
        lvInterests.setOnItemLongClickListener { _, _, position, _ ->
            showRemoveDialog(interestsList[position], interestsList, interestsAdapter, position)
            true
        }
        
        lvNotes.setOnItemLongClickListener { _, _, position, _ ->
            showRemoveDialog(notesList[position], notesList, notesAdapter, position)
            true
        }
        
        btnSave.setOnClickListener { saveUserMemory() }
        btnClear.setOnClickListener { clearAllMemory() }
    }
    
    private fun showAddDialog(title: String, list: MutableList<String>, adapter: ArrayAdapter<String>) {
        val input = EditText(this)
        input.hint = "Enter here..."
        
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty() && !list.contains(text)) {
                    list.add(text)
                    adapter.notifyDataSetChanged()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showRemoveDialog(item: String, list: MutableList<String>, adapter: ArrayAdapter<String>, position: Int) {
        AlertDialog.Builder(this)
            .setTitle("Remove Item")
            .setMessage("Remove '$item'?")
            .setPositiveButton("Remove") { _, _ ->
                list.removeAt(position)
                adapter.notifyDataSetChanged()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun saveUserMemory() {
        val responseStyle = when (spinnerResponseStyle.selectedItemPosition) {
            0 -> UserMemory.ResponseStyle.BRIEF
            1 -> UserMemory.ResponseStyle.BALANCED
            2 -> UserMemory.ResponseStyle.DETAILED
            else -> UserMemory.ResponseStyle.BALANCED
        }
        
        val language = when (spinnerLanguage.selectedItemPosition) {
            0 -> "English"
            1 -> "Hindi"
            else -> "English"
        }
        
        val updatedMemory = UserMemory(
            userName = etUserName.text.toString().trim(),
            occupation = etOccupation.text.toString().trim(),
            location = etLocation.text.toString().trim(),
            likes = likesList.toList(),
            dislikes = dislikesList.toList(),
            interests = interestsList.toList(),
            preferredResponseStyle = responseStyle,
            preferredLanguage = language,
            importantNotes = notesList.toList(),
            lastUpdated = System.currentTimeMillis()
        )
        
        memoryManager.saveUserMemory(updatedMemory)
        
        Toast.makeText(this, "✅ Memory saved! AI will personalize responses.", Toast.LENGTH_SHORT).show()
        finish()
    }
    
    private fun clearAllMemory() {
        AlertDialog.Builder(this)
            .setTitle("Clear All Memory")
            .setMessage("This will delete all your preferences and the AI will forget everything about you. Continue?")
            .setPositiveButton("Clear All") { _, _ ->
                memoryManager.clearAll()
                Toast.makeText(this, "🗑️ All memory cleared", Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
