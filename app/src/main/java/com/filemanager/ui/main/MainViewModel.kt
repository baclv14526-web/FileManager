package com.filemanager.ui.main

import android.app.Application
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

    private val _files = MutableLiveData<List<FileItem>>()
    val files: LiveData<List<FileItem>> = _files

    private val _currentPath = MutableLiveData<String>()
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

    init {
        navigateTo(repository.getStorageRoot())
    }

    fun navigateTo(path: String) {
        val current = _currentPath.value
        if (current != null && current != path) {
            pathHistory.addLast(current)
        }
        _currentPath.value = path
        loadFiles(path)
        exitSelectionMode()
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
            _files.value = repository.getFiles(path, _sortType.value ?: SortType.NAME_ASC)
            _isLoading.value = false
        }
    }

    fun search(query: String) {
        if (query.isBlank()) {
            _searchResults.value = null
            return
        }
        viewModelScope.launch {
            _isLoading.value = true
            val root = _currentPath.value ?: repository.getStorageRoot()
            _searchResults.value = repository.searchFiles(query, root)
            _isLoading.value = false
        }
    }

    fun clearSearch() {
        _searchResults.value = null
    }

    fun setSortType(sort: SortType) {
        _sortType.value = sort
        loadFiles(_currentPath.value ?: repository.getStorageRoot())
    }

    fun toggleGridView() {
        _isGridView.value = !(_isGridView.value ?: false)
    }

    // Selection
    fun enterSelectionMode(path: String) {
        _isSelectionMode.value = true
        _selectedFiles.value = setOf(path)
        refreshFileSelection()
    }

    fun toggleSelection(item: FileItem) {
        val current = _selectedFiles.value?.toMutableSet() ?: mutableSetOf()
        if (item.path in current) current.remove(item.path)
        else current.add(item.path)
        _selectedFiles.value = current
        if (current.isEmpty()) exitSelectionMode()
        refreshFileSelection()
    }

    fun selectAll() {
        val allPaths = _files.value?.map { it.path }?.toSet() ?: emptySet()
        _selectedFiles.value = allPaths
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
            val success = repository.moveToTrash(items)
            if (success) {
                _toastMessage.value = "Đã chuyển ${items.size} mục vào thùng rác"
                exitSelectionMode()
                loadFiles(_currentPath.value ?: repository.getStorageRoot())
            } else {
                _toastMessage.value = "Không thể xóa một số file"
            }
        }
    }

    fun refresh() {
        loadFiles(_currentPath.value ?: repository.getStorageRoot())
    }

    fun getQuickAccessPaths() = repository.getQuickAccessPaths()
    fun getStorageRoot() = repository.getStorageRoot()
}
