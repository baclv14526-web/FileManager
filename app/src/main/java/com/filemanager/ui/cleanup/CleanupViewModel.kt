package com.filemanager.ui.cleanup

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.filemanager.data.model.FileItem
import com.filemanager.data.repository.FileRepository
import com.filemanager.utils.FileUtils
import com.filemanager.utils.StorageHelper
import com.filemanager.utils.StorageVolume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class CleanupViewModel(private val app: Application) : AndroidViewModel(app) {

    private val repository = FileRepository(app)

    private val _isScanning = MutableLiveData(false)
    val isScanning: LiveData<Boolean> = _isScanning

    private val _scanProgress = MutableLiveData("")
    val scanProgress: LiveData<String> = _scanProgress

    private val _listItems = MutableLiveData<List<CleanupListItem>>(emptyList())
    val listItems: LiveData<List<CleanupListItem>> = _listItems

    private val _storageVolumes = MutableLiveData<List<StorageVolume>>(emptyList())
    val storageVolumes: LiveData<List<StorageVolume>> = _storageVolumes

    private val _selectedCount = MutableLiveData(0)
    val selectedCount: LiveData<Int> = _selectedCount

    private val _selectedSize = MutableLiveData(0L)
    val selectedSize: LiveData<Long> = _selectedSize

    private val _toastMsg = MutableLiveData<String>()
    val toastMsg: LiveData<String> = _toastMsg

    // Raw data — giữ để rebuild khi toggle expand / selection
    private val rawItems = mutableMapOf<CleanupCategory, MutableList<CleanupItem>>()
    private val expandedState = mutableMapOf<CleanupCategory, Boolean>().apply {
        CleanupCategory.values().forEach { put(it, true) }
    }

    // Junk file patterns
    private val junkExtensions = setOf(
        "tmp", "temp", "log", "bak", "old", "orig",
        "dmp", "crdownload", "part", "partial"
    )
    private val junkFolderNames = setOf(
        ".thumbnails", "thumbnails", ".cache", "cache",
        "lost.dir", ".lost+found", "tmp", "temp",
        "com.miui.gallery", "albumthumbs"
    )
    private val junkFilePatterns = listOf(
        Regex("^\\._.*"),          // macOS metadata
        Regex("^Thumbs\\.db$", RegexOption.IGNORE_CASE),
        Regex("^\\.DS_Store$"),
        Regex(".*\\.log\\.\\d+$"), // rotated logs
        Regex("^nomedia$", RegexOption.IGNORE_CASE)
    )

    fun loadVolumes() {
        viewModelScope.launch(Dispatchers.IO) {
            val vols = StorageHelper.getStorageVolumes(app)
            _storageVolumes.postValue(vols)
        }
    }

    fun startScan(scanRoots: List<String>) {
        viewModelScope.launch {
            _isScanning.value = true
            _scanProgress.value = "Đang quét..."
            rawItems.clear()
            CleanupCategory.values().forEach { rawItems[it] = mutableListOf() }

            withContext(Dispatchers.IO) {
                scanRoots.forEach { root ->
                    val dir = File(root)
                    if (dir.exists() && dir.canRead()) {
                        scanDirectory(dir, 0)
                    }
                }
            }

            _isScanning.value = false
            _scanProgress.value = buildSummary()
            rebuildList()
        }
    }

    private fun scanDirectory(dir: File, depth: Int) {
        if (depth > 12) return
        try {
            val children = dir.listFiles() ?: return

            // Check thư mục rỗng (không phải root)
            if (depth > 0 && children.isEmpty()) {
                rawItems[CleanupCategory.EMPTY_FOLDERS]?.add(
                    CleanupItem(FileItem(dir), CleanupCategory.EMPTY_FOLDERS)
                )
                return
            }

            children.forEach { file ->
                when {
                    file.isFile -> {
                        val item = FileItem(file)
                        when {
                            // File lớn ≥ 100MB
                            file.length() >= 100 * 1024 * 1024L ->
                                rawItems[CleanupCategory.LARGE_FILES]?.add(
                                    CleanupItem(item, CleanupCategory.LARGE_FILES)
                                )
                            // File rỗng (0 byte)
                            file.length() == 0L ->
                                rawItems[CleanupCategory.EMPTY_FILES]?.add(
                                    CleanupItem(item, CleanupCategory.EMPTY_FILES)
                                )
                            // File rác
                            isJunkFile(file) ->
                                rawItems[CleanupCategory.JUNK_FILES]?.add(
                                    CleanupItem(item, CleanupCategory.JUNK_FILES)
                                )
                        }
                    }
                    file.isDirectory && !file.name.startsWith(".lost") -> {
                        // Thư mục rác đã biết
                        if (isJunkFolder(file)) {
                            rawItems[CleanupCategory.JUNK_FILES]?.add(
                                CleanupItem(FileItem(file), CleanupCategory.JUNK_FILES)
                            )
                        } else {
                            scanDirectory(file, depth + 1)
                        }
                    }
                }
            }
        } catch (e: Exception) { /* bỏ qua thư mục không đọc được */ }
    }

    private fun isJunkFile(file: File): Boolean {
        val ext  = file.extension.lowercase()
        val name = file.name
        if (ext in junkExtensions) return true
        return junkFilePatterns.any { it.matches(name) }
    }

    private fun isJunkFolder(dir: File): Boolean =
        dir.name.lowercase() in junkFolderNames

    private fun buildSummary(): String {
        val total = rawItems.values.sumOf { it.size }
        val size  = rawItems.values.flatten().sumOf { it.fileItem.size }
        return "Tìm thấy $total mục · ${FileUtils.formatSize(size)}"
    }

    // ── Selection ───────────────────────────────────────────────

    fun toggleItem(item: CleanupItem) {
        val list = rawItems[item.category] ?: return
        val idx  = list.indexOfFirst { it.fileItem.path == item.fileItem.path }
        if (idx < 0) return
        list[idx] = list[idx].copy(isSelected = !list[idx].isSelected)
        updateSelectionStats()
        rebuildList()
    }

    fun selectCategory(category: CleanupCategory, select: Boolean) {
        rawItems[category]?.forEach { it.isSelected = select }
        updateSelectionStats()
        rebuildList()
    }

    fun selectAll(select: Boolean) {
        rawItems.values.forEach { list -> list.forEach { it.isSelected = select } }
        updateSelectionStats()
        rebuildList()
    }

    fun toggleExpand(category: CleanupCategory) {
        expandedState[category] = !(expandedState[category] ?: true)
        rebuildList()
    }

    private fun updateSelectionStats() {
        val selected = rawItems.values.flatten().filter { it.isSelected }
        _selectedCount.value = selected.size
        _selectedSize.value  = selected.sumOf { it.fileItem.size }
    }

    fun deleteSelected() {
        val toDelete = rawItems.values.flatten().filter { it.isSelected }.map { it.fileItem }
        if (toDelete.isEmpty()) return

        viewModelScope.launch {
            _isScanning.value = true
            try {
                val ok = repository.moveToTrash(toDelete)
                if (ok) {
                    _toastMsg.value = "Đã chuyển ${toDelete.size} mục vào thùng rác"
                    // Xoá khỏi rawItems
                    val deletedPaths = toDelete.map { it.path }.toSet()
                    rawItems.forEach { (_, list) ->
                        list.removeAll { it.fileItem.path in deletedPaths }
                    }
                    _selectedCount.value = 0
                    _selectedSize.value  = 0L
                    _scanProgress.value  = buildSummary()
                    rebuildList()
                } else {
                    _toastMsg.value = "Không thể xóa một số mục"
                }
            } finally {
                _isScanning.value = false
            }
        }
    }

    private fun rebuildList() {
        val list = mutableListOf<CleanupListItem>()
        CleanupCategory.values().forEach { cat ->
            val items    = rawItems[cat] ?: return@forEach
            if (items.isEmpty()) return@forEach
            val expanded = expandedState[cat] ?: true
            val size     = items.sumOf { it.fileItem.size }
            list.add(CleanupListItem.Header(cat, items.size, size, expanded))
            if (expanded) items.forEach { list.add(CleanupListItem.Entry(it)) }
        }
        _listItems.value = list
    }
}
