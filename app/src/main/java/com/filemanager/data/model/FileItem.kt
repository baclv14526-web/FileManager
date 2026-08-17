package com.filemanager.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.io.File
import java.util.Date

@Parcelize
data class FileItem(
    val file: File,
    val name: String = file.name,
    val path: String = file.absolutePath,
    val isDirectory: Boolean = file.isDirectory,
    val size: Long = if (file.isFile) file.length() else 0L,
    val lastModified: Long = file.lastModified(),
    val extension: String = file.extension.lowercase(),
    var isSelected: Boolean = false
) : Parcelable {

    val fileType: FileType get() = when {
        isDirectory -> FileType.FOLDER
        extension in IMAGE_EXTENSIONS -> FileType.IMAGE
        extension in VIDEO_EXTENSIONS -> FileType.VIDEO
        extension in AUDIO_EXTENSIONS -> FileType.AUDIO
        extension in DOC_EXTENSIONS -> FileType.DOCUMENT
        extension in ARCHIVE_EXTENSIONS -> FileType.ARCHIVE
        extension in CODE_EXTENSIONS -> FileType.CODE
        extension == "apk" -> FileType.APK
        else -> FileType.OTHER
    }

    val lastModifiedDate: Date get() = Date(lastModified)

    fun formattedSize(): String {
        if (isDirectory) return ""
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${"%.1f".format(size / 1024.0)} KB"
            size < 1024 * 1024 * 1024 -> "${"%.1f".format(size / (1024.0 * 1024))} MB"
            else -> "${"%.1f".format(size / (1024.0 * 1024 * 1024))} GB"
        }
    }

    companion object {
        val IMAGE_EXTENSIONS = setOf("jpg","jpeg","png","gif","bmp","webp","heic","heif","svg","tiff")
        val VIDEO_EXTENSIONS = setOf("mp4","mkv","avi","mov","wmv","flv","webm","3gp","m4v","ts")
        val AUDIO_EXTENSIONS = setOf("mp3","wav","aac","flac","ogg","m4a","wma","opus")
        val DOC_EXTENSIONS = setOf("pdf","doc","docx","xls","xlsx","ppt","pptx","txt","rtf","odt","csv")
        val ARCHIVE_EXTENSIONS = setOf("zip","rar","7z","tar","gz","bz2","xz")
        val CODE_EXTENSIONS = setOf("kt","java","py","js","ts","html","css","xml","json","cpp","c","h","rs","go","swift")
    }
}

enum class FileType {
    FOLDER, IMAGE, VIDEO, AUDIO, DOCUMENT, ARCHIVE, CODE, APK, OTHER
}

enum class SortType {
    NAME_ASC, NAME_DESC, SIZE_ASC, SIZE_DESC, DATE_ASC, DATE_DESC, TYPE
}
