package com.filemanager.ui.main

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var fileAdapter: FileListAdapter
    private lateinit var sidebarAdapter: SidebarAdapter

    // SharedPreferences lưu SAF tree URI đã được cấp quyền
    private lateinit var prefs: SharedPreferences
    // SAF tree URI được lưu để xóa file trên thẻ SD
    private var sdSafUri: Uri? = null
    // Callback sau khi nhận được SAF URI (thường gọi deleteSelectedDirectly)
    private var pendingSafAction: ((Uri) -> Unit)? = null

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

    // SAF launcher — nhận quyền ghi thẻ SD từ ACTION_OPEN_DOCUMENT_TREE
    private val safLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            // Lưu quyền tồn tại qua reboot
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {}
            // Kiểm tra user có chọn đúng ROOT thẻ SD không
            // lastPathSegment dạng "volumeId:subpath" — subpath rỗng = đã chọn root
            val segment = uri.lastPathSegment ?: ""
            val subPath = if (segment.contains(':')) segment.substringAfter(':') else segment
            if (subPath.isNotEmpty()) {
                // User chọn thư mục con — cảnh báo nhưng vẫn dùng (sẽ giới hạn phạm vi)
                Toast.makeText(
                    this,
                    "⚠️ Bạn chọn thư mục con, không phải root thẻ SD.\nChỉ thao tác được file trong thư mục đó.",
                    Toast.LENGTH_LONG
                ).show()
            }
            sdSafUri = uri
            prefs.edit().putString(PREF_SD_SAF_URI, uri.toString()).apply()
            // Thực hiện hành động đợi SAF đã chờ
            pendingSafAction?.invoke(uri)
        } else {
            Toast.makeText(this, "Cần cấp quyền thẻ SD để tiếp tục", Toast.LENGTH_LONG).show()
            LoadingHelper.hideOverlay(this)
        }
        pendingSafAction = null
    }

    private val safIntentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (result.resultCode == RESULT_OK && uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {}
            val segment = uri.lastPathSegment ?: ""
            val subPath = if (segment.contains(':')) segment.substringAfter(':') else segment
            if (subPath.isNotEmpty()) {
                Toast.makeText(
                    this,
                    "⚠️ Bạn chọn thư mục con, không phải root thẻ SD.\nChỉ thao tác được file trong thư mục đó.",
                    Toast.LENGTH_LONG
                ).show()
            }
            sdSafUri = uri
            prefs.edit().putString(PREF_SD_SAF_URI, uri.toString()).apply()
            pendingSafAction?.invoke(uri)
        } else {
            Toast.makeText(this, "Cần cấp quyền thẻ SD để tiếp tục", Toast.LENGTH_LONG).show()
            LoadingHelper.hideOverlay(this)
        }
        pendingSafAction = null
    }

    private fun requestSdCardSafPermission() {
        val pickerIntent = com.filemanager.utils.SdCardResolver.buildSdCardPickerIntent(this)
        if (pickerIntent != null) {
            try {
                safIntentLauncher.launch(pickerIntent)
                return
            } catch (_: Exception) {}
        }
        safLauncher.launch(null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        prefs = getSharedPreferences("fm_prefs", MODE_PRIVATE)
        // Khôi phục SAF URI đã lưu từ lần trước
        prefs.getString(PREF_SD_SAF_URI, null)?.let { sdSafUri = Uri.parse(it) }

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
            onSelectionChange = ::onSelectionChange,
            onHeaderToggle = { type -> viewModel.toggleSearchGroup(type) }
        )
        binding.recyclerView.apply {
            adapter = fileAdapter
            layoutManager = LinearLayoutManager(this@MainActivity)
            // ✅ Tối ưu RecyclerView cho list lớn
            setHasFixedSize(true)
            setItemViewCacheSize(20)
            recycledViewPool.setMaxRecycledViews(FileListAdapter.VIEW_LIST, 20)
            recycledViewPool.setMaxRecycledViews(FileListAdapter.VIEW_GRID, 20)
            recycledViewPool.setMaxRecycledViews(FileListAdapter.VIEW_HEADER, 10)
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
        sidebarAdapter = SidebarAdapter(quickAccess) { path ->
            binding.drawerLayout.closeDrawers()
            viewModel.navigateTo(path)
            binding.searchEditText.text?.clear()
        }
        binding.sidebarRecycler.adapter = sidebarAdapter

        binding.drawerLayout.addDrawerListener(object : androidx.drawerlayout.widget.DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) {
                if (drawerView == binding.navDrawer) {
                    sidebarAdapter.submitList(viewModel.getQuickAccessPaths())
                    updateStorageInfo()
                }
            }
        })

        binding.btnTimeline.setOnClickListener {
            binding.drawerLayout.closeDrawers()
            startActivity(Intent(this, TimelineActivity::class.java))
        }
        binding.btnCleanup.setOnClickListener {
            binding.drawerLayout.closeDrawers()
            startActivity(Intent(this, com.filemanager.ui.cleanup.CleanupActivity::class.java))
        }
        binding.btnTrash.setOnClickListener {
            binding.drawerLayout.closeDrawers()
            startActivity(Intent(this, TrashActivity::class.java))
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
                val isLoading = viewModel.isLoading.value == true
                binding.emptyView.visibility =
                    if (files.isEmpty() && !isLoading) View.VISIBLE else View.GONE
            }
        }

        viewModel.searchDisplayItems.observe(this) { displayItems ->
            val isLoading = viewModel.isLoading.value == true
            if (displayItems != null) {
                fileAdapter.submitDisplayList(displayItems) {
                    val rawFiles = viewModel.searchResults.value ?: emptyList()
                    binding.fastScroller.setItems(rawFiles)
                }
                binding.emptyView.visibility =
                    if (displayItems.isEmpty() && !isLoading) View.VISIBLE else View.GONE
                binding.emptyText.text =
                    if (displayItems.isEmpty() && !isLoading) "Không tìm thấy kết quả" else ""
            } else {
                val currentFiles = viewModel.files.value ?: emptyList()
                val query = binding.searchEditText.text?.toString() ?: ""
                if (query.isEmpty()) {
                    fileAdapter.submitList(currentFiles) {
                        binding.fastScroller.setItems(currentFiles)
                    }
                    binding.emptyView.visibility =
                        if (currentFiles.isEmpty() && !isLoading) View.VISIBLE else View.GONE
                    binding.emptyText.text =
                        if (currentFiles.isEmpty() && !isLoading) "Thư mục trống" else ""
                }
            }
        }

        viewModel.currentPath.observe(this) { path ->
            binding.tvCurrentPath.text = path
            val name = path.substringAfterLast("/").ifEmpty { "File Manager" }
            supportActionBar?.title = name
        }

        viewModel.isLoading.observe(this) { loading ->
            binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
            binding.loadingSpinner.visibility = if (loading) View.VISIBLE else View.GONE
            if (loading) {
                binding.emptyView.visibility = View.GONE
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
                if (isGrid) {
                    GridLayoutManager(this, 3).apply {
                        spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                            override fun getSpanSize(position: Int): Int {
                                return if (fileAdapter.isHeader(position)) 3 else 1
                            }
                        }
                    }
                } else {
                    LinearLayoutManager(this)
                }
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
            if (viewModel.hasAnySelectedOnSdCard()) {
                // Có file trên thẻ SD — xóa thẳng, không qua thùng rác
                MaterialAlertDialogBuilder(this)
                    .setTitle("⚠️ Xóa vĩnh viễn")
                    .setMessage("Đây là file trên thẻ SD, không thể vào thùng rác.\nXóa ${items.size} mục vĩnh viễn?")
                    .setPositiveButton("Xóa") { _, _ ->
                        LoadingHelper.showOverlay(this, "Đang xóa...", "${items.size} mục")
                        performSdCardDelete()
                    }
                    .setNegativeButton("Hủy", null)
                    .show()
            } else {
                // Bộ nhớ trong — vào thùng rác như cũ
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
        }
        binding.btnSelectAll.setOnClickListener { viewModel.selectAll() }
        binding.btnCancelSelection.setOnClickListener { viewModel.exitSelectionMode() }
        binding.btnShare.setOnClickListener { shareSelectedFiles() }
    }

    /**
     * Thực hiện xóa file trên thẻ SD:
     * - Nếu đã có SAF URI hợp lệ và đúng volume → xóa luôn
     * - Nếu chưa có hoặc sai volume → mở ACTION_OPEN_DOCUMENT_TREE xin quyền mới
     */
    private fun performSdCardDelete() {
        val existingUri = sdSafUri
        // Kiểm tra URI đã lưu có còn quyền ghi không
        val hasWritePermission = existingUri != null && contentResolver.persistedUriPermissions
            .any { it.uri == existingUri && it.isWritePermission }
        // Kiểm tra thêm: URI phải bao phủ các file cần xóa (đúng volume qua SdCardResolver)
        val uriCoversFiles = hasWritePermission && existingUri != null &&
            viewModel.getSelectedItems().any { item ->
                com.filemanager.utils.SdCardResolver.uriMatchesFile(existingUri, item.file, this)
            }

        if (uriCoversFiles && existingUri != null) {
            viewModel.deleteSelectedDirectly(existingUri)
        } else {
            // Cần xin quyền SAF mới — xóa URI cũ để tránh dùng nhầm
            if (!uriCoversFiles && existingUri != null) {
                sdSafUri = null
                prefs.edit().remove(PREF_SD_SAF_URI).apply()
            }
            pendingSafAction = { uri -> viewModel.deleteSelectedDirectly(uri) }
            // Hiện dialog hướng dẫn rõ ràng trước khi mở picker
            MaterialAlertDialogBuilder(this)
                .setTitle("📂 Cấp quyền thẻ SD")
                .setMessage(
                    "Để xóa file trên thẻ SD, Android yêu cầu bạn cấp quyền thủ công:\n\n" +
                    "1. Nhấn OK để mở trình chọn thư mục\n" +
                    "2. Chuyển sang vị trí Thẻ SD (MicroSD)\n" +
                    "3. Chọn thư mục GỐC của thẻ SD (không chọn thư mục con)\n" +
                    "4. Nhấn 'Cho phép' / 'Use this folder'"
                )
                .setPositiveButton("OK, mở trình chọn") { _, _ ->
                    requestSdCardSafPermission()
                }
                .setNegativeButton("Hủy") { _, _ ->
                    pendingSafAction = null
                    LoadingHelper.hideOverlay(this)
                }
                .show()
        }
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
        val onSd = viewModel.run {
            val f = File(item.path)
            val repo = com.filemanager.data.repository.FileRepository(this@MainActivity)
            repo.isSdCardFile(f)
        }
        val deleteLabel = if (onSd) "⚠️ Xóa vĩnh viễn" else "Xóa vào thùng rác"
        val options = arrayOf("Chọn", "Đổi tên", "Chia sẻ", "Thuộc tính", deleteLabel)
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
                        if (onSd) {
                            MaterialAlertDialogBuilder(this)
                                .setTitle("⚠️ Xóa vĩnh viễn")
                                .setMessage("Đây là file trên thẻ SD.\nXóa \"${item.name}\" vĩnh viễn?")
                                .setPositiveButton("Xóa") { _, _ ->
                                    viewModel.enterSelectionMode(item.path)
                                    LoadingHelper.showOverlay(this, "Đang xóa...", item.name)
                                    performSdCardDelete()
                                }
                                .setNegativeButton("Hủy", null)
                                .show()
                        } else {
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
        val videos = viewModel.files.value
            ?.filter { it.fileType == FileType.VIDEO }
            ?.map { it.path } ?: listOf(item.path)
        val index = videos.indexOf(item.path).coerceAtLeast(0)
        startActivity(Intent(this, VideoPlayerActivity::class.java).apply {
            putStringArrayListExtra(VideoPlayerActivity.EXTRA_PLAYLIST, ArrayList(videos))
            putExtra(VideoPlayerActivity.EXTRA_INDEX, index)
            // Fallback for backward compat
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
        val dialogBinding = com.filemanager.databinding.DialogInputNameBinding.inflate(layoutInflater)
        dialogBinding.textInputLayout.hint = "Tên thư mục"
        dialogBinding.textInputLayout.setStartIconDrawable(R.drawable.ic_folder)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Tạo thư mục mới")
            .setView(dialogBinding.root)
            .setPositiveButton("Tạo", null) // Set null trước để tự xử lý validation không bị tắt dialog khi để trống
            .setNegativeButton("Hủy", null)
            .create()

        dialog.setOnShowListener {
            dialogBinding.editTextName.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(dialogBinding.editTextName, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)

            val positiveBtn = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            positiveBtn.setOnClickListener {
                val name = dialogBinding.editTextName.text.toString().trim()
                if (name.isEmpty()) {
                    dialogBinding.textInputLayout.error = "Vui lòng nhập tên thư mục"
                    return@setOnClickListener
                }
                val cur = viewModel.currentPath.value ?: return@setOnClickListener
                if (File(cur, name).mkdirs()) {
                    viewModel.refresh()
                    Toast.makeText(this, "Đã tạo \"$name\"", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                } else {
                    dialogBinding.textInputLayout.error = "Không thể tạo thư mục (trùng tên hoặc lỗi quyền)"
                }
            }
        }
        dialog.show()
    }

    private fun showRenameDialog(item: FileItem) {
        val dialogBinding = com.filemanager.databinding.DialogInputNameBinding.inflate(layoutInflater)
        dialogBinding.textInputLayout.hint = if (item.isDirectory) "Tên thư mục mới" else "Tên file mới"
        dialogBinding.textInputLayout.setStartIconDrawable(
            if (item.isDirectory) R.drawable.ic_folder else R.drawable.ic_file
        )
        dialogBinding.editTextName.setText(item.name)
        dialogBinding.editTextName.selectAll()

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Đổi tên")
            .setView(dialogBinding.root)
            .setPositiveButton("Đổi tên", null)
            .setNegativeButton("Hủy", null)
            .create()

        dialog.setOnShowListener {
            dialogBinding.editTextName.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(dialogBinding.editTextName, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)

            val positiveBtn = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            positiveBtn.setOnClickListener {
                val newName = dialogBinding.editTextName.text.toString().trim()
                if (newName.isEmpty()) {
                    dialogBinding.textInputLayout.error = "Tên không được để trống"
                    return@setOnClickListener
                }
                if (newName == item.name) {
                    dialog.dismiss()
                    return@setOnClickListener
                }
                // Kiểm tra tên đã tồn tại
                val destCheck = File(item.file.parent, newName)
                if (destCheck.exists()) {
                    dialogBinding.textInputLayout.error = "Tên này đã tồn tại"
                    return@setOnClickListener
                }

                val onSdCard = com.filemanager.data.repository.FileRepository(this)
                    .isSdCardFile(item.file)

                if (onSdCard) {
                    // Thẻ SD: cần SAF URI
                    val existingUri = sdSafUri
                    val hasValidUri = existingUri != null && contentResolver.persistedUriPermissions
                        .any { it.uri == existingUri && it.isWritePermission } &&
                        com.filemanager.utils.SdCardResolver.uriMatchesFile(existingUri, item.file, this)

                    if (hasValidUri && existingUri != null) {
                        // Có quyền rồi → đổi tên luôn
                        doRenameWithSaf(item, newName, existingUri, dialog)
                    } else {
                        // Chưa có quyền → xin SAF rồi đổi tên
                        pendingSafAction = { uri -> doRenameWithSaf(item, newName, uri, dialog) }
                        dialog.dismiss()
                        MaterialAlertDialogBuilder(this)
                            .setTitle("📂 Cấp quyền thẻ SD")
                            .setMessage(
                                "Để đổi tên file trên thẻ SD, Android yêu cầu cấp quyền:\n\n" +
                                "1. Nhấn OK để mở trình chọn thư mục\n" +
                                "2. Chọn thư mục GỐC của thẻ SD\n" +
                                "3. Nhấn 'Cho phép' / 'Use this folder'"
                            )
                            .setPositiveButton("OK, mở trình chọn") { _, _ ->
                                requestSdCardSafPermission()
                            }
                            .setNegativeButton("Hủy", null)
                            .show()
                    }
                } else {
                    // Bộ nhớ trong: renameTo thông thường
                    val dest = File(item.file.parent, newName)
                    if (item.file.renameTo(dest)) {
                        viewModel.refresh()
                        Toast.makeText(this, "Đã đổi tên thành \"$newName\"", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    } else {
                        dialogBinding.textInputLayout.error = "Không thể đổi tên"
                    }
                }
            }
        }
        dialog.show()
    }

    /** Thực hiện đổi tên qua SAF, chạy trong lifecycleScope để có coroutine */
    private fun doRenameWithSaf(
        item: FileItem,
        newName: String,
        safUri: Uri,
        dialog: androidx.appcompat.app.AlertDialog
    ) {
        val repo = com.filemanager.data.repository.FileRepository(this)
        LoadingHelper.showOverlay(this, "Đang đổi tên...", newName)
        lifecycleScope.launch {
            val result = repo.renameFile(item.file, newName, safUri)
            LoadingHelper.hideOverlay(this@MainActivity)
            if (result != null) {
                viewModel.refresh()
                Toast.makeText(this@MainActivity, "Đã đổi tên thành \"$newName\"", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            } else {
                Toast.makeText(this@MainActivity, "Không thể đổi tên trên thẻ SD", Toast.LENGTH_LONG).show()
            }
        }
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

    companion object {
        private const val PREF_SD_SAF_URI = "sd_saf_tree_uri"
    }
}
