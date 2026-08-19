package com.filemanager.ui.cleanup

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.filemanager.R
import com.filemanager.data.model.FileType
import com.filemanager.databinding.ItemCleanupEntryBinding
import com.filemanager.databinding.ItemCleanupHeaderBinding
import com.filemanager.utils.FileUtils

class CleanupAdapter(
    private val onItemToggle: (CleanupItem) -> Unit,
    private val onCategoryToggleSelect: (CleanupCategory, Boolean) -> Unit,
    private val onHeaderClick: (CleanupCategory) -> Unit
) : ListAdapter<CleanupListItem, RecyclerView.ViewHolder>(DIFF) {

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_ENTRY  = 1

        val DIFF = object : DiffUtil.ItemCallback<CleanupListItem>() {
            override fun areItemsTheSame(a: CleanupListItem, b: CleanupListItem): Boolean =
                when {
                    a is CleanupListItem.Header && b is CleanupListItem.Header ->
                        a.category == b.category
                    a is CleanupListItem.Entry && b is CleanupListItem.Entry ->
                        a.item.fileItem.path == b.item.fileItem.path
                    else -> false
                }
            override fun areContentsTheSame(a: CleanupListItem, b: CleanupListItem) = a == b
        }
    }

    fun isHeader(pos: Int) = pos in 0 until itemCount && getItemViewType(pos) == TYPE_HEADER

    override fun getItemViewType(pos: Int) = when (getItem(pos)) {
        is CleanupListItem.Header -> TYPE_HEADER
        is CleanupListItem.Entry  -> TYPE_ENTRY
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER)
            HeaderVH(ItemCleanupHeaderBinding.inflate(inf, parent, false))
        else
            EntryVH(ItemCleanupEntryBinding.inflate(inf, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int) {
        when (val item = getItem(pos)) {
            is CleanupListItem.Header -> (holder as HeaderVH).bind(item)
            is CleanupListItem.Entry  -> (holder as EntryVH).bind(item.item)
        }
    }

    // ── Header ViewHolder ───────────────────────────────────────

    inner class HeaderVH(private val b: ItemCleanupHeaderBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(h: CleanupListItem.Header) {
            b.tvIcon.text         = h.category.icon
            b.tvCategoryName.text = h.category.label
            b.tvCategoryInfo.text = "${h.count} mục · ${FileUtils.formatSize(h.totalSize)}"

            b.ivChevron.setImageResource(
                if (h.isExpanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right
            )

            // Checkbox chọn tất cả trong category
            b.cbSelectAll.setOnCheckedChangeListener(null)
            b.cbSelectAll.isChecked = false // reset trước
            b.cbSelectAll.setOnCheckedChangeListener { _, checked ->
                onCategoryToggleSelect(h.category, checked)
            }

            // Tap header → collapse/expand
            b.root.setOnClickListener { onHeaderClick(h.category) }
        }
    }

    // ── Entry ViewHolder ────────────────────────────────────────

    inner class EntryVH(private val b: ItemCleanupEntryBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(item: CleanupItem) {
            val file = item.fileItem
            b.tvName.text = file.name
            b.tvPath.text = file.file.parent ?: file.path
            b.tvSize.text = if (file.size > 0) FileUtils.formatSize(file.size) else "0 B"

            // Icon / thumbnail
            when (file.fileType) {
                FileType.IMAGE -> {
                    Glide.with(b.root).load(file.file)
                        .centerCrop().placeholder(R.drawable.ic_image).into(b.ivIcon)
                }
                FileType.VIDEO -> {
                    Glide.with(b.root).load(file.file)
                        .centerCrop().placeholder(R.drawable.ic_video).into(b.ivIcon)
                }
                else -> {
                    Glide.with(b.root).clear(b.ivIcon)
                    b.ivIcon.setImageResource(iconFor(file.fileType, file.isDirectory))
                }
            }

            b.cbItem.setOnCheckedChangeListener(null)
            b.cbItem.isChecked = item.isSelected
            b.cbItem.setOnCheckedChangeListener { _, _ -> onItemToggle(item) }

            b.root.setOnClickListener { onItemToggle(item) }
        }

        private fun iconFor(type: FileType, isDir: Boolean) = when {
            isDir               -> R.drawable.ic_folder
            type == FileType.VIDEO    -> R.drawable.ic_video
            type == FileType.AUDIO    -> R.drawable.ic_audio
            type == FileType.DOCUMENT -> R.drawable.ic_document
            type == FileType.ARCHIVE  -> R.drawable.ic_archive
            type == FileType.APK      -> R.drawable.ic_apk
            type == FileType.CODE     -> R.drawable.ic_code
            else                -> R.drawable.ic_file
        }
    }
}
