package com.sdk.glassessdksample.ui.gallery

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.sdk.glassessdksample.R
import java.io.File
import kotlin.math.abs
import com.sdk.glassessdksample.utils.SystemBarsInsets

/**
 * Full-screen image viewer for Glass Gallery images
 * Shows downloaded images in full screen with zoom/pan support
 */
class ImageViewerActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "ImageViewerActivity"
        private const val EXTRA_IMAGE_PATH = "image_path"
        private const val EXTRA_IMAGE_NAME = "image_name"
        private const val EXTRA_ALL_IMAGES = "all_images"
        private const val EXTRA_CURRENT_INDEX = "current_index"
        
        fun open(context: Context, imagePath: String, imageName: String, allImages: ArrayList<String>? = null, currentIndex: Int = 0) {
            val intent = Intent(context, ImageViewerActivity::class.java).apply {
                putExtra(EXTRA_IMAGE_PATH, imagePath)
                putExtra(EXTRA_IMAGE_NAME, imageName)
                putStringArrayListExtra(EXTRA_ALL_IMAGES, allImages)
                putExtra(EXTRA_CURRENT_INDEX, currentIndex)
            }
            context.startActivity(intent)
        }
    }
    
    private lateinit var imageView: ImageView
    private lateinit var closeButton: View
    private lateinit var shareButton: View
    private lateinit var deleteButton: View
    private var imagePath: String? = null
    private var allImagePaths: ArrayList<String>? = null
    private var currentImageIndex: Int = 0
    private lateinit var gestureDetector: GestureDetector
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Full screen immersive mode
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
        
        setContentView(R.layout.activity_image_viewer)
        SystemBarsInsets.apply(this, fullscreen = true)
        
        imageView = findViewById(R.id.fullscreenImageView)
        closeButton = findViewById(R.id.btnClose)
        shareButton = findViewById(R.id.btnShare)
        deleteButton = findViewById(R.id.btnDelete)
        
        imagePath = intent.getStringExtra(EXTRA_IMAGE_PATH)
        val imageName = intent.getStringExtra(EXTRA_IMAGE_NAME) ?: "Image"
        allImagePaths = intent.getStringArrayListExtra(EXTRA_ALL_IMAGES)
        currentImageIndex = intent.getIntExtra(EXTRA_CURRENT_INDEX, 0)
        
        closeButton.setOnClickListener { finish() }
        shareButton.setOnClickListener { shareImage() }
        deleteButton.setOnClickListener { showDeleteConfirmation() }
        
        // Setup swipe gesture for navigation
        setupSwipeGesture()
        
        loadImage()
    }
    
    private fun loadImage() {
        val path = imagePath ?: run {
            Toast.makeText(this, "Image path not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        try {
            val file = File(path)
            if (!file.exists()) {
                Toast.makeText(this, "Image file not found", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            
            // Load image with efficient bitmap decoding
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(path, options)
            
            // Calculate optimal sample size
            options.inSampleSize = calculateInSampleSize(options, 1080, 1920)
            options.inJustDecodeBounds = false
            
            val bitmap = BitmapFactory.decodeFile(path, options)
            imageView.setImageBitmap(bitmap)
            
        } catch (e: Exception) {
            Toast.makeText(this, "Error loading image: ${e.message}", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
    
    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1
        
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            
            while (halfHeight / inSampleSize >= reqHeight && 
                   halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        
        return inSampleSize
    }
    
    private fun shareImage() {
        val path = imagePath ?: return
        
        try {
            val file = File(path)
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )
            
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            startActivity(Intent.createChooser(shareIntent, "Share Image"))
        } catch (e: Exception) {
            Toast.makeText(this, "Error sharing image: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 🗑️ Show delete confirmation dialog
     */
    private fun showDeleteConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Delete Photo")
            .setMessage("Are you sure you want to delete this photo? This action cannot be undone.")
            .setPositiveButton("Delete") { _, _ -> deleteCurrentImage() }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    /**
     * 🗑️ Delete current image file
     */
    private fun deleteCurrentImage() {
        val path = imagePath ?: return
        
        try {
            val file = File(path)
            if (file.exists() && file.delete()) {
                Toast.makeText(this, "Photo deleted", Toast.LENGTH_SHORT).show()
                
                // Remove from list if available
                allImagePaths?.remove(path)
                
                // If more images available, show next/previous, else close
                if (!allImagePaths.isNullOrEmpty()) {
                    if (currentImageIndex >= allImagePaths!!.size) {
                        currentImageIndex = allImagePaths!!.size - 1
                    }
                    imagePath = allImagePaths!![currentImageIndex]
                    loadImage()
                } else {
                    finish() // No more images, close viewer
                }
            } else {
                Toast.makeText(this, "Failed to delete photo", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error deleting photo: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 👆 Setup swipe gesture for left/right navigation (like Google Photos)
     */
    private fun setupSwipeGesture() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            private val SWIPE_THRESHOLD = 100
            private val SWIPE_VELOCITY_THRESHOLD = 100
            
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (allImagePaths.isNullOrEmpty()) return false
                
                try {
                    val diffX = e2.x - (e1?.x ?: 0f)
                    val diffY = e2.y - (e1?.y ?: 0f)
                    
                    if (abs(diffX) > abs(diffY)) {
                        if (abs(diffX) > SWIPE_THRESHOLD && abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                            if (diffX > 0) {
                                // Swipe right - show previous image
                                showPreviousImage()
                            } else {
                                // Swipe left - show next image
                                showNextImage()
                            }
                            return true
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                return false
            }
        })
        
        imageView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }
    
    /**
     * ➡️ Show next image in gallery
     */
    private fun showNextImage() {
        val images = allImagePaths ?: return
        if (images.isEmpty()) return
        
        currentImageIndex = (currentImageIndex + 1) % images.size
        imagePath = images[currentImageIndex]
        loadImage()
        Toast.makeText(this, "${currentImageIndex + 1} / ${images.size}", Toast.LENGTH_SHORT).show()
    }
    
    /**
     * ⬅️ Show previous image in gallery
     */
    private fun showPreviousImage() {
        val images = allImagePaths ?: return
        if (images.isEmpty()) return
        
        currentImageIndex = if (currentImageIndex - 1 < 0) images.size - 1 else currentImageIndex - 1
        imagePath = images[currentImageIndex]
        loadImage()
        Toast.makeText(this, "${currentImageIndex + 1} / ${images.size}", Toast.LENGTH_SHORT).show()
    }
    
    override fun onBackPressed() {
        super.onBackPressed()
        finish()
    }
}
