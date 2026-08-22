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
            override fun areItemsTheSame(a: CleanupListItem, b: CleanupListItem) = when {
                a is CleanupListItem.Header && b is CleanupListItem.Header ->
                    a.category == b.category && a.count == b.count   // count âm = "more" placeholder
                a is CleanupListItem.Entry  && b is CleanupListItem.Entry  ->
                    a.item.fileItem.path == b.item.fileItem.path
                else -> false
            }
            override fun areContentsTheSame(a: CleanupListItem, b: CleanupListItem): Boolean {
                if (a is CleanupListItem.Header && b is CleanupListItem.Header)
                    return a.isExpanded == b.isExpanded &&
                           a.count     == b.count      &&
                           a.totalSize == b.totalSize
                if (a is CleanupListItem.Entry && b is CleanupListItem.Entry)
                    return a.item.isSelected == b.item.isSelected
                return false
            }
        }
    }

    // ✅ stableIds → DiffUtil nhanh hơn
    init { setHasStableIds(true) }

    override fun getItemId(position: Int): Long {
        return when (val item = getItem(position)) {
            is CleanupListItem.Header -> item.category.ordinal.toLong() * -1 - 1
            is CleanupListItem.Entry  -> item.item.fileItem.path.hashCode().toLong()
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

    // ✅ Clear Glide khi recycle để tránh memory leak
    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is EntryVH) {
            Glide.with(holder.itemView).clear(holder.binding.ivIcon)
        }
    }

    // ── Header VH ───────────────────────────────────────────────

    inner class HeaderVH(private val b: ItemCleanupHeaderBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(h: CleanupListItem.Header) {
            // count âm = placeholder "... và N mục khác"
            if (h.count < 0) {
                b.tvIcon.text         = "➕"
                b.tvCategoryName.text = "... và ${-h.count} mục khác"
                b.tvCategoryInfo.text = "(chạm để xem tất cả)"
                b.cbSelectAll.visibility = View.GONE
                b.ivChevron.visibility   = View.GONE
                b.root.setOnClickListener { onHeaderClick(h.category) }
                return
            }

            b.tvIcon.text         = h.category.icon
            b.tvCategoryName.text = h.category.label

            // Mô tả: số lượng + dung lượng (nếu có)
            val sizeStr = if (h.totalSize > 0) " · ${FileUtils.formatSize(h.totalSize)}" else ""
            b.tvCategoryInfo.text = "${h.count} mục$sizeStr"

            // Chevron theo trạng thái expanded/collapsed
            b.ivChevron.setImageResource(
                if (h.isExpanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right
            )
            b.ivChevron.visibility   = View.VISIBLE
            b.cbSelectAll.visibility = View.VISIBLE

            // Checkbox select-all trong category
            b.cbSelectAll.setOnCheckedChangeListener(null)
            b.cbSelectAll.isChecked = false
            b.cbSelectAll.setOnCheckedChangeListener { _, checked ->
                onCategoryToggleSelect(h.category, checked)
            }

            // Tap header → expand/collapse
            b.root.setOnClickListener { onHeaderClick(h.category) }
        }
    }

    // ── Entry VH ────────────────────────────────────────────────

    inner class EntryVH(val binding: ItemCleanupEntryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: CleanupItem) {
            val file = item.fileItem
            binding.tvName.text = file.name
            binding.tvPath.text = file.file.parent ?: ""
            binding.tvSize.text = if (file.size > 0) FileUtils.formatSize(file.size) else "0 B"

            // Icon — ảnh/video dùng Glide, còn lại dùng vector icon tĩnh
            when (file.fileType) {
                FileType.IMAGE -> Glide.with(binding.root)
                    .load(file.file).centerCrop()
                    .placeholder(R.drawable.ic_image).into(binding.ivIcon)
                FileType.VIDEO -> Glide.with(binding.root)
                    .load(file.file).centerCrop()
                    .placeholder(R.drawable.ic_video).into(binding.ivIcon)
                else -> {
                    Glide.with(binding.root).clear(binding.ivIcon)
                    binding.ivIcon.setImageResource(iconRes(file.fileType, file.isDirectory))
                }
            }

            binding.cbItem.setOnCheckedChangeListener(null)
            binding.cbItem.isChecked = item.isSelected
            binding.cbItem.setOnCheckedChangeListener { _, _ -> onItemToggle(item) }
            binding.root.setOnClickListener { onItemToggle(item) }
        }

        private fun iconRes(type: FileType, isDir: Boolean) = when {
            isDir               -> R.drawable.ic_folder
            type == FileType.VIDEO    -> R.drawable.ic_video
            type == FileType.AUDIO    -> R.drawable.ic_audio
            type == FileType.DOCUMENT -> R.drawable.ic_document
            type == FileType.ARCHIVE  -> R.drawable.ic_archive
            type == FileType.APK      -> R.drawable.ic_apk
            else                -> R.drawable.ic_file
        }
    }
}
