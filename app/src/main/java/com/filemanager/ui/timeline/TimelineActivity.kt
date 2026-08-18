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

class TimelineActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTimelineBinding
    private val viewModel: TimelineViewModel by viewModels()
    private lateinit var adapter: TimelineAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTimelineBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Timeline"

        setupRecyclerView()
        setupChips()
        setupObservers()
        viewModel.loadMedia(TimelineMediaType.ALL)
    }

    private fun setupRecyclerView() {
        adapter = TimelineAdapter { item -> openMedia(item) }

        val gridLayoutManager = GridLayoutManager(this, 3)
        gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                // FIX: kiểm tra position hợp lệ trước khi gọi isHeader
                return if (position >= 0 && position < adapter.itemCount && adapter.isHeader(position)) 3 else 1
            }
        }
        binding.recyclerView.layoutManager = gridLayoutManager
        binding.recyclerView.adapter = adapter
    }

    private fun setupChips() {
        binding.chipAll.setOnClickListener    { viewModel.loadMedia(TimelineMediaType.ALL) }
        binding.chipImages.setOnClickListener { viewModel.loadMedia(TimelineMediaType.IMAGES) }
        binding.chipVideos.setOnClickListener { viewModel.loadMedia(TimelineMediaType.VIDEOS) }
    }

    private fun setupObservers() {
        viewModel.timelineItems.observe(this) { items ->
            adapter.submitList(items)
            binding.emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }
        viewModel.isLoading.observe(this) { loading ->
            binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        }
    }

    private fun openMedia(item: FileItem) {
        try {
            when (item.fileType) {
                FileType.IMAGE -> {
                    // FIX: Không truyền toàn bộ danh sách qua Intent (TransactionTooLargeException)
                    // Chỉ truyền path hiện tại + index, ViewModel giữ danh sách
                    val allImages = viewModel.allImagePaths
                    val index = allImages.indexOf(item.path).coerceAtLeast(0)

                    // Giới hạn 200 ảnh xung quanh vị trí hiện tại để tránh vượt Binder 1MB
                    val start  = (index - 100).coerceAtLeast(0)
                    val end    = (index + 100).coerceAtMost(allImages.size)
                    val subList = allImages.subList(start, end)
                    val newIndex = index - start

                    startActivity(Intent(this, ImageViewerActivity::class.java).apply {
                        putStringArrayListExtra(ImageViewerActivity.EXTRA_PATHS, ArrayList(subList))
                        putExtra(ImageViewerActivity.EXTRA_INDEX, newIndex)
                    })
                }
                FileType.VIDEO -> {
                    startActivity(Intent(this, VideoPlayerActivity::class.java).apply {
                        putExtra(VideoPlayerActivity.EXTRA_PATH, item.path)
                    })
                }
                else -> {}
            }
        } catch (e: Exception) {
            // Fallback: mở ảnh đơn lẻ nếu vẫn lỗi
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
