package com.filemanager.ui.timeline

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.filemanager.data.model.FileItem
import com.filemanager.data.model.FileType
import com.filemanager.data.repository.FileRepository
import com.filemanager.data.repository.TimelineMediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

sealed class TimelineListItem {
    data class Header(val title: String) : TimelineListItem()
    data class MediaItem(val file: FileItem) : TimelineListItem()
}

class TimelineViewModel(app: Application) : AndroidViewModel(app) {

    private val app = app
    private val _timelineItems = MutableLiveData<List<TimelineListItem>>(emptyList())
    val timelineItems: LiveData<List<TimelineListItem>> = _timelineItems

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    // Giữ danh sách đầy đủ trong ViewModel, không truyền qua Intent
    var allImagePaths: List<String> = emptyList()
        private set

    fun loadMedia(type: TimelineMediaType) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val items = withContext(Dispatchers.IO) { queryMedia(type) }
                val sdf = SimpleDateFormat("MMMM yyyy", Locale.getDefault())

                // Group theo tháng/năm, sắp xếp mới nhất trước
                val grouped = LinkedHashMap<String, MutableList<FileItem>>()
                items.sortedByDescending { it.lastModified }.forEach { item ->
                    val key = sdf.format(Date(item.lastModified))
                    grouped.getOrPut(key) { mutableListOf() }.add(item)
                }

                // Build flat list cho RecyclerView
                val list = mutableListOf<TimelineListItem>()
                grouped.forEach { (month, files) ->
                    list.add(TimelineListItem.Header(month))
                    files.forEach { list.add(TimelineListItem.MediaItem(it)) }
                }

                allImagePaths = items
                    .filter { it.fileType == FileType.IMAGE }
                    .sortedByDescending { it.lastModified }
                    .map { it.path }

                _timelineItems.value = list
            } catch (e: Exception) {
                _timelineItems.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun queryMedia(type: TimelineMediaType): List<FileItem> {
        val result = mutableListOf<FileItem>()

        if (type == TimelineMediaType.IMAGES || type == TimelineMediaType.ALL) {
            result += queryContentProvider(
                uri       = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                dataCol   = MediaStore.Images.Media.DATA,
                dateCol   = MediaStore.Images.Media.DATE_TAKEN
            )
        }

        if (type == TimelineMediaType.VIDEOS || type == TimelineMediaType.ALL) {
            result += queryContentProvider(
                uri       = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                dataCol   = MediaStore.Video.Media.DATA,
                dateCol   = MediaStore.Video.Media.DATE_TAKEN
            )
        }

        return result
    }

    private fun queryContentProvider(
        uri: android.net.Uri,
        dataCol: String,
        dateCol: String
    ): List<FileItem> {
        val result = mutableListOf<FileItem>()
        try {
            val projection = arrayOf(dataCol, dateCol)
            app.contentResolver.query(
                uri, projection, null, null, "$dateCol DESC"
            )?.use { cursor ->
                val colData = cursor.getColumnIndex(dataCol)
                if (colData < 0) return result   // column không tồn tại → bỏ qua

                while (cursor.moveToNext()) {
                    try {
                        val path = cursor.getString(colData) ?: continue
                        val file = File(path)
                        if (file.exists() && file.canRead()) {
                            result.add(FileItem(file))
                        }
                    } catch (e: Exception) { continue }
                }
            }
        } catch (e: Exception) { /* ignore - không có quyền hoặc provider lỗi */ }
        return result
    }
}
