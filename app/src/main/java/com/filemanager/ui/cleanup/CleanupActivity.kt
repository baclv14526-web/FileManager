package com.filemanager.ui.cleanup

import android.os.Bundle
import com.filemanager.utils.LoadingHelper
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.filemanager.databinding.ActivityCleanupBinding
import com.filemanager.utils.FileUtils
import com.filemanager.utils.StorageVolume
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class CleanupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCleanupBinding
    private val viewModel: CleanupViewModel by viewModels()
    private lateinit var adapter: CleanupAdapter

    private var currentScope = ScanScope.ALL

    enum class ScanScope { ALL, INTERNAL, SD_CARD }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCleanupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Dọn dẹp"

        setupRecyclerView()
        setupButtons()
        setupObservers()

        viewModel.loadVolumes()
    }

    // ── Setup ───────────────────────────────────────────────────

    private fun setupRecyclerView() {
        adapter = CleanupAdapter(
            onItemToggle          = { item -> viewModel.toggleItem(item) },
            onCategoryToggleSelect = { cat, sel -> viewModel.selectCategory(cat, sel) },
            onHeaderClick         = { cat -> viewModel.toggleExpand(cat) }
        )
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@CleanupActivity)
            adapter = this@CleanupActivity.adapter
            isNestedScrollingEnabled = false
            // ✅ Cache ViewHolder để scroll mượt hơn
            setItemViewCacheSize(20)
            recycledViewPool.setMaxRecycledViews(CleanupAdapter.TYPE_ENTRY, 30)
            recycledViewPool.setMaxRecycledViews(CleanupAdapter.TYPE_HEADER, 8)
            // ✅ Kích thước cố định giúp tránh đo lại layout
            setHasFixedSize(false)
        }
    }

    private fun setupButtons() {
        // Scope chips
        binding.chipScanAll.setOnClickListener      { currentScope = ScanScope.ALL;      updateScopeUI() }
        binding.chipScanInternal.setOnClickListener { currentScope = ScanScope.INTERNAL; updateScopeUI() }
        binding.chipScanSD.setOnClickListener       { currentScope = ScanScope.SD_CARD;  updateScopeUI() }

        // Bắt đầu quét
        binding.btnScan.setOnClickListener {
            val roots = getScanRoots()
            if (roots.isEmpty()) {
                Toast.makeText(this, "Không có bộ nhớ để quét", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewModel.startScan(roots)
        }

        // Chọn tất cả
        binding.btnSelectAll.setOnClickListener { viewModel.selectAll(true) }

        // Bỏ chọn
        binding.btnDeselectAll.setOnClickListener { viewModel.selectAll(false) }

        // Xoá vào thùng rác
        binding.btnDeleteSelected.setOnClickListener {
            val count = viewModel.selectedCount.value ?: 0
            val size  = viewModel.selectedSize.value ?: 0L
            if (count == 0) return@setOnClickListener

            MaterialAlertDialogBuilder(this)
                .setTitle("Xóa vào thùng rác")
                .setMessage("Chuyển $count mục (${FileUtils.formatSize(size)}) vào thùng rác?")
                .setPositiveButton("Xóa") { _, _ -> viewModel.deleteSelected() }
                .setNegativeButton("Hủy", null)
                .show()
        }
    }

    private fun setupObservers() {
        // Volumes → update storage bars và chips
        viewModel.storageVolumes.observe(this) { volumes ->
            updateStorageBars(volumes)
            val hasSD = volumes.any { it.isRemovable }
            binding.chipScanSD.visibility = if (hasSD) View.VISIBLE else View.GONE
            if (!hasSD && currentScope == ScanScope.SD_CARD) {
                currentScope = ScanScope.ALL
                binding.chipScanAll.isChecked = true
            }
        }

        // List items
        viewModel.listItems.observe(this) { items ->
            adapter.submitList(items)

            val hasResult = items.isNotEmpty()
            binding.summaryBar.visibility = if (hasResult) View.VISIBLE else View.GONE
            binding.emptyView.visibility  =
                if (!hasResult && viewModel.isScanning.value == false &&
                    viewModel.scanProgress.value?.isNotEmpty() == true)
                    View.VISIBLE else View.GONE
        }

        // Scan progress / summary text
        viewModel.scanProgress.observe(this) { msg ->
            binding.tvSummary.text = msg
            // Update overlay message nếu đang quét
            if (viewModel.isScanning.value == true) {
                LoadingHelper.updateOverlayMessage(this, "Đang quét...", msg)
            }
        }

        // Đang quét
        viewModel.isScanning.observe(this) { scanning ->
            binding.scanningView.visibility = View.GONE   // dùng overlay thay
            binding.btnScan.isEnabled       = !scanning
            binding.btnScan.text            = if (scanning) "Đang quét..." else "🔍  Bắt đầu quét"
            if (scanning) {
                LoadingHelper.showOverlay(
                    this,
                    message = "Đang quét bộ nhớ...",
                    subMsg  = "Tìm file lớn, rỗng và rác"
                )
            } else {
                LoadingHelper.hideOverlay(this)
            }
        }

        // Selection stats → bottom bar
        viewModel.selectedCount.observe(this) { count ->
            val size = viewModel.selectedSize.value ?: 0L
            updateBottomBar(count, size)
        }
        viewModel.selectedSize.observe(this) { size ->
            val count = viewModel.selectedCount.value ?: 0
            updateBottomBar(count, size)
        }

        // Toast
        viewModel.toastMsg.observe(this) { msg ->
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // ── UI helpers ──────────────────────────────────────────────

    private fun updateStorageBars(volumes: List<StorageVolume>) {
        val internal = volumes.firstOrNull { !it.isRemovable }
        val sd       = volumes.firstOrNull { it.isRemovable }

        if (internal != null) {
            binding.tvInternalName.text  = internal.name
            binding.tvInternalUsage.text =
                "${FileUtils.formatSize(internal.usedBytes)} / ${FileUtils.formatSize(internal.totalBytes)}"
            binding.pbInternal.progress  = internal.usedPercent
        }

        if (sd != null) {
            binding.sdStorageRow.visibility = View.VISIBLE
            binding.tvSDName.text           = sd.name
            binding.tvSDUsage.text          =
                "${FileUtils.formatSize(sd.usedBytes)} / ${FileUtils.formatSize(sd.totalBytes)}"
            binding.pbSD.progress           = sd.usedPercent
        } else {
            binding.sdStorageRow.visibility = View.GONE
        }
    }

    private fun updateScopeUI() {
        // chip check state đã tự toggle qua OnClickListener, chỉ cần sync
    }

    private fun getScanRoots(): List<String> {
        val volumes = viewModel.storageVolumes.value ?: return emptyList()
        return when (currentScope) {
            ScanScope.ALL      -> volumes.map { it.path }
            ScanScope.INTERNAL -> volumes.filter { !it.isRemovable }.map { it.path }
            ScanScope.SD_CARD  -> volumes.filter { it.isRemovable }.map { it.path }
        }.filter { android.os.Environment.getExternalStorageDirectory() != null }
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
