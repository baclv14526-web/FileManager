package com.filemanager.ui.main

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.filemanager.data.model.FileItem
import com.filemanager.data.model.SortType
import com.filemanager.data.repository.FileRepository
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = FileRepository(app)

    private val _files = MutableLiveData<List<FileItem>>(emptyList())
    val files: LiveData<List<FileItem>> = _files

    private val _currentPath = MutableLiveData<String>("")
    val currentPath: LiveData<String> = _currentPath

    private val _searchResults = MutableLiveData<List<FileItem>?>()
    val searchResults: LiveData<List<FileItem>?> = _searchResults

    private val _selectedFiles = MutableLiveData<Set<String>>(emptySet())
    val selectedFiles: LiveData<Set<String>> = _selectedFiles

    private val _isSelectionMode = MutableLiveData(false)
    val isSelectionMode: LiveData<Boolean> = _isSelectionMode

    private val _sortType = MutableLiveData(SortType.NAME_ASC)
    val sortType: LiveData<SortType> = _sortType

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _toastMessage = MutableLiveData<String>()
    val toastMessage: LiveData<String> = _toastMessage

    private val _isGridView = MutableLiveData(false)
    val isGridView: LiveData<Boolean> = _isGridView

    val pathHistory = ArrayDeque<String>()

    // FIX: KHÔNG gọi gì trong init — chờ MainActivity xin permission xong mới navigate
    // init { } để trống hoàn toàn

    fun navigateTo(path: String) {
        try {
            val current = _currentPath.value
            if (!current.isNullOrEmpty() && current != path) {
                pathHistory.addLast(current)
            }
            _currentPath.value = path
            loadFiles(path)
            exitSelectionMode()
        } catch (e: Exception) {
            _toastMessage.value = "Không thể mở: ${e.message}"
        }
    }

    fun navigateUp(): Boolean {
        if (pathHistory.isEmpty()) return false
        val prev = pathHistory.removeLast()
        _currentPath.value = prev
        loadFiles(prev)
        exitSelectionMode()
        return true
    }

    private fun loadFiles(path: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                _files.value = repository.getFiles(path, _sortType.value ?: SortType.NAME_ASC)
            } catch (e: Exception) {
                _files.value = emptyList()
                _toastMessage.value = "Lỗi đọc thư mục: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun search(query: String) {
        if (query.isBlank()) { _searchResults.value = null; return }
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val root = _currentPath.value?.ifEmpty { getStorageRoot() } ?: getStorageRoot()
                _searchResults.value = repository.searchFiles(query, root)
            } catch (e: Exception) {
                _searchResults.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearSearch() { _searchResults.value = null }

    fun setSortType(sort: SortType) {
        _sortType.value = sort
        val path = _currentPath.value?.ifEmpty { null } ?: return
        loadFiles(path)
    }

    fun toggleGridView() { _isGridView.value = !(_isGridView.value ?: false) }

    fun enterSelectionMode(path: String) {
        _isSelectionMode.value = true
        _selectedFiles.value = setOf(path)
        refreshFileSelection()
    }

    fun toggleSelection(item: FileItem) {
        val current = _selectedFiles.value?.toMutableSet() ?: mutableSetOf()
        if (item.path in current) current.remove(item.path) else current.add(item.path)
        _selectedFiles.value = current
        if (current.isEmpty()) exitSelectionMode()
        refreshFileSelection()
    }

    fun selectAll() {
        _selectedFiles.value = _files.value?.map { it.path }?.toSet() ?: emptySet()
        refreshFileSelection()
    }

    fun exitSelectionMode() {
        _isSelectionMode.value = false
        _selectedFiles.value = emptySet()
        refreshFileSelection()
    }

    private fun refreshFileSelection() {
        val selected = _selectedFiles.value ?: emptySet()
        _files.value = _files.value?.map { it.copy(isSelected = it.path in selected) }
    }

    fun getSelectedItems(): List<FileItem> {
        val selected = _selectedFiles.value ?: return emptyList()
        return _files.value?.filter { it.path in selected } ?: emptyList()
    }

    fun moveSelectedToTrash() {
        val items = getSelectedItems()
        if (items.isEmpty()) return
        viewModelScope.launch {
            try {
                val success = repository.moveToTrash(items)
                if (success) {
                    _toastMessage.value = "Đã chuyển ${items.size} mục vào thùng rác"
                    exitSelectionMode()
                    val path = _currentPath.value?.ifEmpty { null } ?: return@launch
                    loadFiles(path)
                } else {
                    _toastMessage.value = "Không thể xóa một số file"
                }
            } catch (e: Exception) {
                _toastMessage.value = "Lỗi: ${e.message}"
            }
        }
    }

    fun refresh() {
        val path = _currentPath.value?.ifEmpty { null } ?: return
        loadFiles(path)
    }

    fun getStorageRoot(): String = try {
        Environment.getExternalStorageDirectory()?.absolutePath ?: "/sdcard"
    } catch (e: Exception) { "/sdcard" }

    fun getQuickAccessPaths() = repository.getQuickAccessPaths()
}
