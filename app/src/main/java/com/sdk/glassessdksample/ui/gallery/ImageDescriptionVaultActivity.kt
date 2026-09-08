package com.sdk.glassessdksample.ui.gallery

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sdk.glassessdksample.R
import com.sdk.glassessdksample.utils.SystemBarsInsets

/**
 * 🔐 Image Descriptions Vault — hidden page
 *
 * This activity is NOT linked from the main navigation.
 * Access it by long-pressing the photo-count label in LiveGalleryActivity.
 *
 * It shows every AI-generated description stored in [ImageDescriptionStore],
 * with the most-recent image always pinned at the top.
 *
 * When the chat AI is asked "what was in my last photo", [ImageDescriptionStore.getLast]
 * is used to retrieve this top entry instantly — no network call required.
 */
class ImageDescriptionVaultActivity : AppCompatActivity() {

    companion object {
        fun launch(context: Context) {
            context.startActivity(Intent(context, ImageDescriptionVaultActivity::class.java))
        }
    }

    private lateinit var rvVault: RecyclerView
    private lateinit var tvVaultCount: TextView
    private lateinit var tvVaultEmpty: View
    private lateinit var etSearch: EditText

    private val allEntries = mutableListOf<ImageDescriptionStore.DescEntry>()
    private val filtered   = mutableListOf<ImageDescriptionStore.DescEntry>()
    private var adapter: VaultAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_description_vault)
        SystemBarsInsets.apply(this)

        rvVault      = findViewById(R.id.rvVault)
        tvVaultCount = findViewById(R.id.tvVaultCount)
        tvVaultEmpty = findViewById(R.id.tvVaultEmpty)
        etSearch     = findViewById(R.id.etVaultSearch)

        findViewById<View>(R.id.btnVaultBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnVaultClear).setOnClickListener { confirmClear() }

        adapter = VaultAdapter(filtered)
        rvVault.layoutManager = LinearLayoutManager(this)
        rvVault.adapter = adapter

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applyFilter(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        loadVault()
    }

    override fun onResume() {
        super.onResume()
        loadVault()
    }

    private fun loadVault() {
        allEntries.clear()
        allEntries.addAll(ImageDescriptionStore.getAll(this))
        applyFilter(etSearch.text.toString())

        val count = allEntries.size
        tvVaultCount.text = "$count description${if (count == 1) "" else "s"} stored"
    }

    private fun applyFilter(query: String) {
        filtered.clear()
        if (query.isBlank()) {
            filtered.addAll(allEntries)
        } else {
            val q = query.lowercase()
            filtered.addAll(allEntries.filter {
                it.fileName.lowercase().contains(q) || it.description.lowercase().contains(q)
            })
        }
        adapter?.notifyDataSetChanged()
        tvVaultEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        rvVault.visibility      = if (filtered.isEmpty()) View.GONE   else View.VISIBLE
    }

    private fun confirmClear() {
        if (allEntries.isEmpty()) {
            Toast.makeText(this, "Vault is already empty", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Clear Vault")
            .setMessage("Delete all ${allEntries.size} stored descriptions? This cannot be undone.")
            .setPositiveButton("Delete All") { _, _ ->
                val vaultFile = java.io.File(filesDir, "image_description_vault/vault.json")
                if (vaultFile.exists()) vaultFile.delete()
                Toast.makeText(this, "Vault cleared", Toast.LENGTH_SHORT).show()
                loadVault()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─── Adapter ──────────────────────────────────────────────────────────

    inner class VaultAdapter(
        private val items: List<ImageDescriptionStore.DescEntry>
    ) : RecyclerView.Adapter<VaultAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tvFileName    : TextView = view.findViewById(R.id.tvVaultFileName)
            val tvTimestamp   : TextView = view.findViewById(R.id.tvVaultTimestamp)
            val tvDescription : TextView = view.findViewById(R.id.tvVaultDescription)
            val tvBadge       : TextView = view.findViewById(R.id.tvLatestBadge)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_vault_description, parent, false)
            return VH(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val entry = items[position]
            holder.tvFileName.text    = entry.fileName
            holder.tvTimestamp.text   = entry.displayTime
            holder.tvDescription.text = entry.description

            // "Most Recent" badge only on position 0 (when not filtering)
            holder.tvBadge.visibility =
                if (position == 0 && etSearch.text.isBlank()) View.VISIBLE else View.GONE

            // Long-press to copy description to clipboard
            holder.itemView.setOnLongClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText("Description", entry.description)
                )
                Toast.makeText(
                    this@ImageDescriptionVaultActivity,
                    "Description copied to clipboard",
                    Toast.LENGTH_SHORT
                ).show()
                true
            }
        }
    }
}
