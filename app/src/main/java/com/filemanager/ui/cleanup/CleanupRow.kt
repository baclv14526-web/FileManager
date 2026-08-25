package com.filemanager.ui.cleanup

import com.filemanager.utils.StorageVolume

/**
 * Tất cả row types trong RecyclerView duy nhất của màn hình Dọn dẹp.
 * Không dùng NestedScrollView → view recycling hoạt động đúng với 1000+ items.
 */
sealed class CleanupRow {

    /** Card thông tin bộ nhớ + scope chips + nút Quét */
    data class StorageCard(
        val volumes: List<StorageVolume>,
        val hasSD: Boolean,
        val isScanning: Boolean,
        val scopeIndex: Int = 0
    ) : CleanupRow()

    /** Tóm tắt sau khi quét xong */
    data class Summary(val text: String) : CleanupRow()

    /** Quét xong không có gì */
    object Empty : CleanupRow()

    /** Header nhóm file (có thể collapse/expand) */
    data class CategoryHeader(
        val category: CleanupCategory,
        val count: Int,
        val totalSize: Long,
        val isExpanded: Boolean
    ) : CleanupRow()

    /** Một file/folder trong danh sách kết quả */
    data class FileEntry(val item: CleanupItem) : CleanupRow()

    /** Placeholder "... và N mục khác — nhấn để xem thêm" */
    data class MoreItems(
        val category: CleanupCategory,
        val remaining: Int
    ) : CleanupRow()
}
