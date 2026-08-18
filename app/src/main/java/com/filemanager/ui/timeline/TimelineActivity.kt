package com.filemanager.ui.timeline

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.filemanager.data.model.FileItem
import com.filemanager.data.model.FileType
import com.filemanager.data.repository.TimelineMediaType
import com.filemanager.databinding.ActivityTimelineBinding
import com.filemanager.ui.viewer.ImageViewerActivity
import com.filemanager.ui.viewer.VideoPlayerActivity
import com.filemanager.utils.FileUtils
import com.filemanager.utils.StorageVolume

class TimelineActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTimelineBinding
    private val viewModel: TimelineViewModel by viewModels()
    private lateinit var adapter: TimelineAdapter

    private var currentMediaType = TimelineMediaType.ALL
    private var currentScope = ScanScope.ALL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTimelineBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Timeline"

        setupRecyclerView()
        setupScopeChips()
        setupMediaChips()
        setupObservers()

        viewModel.loadMedia(TimelineMediaType.ALL, ScanScope.ALL)
    }

    private fun setupRecyclerView() {
        adapter = TimelineAdapter { item -> openMedia(item) }
        adapter.onHeaderClick = { title -> viewModel.toggleGroup(title) }

        val glm = GridLayoutManager(this, 3)
        glm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(pos: Int) =
                if (pos >= 0 && pos < adapter.itemCount && adapter.isHeader(pos)) 3 else 1
        }
        binding.recyclerView.layoutManager = glm
        binding.recyclerView.adapter = adapter
    }

    private fun setupScopeChips() {
        binding.chipScopeAll.setOnClickListener      { setScope(ScanScope.ALL) }
        binding.chipScopeInternal.setOnClickListener { setScope(ScanScope.INTERNAL) }
        binding.chipScopeSD.setOnClickListener       { setScope(ScanScope.SD_CARD) }
    }

    private fun setupMediaChips() {
        binding.chipAll.setOnClickListener    { setMediaType(TimelineMediaType.ALL) }
        binding.chipImages.setOnClickListener { setMediaType(TimelineMediaType.IMAGES) }
        binding.chipVideos.setOnClickListener { setMediaType(TimelineMediaType.VIDEOS) }
    }

    private fun setScope(scope: ScanScope) {
        currentScope = scope
        viewModel.loadMedia(currentMediaType, scope)
    }

    private fun setMediaType(type: TimelineMediaType) {
        currentMediaType = type
        viewModel.loadMedia(type, currentScope)
    }

    private fun setupObservers() {
        viewModel.timelineItems.observe(this) { items ->
            adapter.submitList(items)
            binding.emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }

        viewModel.isLoading.observe(this) { loading ->
            binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        }

        // Quan sát danh sách volumes để update UI
        viewModel.storageVolumes.observe(this) { volumes ->
            updateStorageChips(volumes)
        }

        // Quan sát scope hiện tại để update storage info bar
        viewModel.currentScope.observe(this) { scope ->
            updateStorageInfoBar(scope)
        }
    }

    private fun updateStorageChips(volumes: List<StorageVolume>) {
        val hasSD = volumes.any { it.isRemovable }

        // Nếu không có thẻ SD → ẩn chip SD và đổi label
        if (!hasSD) {
            binding.chipScopeSD.visibility = View.GONE
            binding.chipScopeAll.text = "Tất cả"
        } else {
            binding.chipScopeSD.visibility = View.VISIBLE
            // Hiện dung lượng SD trên chip
            val sd = volumes.first { it.isRemovable }
            binding.chipScopeSD.text = "💾 SD (${FileUtils.formatSize(sd.totalBytes)})"
            val internal = volumes.firstOrNull { !it.isRemovable }
            if (internal != null) {
                binding.chipScopeInternal.text = "📱 Trong (${FileUtils.formatSize(internal.totalBytes)})"
            }
        }
    }

    private fun updateStorageInfoBar(scope: ScanScope) {
        if (scope == ScanScope.ALL) {
            binding.storageInfoBar.visibility = View.GONE
            return
        }

        val volumes = viewModel.storageVolumes.value ?: return
        val vol = when (scope) {
            ScanScope.INTERNAL -> volumes.firstOrNull { !it.isRemovable }
            ScanScope.SD_CARD  -> volumes.firstOrNull { it.isRemovable }
            ScanScope.ALL      -> null
        } ?: run {
            binding.storageInfoBar.visibility = View.GONE
            return
        }

        binding.storageInfoBar.visibility = View.VISIBLE
        binding.tvStorageName.text = vol.name
        binding.pbStorage.progress = vol.usedPercent
        binding.tvStorageUsage.text =
            "${FileUtils.formatSize(vol.usedBytes)} / ${FileUtils.formatSize(vol.totalBytes)}"
    }

    private fun openMedia(item: FileItem) {
        try {
            when (item.fileType) {
                FileType.IMAGE -> {
                    val all   = viewModel.allImagePaths
                    val index = all.indexOf(item.path).coerceAtLeast(0)
                    val start = (index - 100).coerceAtLeast(0)
                    val end   = (index + 100).coerceAtMost(all.size)
                    startActivity(Intent(this, ImageViewerActivity::class.java).apply {
                        putStringArrayListExtra(ImageViewerActivity.EXTRA_PATHS, ArrayList(all.subList(start, end)))
                        putExtra(ImageViewerActivity.EXTRA_INDEX, index - start)
                    })
                }
                FileType.VIDEO -> startActivity(
                    Intent(this, VideoPlayerActivity::class.java).apply {
                        putExtra(VideoPlayerActivity.EXTRA_PATH, item.path)
                    })
                else -> {}
            }
        } catch (e: Exception) {
            if (item.fileType == FileType.IMAGE) {
                startActivity(Intent(this, ImageViewerActivity::class.java).apply {
                    putStringArrayListExtra(ImageViewerActivity.EXTRA_PATHS, arrayListOf(item.path))
                    putExtra(ImageViewerActivity.EXTRA_INDEX, 0)
                })
            }
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() = super.onBackPressed()

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { onBackPressed(); return true }
        return super.onOptionsItemSelected(item)
    }
}
