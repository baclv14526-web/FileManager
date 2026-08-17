package com.filemanager.ui.timeline

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.filemanager.R
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
                return if (adapter.isHeader(position)) 3 else 1
            }
        }
        binding.recyclerView.layoutManager = gridLayoutManager
        binding.recyclerView.adapter = adapter
    }

    private fun setupChips() {
        binding.chipAll.setOnClickListener { viewModel.loadMedia(TimelineMediaType.ALL) }
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
        when (item.fileType) {
            FileType.IMAGE -> {
                val allImages = viewModel.allImagePaths
                val index = allImages.indexOf(item.path).coerceAtLeast(0)
                val intent = Intent(this, ImageViewerActivity::class.java).apply {
                    putStringArrayListExtra(ImageViewerActivity.EXTRA_PATHS, ArrayList(allImages))
                    putExtra(ImageViewerActivity.EXTRA_INDEX, index)
                }
                startActivity(intent)
            }
            FileType.VIDEO -> {
                startActivity(Intent(this, VideoPlayerActivity::class.java).apply {
                    putExtra(VideoPlayerActivity.EXTRA_PATH, item.path)
                })
            }
            else -> {}
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { onBackPressed(); return true }
        return super.onOptionsItemSelected(item)
    }
}
