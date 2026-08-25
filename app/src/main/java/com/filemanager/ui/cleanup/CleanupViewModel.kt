package com.filemanager.ui.cleanup

import android.app.Application
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

    // UI state — tất cả emit trên Main thread qua postValue
    private val _rows          = MutableLiveData<List<CleanupRow>>(emptyList())
    val rows: LiveData<List<CleanupRow>> = _rows

    private val _isScanning    = MutableLiveData(false)
    val isScanning: LiveData<Boolean> = _isScanning

    private val _selectedCount = MutableLiveData(0)
    val selectedCount: LiveData<Int> = _selectedCount

    private val _selectedSize  = MutableLiveData(0L)
    val selectedSize: LiveData<Long> = _selectedSize

    private val _toast         = MutableLiveData<String>()
    val toast: LiveData<String> = _toast

    // Internal state
    private var volumes: List<StorageVolume> = emptyList()
    private var currentScope = 0
    private var summaryText  = ""

    // Raw scan results — mỗi category tối đa MAX_VISIBLE items hiển thị
    // loadMore sẽ tăng limit
    private val rawItems   = mutableMapOf<CleanupCategory, MutableList<CleanupItem>>()
    private val expanded   = mutableMapOf<CleanupCategory, Boolean>()
    private val visibleMax = mutableMapOf<CleanupCategory, Int>()

    // Junk patterns
    private val junkExts = setOf("tmp","temp","log","bak","old","orig","dmp","crdownload","part","partial")
    private val junkDirs = setOf(".thumbnails","thumbnails",".cache","cache","lost.dir","tmp","temp","albumthumbs")
    private val junkPats = listOf(
        Regex("^\\._.*"), Regex("^Thumbs\\.db$", RegexOption.IGNORE_CASE),
        Regex("^\\.DS_Store$"), Regex(".*\\.log\\.\\d+\$"), Regex("^nomedia\$", RegexOption.IGNORE_CASE)
    )

    companion object { private const val PAGE = 50 } // items mỗi lần hiện

    // ── Init ────────────────────────────────────────────────────

    fun loadVolumes() {
        viewModelScope.launch(Dispatchers.IO) {
            volumes = StorageHelper.getStorageVolumes(app)
            rebuildRows()
        }
    }

    // ── Scan ────────────────────────────────────────────────────

    fun startScan(scope: Int) {
        currentScope = scope
        viewModelScope.launch {
            _isScanning.postValue(true)

            // Reset
            rawItems.clear()
            expanded.clear()
            visibleMax.clear()
            CleanupCategory.values().forEach {
                rawItems[it] = mutableListOf()
                expanded[it] = false
                visibleMax[it] = PAGE
            }
            _selectedCount.postValue(0)
            _selectedSize.postValue(0L)

            val roots = when (scope) {
                1    -> volumes.filter { !it.isRemovable }.map { it.path }
                2    -> volumes.filter { it.isRemovable  }.map { it.path }
                else -> volumes.map { it.path }
            }.ifEmpty { listOf(android.os.Environment.getExternalStorageDirectory()?.absolutePath ?: "/sdcard") }

            // Scan trên IO thread
            withContext(Dispatchers.IO) {
                roots.forEach { root ->
                    val dir = File(root)
                    if (dir.exists() && dir.canRead()) scanDir(dir, 0)
                }
            }

            val total = rawItems.values.sumOf { it.size }
            val size  = rawItems.values.flatten().sumOf { it.fileItem.size }
            summaryText = if (total == 0) "Không tìm thấy gì"
            else "Tìm thấy $total mục · ${FileUtils.formatSize(size)}"

            _isScanning.postValue(false)
            rebuildRows()
        }
    }

    // ── Selection ───────────────────────────────────────────────

    fun toggleItem(item: CleanupItem) {
        val list = rawItems[item.category] ?: return
        val idx  = list.indexOfFirst { it.fileItem.path == item.fileItem.path }
        if (idx < 0) return
        list[idx] = list[idx].copy(isSelected = !list[idx].isSelected)
        updateStats()
        rebuildRows()
    }

    fun selectCategory(cat: CleanupCategory, sel: Boolean) {
        rawItems[cat]?.forEach { it.isSelected = sel }
        updateStats(); rebuildRows()
    }

    fun selectAll() {
        rawItems.values.forEach { list -> list.forEach { it.isSelected = true } }
        updateStats(); rebuildRows()
    }

    fun deselectAll() {
        rawItems.values.forEach { list -> list.forEach { it.isSelected = false } }
        updateStats(); rebuildRows()
    }

    fun toggleExpand(cat: CleanupCategory) {
        expanded[cat] = !(expanded[cat] ?: false)
        rebuildRows()
    }

    fun loadMore(cat: CleanupCategory) {
        visibleMax[cat] = (visibleMax[cat] ?: PAGE) + PAGE
        rebuildRows()
    }

    private fun updateStats() {
        val sel = rawItems.values.flatten().filter { it.isSelected }
        _selectedCount.value = sel.size
        _selectedSize.value  = sel.sumOf { it.fileItem.size }
    }

    // ── Delete ──────────────────────────────────────────────────

    fun deleteSelected() {
        val toDelete = rawItems.values.flatten().filter { it.isSelected }.map { it.fileItem }
        if (toDelete.isEmpty()) return

        viewModelScope.launch {
            _isScanning.postValue(true)
            try {
                val ok = withContext(Dispatchers.IO) { repository.moveToTrash(toDelete) }
                if (ok) {
                    val paths = toDelete.map { it.path }.toSet()
                    rawItems.forEach { (_, list) -> list.removeAll { it.fileItem.path in paths } }
                    val total = rawItems.values.sumOf { it.size }
                    val size  = rawItems.values.flatten().sumOf { it.fileItem.size }
                    summaryText = if (total == 0) "Không tìm thấy gì"
                    else "Còn $total mục · ${FileUtils.formatSize(size)}"
                    _selectedCount.postValue(0)
                    _selectedSize.postValue(0L)
                    _toast.postValue("Đã chuyển ${toDelete.size} mục vào thùng rác")
                } else {
                    _toast.postValue("Không thể xóa một số mục")
                }
            } catch (e: Exception) {
                _toast.postValue("Lỗi: ${e.message}")
            } finally {
                _isScanning.postValue(false)
                rebuildRows()
            }
        }
    }

    // ── Scan logic ──────────────────────────────────────────────

    private fun scanDir(dir: File, depth: Int) {
        if (depth > 12) return
        try {
            val children = dir.listFiles() ?: return
            if (depth > 0 && children.isEmpty()) {
                rawItems[CleanupCategory.EMPTY_FOLDERS]!!.add(
                    CleanupItem(FileItem(dir), CleanupCategory.EMPTY_FOLDERS)
                )
                return
            }
            children.forEach { file ->
                when {
                    file.isFile -> when {
                        file.length() >= 100L * 1024 * 1024 ->
                            rawItems[CleanupCategory.LARGE_FILES]!!.add(CleanupItem(FileItem(file), CleanupCategory.LARGE_FILES))
                        file.length() == 0L ->
                            rawItems[CleanupCategory.EMPTY_FILES]!!.add(CleanupItem(FileItem(file), CleanupCategory.EMPTY_FILES))
                        isJunkFile(file) ->
                            rawItems[CleanupCategory.JUNK_FILES]!!.add(CleanupItem(FileItem(file), CleanupCategory.JUNK_FILES))
                    }
                    file.isDirectory && !file.name.startsWith(".lost") ->
                        if (isJunkDir(file)) rawItems[CleanupCategory.JUNK_FILES]!!.add(
                            CleanupItem(FileItem(file), CleanupCategory.JUNK_FILES)
                        ) else scanDir(file, depth + 1)
                }
            }
        } catch (_: Exception) {}
    }

    private fun isJunkFile(f: File) = f.extension.lowercase() in junkExts || junkPats.any { it.matches(f.name) }
    private fun isJunkDir(d: File)  = d.name.lowercase() in junkDirs

    // ── Rebuild rows — chạy trên Main thread ────────────────────

    private fun rebuildRows() {
        viewModelScope.launch(Dispatchers.Main) {
            val rows = mutableListOf<CleanupRow>()

            // 1. Storage card (luôn có)
            rows.add(CleanupRow.StorageCard(volumes, volumes.any { it.isRemovable },
                _isScanning.value ?: false, currentScope))

            val hasResults = rawItems.any { (_, v) -> v.isNotEmpty() }
            val scanned    = summaryText.isNotEmpty()

            if (!scanned) {
                // Chưa scan → chỉ hiện storage card
                _rows.value = rows
                return@launch
            }

            // 2. Summary / Empty
            if (!hasResults) {
                rows.add(CleanupRow.Empty)
            } else {
                rows.add(CleanupRow.Summary(summaryText))

                // 3. Category headers + entries (chỉ khi expanded)
                CleanupCategory.values().forEach { cat ->
                    val items = rawItems[cat] ?: return@forEach
                    if (items.isEmpty()) return@forEach

                    val isExp  = expanded[cat] ?: false
                    val size   = items.sumOf { it.fileItem.size }
                    rows.add(CleanupRow.CategoryHeader(cat, items.size, size, isExp))

                    if (isExp) {
                        val max = visibleMax[cat] ?: PAGE
                        // Chỉ lấy `max` item → tránh render cả nghìn item cùng lúc
                        items.take(max).forEach { rows.add(CleanupRow.FileEntry(it)) }
                        if (items.size > max) {
                            rows.add(CleanupRow.MoreItems(cat, items.size - max))
                        }
                    }
                }
            }

            _rows.value = rows
        }
    }

    fun getVolumes() = volumes
    fun getScopeIndex() = currentScope
}
