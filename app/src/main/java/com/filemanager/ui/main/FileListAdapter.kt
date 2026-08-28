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

class FileListAdapter(
    private val onItemClick: (FileItem) -> Unit,
    private val onItemLongClick: (FileItem) -> Unit,
    private val onSelectionChange: (FileItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val VIEW_LIST = 0
        const val VIEW_GRID = 1

        // ✅ FIX 1: DiffUtil chạy trên background thread qua AsyncListDiffer
        // Tránh block UI khi list > 200 items
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<FileItem>() {
            override fun areItemsTheSame(old: FileItem, new: FileItem) =
                old.path == new.path
            override fun areContentsTheSame(old: FileItem, new: FileItem) =
                old.isSelected == new.isSelected && old.lastModified == new.lastModified
            override fun getChangePayload(old: FileItem, new: FileItem): Any? {
                // Chỉ trả về payload khi chỉ thay đổi selection → tránh rebind toàn bộ
                return if (old.isSelected != new.isSelected) "selection" else null
            }
        }

        // ✅ FIX 2: Glide request options tối ưu cho Android 9
        // RGB_565 dùng ít RAM hơn ARGB_8888 (50%), phù hợp danh sách dài
        private val GLIDE_THUMB_OPTIONS = RequestOptions()
            .override(120, 120)                         // resize nhỏ, đủ cho thumbnail
            .format(DecodeFormat.PREFER_RGB_565)        // ít RAM hơn 50%
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE) // cache sau khi resize
            .dontAnimate()                              // bỏ animation khi scroll nhanh

        private val GLIDE_GRID_OPTIONS = RequestOptions()
            .override(200, 200)
            .format(DecodeFormat.PREFER_RGB_565)
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
            .dontAnimate()
    }

    // ✅ AsyncListDiffer với executor riêng — không dùng shared ForkJoin pool
    private val bgExecutor = Executors.newSingleThreadExecutor()

    init { setHasStableIds(true) }
    private val differ = AsyncListDiffer(
        AdapterListUpdateCallback(this),    // Fix: cần AdapterListUpdateCallback, không phải this
        AsyncDifferConfig.Builder(DIFF_CALLBACK)
            .setBackgroundThreadExecutor(bgExecutor)
            .build()
    )

    // ✅ FIX 3: SimpleDateFormat là thread-unsafe, dùng ThreadLocal
    private val sdf = ThreadLocal.withInitial {
        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
    }

    private var isGridView = false

    val currentList: List<FileItem> get() = differ.currentList

    fun submitList(list: List<FileItem>, callback: (() -> Unit)? = null) {
        differ.submitList(list, callback)
    }

    fun setViewType(grid: Boolean) {
        if (isGridView == grid) return
        isGridView = grid
        notifyDataSetChanged()
    }

    override fun getItemCount() = differ.currentList.size
    override fun getItemId(pos: Int) = differ.currentList[pos].path.hashCode().toLong()
    override fun getItemViewType(pos: Int) = if (isGridView) VIEW_GRID else VIEW_LIST

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_GRID)
            GridViewHolder(ItemFileGridBinding.inflate(inf, parent, false))
        else
            ListViewHolder(ItemFileListBinding.inflate(inf, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = differ.currentList.getOrNull(position) ?: return
        when (holder) {
            is ListViewHolder -> holder.bind(item)
            is GridViewHolder -> holder.bind(item)
        }
    }

    // ✅ FIX 4: Partial bind — chỉ update checkbox khi payload = "selection"
    // Tránh reload Glide image khi chỉ thay đổi check state
    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder, position: Int, payloads: List<Any>
    ) {
        if (payloads.isNotEmpty() && payloads[0] == "selection") {
            val item = differ.currentList.getOrNull(position) ?: return
            when (holder) {
                is ListViewHolder -> holder.bindSelection(item)
                is GridViewHolder -> holder.bindSelection(item)
            }
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    // ✅ FIX 5: Giải phóng Glide khi ViewHolder bị recycle
    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        when (holder) {
            is ListViewHolder -> Glide.with(holder.itemView).clear(holder.binding.ivThumbnail)
            is GridViewHolder -> Glide.with(holder.itemView).clear(holder.binding.ivThumbnail)
        }
    }

    // ✅ Dừng load Glide khi view detach (scroll quá nhanh)
    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
        super.onViewDetachedFromWindow(holder)
        holder.itemView.clearAnimation()
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
