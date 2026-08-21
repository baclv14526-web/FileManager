package com.filemanager.utils

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.filemanager.R

enum class ShimmerType { LIST, GRID, MEDIA }

class ShimmerAdapter(
    private val itemCount: Int = 8,
    private val type: ShimmerType = ShimmerType.LIST
) : RecyclerView.Adapter<ShimmerAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = when (type) {
            ShimmerType.LIST  -> R.layout.item_shimmer_file
            ShimmerType.GRID  -> R.layout.item_shimmer_grid
            ShimmerType.MEDIA -> R.layout.item_shimmer_media
        }
        return VH(LayoutInflater.from(parent.context).inflate(layout, parent, false))
    }

    override fun onBindViewHolder(holder: VH, position: Int) {}
    override fun getItemCount() = itemCount
}
