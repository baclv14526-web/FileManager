package com.filemanager.ui.main

import android.app.Application
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.filemanager.data.model.FileItem
import com.filemanager.data.model.FileType
import com.filemanager.data.model.SortType
import com.filemanager.data.repository.FileRepository
import com.filemanager.utils.StorageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class SearchScope { CURRENT, INTERNAL, SD_CARD, ALL }

enum class SearchFileType {
    ALL, IMAGE, VIDEO, AUDIO, DOCUMENT, ARCHIVE, APK, FOLDER;
    fun matches(item: FileItem) = when (this) {
        ALL      -> true
        IMAGE    -> item.fileType == FileType.IMAGE
        VIDEO    -> item.fileType == FileType.VIDEO
        AUDIO    -> item.fileType == FileType.AUDIO
        DOCUMENT -> item.fileType == FileType.DOCUMENT
        ARCHIVE  -> item.fileType == FileType.ARCHIVE
        APK      -> item.fileType == FileType.APK
        FOLDER   -> item.isDirectory
    }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = FileRepository(app)

    private val _files           = MutableLiveData<List<FileItem>>(emptyList())
    val files: LiveData<List<FileItem>> = _files

    private val _currentPath     = MutableLiveData<String>("")
    val currentPath: LiveData<String> = _currentPath

    private val _searchResults   = MutableLiveData<List<FileItem>?>()
    val searchResults: LiveData<List<FileItem>?> = _searchResults

    private val _searchDisplayItems = MutableLiveData<List<FileDisplayItem>?>()
    val searchDisplayItems: LiveData<List<FileDisplayItem>?> = _searchDisplayItems

    private val searchExpandedState = mutableMapOf<FileType, Boolean>()
    private var rawSearchResults = listOf<FileItem>()

    private val _selectedFiles   = MutableLiveData<Set<String>>(emptySet())
    val selectedFiles: LiveData<Set<String>> = _selectedFiles

    private val _isSelectionMode = MutableLiveData(false)
    val isSelectionMode: LiveData<Boolean> = _isSelectionMode

    private val _sortType        = MutableLiveData(SortType.NAME_ASC)
    val sortType: LiveData<SortType> = _sortType

    private val _isLoading       = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _toastMessage    = MutableLiveData<String>()
    val toastMessage: LiveData<String> = _toastMessage

    private val _isGridView      = MutableLiveData(false)
    val isGridView: LiveData<Boolean> = _isGridView

    private val _hasSDCard       = MutableLiveData(false)
    val hasSDCard: LiveData<Boolean> = _hasSDCard

    private val _searchScope     = MutableLiveData(SearchScope.CURRENT)
    val searchScope: LiveData<SearchScope> = _searchScope

    private val _searchFileType  = MutableLiveData(SearchFileType.ALL)
    val searchFileType: LiveData<SearchFileType> = _searchFileType

    val pathHistory = ArrayDeque<String>()
    private var searchJob: Job? = null
    private var lastQuery = ""

    // ── Init ────────────────────────────────────────────────────

    fun init() {
        viewModelScope.launch(Dispatchers.IO) {
            val vols = StorageHelper.getStorageVolumes(getApplication())
            _hasSDCard.postValue(vols.any { it.isRemovable })
        }
    }

    // ── Navigation ──────────────────────────────────────────────

    fun navigateTo(path: String) {
        try {
            val current = _currentPath.value
            if (!current.isNullOrEmpty() && current != path) pathHistory.addLast(current)
            _currentPath.value = path
            exitSelectionMode()
            loadFiles(path)
        } catch (e: Exception) {
            _toastMessage.value = "Không thể mở: ${e.message}"
        }
    }

    fun navigateUp(): Boolean {
        if (pathHistory.isEmpty()) return false
        val prev = pathHistory.removeLast()
        _currentPath.value = prev
        exitSelectionMode()
        loadFiles(prev)
        return true
    }

    private fun loadFiles(path: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // ✅ Toàn bộ IO + sort trên background thread
                val result = withContext(Dispatchers.IO) {
                    repository.getFiles(path, _sortType.value ?: SortType.NAME_ASC)
                }
                _files.value = result
            } catch (e: Exception) {
                _files.value = emptyList()
                _toastMessage.value = "Lỗi đọc thư mục: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    // ── Search ──────────────────────────────────────────────────

    fun search(query: String) {
        lastQuery = query
        if (query.isBlank()) {
            _searchResults.value = null
            _searchDisplayItems.value = null
            return
        }

        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            // ✅ Debounce 250ms — tránh search mỗi ký tự gõ
            delay(250)
            _isLoading.value = true
            try {
                val roots    = getSearchRoots(_searchScope.value ?: SearchScope.CURRENT)
                val fileType = _searchFileType.value ?: SearchFileType.ALL

                val results = withContext(Dispatchers.IO) {
                    val raw = mutableListOf<FileItem>()
                    for (root in roots) {
                        if (raw.size >= 2000) break   // đủ kết quả — dừng sớm
                        raw += repository.searchFiles(query, root)
                    }
                    raw.distinctBy { it.path }
                        .filter { fileType.matches(it) }
                        .sortedWith(
                            compareBy<FileItem> { it.fileType.ordinal }
                                .thenByDescending { it.lastModified }
                                .thenBy { it.name.lowercase() }
                        )
                }
                rawSearchResults = results
                _searchResults.value = results
                rebuildSearchDisplayItems()
            } catch (e: Exception) {
                rawSearchResults = emptyList()
                _searchResults.value = emptyList()
                _searchDisplayItems.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun toggleSearchGroup(type: FileType) {
        searchExpandedState[type] = !(searchExpandedState[type] ?: true)
        rebuildSearchDisplayItems()
    }

    private fun rebuildSearchDisplayItems() {
        if (_searchResults.value == null) {
            _searchDisplayItems.value = null
            return
        }

        val displayList = mutableListOf<FileDisplayItem>()
        // Nhóm các file theo FileType
        val grouped = rawSearchResults.groupBy { it.fileType }

        grouped.forEach { (type, items) ->
            val title = when (type) {
                FileType.FOLDER   -> "📁 Thư mục"
                FileType.IMAGE    -> "🖼️ Hình ảnh"
                FileType.VIDEO    -> "🎬 Video"
                FileType.AUDIO    -> "🎵 Âm thanh"
                FileType.DOCUMENT -> "📄 Tài liệu"
                FileType.ARCHIVE  -> "📦 File nén"
                FileType.CODE     -> "💻 Mã nguồn"
                FileType.APK      -> "🤖 Ứng dụng (APK)"
                FileType.OTHER    -> "📎 Khác"
            }
            val isExpanded = searchExpandedState[type] ?: true
            displayList.add(FileDisplayItem.Header(type, title, items.size, isExpanded))
            if (isExpanded) {
                items.forEach { displayList.add(FileDisplayItem.Item(it)) }
            }
        }

        _searchDisplayItems.value = displayList
    }

    fun setSearchScope(scope: SearchScope) {
        _searchScope.value = scope
        if (lastQuery.isNotBlank()) search(lastQuery)
    }

    fun setSearchFileType(type: SearchFileType) {
        _searchFileType.value = type
        if (lastQuery.isNotBlank()) search(lastQuery)
    }

    fun clearSearch() {
        lastQuery = ""
        searchJob?.cancel()
        rawSearchResults = emptyList()
        _searchResults.value  = null
        _searchDisplayItems.value = null
        _searchFileType.value = SearchFileType.ALL
    }

    private fun getSearchRoots(scope: SearchScope): List<String> {
        val volumes = try { StorageHelper.getStorageVolumes(getApplication()) }
        catch (e: Exception) { emptyList() }
        return when (scope) {
            SearchScope.CURRENT  -> listOf(
                _currentPath.value?.ifEmpty { getStorageRoot() } ?: getStorageRoot()
            )
            SearchScope.INTERNAL -> volumes.filter { !it.isRemovable }.map { it.path }
                .ifEmpty { listOf(getStorageRoot()) }
            SearchScope.SD_CARD  -> volumes.filter { it.isRemovable }.map { it.path }
            SearchScope.ALL      -> volumes.map { it.path }.ifEmpty { listOf(getStorageRoot()) }
        }
    }

    // ── Sort / View ─────────────────────────────────────────────

    fun setSortType(sort: SortType) {
        _sortType.value = sort
        val path = _currentPath.value?.ifEmpty { null } ?: return
        loadFiles(path)
    }

    fun toggleGridView() { _isGridView.value = !(_isGridView.value ?: false) }

    // ── Selection ───────────────────────────────────────────────

    fun enterSelectionMode(path: String) {
        _isSelectionMode.value = true
        _selectedFiles.value = setOf(path)
        applySelection()
    }

    fun toggleSelection(item: FileItem) {
        val cur = _selectedFiles.value?.toMutableSet() ?: mutableSetOf()
        if (item.path in cur) cur.remove(item.path) else cur.add(item.path)
        _selectedFiles.value = cur
        if (cur.isEmpty()) exitSelectionMode() else applySelection()
    }

    fun selectAll() {
        val inSearch = _searchResults.value != null
        val sourceList = if (inSearch) (_searchResults.value ?: emptyList()) else (_files.value ?: emptyList())
        _selectedFiles.value = sourceList.map { it.path }.toSet()
        applySelection()
    }

    fun exitSelectionMode() {
        _isSelectionMode.value = false
        _selectedFiles.value   = emptySet()
        applySelection()
    }

    private fun applySelection() {
        val selected = _selectedFiles.value ?: emptySet()
        val inSearch = _searchResults.value != null

        if (inSearch) {
            val updated = rawSearchResults.map { it.copy(isSelected = it.path in selected) }
            rawSearchResults = updated
            _searchResults.value = updated
            rebuildSearchDisplayItems()
        } else {
            val current = _files.value ?: return
            if (selected.isEmpty() && current.none { it.isSelected }) return
            viewModelScope.launch(Dispatchers.Default) {
                val updated = current.map { it.copy(isSelected = it.path in selected) }
                _files.postValue(updated)
            }
        }
    }

    fun getSelectedItems(): List<FileItem> {
        val selected = _selectedFiles.value ?: return emptyList()
        val inSearch = _searchResults.value != null
        val sourceList = if (inSearch) (_searchResults.value ?: emptyList()) else (_files.value ?: emptyList())
        return sourceList.filter { it.path in selected }
    }

    // ── Trash ───────────────────────────────────────────────────

    fun moveSelectedToTrash() {
        val items = getSelectedItems()
        if (items.isEmpty()) return
        viewModelScope.launch {
            try {
                val ok = withContext(Dispatchers.IO) { repository.moveToTrash(items) }
                if (ok) {
                    _toastMessage.value = "Đã chuyển ${items.size} mục vào thùng rác"
                    exitSelectionMode()
                    refresh()
                } else {
                    _toastMessage.value = "Không thể xóa một số file"
                }
            } catch (e: Exception) {
                _toastMessage.value = "Lỗi: ${e.message}"
            }
        }
    }

    // ── Xóa vĩnh viễn (không vào thùng rác) ────────────────────

    /** true nếu bất kỳ item nào đang được chọn nằm trên thẻ SD */
    fun hasAnySelectedOnSdCard(): Boolean {
        val items = getSelectedItems()
        return items.any { repository.isSdCardFile(File(it.path)) }
    }

    /** Xóa vĩnh viễn các item đang được chọn, không qua thùng rác.
     *  sdTreeUri: URI quyền SAF từ ACTION_OPEN_DOCUMENT_TREE (null nếu tất cả trên bộ nhớ trong) */
    fun deleteSelectedDirectly(sdTreeUri: Uri?) {
        val items = getSelectedItems()
        if (items.isEmpty()) return
        viewModelScope.launch {
            try {
                val ok = withContext(Dispatchers.IO) {
                    repository.deleteFilesDirectly(items, sdTreeUri)
                }
                if (ok) {
                    _toastMessage.value = "Đã xóa ${items.size} mục vĩnh viễn"
                    exitSelectionMode()
                    refresh()
                } else {
                    _toastMessage.value = "Không thể xóa một số file"
                }
            } catch (e: Exception) {
                _toastMessage.value = "Lỗi: ${e.message}"
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────

    fun refresh() {
        if (lastQuery.isNotBlank()) {
            search(lastQuery)
        }
        val path = _currentPath.value?.ifEmpty { null } ?: return
        loadFiles(path)
    }

    fun getStorageRoot(): String = try {
        Environment.getExternalStorageDirectory()?.absolutePath ?: "/sdcard"
    } catch (e: Exception) { "/sdcard" }

    fun getQuickAccessPaths() = repository.getQuickAccessPaths()
}
