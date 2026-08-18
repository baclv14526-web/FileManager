package com.filemanager.ui.timeline

import android.app.Application
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.filemanager.data.model.FileItem
import com.filemanager.data.model.FileType
import com.filemanager.data.repository.TimelineMediaType
import com.filemanager.utils.StorageHelper
import com.filemanager.utils.StorageVolume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// ── Scan scope ──────────────────────────────────────────────────
enum class ScanScope { ALL, INTERNAL, SD_CARD }

// ── Timeline list items ─────────────────────────────────────────
sealed class TimelineListItem {
    data class Header(
        val title: String,
        val count: Int,
        val isExpanded: Boolean = true
    ) : TimelineListItem()
    data class MediaItem(val file: FileItem) : TimelineListItem()
}

class TimelineViewModel(private val app: Application) : AndroidViewModel(app) {

    private val _timelineItems = MutableLiveData<List<TimelineListItem>>(emptyList())
    val timelineItems: LiveData<List<TimelineListItem>> = _timelineItems

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _storageVolumes = MutableLiveData<List<StorageVolume>>(emptyList())
    val storageVolumes: LiveData<List<StorageVolume>> = _storageVolumes

    // Scope hiện tại
    private val _currentScope = MutableLiveData(ScanScope.ALL)
    val currentScope: LiveData<ScanScope> = _currentScope

    var allImagePaths: List<String> = emptyList()
        private set

    private var rawGrouped: LinkedHashMap<String, List<FileItem>> = linkedMapOf()
    private val expandedState: MutableMap<String, Boolean> = mutableMapOf()
    private var currentMediaType = TimelineMediaType.ALL

    init {
        // Load danh sách storage volumes
        viewModelScope.launch(Dispatchers.IO) {
            val vols = StorageHelper.getStorageVolumes(app)
            _storageVolumes.postValue(vols)
        }
    }

    fun loadMedia(type: TimelineMediaType, scope: ScanScope = _currentScope.value ?: ScanScope.ALL) {
        currentMediaType = type
        _currentScope.value = scope
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val scopePaths = getScopePaths(scope)
                val items = withContext(Dispatchers.IO) { queryMedia(type, scopePaths) }

                val sdf = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
                rawGrouped = linkedMapOf()
                items.sortedByDescending { it.lastModified }.forEach { item ->
                    val key = sdf.format(Date(item.lastModified))
                    (rawGrouped.getOrPut(key) { mutableListOf() } as MutableList).add(item)
                }

                rawGrouped.keys.forEach { key ->
                    if (!expandedState.containsKey(key)) expandedState[key] = true
                }

                allImagePaths = items
                    .filter { it.fileType == FileType.IMAGE }
                    .sortedByDescending { it.lastModified }
                    .map { it.path }

                rebuildList()
            } catch (e: Exception) {
                _timelineItems.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun setScope(scope: ScanScope) = loadMedia(currentMediaType, scope)

    fun toggleGroup(title: String) {
        expandedState[title] = !(expandedState[title] ?: true)
        rebuildList()
    }

    private fun getScopePaths(scope: ScanScope): List<String>? {
        if (scope == ScanScope.ALL) return null   // null = không filter, lấy tất cả
        val volumes = _storageVolumes.value ?: return null
        return when (scope) {
            ScanScope.INTERNAL -> volumes.filter { !it.isRemovable }.map { it.path }
            ScanScope.SD_CARD  -> volumes.filter { it.isRemovable }.map { it.path }
            ScanScope.ALL      -> null
        }
    }

    private fun rebuildList() {
        val list = mutableListOf<TimelineListItem>()
        rawGrouped.forEach { (month, files) ->
            val expanded = expandedState[month] ?: true
            list.add(TimelineListItem.Header(month, files.size, expanded))
            if (expanded) files.forEach { list.add(TimelineListItem.MediaItem(it)) }
        }
        _timelineItems.value = list
    }

    private fun queryMedia(type: TimelineMediaType, scopePaths: List<String>?): List<FileItem> {
        val result = mutableListOf<FileItem>()
        if (type == TimelineMediaType.IMAGES || type == TimelineMediaType.ALL)
            result += query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Images.Media.DATA, MediaStore.Images.Media.DATE_TAKEN, scopePaths)
        if (type == TimelineMediaType.VIDEOS || type == TimelineMediaType.ALL)
            result += query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Video.Media.DATA, MediaStore.Video.Media.DATE_TAKEN, scopePaths)
        return result
    }

    private fun query(
        uri: android.net.Uri,
        dataCol: String,
        dateCol: String,
        scopePaths: List<String>?
    ): List<FileItem> {
        val result = mutableListOf<FileItem>()
        try {
            app.contentResolver.query(
                uri, arrayOf(dataCol, dateCol), null, null, "$dateCol DESC"
            )?.use { cursor ->
                val col = cursor.getColumnIndex(dataCol)
                if (col < 0) return result
                while (cursor.moveToNext()) {
                    try {
                        val path = cursor.getString(col) ?: continue
                        // Filter theo scope
                        if (scopePaths != null && scopePaths.none { path.startsWith(it) }) continue
                        val file = File(path)
                        if (file.exists() && file.canRead()) result.add(FileItem(file))
                    } catch (e: Exception) { continue }
                }
            }
        } catch (e: Exception) { }
        return result
    }
}
