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

    private val _isScanning   = MutableLiveData(false)
    val isScanning: LiveData<Boolean> = _isScanning

    private val _scanProgress = MutableLiveData("")
    val scanProgress: LiveData<String> = _scanProgress

    private val _listItems    = MutableLiveData<List<CleanupListItem>>(emptyList())
    val listItems: LiveData<List<CleanupListItem>> = _listItems

    private val _storageVolumes = MutableLiveData<List<StorageVolume>>(emptyList())
    val storageVolumes: LiveData<List<StorageVolume>> = _storageVolumes

    private val _selectedCount = MutableLiveData(0)
    val selectedCount: LiveData<Int> = _selectedCount

    private val _selectedSize  = MutableLiveData(0L)
    val selectedSize: LiveData<Long> = _selectedSize

    private val _toastMsg      = MutableLiveData<String>()
    val toastMsg: LiveData<String> = _toastMsg

    // Raw data theo category
    private val rawItems = mutableMapOf<CleanupCategory, MutableList<CleanupItem>>()

    // ✅ FIX 1: Mặc định TẤT CẢ thu gọn (false) sau scan
    private val expandedState = mutableMapOf<CleanupCategory, Boolean>().apply {
        CleanupCategory.values().forEach { put(it, false) }
    }

    // Junk patterns
    private val junkExtensions = setOf(
        "tmp","temp","log","bak","old","orig","dmp","crdownload","part","partial"
    )
    private val junkFolderNames = setOf(
        ".thumbnails","thumbnails",".cache","cache",
        "lost.dir",".lost+found","tmp","temp","albumthumbs"
    )
    private val junkFilePatterns = listOf(
        Regex("^\\._.*"),
        Regex("^Thumbs\\.db$", RegexOption.IGNORE_CASE),
        Regex("^\\.DS_Store$"),
        Regex(".*\\.log\\.\\d+$"),
        Regex("^nomedia$", RegexOption.IGNORE_CASE)
    )

    // ── Volumes ─────────────────────────────────────────────────

    fun loadVolumes() {
        viewModelScope.launch(Dispatchers.IO) {
            val vols = StorageHelper.getStorageVolumes(app)
            _storageVolumes.postValue(vols)
        }
    }

    // ── Scan ────────────────────────────────────────────────────

    fun startScan(scanRoots: List<String>) {
        viewModelScope.launch {
            _isScanning.value = true
            _scanProgress.value = "Đang quét..."

            // Reset state
            rawItems.clear()
            CleanupCategory.values().forEach { rawItems[it] = mutableListOf() }
            // ✅ Reset expand về collapsed sau mỗi lần scan mới
            CleanupCategory.values().forEach { expandedState[it] = false }
            _selectedCount.value = 0
            _selectedSize.value = 0L

            withContext(Dispatchers.IO) {
                scanRoots.forEach { root ->
                    val dir = File(root)
                    if (dir.exists() && dir.canRead()) {
                        // ✅ FIX 2: Update progress trên Main thread qua postValue
                        _scanProgress.postValue("Đang quét: ${dir.name}")
                        scanDirectory(dir, 0)
                    }
                }
            }

            _isScanning.value = false
            _scanProgress.value = buildSummary()
            // ✅ FIX 3: rebuildList gọi sau khi scan xong trên Main thread
            rebuildList()
        }
    }

    private fun scanDirectory(dir: File, depth: Int) {
        if (depth > 12) return
        try {
            val children = dir.listFiles() ?: return

            if (depth > 0 && children.isEmpty()) {
                rawItems[CleanupCategory.EMPTY_FOLDERS]?.add(
                    CleanupItem(FileItem(dir), CleanupCategory.EMPTY_FOLDERS)
                )
                return
            }

            children.forEach { file ->
                when {
                    file.isFile -> when {
                        file.length() >= 100 * 1024 * 1024L ->
                            rawItems[CleanupCategory.LARGE_FILES]?.add(
                                CleanupItem(FileItem(file), CleanupCategory.LARGE_FILES)
                            )
                        file.length() == 0L ->
                            rawItems[CleanupCategory.EMPTY_FILES]?.add(
                                CleanupItem(FileItem(file), CleanupCategory.EMPTY_FILES)
                            )
                        isJunkFile(file) ->
                            rawItems[CleanupCategory.JUNK_FILES]?.add(
                                CleanupItem(FileItem(file), CleanupCategory.JUNK_FILES)
                            )
                    }
                    file.isDirectory && !file.name.startsWith(".lost") -> {
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
        } catch (e: Exception) { /* bỏ qua lỗi permission */ }
    }

    private fun isJunkFile(file: File): Boolean {
        val ext = file.extension.lowercase()
        return ext in junkExtensions || junkFilePatterns.any { it.matches(file.name) }
    }
    private fun isJunkFolder(dir: File) = dir.name.lowercase() in junkFolderNames

    private fun buildSummary(): String {
        val total = rawItems.values.sumOf { it.size }
        val size  = rawItems.values.flatten().sumOf { it.fileItem.size }
        return if (total == 0) "Không tìm thấy gì"
        else "Tìm thấy $total mục · ${FileUtils.formatSize(size)}"
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
        expandedState[category] = !(expandedState[category] ?: false)
        rebuildList()
    }

    private fun updateSelectionStats() {
        val selected = rawItems.values.flatten().filter { it.isSelected }
        _selectedCount.value = selected.size
        _selectedSize.value  = selected.sumOf { it.fileItem.size }
    }

    // ── Delete ──────────────────────────────────────────────────

    fun deleteSelected() {
        val toDelete = rawItems.values.flatten().filter { it.isSelected }.map { it.fileItem }
        if (toDelete.isEmpty()) return

        viewModelScope.launch {
            _isScanning.value = true
            try {
                val ok = repository.moveToTrash(toDelete)
                if (ok) {
                    val deletedPaths = toDelete.map { it.path }.toSet()
                    rawItems.forEach { (_, list) ->
                        list.removeAll { it.fileItem.path in deletedPaths }
                    }
                    _selectedCount.value = 0
                    _selectedSize.value  = 0L
                    _scanProgress.value  = buildSummary()
                    _toastMsg.value = "Đã chuyển ${toDelete.size} mục vào thùng rác"
                    rebuildList()
                } else {
                    _toastMsg.value = "Không thể xóa một số mục"
                }
            } catch (e: Exception) {
                _toastMsg.value = "Lỗi: ${e.message}"
            } finally {
                _isScanning.value = false
            }
        }
    }

    // ── Rebuild ─────────────────────────────────────────────────

    /**
     * Chỉ emit header khi collapsed → KHÔNG emit hàng nghìn Entry.
     * Chỉ emit Entry của category đang expanded.
     * ✅ FIX: Gọi trực tiếp = đang trên Main thread (viewModelScope).
     */
    private fun rebuildList() {
        val list = mutableListOf<CleanupListItem>()
        CleanupCategory.values().forEach { cat ->
            val items    = rawItems[cat] ?: return@forEach
            if (items.isEmpty()) return@forEach
            val expanded = expandedState[cat] ?: false
            val size     = items.sumOf { it.fileItem.size }
            // Header luôn hiện
            list.add(CleanupListItem.Header(cat, items.size, size, expanded))
            // Entry chỉ hiện khi expanded
            if (expanded) {
                // ✅ FIX 4: Giới hạn 200 item hiển thị để tránh đơ
                items.take(200).forEach { list.add(CleanupListItem.Entry(it)) }
                if (items.size > 200) {
                    // Thêm placeholder "... và N mục khác"
                    list.add(CleanupListItem.Header(
                        category   = cat,
                        count      = -(items.size - 200), // âm = signal "more"
                        totalSize  = 0L,
                        isExpanded = false
                    ))
                }
            }
        }
        _listItems.value = list
    }
}
