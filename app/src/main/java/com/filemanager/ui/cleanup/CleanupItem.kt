package com.filemanager.ui.cleanup

import com.filemanager.data.model.FileItem

enum class CleanupCategory(val label: String, val description: String, val icon: String) {
    LARGE_FILES  ("File lớn",       "Kích thước từ 100MB trở lên",    "📦"),
    EMPTY_FILES  ("File rỗng",      "File có dung lượng 0 byte",       "📄"),
    EMPTY_FOLDERS("Thư mục rỗng",   "Thư mục không chứa gì",           "📁"),
    JUNK_FILES   ("File rác",       "File tạm, cache, log, thumbnail", "🗑")
}

data class CleanupItem(
    val fileItem: FileItem,
    val category: CleanupCategory,
    var isSelected: Boolean = false
)
