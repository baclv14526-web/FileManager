package com.filemanager.ui.cleanup

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
import com.bumptech.glide.request.RequestOptions
import com.filemanager.R
import com.filemanager.data.model.FileType
import com.filemanager.databinding.ItemCleanupEmptyBinding
import com.filemanager.databinding.ItemCleanupEntryBinding
import com.filemanager.databinding.ItemCleanupHeaderBinding
import com.filemanager.databinding.ItemCleanupStorageBinding
import com.filemanager.databinding.ItemCleanupSummaryBinding
import com.filemanager.utils.FileUtils
import com.filemanager.utils.StorageVolume
import java.util.concurrent.Executors

class CleanupAdapter(
    private val onScan: (scope: Int) -> Unit,
    private val onItemToggle: (CleanupItem) -> Unit,
    private val onCategorySelectAll: (CleanupCategory, Boolean) -> Unit,
    private val onHeaderExpand: (CleanupCategory) -> Unit,
    private val onSelectAll: () -> Unit,
    private val onLoadMore: (CleanupCategory) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val T_STORAGE  = 0
        const val T_SUMMARY  = 1
        const val T_EMPTY    = 2
        const val T_HEADER   = 3
        const val T_ENTRY    = 4
        const val T_MORE     = 5

        private val DIFF = object : DiffUtil.ItemCallback<CleanupRow>() {
            override fun areItemsTheSame(a: CleanupRow, b: CleanupRow) = when {
                a is CleanupRow.StorageCard    && b is CleanupRow.StorageCard    -> true
                a is CleanupRow.Summary        && b is CleanupRow.Summary        -> true
                a is CleanupRow.Empty          && b is CleanupRow.Empty          -> true
                a is CleanupRow.CategoryHeader && b is CleanupRow.CategoryHeader -> a.category == b.category
                a is CleanupRow.FileEntry      && b is CleanupRow.FileEntry      -> a.item.fileItem.path == b.item.fileItem.path
                a is CleanupRow.MoreItems      && b is CleanupRow.MoreItems      -> a.category == b.category
                else -> false
            }
            override fun areContentsTheSame(a: CleanupRow, b: CleanupRow) = a == b
            override fun getChangePayload(a: CleanupRow, b: CleanupRow): Any? =
                if (a is CleanupRow.FileEntry && b is CleanupRow.FileEntry &&
                    a.item.isSelected != b.item.isSelected) "sel" else null
        }

        private val GLIDE_OPTS = RequestOptions()
            .override(80, 80)
            .format(DecodeFormat.PREFER_RGB_565)
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
            .dontAnimate()
    }

    private val bgExec = Executors.newSingleThreadExecutor()
    private val differ = AsyncListDiffer(
        AdapterListUpdateCallback(this),
        AsyncDifferConfig.Builder(DIFF).setBackgroundThreadExecutor(bgExec).build()
    )

    init { setHasStableIds(true) }

    override fun getItemCount() = differ.currentList.size
    override fun getItemViewType(pos: Int) = when (differ.currentList[pos]) {
        is CleanupRow.StorageCard    -> T_STORAGE
        is CleanupRow.Summary        -> T_SUMMARY
        is CleanupRow.Empty          -> T_EMPTY
        is CleanupRow.CategoryHeader -> T_HEADER
        is CleanupRow.FileEntry      -> T_ENTRY
        is CleanupRow.MoreItems      -> T_MORE
    }
    override fun getItemId(pos: Int) = when (val r = differ.currentList[pos]) {
        is CleanupRow.StorageCard    -> -1L
        is CleanupRow.Summary        -> -2L
        is CleanupRow.Empty          -> -3L
        is CleanupRow.CategoryHeader -> r.category.ordinal.toLong() * -100 - 10
        is CleanupRow.FileEntry      -> r.item.fileItem.path.hashCode().toLong()
        is CleanupRow.MoreItems      -> r.category.ordinal.toLong() * -100 - 20
    }

    fun submitList(list: List<CleanupRow>, cb: (() -> Unit)? = null) =
        differ.submitList(list, cb)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            T_STORAGE -> StorageVH(ItemCleanupStorageBinding.inflate(inf, parent, false))
            T_SUMMARY -> SummaryVH(ItemCleanupSummaryBinding.inflate(inf, parent, false))
            T_EMPTY   -> EmptyVH(ItemCleanupEmptyBinding.inflate(inf, parent, false))
            T_HEADER  -> HeaderVH(ItemCleanupHeaderBinding.inflate(inf, parent, false))
            T_MORE    -> MoreVH(inf.inflate(R.layout.item_cleanup_more, parent, false))
            else      -> EntryVH(ItemCleanupEntryBinding.inflate(inf, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int, payloads: List<Any>) {
        if (payloads.isNotEmpty() && payloads[0] == "sel" &&
            holder is EntryVH && differ.currentList[pos] is CleanupRow.FileEntry) {
            holder.bindSel((differ.currentList[pos] as CleanupRow.FileEntry).item)
            return
        }
        super.onBindViewHolder(holder, pos, payloads)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int) {
        when (val row = differ.currentList[pos]) {
            is CleanupRow.StorageCard    -> (holder as StorageVH).bind(row)
            is CleanupRow.Summary        -> (holder as SummaryVH).bind(row)
            is CleanupRow.Empty          -> { /* static */ }
            is CleanupRow.CategoryHeader -> (holder as HeaderVH).bind(row)
            is CleanupRow.FileEntry      -> (holder as EntryVH).bind(row.item)
            is CleanupRow.MoreItems      -> (holder as MoreVH).bind(row)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is EntryVH) Glide.with(holder.itemView).clear(holder.binding.ivIcon)
    }

    override fun onDetachedFromRecyclerView(rv: RecyclerView) {
        super.onDetachedFromRecyclerView(rv)
        bgExec.shutdown()
    }

    // ── ViewHolders ─────────────────────────────────────────────

    inner class StorageVH(private val b: ItemCleanupStorageBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(row: CleanupRow.StorageCard) {
            val internal = row.volumes.firstOrNull { !it.isRemovable }
            val sd       = row.volumes.firstOrNull { it.isRemovable }

            internal?.let {
                b.tvInternalName.text  = it.name
                b.tvInternalUsage.text = "${FileUtils.formatSize(it.usedBytes)} / ${FileUtils.formatSize(it.totalBytes)}"
                b.pbInternal.progress  = it.usedPercent
            }
            if (sd != null) {
                b.sdRow.visibility   = View.VISIBLE
                b.tvSDName.text      = sd.name
                b.tvSDUsage.text     = "${FileUtils.formatSize(sd.usedBytes)} / ${FileUtils.formatSize(sd.totalBytes)}"
                b.pbSD.progress      = sd.usedPercent
                b.chipScanSD.visibility = View.VISIBLE
                b.chipScanSD.text = "💾 SD (${FileUtils.formatSize(sd.totalBytes)})"
            } else {
                b.sdRow.visibility      = View.GONE
                b.chipScanSD.visibility = View.GONE
            }

            b.btnScan.isEnabled = !row.isScanning
            b.btnScan.text      = if (row.isScanning) "Đang quét..." else "🔍  Bắt đầu quét"

            // Restore chip state
            when (row.scopeIndex) {
                1    -> b.chipScanInternal.isChecked = true
                2    -> b.chipScanSD.isChecked        = true
                else -> b.chipScanAll.isChecked       = true
            }

            b.btnScan.setOnClickListener         { onScan(currentScope(b)) }
            b.chipScanAll.setOnClickListener     { onScan(0) }
            b.chipScanInternal.setOnClickListener { onScan(1) }
            b.chipScanSD.setOnClickListener      { onScan(2) }
        }

        private fun currentScope(b: ItemCleanupStorageBinding) = when {
            b.chipScanInternal.isChecked -> 1
            b.chipScanSD.isChecked       -> 2
            else                         -> 0
        }
    }

    inner class SummaryVH(private val b: ItemCleanupSummaryBinding) :
        RecyclerView.ViewHolder(b.root) {
        fun bind(row: CleanupRow.Summary) {
            b.tvSummary.text = row.text
            b.btnSelectAll.setOnClickListener { onSelectAll() }
        }
    }

    inner class EmptyVH(b: ItemCleanupEmptyBinding) : RecyclerView.ViewHolder(b.root)

    inner class HeaderVH(private val b: ItemCleanupHeaderBinding) :
        RecyclerView.ViewHolder(b.root) {
        fun bind(row: CleanupRow.CategoryHeader) {
            b.tvIcon.text         = row.category.icon
            b.tvCategoryName.text = row.category.label
            val sizeStr = if (row.totalSize > 0) " · ${FileUtils.formatSize(row.totalSize)}" else ""
            b.tvCategoryInfo.text = "${row.count} mục$sizeStr"
            b.ivChevron.setImageResource(
                if (row.isExpanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right
            )
            b.ivChevron.visibility   = View.VISIBLE
            b.cbSelectAll.visibility = View.VISIBLE
            b.cbSelectAll.setOnCheckedChangeListener(null)
            b.cbSelectAll.isChecked = false
            b.cbSelectAll.setOnCheckedChangeListener { _, checked ->
                onCategorySelectAll(row.category, checked)
            }
            b.root.setOnClickListener { onHeaderExpand(row.category) }
        }
    }

    inner class EntryVH(val binding: ItemCleanupEntryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: CleanupItem) {
            binding.tvName.text = item.fileItem.name
            binding.tvPath.text = item.fileItem.file.parent ?: ""
            binding.tvSize.text = if (item.fileItem.size > 0) FileUtils.formatSize(item.fileItem.size) else "0 B"
            when (item.fileItem.fileType) {
                FileType.IMAGE -> Glide.with(binding.root).load(item.fileItem.file)
                    .apply(GLIDE_OPTS).placeholder(R.drawable.ic_image).into(binding.ivIcon)
                FileType.VIDEO -> Glide.with(binding.root).load(item.fileItem.file)
                    .apply(GLIDE_OPTS).placeholder(R.drawable.ic_video).into(binding.ivIcon)
                else -> {
                    Glide.with(binding.root).clear(binding.ivIcon)
                    binding.ivIcon.setImageResource(iconRes(item.fileItem.fileType, item.fileItem.isDirectory))
                }
            }
            bindSel(item)
            binding.cbItem.setOnCheckedChangeListener(null)
            binding.cbItem.setOnCheckedChangeListener { _, _ -> onItemToggle(item) }
            binding.root.setOnClickListener { onItemToggle(item) }
        }

        fun bindSel(item: CleanupItem) {
            binding.cbItem.setOnCheckedChangeListener(null)
            binding.cbItem.isChecked = item.isSelected
            binding.cbItem.setOnCheckedChangeListener { _, _ -> onItemToggle(item) }
            binding.root.isActivated = item.isSelected
        }

        private fun iconRes(type: FileType, isDir: Boolean) = when {
            isDir                     -> R.drawable.ic_folder
            type == FileType.VIDEO    -> R.drawable.ic_video
            type == FileType.AUDIO    -> R.drawable.ic_audio
            type == FileType.DOCUMENT -> R.drawable.ic_document
            type == FileType.ARCHIVE  -> R.drawable.ic_archive
            type == FileType.APK      -> R.drawable.ic_apk
            else                      -> R.drawable.ic_file
        }
    }

    inner class MoreVH(view: View) : RecyclerView.ViewHolder(view) {
        fun bind(row: CleanupRow.MoreItems) {
            itemView.setOnClickListener { onLoadMore(row.category) }
        }
    }
}
