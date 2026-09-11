package com.filemanager.data.repository

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.filemanager.data.model.FileItem
import com.filemanager.data.model.SortType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class FileRepository(private val context: Context) {

    suspend fun getFiles(path: String, sortType: SortType = SortType.NAME_ASC): List<FileItem> =
        withContext(Dispatchers.IO) {
            try {
                val dir = File(path)
                if (!dir.exists() || !dir.isDirectory || !dir.canRead())
                    return@withContext emptyList()
                // ✅ Giới hạn 2000 item để tránh OOM trên Android 9 (heap nhỏ hơn)
                val files = dir.listFiles()
                    ?.take(2000)
                    ?.map { FileItem(it) }
                    ?: emptyList()
                sortFiles(files, sortType)
            } catch (e: Exception) {
                emptyList()
            }
        }

    companion object {
        // Giới hạn để search không gây OOM khi quét toàn bộ storage
        private const val SEARCH_MAX_RESULTS = 2000
        private const val SEARCH_MAX_DEPTH   = 25
    }

    suspend fun searchFiles(query: String, rootPath: String): List<FileItem> =
        withContext(Dispatchers.IO) {
            try {
                val dir = File(rootPath)
                if (!dir.exists() || !dir.canRead()) return@withContext emptyList()
                val results = mutableListOf<FileItem>()
                searchRecursive(dir, query.lowercase(), results, depth = 0)
                results
            } catch (e: Exception) {
                emptyList()
            }
        }

    /**
     * Duyệt đệ quy tìm kiếm toàn bộ file và folder (bao gồm cả thư mục hệ thống và ẩn)
     * Sử dụng yield() để coroutine cancellation hoạt động khi người dùng thay đổi từ khoá
     */
    private suspend fun searchRecursive(
        dir: File, query: String, results: MutableList<FileItem>, depth: Int
    ) {
        if (results.size >= SEARCH_MAX_RESULTS) return
        if (depth > SEARCH_MAX_DEPTH) return
        if (!dir.exists() || !dir.canRead()) return

        // Nhường CPU định kỳ → cho phép coroutine.cancel() cắt ngang kịp thời
        yield()

        try {
            val children = dir.listFiles() ?: return
            for (file in children) {
                if (results.size >= SEARCH_MAX_RESULTS) return

                if (file.name.lowercase().contains(query)) results.add(FileItem(file))

                if (file.isDirectory) {
                    searchRecursive(file, query, results, depth + 1)
                }
            }
        } catch (e: Exception) { /* bỏ qua thư mục không đọc được */ }
    }

    suspend fun getMediaByTimeline(type: TimelineMediaType): Map<String, List<FileItem>> =
        withContext(Dispatchers.IO) {
            try {
                val items = mutableListOf<FileItem>()

                if (type == TimelineMediaType.IMAGES || type == TimelineMediaType.ALL) {
                    queryMedia(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        MediaStore.Images.Media.DATA,
                        MediaStore.Images.Media.DATE_TAKEN
                    ).forEach { items.add(it) }
                }

                if (type == TimelineMediaType.VIDEOS || type == TimelineMediaType.ALL) {
                    queryMedia(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        MediaStore.Video.Media.DATA,
                        MediaStore.Video.Media.DATE_TAKEN
                    ).forEach { items.add(it) }
                }

                val sdf = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
                val grouped = linkedMapOf<String, MutableList<FileItem>>()

                items.sortedByDescending { it.lastModified }.forEach { item ->
                    val key = sdf.format(Date(item.lastModified))
                    grouped.getOrPut(key) { mutableListOf() }.add(item)
                }
                grouped
            } catch (e: Exception) {
                emptyMap()
            }
        }

    private fun queryMedia(uri: android.net.Uri, dataCol: String, dateCol: String): List<FileItem> {
        val result = mutableListOf<FileItem>()
        try {
            val projection = arrayOf(dataCol, dateCol)
            context.contentResolver.query(
                uri, projection, null, null, "$dateCol DESC"
            )?.use { cursor ->
                val col = cursor.getColumnIndexOrThrow(dataCol)
                while (cursor.moveToNext()) {
                    val path = cursor.getString(col) ?: continue
                    val file = File(path)
                    if (file.exists()) result.add(FileItem(file))
                }
            }
        } catch (e: Exception) { /* ignore */ }
        return result
    }

    suspend fun moveToTrash(files: List<FileItem>): Boolean = withContext(Dispatchers.IO) {
        try {
            val trashDir = File(context.getExternalFilesDir(null), ".trash")
                .also { it.mkdirs() }
            files.all { item ->
                val src = item.file
                if (!src.exists()) return@all false
                val dest = File(trashDir, "${System.currentTimeMillis()}_${item.name}")
                // 1. Thử rename trước (nhanh nhất đối với cùng partition bộ nhớ trong)
                if (src.renameTo(dest)) {
                    true
                } else {
                    // 2. Nếu rename thất bại (cross-filesystem giữa thẻ SD và bộ nhớ trong), thực hiện copy & delete
                    val copyOk = if (src.isDirectory) {
                        src.copyRecursively(dest, overwrite = true)
                    } else {
                        try {
                            src.inputStream().use { input ->
                                dest.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                            true
                        } catch (e: Exception) {
                            false
                        }
                    }

                    if (copyOk) {
                        src.deleteRecursively()
                    } else {
                        dest.deleteRecursively()
                        false
                    }
                }
            }
        } catch (e: Exception) { false }
    }

    /** Kiểm tra file có nằm trên thẻ SD (removable storage) không */
    fun isSdCardFile(file: File): Boolean {
        return com.filemanager.utils.SdCardResolver.isSdCardPath(context, file.absolutePath)
    }

    /**
     * Xóa thẳng file/folder mà không vào thùng rác, dùng SAF DocumentFile cho thẻ SD.
     * @param files  Danh sách file cần xóa
     * @param sdTreeUri  URI quyền từ ACTION_OPEN_DOCUMENT_TREE (chỉ cần khi file nằm trên thẻ SD)
     * @return true nếu tất cả xóa thành công
     */
    suspend fun deleteFilesDirectly(
        files: List<FileItem>,
        sdTreeUri: Uri? = null
    ): Boolean = withContext(Dispatchers.IO) {
        files.all { item ->
            try {
                val src = item.file
                if (!src.exists()) return@all true  // đã không còn tồn tại → coi như thành công

                if (sdTreeUri != null && isSdCardFile(src)) {
                    // Xóa qua SAF DocumentFile (thẻ SD cần quyền đặc biệt)
                    val docFile = com.filemanager.utils.SdCardResolver.resolveDocumentFile(context, src, sdTreeUri)
                        ?: return@all false
                    // ✅ Phải capture return value — delete() trả về false nếu thất bại
                    docFile.delete()
                } else {
                    // Bộ nhớ trong: xóa thẳng qua java.io.File
                    src.deleteRecursively()
                }
            } catch (e: Exception) { false }
        }
    }

    /**
     * Đổi tên file/folder.
     * - Bộ nhớ trong: dùng File.renameTo() thông thường.
     * - Thẻ SD: phải dùng SAF DocumentFile.renameTo() vì Android chặn ghi thẳng.
     * @param file      File cần đổi tên
     * @param newName   Tên mới (chỉ tên, không phải đường dẫn đầy đủ)
     * @param sdTreeUri URI quyền SAF từ ACTION_OPEN_DOCUMENT_TREE (chỉ cần khi file trên thẻ SD)
     * @return File sau khi đổi tên, hoặc null nếu thất bại
     */
    suspend fun renameFile(
        file: File,
        newName: String,
        sdTreeUri: Uri? = null
    ): File? = withContext(Dispatchers.IO) {
        try {
            if (sdTreeUri != null && isSdCardFile(file)) {
                // Đổi tên qua SAF (thẻ SD)
                val docFile = com.filemanager.utils.SdCardResolver.resolveDocumentFile(context, file, sdTreeUri)
                    ?: return@withContext null
                if (docFile.renameTo(newName)) {
                    File(file.parent, newName)
                } else null
            } else {
                // Bộ nhớ trong: renameTo thông thường
                val dest = File(file.parent, newName)
                if (file.renameTo(dest)) dest else null
            }
        } catch (e: Exception) { null }
    }

    fun sortFiles(files: List<FileItem>, sortType: SortType): List<FileItem> {
        val (folders, regularFiles) = files.partition { it.isDirectory }
        val sortedFolders = when (sortType) {
            SortType.NAME_DESC -> folders.sortedByDescending { it.name.lowercase() }
            SortType.DATE_ASC  -> folders.sortedBy { it.lastModified }
            SortType.DATE_DESC -> folders.sortedByDescending { it.lastModified }
            else               -> folders.sortedBy { it.name.lowercase() }
        }
        val sortedFiles = when (sortType) {
            SortType.NAME_ASC  -> regularFiles.sortedBy { it.name.lowercase() }
            SortType.NAME_DESC -> regularFiles.sortedByDescending { it.name.lowercase() }
            SortType.SIZE_ASC  -> regularFiles.sortedBy { it.size }
            SortType.SIZE_DESC -> regularFiles.sortedByDescending { it.size }
            SortType.DATE_ASC  -> regularFiles.sortedBy { it.lastModified }
            SortType.DATE_DESC -> regularFiles.sortedByDescending { it.lastModified }
            SortType.TYPE      -> regularFiles.sortedWith(compareBy({ it.extension }, { it.name.lowercase() }))
        }
        return sortedFolders + sortedFiles
    }

    fun getStorageRoot(): String = try {
        Environment.getExternalStorageDirectory()?.absolutePath ?: "/sdcard"
    } catch (e: Exception) { "/sdcard" }

    fun getQuickAccessPaths(): List<Pair<String, String>> = try {
        val topVisited = com.filemanager.utils.FolderHistoryManager.getTopVisitedFolders(context, 3)
        val defaultPaths = listOf(
            "Internal Storage" to (Environment.getExternalStorageDirectory()?.absolutePath ?: "/sdcard"),
            "Downloads"  to (Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)?.absolutePath ?: "/sdcard/Download"),
            "DCIM"       to (Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)?.absolutePath ?: "/sdcard/DCIM"),
            "Pictures"   to (Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)?.absolutePath ?: "/sdcard/Pictures"),
            "Music"      to (Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)?.absolutePath ?: "/sdcard/Music"),
            "Movies"     to (Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)?.absolutePath ?: "/sdcard/Movies"),
            "Documents"  to (Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)?.absolutePath ?: "/sdcard/Documents"),
        )
        // Ghép top thư mục hay vào nhất (không trùng lặp path) với danh sách mặc định
        val topPathsSet = topVisited.map { it.second }.toSet()
        topVisited + defaultPaths.filterNot { it.second in topPathsSet }
    } catch (e: Exception) {
        listOf("Internal Storage" to "/sdcard")
    }
}

enum class TimelineMediaType { IMAGES, VIDEOS, ALL }


