package com.filemanager.ui.main

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.filemanager.R
import com.filemanager.databinding.ItemSidebarBinding

class SidebarAdapter(
    private val items: List<Pair<String, String>>,
    private val onClick: (String) -> Unit
) : RecyclerView.Adapter<SidebarAdapter.VH>() {

    private val icons = mapOf(
        "Internal Storage" to R.drawable.ic_storage,
        "Downloads" to R.drawable.ic_download,
        "DCIM" to R.drawable.ic_camera,
        "Pictures" to R.drawable.ic_image,
        "Music" to R.drawable.ic_audio,
        "Movies" to R.drawable.ic_video,
        "Documents" to R.drawable.ic_document,
    )

    inner class VH(private val binding: ItemSidebarBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: Pair<String, String>) {
            binding.tvLabel.text = item.first
            binding.ivIcon.setImageResource(icons[item.first] ?: R.drawable.ic_folder)
            binding.root.setOnClickListener { onClick(item.second) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemSidebarBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
    override fun getItemCount() = items.size
}
