package com.filemanager.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
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
import com.filemanager.utils.FastScroller
import com.filemanager.utils.LoadingHelper
import com.filemanager.utils.ShimmerType
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var fileAdapter: FileListAdapter

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) onPermissionGranted()
        else showPermissionDialog()
    }

    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            Environment.isExternalStorageManager()) {
            onPermissionGranted()
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
            // ✅ Tối ưu RecyclerView cho list lớn
            setHasFixedSize(true)
            setItemViewCacheSize(20)
            recycledViewPool.setMaxRecycledViews(FileListAdapter.VIEW_LIST, 20)
            recycledViewPool.setMaxRecycledViews(FileListAdapter.VIEW_GRID, 20)
        }
        binding.fastScroller.attachToRecyclerView(binding.recyclerView)
    }

    private fun setupSearch() {
        binding.searchEditText.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString() ?: ""
                if (q.isEmpty()) {
                    viewModel.clearSearch()
                    binding.searchScopeBar.visibility = View.GONE
                    binding.searchTypeBar.visibility  = View.GONE
                    // Reset chip "Tất cả loại"
                    binding.chipTypeAll.isChecked = true
                } else {
                    binding.searchScopeBar.visibility = View.VISIBLE
                    binding.searchTypeBar.visibility  = View.VISIBLE
                    viewModel.search(q)
                }
            }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })

        binding.btnClearSearch.setOnClickListener {
            binding.searchEditText.text?.clear()
            binding.searchScopeBar.visibility = View.GONE
            binding.searchTypeBar.visibility  = View.GONE
            viewModel.clearSearch()
        }

        // Scope chips
        binding.chipSearchCurrent.setOnClickListener  { viewModel.setSearchScope(SearchScope.CURRENT) }
        binding.chipSearchInternal.setOnClickListener { viewModel.setSearchScope(SearchScope.INTERNAL) }
        binding.chipSearchSD.setOnClickListener       { viewModel.setSearchScope(SearchScope.SD_CARD) }
        binding.chipSearchAll.setOnClickListener      { viewModel.setSearchScope(SearchScope.ALL) }

        // File type chips — "Tất cả loại" là toggle đặc biệt: bật nó thì tắt các chip kia
        binding.chipTypeAll.setOnClickListener {
            binding.chipTypeImage.isChecked   = false
            binding.chipTypeVideo.isChecked   = false
            binding.chipTypeAudio.isChecked   = false
            binding.chipTypeDoc.isChecked     = false
            binding.chipTypeArchive.isChecked = false
            binding.chipTypeApk.isChecked     = false
            binding.chipTypeFolder.isChecked  = false
            binding.chipTypeAll.isChecked     = true
            viewModel.setSearchFileType(SearchFileType.ALL)
        }

        fun onTypeChipToggle() {
            // Nếu không chip nào được chọn → tự động check "Tất cả loại"
            val anyChecked = listOf(
                binding.chipTypeImage, binding.chipTypeVideo, binding.chipTypeAudio,
                binding.chipTypeDoc, binding.chipTypeArchive, binding.chipTypeApk,
                binding.chipTypeFolder
            ).any { it.isChecked }

            if (!anyChecked) {
                binding.chipTypeAll.isChecked = true
                viewModel.setSearchFileType(SearchFileType.ALL)
                return
            }
            binding.chipTypeAll.isChecked = false

            // Ưu tiên: nếu chọn 1 loại → filter đúng loại đó
            // Nếu chọn nhiều loại → ALL (hiện tại chưa hỗ trợ multi-type, dùng ALL)
            val checkedCount = listOf(
                binding.chipTypeImage, binding.chipTypeVideo, binding.chipTypeAudio,
                binding.chipTypeDoc, binding.chipTypeArchive, binding.chipTypeApk,
                binding.chipTypeFolder
            ).count { it.isChecked }

            val type = if (checkedCount > 1) SearchFileType.ALL else when {
                binding.chipTypeImage.isChecked   -> SearchFileType.IMAGE
                binding.chipTypeVideo.isChecked   -> SearchFileType.VIDEO
                binding.chipTypeAudio.isChecked   -> SearchFileType.AUDIO
                binding.chipTypeDoc.isChecked     -> SearchFileType.DOCUMENT
                binding.chipTypeArchive.isChecked -> SearchFileType.ARCHIVE
                binding.chipTypeApk.isChecked     -> SearchFileType.APK
                binding.chipTypeFolder.isChecked  -> SearchFileType.FOLDER
                else -> SearchFileType.ALL
            }
            viewModel.setSearchFileType(type)
        }

        binding.chipTypeImage.setOnClickListener   { onTypeChipToggle() }
        binding.chipTypeVideo.setOnClickListener   { onTypeChipToggle() }
        binding.chipTypeAudio.setOnClickListener   { onTypeChipToggle() }
        binding.chipTypeDoc.setOnClickListener     { onTypeChipToggle() }
        binding.chipTypeArchive.setOnClickListener { onTypeChipToggle() }
        binding.chipTypeApk.setOnClickListener     { onTypeChipToggle() }
        binding.chipTypeFolder.setOnClickListener  { onTypeChipToggle() }
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
        binding.btnCleanup.setOnClickListener {
            binding.drawerLayout.closeDrawers()
            startActivity(Intent(this, com.filemanager.ui.cleanup.CleanupActivity::class.java))
        }
        binding.btnMenuToggle.setOnClickListener {
            binding.drawerLayout.openDrawer(binding.navDrawer)
        }

        // FIX 2: Bọc StatFs trong try-catch — Android 9 có thể chưa mount storage khi gọi
        updateStorageInfo()
    }

    private fun updateStorageInfo() {
        try {
            val root = viewModel.getStorageRoot()
            val stat = StatFs(root)
            val total = stat.totalBytes
            val free = stat.availableBytes
            val used = total - free
            val pct = if (total > 0) ((used.toFloat() / total) * 100).toInt() else 0
            binding.storageProgress.progress = pct
            binding.tvStorageInfo.text =
                "${FileUtils.formatSize(used)} / ${FileUtils.formatSize(total)} đã dùng"
        } catch (e: Exception) {
            // Storage chưa sẵn sàng — ẩn widget đi, không crash
            binding.tvStorageInfo.text = "Đang tải..."
            binding.storageProgress.progress = 0
        }
    }

    private fun setupObservers() {
        viewModel.files.observe(this) { files ->
            val query = binding.searchEditText.text?.toString() ?: ""
            if (query.isEmpty()) {
                // ✅ callback sau khi AsyncListDiffer diff xong → cập nhật FastScroller
                fileAdapter.submitList(files) {
                    binding.fastScroller.setItems(files)
                }
                binding.emptyView.visibility =
                    if (files.isEmpty()) View.VISIBLE else View.GONE
            }
        }

        viewModel.searchResults.observe(this) { results ->
            if (results != null) {
                fileAdapter.submitList(results) {
                    binding.fastScroller.setItems(results)
                }
                binding.emptyView.visibility =
                    if (results.isEmpty()) View.VISIBLE else View.GONE
                binding.emptyText.text =
                    if (results.isEmpty()) "Không tìm thấy kết quả" else ""
            }
        }

        viewModel.currentPath.observe(this) { path ->
            binding.tvCurrentPath.text = path
            val name = path.substringAfterLast("/").ifEmpty { "File Manager" }
            supportActionBar?.title = name
        }

        viewModel.isLoading.observe(this) { loading ->
            val query = binding.searchEditText.text?.toString() ?: ""
            if (loading) {
                binding.progressBar.visibility = View.VISIBLE
                val shimType = if (viewModel.isGridView.value == true)
                    ShimmerType.GRID else ShimmerType.LIST
                LoadingHelper.showShimmer(binding.recyclerView, shimType, 10)
            } else {
                binding.progressBar.visibility = View.GONE
                LoadingHelper.hideShimmer(binding.recyclerView)
            }
        }

        viewModel.isSelectionMode.observe(this) { selMode ->
            binding.bottomActionBar.visibility = if (selMode) View.VISIBLE else View.GONE
            binding.searchBar.visibility       = if (selMode) View.GONE  else View.VISIBLE
            binding.fabNewFolder.visibility    = if (selMode) View.GONE  else View.VISIBLE
            binding.fastScroller.visibility    = if (selMode) View.GONE  else View.VISIBLE
            if (selMode) {
                binding.searchScopeBar.visibility = View.GONE
                binding.searchTypeBar.visibility  = View.GONE
                supportActionBar?.subtitle = null
            }
            invalidateOptionsMenu()
        }

        // Cập nhật subtitle khi loại tìm kiếm thay đổi
        viewModel.searchFileType.observe(this) { type ->
            if (!binding.searchEditText.text.isNullOrEmpty()) {
                val typeLabel = when (type) {
                    SearchFileType.ALL      -> null
                    SearchFileType.IMAGE    -> "Ảnh"
                    SearchFileType.VIDEO    -> "Video"
                    SearchFileType.AUDIO    -> "Âm thanh"
                    SearchFileType.DOCUMENT -> "Tài liệu"
                    SearchFileType.ARCHIVE  -> "File nén"
                    SearchFileType.APK      -> "APK"
                    SearchFileType.FOLDER   -> "Thư mục"
                }
                val scopeLabel = when (viewModel.searchScope.value) {
                    SearchScope.CURRENT  -> "Thư mục hiện tại"
                    SearchScope.INTERNAL -> "Bộ nhớ trong"
                    SearchScope.SD_CARD  -> "Thẻ MicroSD"
                    SearchScope.ALL      -> "Tất cả"
                    null                 -> "Tất cả"
                }
                supportActionBar?.subtitle =
                    if (typeLabel != null) "$typeLabel · $scopeLabel"
                    else "Tìm trong: $scopeLabel"
            }
        }

        viewModel.selectedFiles.observe(this) { selected ->
            val count = selected.size
            if (count > 0) {
                supportActionBar?.title = "$count đã chọn"
            } else {
                val path = viewModel.currentPath.value ?: ""
                supportActionBar?.title =
                    path.substringAfterLast("/").ifEmpty { "File Manager" }
            }
        }

        viewModel.isGridView.observe(this) { isGrid ->
            binding.recyclerView.layoutManager =
                if (isGrid) GridLayoutManager(this, 3)
                else        LinearLayoutManager(this)
            fileAdapter.setViewType(isGrid)
            invalidateOptionsMenu()
        }

        viewModel.toastMessage.observe(this) { msg ->
            LoadingHelper.hideOverlay(this)
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // Ẩn/hiện chip SD tùy theo có thẻ SD không
        viewModel.hasSDCard.observe(this) { hasSD ->
            binding.chipSearchSD.visibility = if (hasSD) View.VISIBLE else View.GONE
        }

        // Hiện kết quả search kèm thông tin scope
        viewModel.searchScope.observe(this) { scope ->
            val label = when (scope) {
                SearchScope.CURRENT  -> "Thư mục hiện tại"
                SearchScope.INTERNAL -> "Bộ nhớ trong"
                SearchScope.SD_CARD  -> "Thẻ MicroSD"
                SearchScope.ALL      -> "Tất cả bộ nhớ"
            }
            if (!binding.searchEditText.text.isNullOrEmpty()) {
                supportActionBar?.subtitle = "Tìm trong: $label"
            } else {
                supportActionBar?.subtitle = null
            }
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
                    LoadingHelper.showOverlay(this, "Đang xóa...", "${items.size} mục")
                    viewModel.moveSelectedToTrash()
                }
                .setNegativeButton("Hủy", null)
                .show()
        }
        binding.btnSelectAll.setOnClickListener { viewModel.selectAll() }
        binding.btnCancelSelection.setOnClickListener { viewModel.exitSelectionMode() }
        binding.btnShare.setOnClickListener { shareSelectedFiles() }
    }

    // ── File actions ────────────────────────────────────────────

    private fun onFileItemClick(item: FileItem) {
        if (viewModel.isSelectionMode.value == true) {
            viewModel.toggleSelection(item)
            return
        }
        when {
            item.isDirectory              -> {
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
                    4 -> MaterialAlertDialogBuilder(this)
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
            .show()
    }

    private fun onSelectionChange(item: FileItem) = viewModel.toggleSelection(item)

    private fun openImageViewer(item: FileItem) {
        val images = viewModel.files.value
            ?.filter { it.fileType == FileType.IMAGE }
            ?.map { it.path } ?: listOf(item.path)
        val index = images.indexOf(item.path).coerceAtLeast(0)
        startActivity(Intent(this, ImageViewerActivity::class.java).apply {
            putStringArrayListExtra(ImageViewerActivity.EXTRA_PATHS, ArrayList(images))
            putExtra(ImageViewerActivity.EXTRA_INDEX, index)
        })
    }

    private fun openVideoPlayer(item: FileItem) {
        startActivity(Intent(this, VideoPlayerActivity::class.java).apply {
            putExtra(VideoPlayerActivity.EXTRA_PATH, item.path)
        })
    }

    private fun shareSelectedFiles() {
        val items = viewModel.getSelectedItems()
        if (items.isEmpty()) return
        FileUtils.shareFiles(this, items.map { it.file })
    }

    // ── Dialogs ─────────────────────────────────────────────────

    private fun showNewFolderDialog() {
        val et = EditText(this).apply {
            hint = "Tên thư mục"
            setPadding(48, 24, 48, 8)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Tạo thư mục mới")
            .setView(et)
            .setPositiveButton("Tạo") { _, _ ->
                val name = et.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                val cur = viewModel.currentPath.value ?: return@setPositiveButton
                if (File(cur, name).mkdirs()) {
                    viewModel.refresh()
                    Toast.makeText(this, "Đã tạo \"$name\"", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Không thể tạo thư mục", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun showRenameDialog(item: FileItem) {
        val et = EditText(this).apply {
            setText(item.name)
            selectAll()
            setPadding(48, 24, 48, 8)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Đổi tên")
            .setView(et)
            .setPositiveButton("Đổi tên") { _, _ ->
                val newName = et.text.toString().trim()
                if (newName.isNotEmpty() && newName != item.name) {
                    val dest = File(item.file.parent, newName)
                    if (item.file.renameTo(dest)) {
                        viewModel.refresh()
                        Toast.makeText(this, "Đã đổi tên", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Không thể đổi tên", Toast.LENGTH_SHORT).show()
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
        AlertDialog.Builder(this)
            .setTitle("Sắp xếp theo")
            .setItems(options) { _, which ->
                viewModel.setSortType(SortType.values()[which])
            }
            .show()
        return true
    }

    // ── Permissions ─────────────────────────────────────────────

    private fun checkPermissions() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                if (!Environment.isExternalStorageManager()) showPermissionDialog()
                else onPermissionGranted()
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                val perms = mutableListOf<String>()
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED)
                    perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED)
                    perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)

                if (perms.isEmpty()) onPermissionGranted()
                else permissionLauncher.launch(perms.toTypedArray())
            }
            else -> onPermissionGranted()
        }
    }

    private fun onPermissionGranted() {
        viewModel.init()                                    // detect SD card
        viewModel.navigateTo(viewModel.getStorageRoot())   // load files
    }

    private fun showPermissionDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Cần quyền truy cập")
            .setMessage("App cần quyền truy cập bộ nhớ để quản lý file.")
            .setPositiveButton("Cấp quyền") { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // FIX 4: ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
                    // crash trên một số ROM nếu package URI thiếu — bọc try-catch
                    try {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                        manageStorageLauncher.launch(intent)
                    } catch (e: Exception) {
                        // Fallback: mở trang Settings tổng
                        manageStorageLauncher.launch(
                            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        )
                    }
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

    // ── Menu ────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val sel = viewModel.isSelectionMode.value == true
        menu.findItem(R.id.action_sort)?.isVisible        = !sel
        menu.findItem(R.id.action_toggle_view)?.isVisible = !sel
        menu.findItem(R.id.action_toggle_view)?.setIcon(
            if (viewModel.isGridView.value == true) R.drawable.ic_list else R.drawable.ic_grid
        )
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_sort        -> showSortDialog()
            R.id.action_toggle_view -> { viewModel.toggleGridView(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
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
}
