package com.filemanager.ui.main

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.AdapterListUpdateCallback
import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestOptions
import com.filemanager.R
import com.filemanager.data.model.FileItem
import com.filemanager.data.model.FileType
import com.filemanager.databinding.ItemFileGridBinding
import com.filemanager.databinding.ItemFileListBinding
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors

// Sealed class đại diện cho item hiển thị trong danh sách file (Bao gồm FileItem thông thường hoặc Header nhóm khi search)
sealed class FileDisplayItem {
    data class Header(
        val type: FileType,
        val title: String,
        val count: Int,
        val isExpanded: Boolean = true
    ) : FileDisplayItem()

    data class Item(val file: FileItem) : FileDisplayItem()
}

class FileListAdapter(
    private val onItemClick: (FileItem) -> Unit,
    private val onItemLongClick: (FileItem) -> Unit,
    private val onSelectionChange: (FileItem) -> Unit,
    private val onHeaderToggle: ((FileType) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val VIEW_LIST   = 0
        const val VIEW_GRID   = 1
        const val VIEW_HEADER = 2

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<FileDisplayItem>() {
            override fun areItemsTheSame(old: FileDisplayItem, new: FileDisplayItem) = when {
                old is FileDisplayItem.Header && new is FileDisplayItem.Header ->
                    old.type == new.type
                old is FileDisplayItem.Item && new is FileDisplayItem.Item ->
                    old.file.path == new.file.path
                else -> false
            }

            override fun areContentsTheSame(old: FileDisplayItem, new: FileDisplayItem) = when {
                old is FileDisplayItem.Header && new is FileDisplayItem.Header ->
                    old.title == new.title && old.count == new.count && old.isExpanded == new.isExpanded
                old is FileDisplayItem.Item && new is FileDisplayItem.Item ->
                    old.file.isSelected == new.file.isSelected && old.file.lastModified == new.file.lastModified
                else -> false
            }

            override fun getChangePayload(old: FileDisplayItem, new: FileDisplayItem): Any? {
                if (old is FileDisplayItem.Item && new is FileDisplayItem.Item) {
                    return if (old.file.isSelected != new.file.isSelected) "selection" else null
                }
                return null
            }
        }

        private val GLIDE_THUMB_OPTIONS = RequestOptions()
            .override(120, 120)
            .format(DecodeFormat.PREFER_RGB_565)
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
            .dontAnimate()

        private val GLIDE_GRID_OPTIONS = RequestOptions()
            .override(200, 200)
            .format(DecodeFormat.PREFER_RGB_565)
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
            .dontAnimate()
    }

    private val bgExecutor = Executors.newSingleThreadExecutor()

    init { setHasStableIds(true) }

    private val differ = AsyncListDiffer(
        AdapterListUpdateCallback(this),
        AsyncDifferConfig.Builder(DIFF_CALLBACK)
            .setBackgroundThreadExecutor(bgExecutor)
            .build()
    )

    private val sdf = ThreadLocal.withInitial {
        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
    }

    private var isGridView = false

    val currentDisplayList: List<FileDisplayItem> get() = differ.currentList

    val currentList: List<FileItem>
        get() = differ.currentList.mapNotNull { (it as? FileDisplayItem.Item)?.file }

    fun submitDisplayList(list: List<FileDisplayItem>, callback: (() -> Unit)? = null) {
        differ.submitList(list, callback)
    }

    fun submitList(list: List<FileItem>, callback: (() -> Unit)? = null) {
        differ.submitList(list.map { FileDisplayItem.Item(it) }, callback)
    }

    fun setViewType(grid: Boolean) {
        if (isGridView == grid) return
        isGridView = grid
        notifyDataSetChanged()
    }

    fun isHeader(position: Int): Boolean {
        if (position < 0 || position >= itemCount) return false
        return differ.currentList[position] is FileDisplayItem.Header
    }

    override fun getItemCount() = differ.currentList.size

    override fun getItemId(pos: Int): Long {
        return when (val item = differ.currentList[pos]) {
            is FileDisplayItem.Header -> item.type.ordinal.toLong() * -1000L - 1L
            is FileDisplayItem.Item   -> item.file.path.hashCode().toLong()
        }
    }

    override fun getItemViewType(pos: Int): Int {
        return when (differ.currentList[pos]) {
            is FileDisplayItem.Header -> VIEW_HEADER
            is FileDisplayItem.Item   -> if (isGridView) VIEW_GRID else VIEW_LIST
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_HEADER -> HeaderViewHolder(com.filemanager.databinding.ItemSearchGroupHeaderBinding.inflate(inf, parent, false))
            VIEW_GRID   -> GridViewHolder(ItemFileGridBinding.inflate(inf, parent, false))
            else        -> ListViewHolder(ItemFileListBinding.inflate(inf, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = differ.currentList.getOrNull(position) ?: return
        when (holder) {
            is HeaderViewHolder -> if (item is FileDisplayItem.Header) holder.bind(item)
            is ListViewHolder   -> if (item is FileDisplayItem.Item) holder.bind(item.file)
            is GridViewHolder   -> if (item is FileDisplayItem.Item) holder.bind(item.file)
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder, position: Int, payloads: List<Any>
    ) {
        if (payloads.isNotEmpty() && payloads[0] == "selection") {
            val item = differ.currentList.getOrNull(position) ?: return
            if (item is FileDisplayItem.Item) {
                when (holder) {
                    is ListViewHolder -> holder.bindSelection(item.file)
                    is GridViewHolder -> holder.bindSelection(item.file)
                }
            }
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        when (holder) {
            is ListViewHolder -> Glide.with(holder.itemView).clear(holder.binding.ivThumbnail)
            is GridViewHolder -> Glide.with(holder.itemView).clear(holder.binding.ivThumbnail)
        }
    }

    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
        super.onViewDetachedFromWindow(holder)
        holder.itemView.clearAnimation()
    }

    // ── Header ViewHolder ────────────────────────────────────────

    inner class HeaderViewHolder(val binding: com.filemanager.databinding.ItemSearchGroupHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(header: FileDisplayItem.Header) {
            binding.tvGroupTitle.text = header.title
            binding.tvCount.text = "${header.count} mục"

            binding.ivChevron.rotation = if (header.isExpanded) 0f else -90f
            binding.ivChevron.setImageResource(
                if (header.isExpanded) R.drawable.ic_chevron_down
                else R.drawable.ic_chevron_right
            )

            binding.root.setOnClickListener {
                onHeaderToggle?.invoke(header.type)
            }
        }
    }

    // ── List ViewHolder ──────────────────────────────────────────

    inner class ListViewHolder(val binding: ItemFileListBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: FileItem) {
            binding.tvName.text = item.name
            binding.tvSize.text = if (item.isDirectory) "" else item.formattedSize()
            binding.tvDate.text = sdf.get()!!.format(java.util.Date(item.lastModified))
            binding.tvExtension.text = if (!item.isDirectory) item.extension.uppercase() else ""

            when {
                item.fileType == FileType.IMAGE || item.fileType == FileType.VIDEO -> {
                    binding.ivThumbnail.visibility = View.VISIBLE
                    binding.ivIcon.visibility      = View.GONE
                    Glide.with(binding.root)
                        .load(item.file)
                        .apply(GLIDE_THUMB_OPTIONS)
                        .transition(DrawableTransitionOptions.withCrossFade(150))
                        .placeholder(iconRes(item.fileType, item.isDirectory))
                        .into(binding.ivThumbnail)
                }
                else -> {
                    Glide.with(binding.root).clear(binding.ivThumbnail)
                    binding.ivThumbnail.setImageDrawable(null)
                    binding.ivThumbnail.visibility = View.GONE
                    binding.ivIcon.visibility      = View.VISIBLE
                    binding.ivIcon.setImageResource(iconRes(item.fileType, item.isDirectory))
                }
            }

            bindSelection(item)
            binding.root.setOnClickListener { onItemClick(item) }
            binding.root.setOnLongClickListener { onItemLongClick(item); true }
            binding.checkBox.setOnClickListener { onSelectionChange(item) }
        }

        fun bindSelection(item: FileItem) {
            val inSel = isSelectionMode()
            binding.checkBox.visibility = if (item.isSelected || inSel) View.VISIBLE else View.GONE
            binding.checkBox.isChecked  = item.isSelected
            binding.root.isActivated    = item.isSelected
        }
    }

    // ── Grid ViewHolder ──────────────────────────────────────────

    inner class GridViewHolder(val binding: ItemFileGridBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: FileItem) {
            binding.tvName.text = item.name

            when {
                item.fileType == FileType.IMAGE || item.fileType == FileType.VIDEO -> {
                    Glide.with(binding.root)
                        .load(item.file)
                        .apply(GLIDE_GRID_OPTIONS)
                        .placeholder(iconRes(item.fileType, item.isDirectory))
                        .into(binding.ivThumbnail)
                    binding.videoIcon.visibility =
                        if (item.fileType == FileType.VIDEO) View.VISIBLE else View.GONE
                }
                else -> {
                    Glide.with(binding.root).clear(binding.ivThumbnail)
                    binding.ivThumbnail.setImageResource(iconRes(item.fileType, item.isDirectory))
                    binding.videoIcon.visibility = View.GONE
                }
            }

            bindSelection(item)
            binding.root.setOnClickListener { onItemClick(item) }
            binding.root.setOnLongClickListener { onItemLongClick(item); true }
            binding.checkBox.setOnClickListener { onSelectionChange(item) }
        }

        fun bindSelection(item: FileItem) {
            val inSel = isSelectionMode()
            binding.checkBox.visibility = if (item.isSelected || inSel) View.VISIBLE else View.GONE
            binding.checkBox.isChecked  = item.isSelected
            binding.root.isActivated    = item.isSelected
        }
    }

    // ── Helpers ──────────────────────────────────────────────────

    private fun isSelectionMode() = differ.currentList.any { item -> item.isSelected }

    private fun iconRes(type: FileType, isDir: Boolean) = when {
        isDir                   -> R.drawable.ic_folder
        type == FileType.IMAGE  -> R.drawable.ic_image
        type == FileType.VIDEO  -> R.drawable.ic_video
        type == FileType.AUDIO  -> R.drawable.ic_audio
        type == FileType.DOCUMENT -> R.drawable.ic_document
        type == FileType.ARCHIVE  -> R.drawable.ic_archive
        type == FileType.CODE     -> R.drawable.ic_code
        type == FileType.APK      -> R.drawable.ic_apk
        else                    -> R.drawable.ic_file
    }
}
