package com.filemanager.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.filemanager.R
import com.filemanager.data.model.FileItem
import com.filemanager.data.model.FileType
import com.filemanager.data.model.SortType
import com.filemanager.databinding.ActivityMainBinding
import com.filemanager.ui.timeline.TimelineActivity
import com.filemanager.ui.viewer.ImageViewerActivity
import com.filemanager.ui.viewer.VideoPlayerActivity
import com.filemanager.utils.FileUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var fileAdapter: FileListAdapter

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            viewModel.navigateTo(viewModel.getStorageRoot())
        } else {
            showPermissionDialog()
        }
    }

    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            viewModel.navigateTo(viewModel.getStorageRoot())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        setupRecyclerView()
        setupSearch()
        setupSidebar()
        setupObservers()
        setupBottomBar()
        checkPermissions()
    }

    private fun setupRecyclerView() {
        fileAdapter = FileListAdapter(
            onItemClick = ::onFileItemClick,
            onItemLongClick = ::onFileItemLongClick,
            onSelectionChange = ::onSelectionChange
        )
        binding.recyclerView.apply {
            adapter = fileAdapter
            layoutManager = LinearLayoutManager(this@MainActivity)
        }
    }

    private fun setupSearch() {
        binding.searchEditText.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString() ?: ""
                if (q.isEmpty()) viewModel.clearSearch()
                else viewModel.search(q)
            }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
        binding.btnClearSearch.setOnClickListener {
            binding.searchEditText.text?.clear()
            viewModel.clearSearch()
        }
    }

    private fun setupSidebar() {
        val quickAccess = viewModel.getQuickAccessPaths()
        val sidebarAdapter = SidebarAdapter(quickAccess) { path ->
            binding.drawerLayout.closeDrawers()
            viewModel.navigateTo(path)
            binding.searchEditText.text?.clear()
        }
        binding.sidebarRecycler.adapter = sidebarAdapter

        binding.btnTimeline.setOnClickListener {
            binding.drawerLayout.closeDrawers()
            startActivity(Intent(this, TimelineActivity::class.java))
        }
        binding.btnTrash.setOnClickListener {
            binding.drawerLayout.closeDrawers()
            startActivity(Intent(this, TrashActivity::class.java))
        }
        binding.btnMenuToggle.setOnClickListener {
            binding.drawerLayout.openDrawer(binding.navDrawer)
        }

        // Storage info
        updateStorageInfo()
    }

    private fun updateStorageInfo() {
        val stat = android.os.StatFs(viewModel.getStorageRoot())
        val total = stat.totalBytes
        val free = stat.availableBytes
        val used = total - free
        val pct = ((used.toFloat() / total) * 100).toInt()
        binding.storageProgress.progress = pct
        binding.tvStorageInfo.text = "${FileUtils.formatSize(used)} / ${FileUtils.formatSize(total)} đã dùng"
    }

    private fun setupObservers() {
        viewModel.files.observe(this) { files ->
            val query = binding.searchEditText.text?.toString() ?: ""
            if (query.isEmpty()) {
                fileAdapter.submitList(files)
                binding.emptyView.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
            }
        }

        viewModel.searchResults.observe(this) { results ->
            if (results != null) {
                fileAdapter.submitList(results)
                binding.emptyView.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
                binding.emptyText.text = if (results.isEmpty()) "Không tìm thấy kết quả" else ""
            }
        }

        viewModel.currentPath.observe(this) { path ->
            binding.tvCurrentPath.text = path
            supportActionBar?.title = path.substringAfterLast("/").ifEmpty { "File Manager" }
        }

        viewModel.isLoading.observe(this) { loading ->
            binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        }

        viewModel.isSelectionMode.observe(this) { selMode ->
            binding.bottomActionBar.visibility = if (selMode) View.VISIBLE else View.GONE
            binding.searchBar.visibility = if (selMode) View.GONE else View.VISIBLE
            binding.fabNewFolder.visibility = if (selMode) View.GONE else View.VISIBLE
            invalidateOptionsMenu()
        }

        viewModel.selectedFiles.observe(this) { selected ->
            val count = selected.size
            if (count > 0) {
                supportActionBar?.title = "$count đã chọn"
            } else {
                val path = viewModel.currentPath.value ?: ""
                supportActionBar?.title = path.substringAfterLast("/").ifEmpty { "File Manager" }
            }
        }

        viewModel.isGridView.observe(this) { isGrid ->
            val span = if (isGrid) 3 else 1
            binding.recyclerView.layoutManager = if (isGrid)
                GridLayoutManager(this, span)
            else
                LinearLayoutManager(this)
            fileAdapter.setViewType(isGrid)
            invalidateOptionsMenu()
        }

        viewModel.toastMessage.observe(this) { msg ->
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupBottomBar() {
        binding.fabNewFolder.setOnClickListener { showNewFolderDialog() }

        binding.btnDelete.setOnClickListener {
            val items = viewModel.getSelectedItems()
            MaterialAlertDialogBuilder(this)
                .setTitle("Xóa vào thùng rác")
                .setMessage("Chuyển ${items.size} mục vào thùng rác?")
                .setPositiveButton("Xóa") { _, _ ->
                    viewModel.moveSelectedToTrash()
                }
                .setNegativeButton("Hủy", null)
                .show()
        }
        binding.btnSelectAll.setOnClickListener { viewModel.selectAll() }
        binding.btnCancelSelection.setOnClickListener { viewModel.exitSelectionMode() }
        binding.btnShare.setOnClickListener { shareSelectedFiles() }
    }

    private fun onFileItemClick(item: FileItem) {
        if (viewModel.isSelectionMode.value == true) {
            viewModel.toggleSelection(item)
            return
        }
        when {
            item.isDirectory -> {
                binding.searchEditText.text?.clear()
                viewModel.navigateTo(item.path)
            }
            item.fileType == FileType.IMAGE -> openImageViewer(item)
            item.fileType == FileType.VIDEO -> openVideoPlayer(item)
            else -> FileUtils.openFile(this, item.file)
        }
    }

    private fun onFileItemLongClick(item: FileItem) {
        if (viewModel.isSelectionMode.value == true) {
            viewModel.toggleSelection(item)
            return
        }
        // Show context menu
        val options = arrayOf("Chọn", "Đổi tên", "Chia sẻ", "Thuộc tính", "Xóa vào thùng rác")
        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> viewModel.enterSelectionMode(item.path)
                    1 -> showRenameDialog(item)
                    2 -> FileUtils.shareFiles(this, listOf(item.file))
                    3 -> FilePropertiesDialog.newInstance(item)
                            .show(supportFragmentManager, "props")
                    4 -> {
                        MaterialAlertDialogBuilder(this)
                            .setTitle("Xóa vào thùng rác")
                            .setMessage("Chuyển \"${item.name}\" vào thùng rác?")
                            .setPositiveButton("Xóa") { _, _ ->
                                viewModel.enterSelectionMode(item.path)
                                viewModel.moveSelectedToTrash()
                            }
                            .setNegativeButton("Hủy", null)
                            .show()
                    }
                }
            }
            .show()
    }

    private fun showRenameDialog(item: FileItem) {
        val editText = android.widget.EditText(this).apply {
            setText(item.name)
            selectAll()
            setPadding(48, 24, 48, 8)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Đổi tên")
            .setView(editText)
            .setPositiveButton("Đổi tên") { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotEmpty() && newName != item.name) {
                    val newFile = java.io.File(item.file.parent, newName)
                    if (item.file.renameTo(newFile)) {
                        viewModel.refresh()
                        Toast.makeText(this, "Đã đổi tên thành công", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Không thể đổi tên", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun onSelectionChange(item: FileItem) {
        viewModel.toggleSelection(item)
    }

    private fun openImageViewer(item: FileItem) {
        val images = viewModel.files.value
            ?.filter { it.fileType == FileType.IMAGE }
            ?.map { it.path } ?: listOf(item.path)
        val index = images.indexOf(item.path).coerceAtLeast(0)
        val intent = Intent(this, ImageViewerActivity::class.java).apply {
            putStringArrayListExtra(ImageViewerActivity.EXTRA_PATHS, ArrayList(images))
            putExtra(ImageViewerActivity.EXTRA_INDEX, index)
        }
        startActivity(intent)
    }

    private fun openVideoPlayer(item: FileItem) {
        val intent = Intent(this, VideoPlayerActivity::class.java).apply {
            putExtra(VideoPlayerActivity.EXTRA_PATH, item.path)
        }
        startActivity(intent)
    }

    private fun shareSelectedFiles() {
        val items = viewModel.getSelectedItems()
        if (items.isEmpty()) return
        FileUtils.shareFiles(this, items.map { it.file })
    }

    override fun onBackPressed() {
        when {
            binding.drawerLayout.isDrawerOpen(binding.navDrawer) ->
                binding.drawerLayout.closeDrawers()
            viewModel.isSelectionMode.value == true ->
                viewModel.exitSelectionMode()
            !binding.searchEditText.text.isNullOrEmpty() -> {
                binding.searchEditText.text?.clear()
                viewModel.clearSearch()
            }
            !viewModel.navigateUp() -> super.onBackPressed()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val selMode = viewModel.isSelectionMode.value == true
        menu.findItem(R.id.action_sort)?.isVisible = !selMode
        menu.findItem(R.id.action_toggle_view)?.isVisible = !selMode
        menu.findItem(R.id.action_toggle_view)?.setIcon(
            if (viewModel.isGridView.value == true) R.drawable.ic_list else R.drawable.ic_grid
        )
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_sort -> showSortDialog()
            R.id.action_toggle_view -> { viewModel.toggleGridView(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showNewFolderDialog() {
        val editText = android.widget.EditText(this).apply {
            hint = "Tên thư mục"
            setPadding(48, 24, 48, 8)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Tạo thư mục mới")
            .setView(editText)
            .setPositiveButton("Tạo") { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isNotEmpty()) {
                    val currentPath = viewModel.currentPath.value ?: return@setPositiveButton
                    val newDir = java.io.File(currentPath, name)
                    if (newDir.mkdirs()) {
                        viewModel.refresh()
                        Toast.makeText(this, "Đã tạo thư mục \"$name\"", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Không thể tạo thư mục", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun showSortDialog(): Boolean {
        val options = arrayOf(
            "Tên A→Z", "Tên Z→A",
            "Kích thước ↑", "Kích thước ↓",
            "Ngày cũ nhất", "Ngày mới nhất",
            "Theo loại file"
        )
        val sorts = SortType.values()
        AlertDialog.Builder(this)
            .setTitle("Sắp xếp theo")
            .setItems(options) { _, which ->
                viewModel.setSortType(sorts[which])
            }
            .show()
        return true
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                showPermissionDialog()
            }
        } else {
            val perms = arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            val denied = perms.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (denied.isNotEmpty()) permissionLauncher.launch(denied.toTypedArray())
        }
    }

    private fun showPermissionDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Cần quyền truy cập")
            .setMessage("App cần quyền truy cập bộ nhớ để quản lý file.")
            .setPositiveButton("Cấp quyền") { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    manageStorageLauncher.launch(intent)
                } else {
                    permissionLauncher.launch(arrayOf(
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ))
                }
            }
            .setNegativeButton("Thoát") { _, _ -> finish() }
            .show()
    }
}
