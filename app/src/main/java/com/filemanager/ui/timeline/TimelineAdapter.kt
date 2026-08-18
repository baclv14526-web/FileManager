package com.filemanager.ui.timeline

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
import com.filemanager.data.model.FileItem.Companion.VIDEO_EXTENSIONS
import com.filemanager.databinding.ItemTimelineHeaderBinding
import com.filemanager.databinding.ItemTimelineMediaBinding
import java.io.File

class TimelineAdapter(
    private val onItemClick: (FileItem) -> Unit
) : ListAdapter<TimelineListItem, RecyclerView.ViewHolder>(DIFF) {

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_MEDIA  = 1

        val DIFF = object : DiffUtil.ItemCallback<TimelineListItem>() {
            override fun areItemsTheSame(old: TimelineListItem, new: TimelineListItem): Boolean =
                when {
                    old is TimelineListItem.Header    && new is TimelineListItem.Header    -> old.title == new.title
                    old is TimelineListItem.MediaItem && new is TimelineListItem.MediaItem -> old.file.path == new.file.path
                    else -> false
                }
            override fun areContentsTheSame(old: TimelineListItem, new: TimelineListItem) = old == new
        }
    }

    // FIX: kiểm tra bounds trước khi trả về type
    fun isHeader(position: Int): Boolean {
        if (position < 0 || position >= itemCount) return false
        return getItemViewType(position) == TYPE_HEADER
    }

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is TimelineListItem.Header    -> TYPE_HEADER
        is TimelineListItem.MediaItem -> TYPE_MEDIA
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER)
            HeaderVH(ItemTimelineHeaderBinding.inflate(inflater, parent, false))
        else
            MediaVH(ItemTimelineMediaBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is TimelineListItem.Header    -> (holder as HeaderVH).bind(item)
            is TimelineListItem.MediaItem -> (holder as MediaVH).bind(item.file)
        }
    }

    // FIX: clear Glide khi view được recycle để không hiện ảnh cũ
    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is MediaVH) {
            Glide.with(holder.itemView.context).clear(holder.binding.ivThumb)
        }
    }

    inner class HeaderVH(private val binding: ItemTimelineHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: TimelineListItem.Header) {
            binding.tvMonth.text = item.title
        }
    }

    inner class MediaVH(val binding: ItemTimelineMediaBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: FileItem) {
            Glide.with(binding.root)
                .load(File(item.path))
                .centerCrop()
                .transition(DrawableTransitionOptions.withCrossFade())
                .placeholder(R.drawable.ic_image)
                .error(R.drawable.ic_image)
                .into(binding.ivThumb)

            binding.videoIndicator.visibility =
                if (item.extension.lowercase() in VIDEO_EXTENSIONS) View.VISIBLE else View.GONE

            binding.root.setOnClickListener { onItemClick(item) }
        }
    }
}
