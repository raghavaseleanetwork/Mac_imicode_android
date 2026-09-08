package com.sdk.glassessdksample.ui.gallery

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.sdk.glassessdksample.R
import java.io.File
import java.io.FileInputStream
import com.sdk.glassessdksample.utils.SystemBarsInsets

/**
 * Full-screen video player for Glass Gallery videos
 * Plays downloaded videos in full screen with controls
 */
class VideoPlayerActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "VideoPlayerActivity"
        private const val EXTRA_VIDEO_PATH = "video_path"
        private const val EXTRA_VIDEO_NAME = "video_name"
        
        fun open(context: Context, videoPath: String, videoName: String) {
            val intent = Intent(context, VideoPlayerActivity::class.java).apply {
                putExtra(EXTRA_VIDEO_PATH, videoPath)
                putExtra(EXTRA_VIDEO_NAME, videoName)
            }
            context.startActivity(intent)
        }
    }
    
    private lateinit var videoView: VideoView
    private lateinit var closeButton: ImageView
    private lateinit var shareButton: ImageView
    private lateinit var deleteButton: ImageView
    private var videoPath: String? = null
    private var currentPosition = 0
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Full screen immersive mode
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
        
        setContentView(R.layout.activity_video_player)
        SystemBarsInsets.apply(this, fullscreen = true)
        
        videoView = findViewById(R.id.videoView)
        closeButton = findViewById(R.id.btnClose)
        shareButton = findViewById(R.id.btnShare)
        deleteButton = findViewById(R.id.btnDelete)
        
        videoPath = intent.getStringExtra(EXTRA_VIDEO_PATH)
        val videoName = intent.getStringExtra(EXTRA_VIDEO_NAME) ?: "Video"
        
        closeButton.setOnClickListener { finish() }
        shareButton.setOnClickListener { shareVideo() }
        deleteButton.setOnClickListener { showDeleteConfirmation() }
        
        setupVideoPlayer()
    }
    
    private fun setupVideoPlayer() {
        val path = videoPath ?: run {
            Toast.makeText(this, "Video path not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        try {
            val file = File(path)
            if (!file.exists()) {
                Toast.makeText(this, "Video file not found", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            
            if (!file.canRead()) {
                Toast.makeText(this, "Cannot read video file", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            
            Log.d(TAG, "Loading video: $path (size: ${file.length()} bytes, readable: ${file.canRead()})")
            
            // Reset VideoView completely before setting new video
            videoView.stopPlayback()
            videoView.setVideoURI(null)
            
            // Setup media controls
            val mediaController = MediaController(this)
            mediaController.setAnchorView(videoView)
            videoView.setMediaController(mediaController)
            
            // Handle errors BEFORE setting video
            videoView.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "Video error - what: $what, extra: $extra, path: $path, readable: ${file.canRead()}")
                runOnUiThread {
                    // Show option to open with external player
                    android.app.AlertDialog.Builder(this)
                        .setTitle("Cannot Play Video")
                        .setMessage("This video cannot be played in the app. Would you like to open it with an external player?")
                        .setPositiveButton("Open Externally") { _, _ ->
                            openVideoExternally(path)
                            finish()
                        }
                        .setNegativeButton("Close") { _, _ ->
                            finish()
                        }
                        .setCancelable(false)
                        .show()
                }
                true
            }
            
            // Set video preparation listener BEFORE setting URI
            videoView.setOnPreparedListener { mp ->
                Log.d(TAG, "Video prepared successfully, starting playback")
                mp.isLooping = false
                mp.setOnInfoListener { _, what, extra ->
                    Log.d(TAG, "MediaPlayer info: what=$what, extra=$extra")
                    false
                }
                if (currentPosition > 0) {
                    videoView.seekTo(currentPosition)
                }
                videoView.start()
            }
            
            // Handle completion
            videoView.setOnCompletionListener {
                Log.d(TAG, "Video playback completed")
                Toast.makeText(this, "Video ended", Toast.LENGTH_SHORT).show()
            }
            
            // Now set the video URI (use Uri.parse for better compatibility)
            val videoUri = Uri.parse("file://$path")
            Log.d(TAG, "Setting video URI: $videoUri")
            videoView.setVideoURI(videoUri)
            
        } catch (e: Exception) {
            Log.e(TAG, "Exception loading video", e)
            Toast.makeText(this, "Error loading video: ${e.message}", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
    
    private fun openVideoExternally(path: String) {
        try {
            val file = File(path)
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )
            
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            
            // Grant URI permission to all apps that can handle this intent
            val resInfoList = packageManager.queryIntentActivities(intent, 0)
            for (resolveInfo in resInfoList) {
                val packageName = resolveInfo.activityInfo.packageName
                grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            // Use chooser to let user select video player
            try {
                startActivity(Intent.createChooser(intent, "Open Video With"))
            } catch (e: Exception) {
                Toast.makeText(this, "No app found to play videos", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening video externally", e)
            Toast.makeText(this, "Cannot open video: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun shareVideo() {
        val path = videoPath ?: return
        
        try {
            val file = File(path)
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )
            
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "video/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            startActivity(Intent.createChooser(shareIntent, "Share Video"))
        } catch (e: Exception) {
            Toast.makeText(this, "Error sharing video: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 🗑️ Show delete confirmation dialog
     */
    private fun showDeleteConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Delete Video")
            .setMessage("Are you sure you want to delete this video? This action cannot be undone.")
            .setPositiveButton("Delete") { _, _ -> deleteVideo() }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    /**
     * 🗑️ Delete current video file
     */
    private fun deleteVideo() {
        val path = videoPath ?: return
        
        try {
            // Stop playback first
            videoView.stopPlayback()
            
            val file = File(path)
            if (file.exists() && file.delete()) {
                Toast.makeText(this, "Video deleted", Toast.LENGTH_SHORT).show()
                finish() // Close viewer after deleting
            } else {
                Toast.makeText(this, "Failed to delete video", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error deleting video: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onPause() {
        super.onPause()
        // Save current position when pausing
        currentPosition = videoView.currentPosition
        videoView.pause()
    }
    
    override fun onResume() {
        super.onResume()
        // Resume playback if we had saved position
        if (currentPosition > 0 && !videoView.isPlaying) {
            videoView.seekTo(currentPosition)
            videoView.start()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        videoView.stopPlayback()
    }
    
    override fun onBackPressed() {
        super.onBackPressed()
        finish()
    }
}
