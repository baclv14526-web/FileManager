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
import kotlinx.coroutines.launch

sealed class TimelineListItem {
    data class Header(val title: String) : TimelineListItem()
    data class MediaItem(val file: FileItem) : TimelineListItem()
}

class TimelineViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = FileRepository(app)

    private val _timelineItems = MutableLiveData<List<TimelineListItem>>()
    val timelineItems: LiveData<List<TimelineListItem>> = _timelineItems

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    var allImagePaths: List<String> = emptyList()
        private set

    fun loadMedia(type: TimelineMediaType) {
        viewModelScope.launch {
            _isLoading.value = true
            val grouped = repository.getMediaByTimeline(type)
            val list = mutableListOf<TimelineListItem>()

            grouped.forEach { (monthYear, files) ->
                list.add(TimelineListItem.Header(monthYear))
                files.forEach { list.add(TimelineListItem.MediaItem(it)) }
            }

            allImagePaths = grouped.values.flatten()
                .filter { it.fileType == FileType.IMAGE }
                .map { it.path }

            _timelineItems.value = list
            _isLoading.value = false
        }
    }
}
