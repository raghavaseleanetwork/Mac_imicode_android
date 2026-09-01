package com.sdk.glassessdksample

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.sdk.glassessdksample.databinding.ActivityMoreBinding
import com.sdk.glassessdksample.ui.BottomNavManager
import com.sdk.glassessdksample.ui.ChatActivity
import com.sdk.glassessdksample.ui.ConversationHistoryActivity
import com.sdk.glassessdksample.ui.MeetingMinutesActivity
import com.sdk.glassessdksample.ui.QuickNotesActivity
import com.sdk.glassessdksample.ui.UserMemoryActivity
import com.sdk.glassessdksample.ui.web.WebBrowserActivity
import com.sdk.glassessdksample.ui.gallery.GlassMediaGalleryActivity
import com.sdk.glassessdksample.ui.gallery.ImageDescriptionVaultActivity
import java.io.File

class MoreActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMoreBinding
    private val recentPhotos = mutableListOf<File>()
    private lateinit var recentsAdapter: RecentsSliderAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMoreBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupActions()
        setupRecentsSlider()
        BottomNavManager.setup(binding.bottomNavigation, R.id.nav_more, this)
    }

    override fun onResume() {
        super.onResume()
        loadRecentPhotos()
    }

    private fun setupActions() {
        binding.cardCamera.setOnClickListener { open(CameraActivity::class.java) }
        binding.cardQuickNotes.setOnClickListener { open(QuickNotesActivity::class.java) }
        binding.cardWeb.setOnClickListener { open(WebBrowserActivity::class.java) }
        binding.cardMeetingMinutes.setOnClickListener { open(MeetingMinutesActivity::class.java) }
        binding.cardConversationHistory.setOnClickListener { open(ConversationHistoryActivity::class.java) }
        binding.cardChat.setOnClickListener { open(ChatActivity::class.java) }
        binding.cardUserMemory.setOnClickListener { open(UserMemoryActivity::class.java) }
        binding.cardLiveGallery.setOnClickListener { open(GlassMediaGalleryActivity::class.java) }
        binding.cardVisionDescriptions.setOnClickListener { open(ImageDescriptionVaultActivity::class.java) }
        binding.cardGlassGallery.setOnClickListener { open(GlassMediaGalleryActivity::class.java) }
        binding.cardVisionChat.setOnClickListener { open(VisionChatActivity::class.java) }
    }

    private fun setupRecentsSlider() {
        recentsAdapter = RecentsSliderAdapter(recentPhotos) {
            // Tapping a recent photo opens the Glass Media Gallery
            open(GlassMediaGalleryActivity::class.java)
        }
        binding.pagerRecents.adapter = recentsAdapter

        binding.btnRecentsPrev.setOnClickListener {
            val current = binding.pagerRecents.currentItem
            if (current > 0) binding.pagerRecents.setCurrentItem(current - 1, true)
        }
        binding.btnRecentsNext.setOnClickListener {
            val current = binding.pagerRecents.currentItem
            if (current < recentPhotos.size - 1) binding.pagerRecents.setCurrentItem(current + 1, true)
        }
    }

    private fun loadRecentPhotos() {
        val photos = getRecentGlassPhotos().take(MAX_RECENTS)
        recentPhotos.clear()
        recentPhotos.addAll(photos)
        recentsAdapter.notifyDataSetChanged()

        val hasPhotos = recentPhotos.isNotEmpty()
        binding.tvRecentsEmpty.visibility = if (hasPhotos) View.GONE else View.VISIBLE
        binding.btnRecentsPrev.visibility = if (recentPhotos.size > 1) View.VISIBLE else View.GONE
        binding.btnRecentsNext.visibility = if (recentPhotos.size > 1) View.VISIBLE else View.GONE
    }

    /**
     * Reads the most recent photos from the same folder the Glass Media Gallery
     * displays: public Pictures/GlassMedia/. Newest first.
     */
    private fun getRecentGlassPhotos(): List<File> {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "GlassMedia"
        )
        val files = dir.listFiles { f ->
            f.isFile && f.extension.lowercase() in IMAGE_EXTENSIONS
        } ?: return emptyList()
        return files.sortedByDescending { it.lastModified() }
    }

    private fun open(target: Class<*>) {
        startActivity(Intent(this, target))
    }

    /** Slider adapter that shows the user's most recent captured photos. */
    private class RecentsSliderAdapter(
        private val items: List<File>,
        private val onTap: () -> Unit
    ) : RecyclerView.Adapter<RecentsSliderAdapter.SlideVH>() {

        inner class SlideVH(val image: ImageView) : RecyclerView.ViewHolder(image)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SlideVH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_more_recent_slide, parent, false) as ImageView
            return SlideVH(view)
        }

        override fun onBindViewHolder(holder: SlideVH, position: Int) {
            val file = items[position]
            try {
                val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                val bmp = BitmapFactory.decodeFile(file.absolutePath, opts)
                if (bmp != null) holder.image.setImageBitmap(bmp)
                else holder.image.setImageResource(R.drawable.ic_gallery)
            } catch (_: Exception) {
                holder.image.setImageResource(R.drawable.ic_gallery)
            }
            holder.image.setOnClickListener { onTap() }
        }

        override fun getItemCount(): Int = items.size
    }

    companion object {
        private const val MAX_RECENTS = 12
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "bmp")
    }
}
