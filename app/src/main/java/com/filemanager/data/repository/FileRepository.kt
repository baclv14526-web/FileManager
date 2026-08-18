package com.filemanager.data.repository

import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.filemanager.data.model.FileItem
import com.filemanager.data.model.SortType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
                val files = dir.listFiles()?.map { FileItem(it) } ?: emptyList()
                sortFiles(files, sortType)
            } catch (e: Exception) {
                emptyList()
            }
        }

    suspend fun searchFiles(query: String, rootPath: String): List<FileItem> =
        withContext(Dispatchers.IO) {
            try {
                val results = mutableListOf<FileItem>()
                searchRecursive(File(rootPath), query.lowercase(), results)
                results.sortedBy { it.name.lowercase() }
            } catch (e: Exception) {
                emptyList()
            }
        }

    private fun searchRecursive(dir: File, query: String, results: MutableList<FileItem>) {
        if (!dir.exists() || !dir.canRead()) return
        try {
            dir.listFiles()?.forEach { file ->
                if (file.name.lowercase().contains(query)) results.add(FileItem(file))
                if (file.isDirectory && !file.name.startsWith("."))
                    searchRecursive(file, query, results)
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
                val dest = File(trashDir, "${System.currentTimeMillis()}_${item.name}")
                item.file.renameTo(dest)
            }
        } catch (e: Exception) { false }
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
        listOf(
            "Internal Storage" to (Environment.getExternalStorageDirectory()?.absolutePath ?: "/sdcard"),
            "Downloads"  to (Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)?.absolutePath ?: "/sdcard/Download"),
            "DCIM"       to (Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)?.absolutePath ?: "/sdcard/DCIM"),
            "Pictures"   to (Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)?.absolutePath ?: "/sdcard/Pictures"),
            "Music"      to (Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)?.absolutePath ?: "/sdcard/Music"),
            "Movies"     to (Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)?.absolutePath ?: "/sdcard/Movies"),
            "Documents"  to (Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)?.absolutePath ?: "/sdcard/Documents"),
        )
    } catch (e: Exception) {
        listOf("Internal Storage" to "/sdcard")
    }
}

enum class TimelineMediaType { IMAGES, VIDEOS, ALL }
