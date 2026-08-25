package com.filemanager.ui.cleanup

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.filemanager.databinding.ActivityCleanupBinding
import com.filemanager.utils.FileUtils
import com.filemanager.utils.LoadingHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class CleanupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCleanupBinding
    private val viewModel: CleanupViewModel by viewModels()
    private lateinit var adapter: CleanupAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCleanupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Dọn dẹp"

        setupRecyclerView()
        setupBottomBar()
        setupObservers()
        viewModel.loadVolumes()
    }

    // ── RecyclerView ─────────────────────────────────────────────

    private fun setupRecyclerView() {
        adapter = CleanupAdapter(
            onScan             = { scope -> viewModel.startScan(scope) },
            onItemToggle       = { item  -> viewModel.toggleItem(item) },
            onCategorySelectAll = { cat, sel -> viewModel.selectCategory(cat, sel) },
            onHeaderExpand     = { cat  -> viewModel.toggleExpand(cat) },
            onSelectAll        = { viewModel.selectAll() },
            onLoadMore         = { cat  -> viewModel.loadMore(cat) }
        )

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@CleanupActivity)
            adapter       = this@CleanupActivity.adapter
            // ✅ setHasFixedSize(false) vì StorageCard có chiều cao động
            setHasFixedSize(false)
            setItemViewCacheSize(24)
            recycledViewPool.apply {
                setMaxRecycledViews(CleanupAdapter.T_ENTRY,  30)
                setMaxRecycledViews(CleanupAdapter.T_HEADER, 8)
                setMaxRecycledViews(CleanupAdapter.T_MORE,   4)
            }
            // Tắt over-scroll animation để tránh đo lại layout không cần thiết
            overScrollMode = View.OVER_SCROLL_NEVER
        }
    }

    // ── Bottom bar ───────────────────────────────────────────────

    private fun setupBottomBar() {
        binding.btnDeselectAll.setOnClickListener {
            viewModel.deselectAll()
        }
        binding.btnDeleteSelected.setOnClickListener {
            val count = viewModel.selectedCount.value ?: 0
            val size  = viewModel.selectedSize.value  ?: 0L
            if (count == 0) return@setOnClickListener
            MaterialAlertDialogBuilder(this)
                .setTitle("Xóa vào thùng rác")
                .setMessage("Chuyển $count mục (${FileUtils.formatSize(size)}) vào thùng rác?")
                .setPositiveButton("Xóa") { _, _ -> viewModel.deleteSelected() }
                .setNegativeButton("Hủy", null)
                .show()
        }
    }

    // ── Observers ────────────────────────────────────────────────

    private fun setupObservers() {
        viewModel.rows.observe(this) { rows ->
            adapter.submitList(rows)
        }

        viewModel.isScanning.observe(this) { scanning ->
            if (scanning) LoadingHelper.showOverlay(this, "Đang quét bộ nhớ...", "Vui lòng chờ")
            else          LoadingHelper.hideOverlay(this)
        }

        viewModel.selectedCount.observe(this) { count ->
            val size = viewModel.selectedSize.value ?: 0L
            updateBottomBar(count, size)
        }
        viewModel.selectedSize.observe(this) { size ->
            val count = viewModel.selectedCount.value ?: 0
            updateBottomBar(count, size)
        }

        viewModel.toast.observe(this) { msg ->
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateBottomBar(count: Int, size: Long) {
        if (count > 0) {
            binding.bottomDeleteBar.visibility = View.VISIBLE
            binding.tvSelectedInfo.text =
                "Đã chọn $count mục · ${FileUtils.formatSize(size)} sẽ được giải phóng"
        } else {
            binding.bottomDeleteBar.visibility = View.GONE
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { onBackPressed(); return true }
        return super.onOptionsItemSelected(item)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() = super.onBackPressed()
}
