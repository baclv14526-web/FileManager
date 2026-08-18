package com.filemanager.ui.main

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.filemanager.R
import com.filemanager.data.model.FileItem
import com.filemanager.data.model.FileType
import com.filemanager.databinding.ItemFileGridBinding
import com.filemanager.databinding.ItemFileListBinding
import java.text.SimpleDateFormat
import java.util.*

class FileListAdapter(
    private val onItemClick: (FileItem) -> Unit,
    private val onItemLongClick: (FileItem) -> Unit,
    private val onSelectionChange: (FileItem) -> Unit
) : ListAdapter<FileItem, RecyclerView.ViewHolder>(DIFF_CALLBACK) {

    private var isGridView = false
    private val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

    companion object {
        const val VIEW_LIST = 0
        const val VIEW_GRID = 1

        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<FileItem>() {
            override fun areItemsTheSame(old: FileItem, new: FileItem) = old.path == new.path
            override fun areContentsTheSame(old: FileItem, new: FileItem) =
                old.isSelected == new.isSelected && old.lastModified == new.lastModified
        }
    }

    fun setViewType(grid: Boolean) {
        isGridView = grid
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int) = if (isGridView) VIEW_GRID else VIEW_LIST

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_GRID) {
            GridViewHolder(ItemFileGridBinding.inflate(inflater, parent, false))
        } else {
            ListViewHolder(ItemFileListBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (holder) {
            is ListViewHolder -> holder.bind(item)
            is GridViewHolder -> holder.bind(item)
        }
    }

    inner class ListViewHolder(private val binding: ItemFileListBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: FileItem) {
            binding.tvName.text = item.name
            binding.tvSize.text = if (item.isDirectory) "" else item.formattedSize()
            binding.tvDate.text = sdf.format(Date(item.lastModified))
            binding.tvExtension.text = if (!item.isDirectory) item.extension.uppercase() else ""

            // Thumbnail / Icon
            when {
                item.fileType == FileType.IMAGE -> {
                    binding.ivThumbnail.visibility = View.VISIBLE
                    binding.ivIcon.visibility = View.GONE
                    Glide.with(binding.root)
                        .load(item.file)
                        .centerCrop()
                        .transition(DrawableTransitionOptions.withCrossFade())
                        .placeholder(R.drawable.ic_image)
                        .into(binding.ivThumbnail)
                }
                item.fileType == FileType.VIDEO -> {
                    binding.ivThumbnail.visibility = View.VISIBLE
                    binding.ivIcon.visibility = View.GONE
                    Glide.with(binding.root)
                        .load(item.file)
                        .centerCrop()
                        .placeholder(R.drawable.ic_video)
                        .into(binding.ivThumbnail)
                }
                else -> {
                    Glide.with(binding.root).clear(binding.ivThumbnail)
                    binding.ivThumbnail.setImageDrawable(null)
                    binding.ivThumbnail.visibility = View.GONE
                    binding.ivIcon.visibility = View.VISIBLE
                    binding.ivIcon.setImageResource(getIconForType(item.fileType))
                }
            }

            // Selection
            binding.checkBox.visibility = if (item.isSelected || isSelectionMode()) View.VISIBLE else View.GONE
            binding.checkBox.isChecked = item.isSelected
            binding.root.isActivated = item.isSelected

            binding.root.setOnClickListener { onItemClick(item) }
            binding.root.setOnLongClickListener {
                onItemLongClick(item)
                true
            }
            binding.checkBox.setOnClickListener { onSelectionChange(item) }
        }
    }

    inner class GridViewHolder(private val binding: ItemFileGridBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: FileItem) {
            binding.tvName.text = item.name

            when {
                item.fileType == FileType.IMAGE || item.fileType == FileType.VIDEO -> {
                    Glide.with(binding.root)
                        .load(item.file)
                        .centerCrop()
                        .transition(DrawableTransitionOptions.withCrossFade())
                        .placeholder(getIconForType(item.fileType))
                        .into(binding.ivThumbnail)
                    binding.videoIcon.visibility =
                        if (item.fileType == FileType.VIDEO) View.VISIBLE else View.GONE
                }
                else -> {
                    Glide.with(binding.root).clear(binding.ivThumbnail)
                    binding.ivThumbnail.setImageResource(getIconForType(item.fileType))
                    binding.videoIcon.visibility = View.GONE
                }
            }

            binding.checkBox.visibility = if (item.isSelected || isSelectionMode()) View.VISIBLE else View.GONE
            binding.checkBox.isChecked = item.isSelected
            binding.root.isActivated = item.isSelected

            binding.root.setOnClickListener { onItemClick(item) }
            binding.root.setOnLongClickListener { onItemLongClick(item); true }
            binding.checkBox.setOnClickListener { onSelectionChange(item) }
        }
    }

    private fun isSelectionMode(): Boolean = currentList.any { it.isSelected }

    private fun getIconForType(type: FileType): Int = when (type) {
        FileType.FOLDER -> R.drawable.ic_folder
        FileType.IMAGE -> R.drawable.ic_image
        FileType.VIDEO -> R.drawable.ic_video
        FileType.AUDIO -> R.drawable.ic_audio
        FileType.DOCUMENT -> R.drawable.ic_document
        FileType.ARCHIVE -> R.drawable.ic_archive
        FileType.CODE -> R.drawable.ic_code
        FileType.APK -> R.drawable.ic_apk
        FileType.OTHER -> R.drawable.ic_file
    }
}
