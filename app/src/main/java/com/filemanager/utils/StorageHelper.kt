package com.filemanager.utils

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File

data class StorageVolume(
    val name: String,
    val path: String,
    val isRemovable: Boolean,       // true = thẻ SD, false = bộ nhớ trong
    val totalBytes: Long = 0L,
    val freeBytes: Long = 0L
) {
    val usedBytes get() = totalBytes - freeBytes
    val usedPercent get() = if (totalBytes > 0) ((usedBytes.toFloat() / totalBytes) * 100).toInt() else 0
}

object StorageHelper {

    /**
     * Trả về danh sách tất cả storage volume có thể đọc được.
     * - Luôn có bộ nhớ trong (index 0)
     * - Thẻ SD nếu có (index 1+)
     */
    fun getStorageVolumes(context: Context): List<StorageVolume> {
        val volumes = mutableListOf<StorageVolume>()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
                sm.storageVolumes.forEach { sv ->
                    val path = getVolumePath(sv) ?: return@forEach
                    if (!File(path).canRead()) return@forEach
                    val stat = android.os.StatFs(path)
                    volumes.add(StorageVolume(
                        name        = if (sv.isRemovable) "Thẻ MicroSD" else "Bộ nhớ trong",
                        path        = path,
                        isRemovable = sv.isRemovable,
                        totalBytes  = stat.totalBytes,
                        freeBytes   = stat.availableBytes
                    ))
                }
            }
        } catch (e: Exception) { /* fallback bên dưới */ }

        // Fallback nếu API mới không hoạt động hoặc danh sách rỗng
        if (volumes.isEmpty()) {
            fallbackVolumes(context).forEach { volumes.add(it) }
        }

        // Đảm bảo bộ nhớ trong luôn đứng đầu
        return volumes.sortedBy { it.isRemovable }
    }

    @Suppress("DEPRECATION")
    private fun getVolumePath(sv: android.os.storage.StorageVolume): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                sv.directory?.absolutePath
            } else {
                // Reflection cho Android 7-9
                val method = sv.javaClass.getMethod("getPath")
                method.invoke(sv) as? String
            }
        } catch (e: Exception) { null }
    }

    private fun fallbackVolumes(context: Context): List<StorageVolume> {
        val list = mutableListOf<StorageVolume>()
        try {
            val internalPath = Environment.getExternalStorageDirectory().absolutePath
            val stat = android.os.StatFs(internalPath)
            list.add(StorageVolume(
                name        = "Bộ nhớ trong",
                path        = internalPath,
                isRemovable = false,
                totalBytes  = stat.totalBytes,
                freeBytes   = stat.availableBytes
            ))
        } catch (e: Exception) {
            list.add(StorageVolume("Bộ nhớ trong", "/sdcard", false))
        }

        // Thử detect SD card qua các path phổ biến
        val sdCandidates = listOf(
            "/storage/sdcard1",
            "/storage/extsdcard",
            "/storage/external_sd",
            "/mnt/extsdcard",
            "/mnt/sdcard1"
        )
        sdCandidates.forEach { path ->
            val f = File(path)
            if (f.exists() && f.canRead() && f.isDirectory) {
                try {
                    val stat = android.os.StatFs(path)
                    if (stat.totalBytes > 0) {
                        list.add(StorageVolume(
                            name        = "Thẻ MicroSD",
                            path        = path,
                            isRemovable = true,
                            totalBytes  = stat.totalBytes,
                            freeBytes   = stat.availableBytes
                        ))
                        return list  // chỉ lấy 1 SD card
                    }
                } catch (e: Exception) { }
            }
        }
        return list
    }

    /** Tìm external files dir qua StorageManager để dùng với getExternalFilesDirs */
    fun getExternalStorageDirectories(context: Context): List<File> = try {
        context.getExternalFilesDirs(null)
            .filterNotNull()
            .mapNotNull { appDir ->
                // Đi ngược lên root của volume: /storage/xxxx/Android/data/pkg/files → /storage/xxxx
                var f = appDir
                repeat(4) { f = f.parentFile ?: return@repeat }
                if (f.canRead()) f else null
            }
    } catch (e: Exception) { emptyList() }
}
