package com.filemanager.data.repository

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.filemanager.data.model.FileItem
import com.filemanager.data.model.FileType
import com.filemanager.data.model.SortType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class FileRepository(private val context: Context) {

    suspend fun getFiles(path: String, sortType: SortType = SortType.NAME_ASC): List<FileItem> =
        withContext(Dispatchers.IO) {
            val dir = File(path)
            if (!dir.exists() || !dir.isDirectory) return@withContext emptyList()
            val files = dir.listFiles()?.map { FileItem(it) } ?: emptyList()
            sortFiles(files, sortType)
        }

    suspend fun searchFiles(query: String, rootPath: String): List<FileItem> =
        withContext(Dispatchers.IO) {
            val results = mutableListOf<FileItem>()
            searchRecursive(File(rootPath), query.lowercase(), results)
            results.sortedBy { it.name.lowercase() }
        }

    private fun searchRecursive(dir: File, query: String, results: MutableList<FileItem>) {
        if (!dir.exists() || !dir.canRead()) return
        dir.listFiles()?.forEach { file ->
            if (file.name.lowercase().contains(query)) {
                results.add(FileItem(file))
            }
            if (file.isDirectory && !file.name.startsWith(".")) {
                searchRecursive(file, query, results)
            }
        }
    }

    suspend fun getMediaByTimeline(type: TimelineMediaType): Map<String, List<FileItem>> =
        withContext(Dispatchers.IO) {
            val projection = when (type) {
                TimelineMediaType.IMAGES -> arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DATA,
                    MediaStore.Images.Media.DATE_TAKEN,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.SIZE
                )
                TimelineMediaType.VIDEOS -> arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DATA,
                    MediaStore.Video.Media.DATE_TAKEN,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.SIZE
                )
                TimelineMediaType.ALL -> arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DATA,
                    MediaStore.Images.Media.DATE_TAKEN,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.SIZE
                )
            }

            val uri = when (type) {
                TimelineMediaType.IMAGES -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                TimelineMediaType.VIDEOS -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                TimelineMediaType.ALL -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

            val items = mutableListOf<FileItem>()
            val cursor = context.contentResolver.query(
                uri, projection, null, null,
                "${MediaStore.Images.Media.DATE_TAKEN} DESC"
            )
            cursor?.use {
                val dataCol = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                while (it.moveToNext()) {
                    val path = it.getString(dataCol) ?: continue
                    val file = File(path)
                    if (file.exists()) items.add(FileItem(file))
                }
            }

            if (type == TimelineMediaType.ALL || type == TimelineMediaType.VIDEOS) {
                val videoCursor = context.contentResolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Video.Media.DATA, MediaStore.Video.Media.DATE_TAKEN),
                    null, null,
                    "${MediaStore.Video.Media.DATE_TAKEN} DESC"
                )
                videoCursor?.use {
                    val dataCol = it.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                    while (it.moveToNext()) {
                        val path = it.getString(dataCol) ?: continue
                        val file = File(path)
                        if (file.exists()) items.add(FileItem(file))
                    }
                }
            }

            // Group by year-month
            val sdf = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
            val grouped = TreeMap<String, MutableList<FileItem>>(compareByDescending {
                val parts = it.split(" ")
                try {
                    val date = sdf.parse(it)
                    date?.time?.unaryMinus() ?: 0L
                } catch (e: Exception) { 0L }
            })

            items.sortedByDescending { it.lastModified }.forEach { item ->
                val key = sdf.format(Date(item.lastModified))
                grouped.getOrPut(key) { mutableListOf() }.add(item)
            }
            grouped
        }

    suspend fun moveToTrash(files: List<FileItem>): Boolean = withContext(Dispatchers.IO) {
        val trashDir = File(
            context.getExternalFilesDir(null), ".trash"
        ).also { it.mkdirs() }

        files.all { item ->
            try {
                val dest = File(trashDir, "${System.currentTimeMillis()}_${item.name}")
                item.file.renameTo(dest)
            } catch (e: Exception) { false }
        }
    }

    suspend fun deleteFiles(files: List<FileItem>): Boolean = withContext(Dispatchers.IO) {
        files.all { item ->
            try {
                if (item.isDirectory) item.file.deleteRecursively()
                else item.file.delete()
            } catch (e: Exception) { false }
        }
    }

    fun sortFiles(files: List<FileItem>, sortType: SortType): List<FileItem> {
        val (folders, regularFiles) = files.partition { it.isDirectory }
        val sortedFolders = when (sortType) {
            SortType.NAME_ASC -> folders.sortedBy { it.name.lowercase() }
            SortType.NAME_DESC -> folders.sortedByDescending { it.name.lowercase() }
            SortType.DATE_ASC -> folders.sortedBy { it.lastModified }
            SortType.DATE_DESC -> folders.sortedByDescending { it.lastModified }
            else -> folders.sortedBy { it.name.lowercase() }
        }
        val sortedFiles = when (sortType) {
            SortType.NAME_ASC -> regularFiles.sortedBy { it.name.lowercase() }
            SortType.NAME_DESC -> regularFiles.sortedByDescending { it.name.lowercase() }
            SortType.SIZE_ASC -> regularFiles.sortedBy { it.size }
            SortType.SIZE_DESC -> regularFiles.sortedByDescending { it.size }
            SortType.DATE_ASC -> regularFiles.sortedBy { it.lastModified }
            SortType.DATE_DESC -> regularFiles.sortedByDescending { it.lastModified }
            SortType.TYPE -> regularFiles.sortedWith(compareBy({ it.extension }, { it.name.lowercase() }))
        }
        return sortedFolders + sortedFiles
    }

    fun getStorageRoot(): String =
        Environment.getExternalStorageDirectory().absolutePath

    fun getQuickAccessPaths(): List<Pair<String, String>> = listOf(
        "Internal Storage" to Environment.getExternalStorageDirectory().absolutePath,
        "Downloads" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath,
        "DCIM" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).absolutePath,
        "Pictures" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).absolutePath,
        "Music" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).absolutePath,
        "Movies" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES).absolutePath,
        "Documents" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).absolutePath,
    )
}

enum class TimelineMediaType { IMAGES, VIDEOS, ALL }
