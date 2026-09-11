package com.filemanager.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * Giải quyết mapping giữa java.io.File path ↔ SAF DocumentFile cho thẻ SD.
 *
 * Hỗ trợ tất cả các trường hợp mount path thực tế:
 *   /storage/XXXX-XXXX/...  → AOSP / Pixel / Samsung mới (volume ID hex)
 *   /storage/sdcard1/...    → Oppo / Realme / ColorOS
 *   /storage/extsdcard/...  → Samsung cũ
 *   /storage/external_sd/.. → một số thiết bị khác
 *   /mnt/sdcard1/...        → ROM cũ / Android < 6
 *
 * Thiết kế:
 *   - Dùng StorageManager.getStorageVolumes() (API 24+) làm nguồn chính xác
 *   - Reflection fallback cho API 21-23
 *   - Hardcode scan fallback nếu StorageManager thất bại (Oppo/Realme ROM tùy chỉnh)
 *   - Cache volume list để tránh query lại (clear khi thẻ SD thay đổi)
 */
object SdCardResolver {

    /**
     * Thông tin về một storage volume đã được mount.
     * @param mountPath  Path thực tế, đã resolve symlink. VD: /storage/sdcard1
     * @param volumeId   Phần cuối của mountPath — khớp với volumeId trong SAF URI.
     *                   SAF URI lastPathSegment có dạng "volumeId:subpath"
     *                   VD: "sdcard1:" hoặc "1234-ABCD:"
     * @param isRemovable  true = thẻ SD, false = bộ nhớ trong / emulated
     */
    data class VolumeInfo(
        val mountPath: String,
        val volumeId: String,
        val isRemovable: Boolean
    )

    private var _cachedVolumes: List<VolumeInfo>? = null

    /** Xóa cache (gọi khi có thay đổi thẻ SD) */
    fun clearCache() { _cachedVolumes = null }

    // ── Public API ────────────────────────────────────────────────

    /**
     * Lấy danh sách tất cả storage volumes hiện có.
     * Kết quả được cache — gọi [clearCache] khi thẻ SD thay đổi.
     */
    fun getVolumes(context: Context): List<VolumeInfo> {
        _cachedVolumes?.let { return it }
        val result = buildVolumeList(context)
        _cachedVolumes = result
        return result
    }

    /**
     * Path thực tế của thẻ SD (VD: /storage/sdcard1).
     * Trả về null nếu không có thẻ SD hoặc chưa mount.
     */
    fun getSdCardRootPath(context: Context): String? =
        getVolumes(context).firstOrNull { it.isRemovable }?.mountPath

    /**
     * Kiểm tra xem [path] có nằm trên thẻ SD (removable storage) không.
     * Dùng StorageManager để nhận diện chính xác, tránh hardcode path.
     */
    fun isSdCardPath(context: Context, path: String): Boolean {
        // Fast check: bộ nhớ trong thường bắt đầu bằng /storage/emulated/
        val internalRoot = try {
            Environment.getExternalStorageDirectory().canonicalPath
        } catch (_: Exception) { "/storage/emulated/0" }
        if (path.startsWith(internalRoot)) return false

        return getVolumes(context).any { vol ->
            vol.isRemovable && (path.startsWith("${vol.mountPath}/") || path == vol.mountPath)
        }
    }

    /**
     * Kiểm tra SAF tree URI có bao phủ [file] này không.
     * Dùng để validate URI đã lưu trước khi dùng, tránh dùng URI cũ sai volume.
     *
     * Logic: lấy volumeId từ URI lastPathSegment ("volumeId:subpath"),
     * kiểm tra xem file có nằm trên volume đó không bằng mountPath thực tế.
     */
    fun uriMatchesFile(treeUri: Uri, file: File, context: Context): Boolean {
        val treePath = treeUri.lastPathSegment ?: return false
        val volumeIdFromUri = treePath.substringBefore(':').trim()
        if (volumeIdFromUri.isBlank()) return false

        val fileCanonical = file.safeCanonical()
        return getVolumes(context).any { vol ->
            vol.volumeId.equals(volumeIdFromUri, ignoreCase = true) &&
            (fileCanonical.startsWith("${vol.mountPath}/") || fileCanonical == vol.mountPath)
        }
    }

    /**
     * Tìm DocumentFile tương ứng với [file] trong SAF tree.
     *
     * Thuật toán:
     * 1. Dùng mountPath thực tế (từ StorageManager) để tìm volume chứa file
     * 2. Tính fullRelativePath = path tương đối so với volume root
     * 3. Tính relativeFromTree = trừ đi phần treeRelativePath (nếu user chọn subfolder)
     * 4. Navigate DocumentFile từng segment
     *
     * @param context   Application context
     * @param file      File/folder cần truy cập qua SAF
     * @param treeUri   SAF tree URI từ ACTION_OPEN_DOCUMENT_TREE
     * @return DocumentFile nếu tìm thấy, null nếu file nằm ngoài scope hoặc thất bại
     */
    fun resolveDocumentFile(context: Context, file: File, treeUri: Uri): DocumentFile? {
        val treePath = treeUri.lastPathSegment ?: return null
        val colonIdx = treePath.indexOf(':')
        // Phần path trong volume mà user đã chọn làm tree root
        // "" = user chọn root thẻ SD (chuẩn nhất)
        // "DCIM" = user chọn thư mục DCIM (chỉ có thể thao tác file trong DCIM)
        val treeRelativePath = if (colonIdx >= 0) treePath.substring(colonIdx + 1) else ""

        // Tìm volume chứa file — đây là điểm cốt lõi, dùng mountPath thực tế
        val fileCanonical = file.safeCanonical()
        val volume = getVolumes(context).firstOrNull { vol ->
            fileCanonical.startsWith("${vol.mountPath}/") || fileCanonical == vol.mountPath
        } ?: return null

        // fullRelativePath: path tương đối so với root của volume
        // VD: /storage/sdcard1/DCIM/photo.jpg → "DCIM/photo.jpg"
        //     /storage/1234-ABCD/Music/song.mp3 → "Music/song.mp3"
        val fullRelativePath = fileCanonical
            .removePrefix(volume.mountPath)
            .trimStart('/')

        // relativeFromTree: path tương đối so với CÂY mà user đã cấp quyền
        // VD: treeRelativePath=""     + full="DCIM/photo.jpg" → "DCIM/photo.jpg"
        //     treeRelativePath="DCIM" + full="DCIM/photo.jpg" → "photo.jpg"
        //     treeRelativePath="DCIM" + full="Music/song.mp3" → null (ngoài scope!)
        val relativeFromTree: String = when {
            treeRelativePath.isEmpty() ->
                fullRelativePath                // tree root = volume root ✓
            fullRelativePath == treeRelativePath ->
                ""                              // target chính là tree root
            fullRelativePath.startsWith("$treeRelativePath/") ->
                fullRelativePath.substring(treeRelativePath.length + 1)
            else ->
                return null  // ❌ file nằm ngoài phạm vi được cấp quyền
        }

        val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        if (relativeFromTree.isEmpty()) return rootDoc

        // Navigate từng cấp thư mục xuống file đích
        var current = rootDoc
        for (segment in relativeFromTree.split('/')) {
            if (segment.isEmpty()) continue
            current = current.findFile(segment) ?: return null
        }
        return current
    }

    /**
     * Tạo Intent để mở SAF picker thẳng vào thẻ SD (nếu có thể).
     * Trên Oppo/Realme, hàm này giúp picker mở đúng vị trí thẻ SD
     * thay vì mở ở bộ nhớ trong, tránh user chọn nhầm.
     *
     * - API 29+: StorageVolume.createOpenDocumentTreeIntent() — chính xác nhất
     * - API 24-28: StorageVolume.createAccessIntent(null) — deprecated nhưng hoạt động
     * - API < 24: trả về null → caller dùng safLauncher.launch(null)
     */
    fun buildSdCardPickerIntent(context: Context): Intent? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
                val sdVolume = sm.storageVolumes.firstOrNull { it.isRemovable } ?: return null
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    sdVolume.createOpenDocumentTreeIntent()
                } else {
                    sdVolume.createAccessIntent(null)
                }
            } else null
        } catch (_: Exception) { null }
    }

    // ── Private helpers ──────────────────────────────────────────

    private fun buildVolumeList(context: Context): List<VolumeInfo> {
        val result = mutableListOf<VolumeInfo>()

        // === Phương pháp 1: StorageManager API (API 24+) — nguồn chính xác nhất ===
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
                for (sv in sm.storageVolumes) {
                    val rawPath = getVolumeMountPath(sv) ?: continue
                    val mountFile = File(rawPath)
                    if (!mountFile.exists() || !mountFile.isDirectory) continue
                    val canonicalPath = mountFile.safeCanonical()
                    val volumeId = canonicalPath.substringAfterLast('/')
                    if (volumeId.isBlank()) continue
                    // Tránh trùng lặp
                    if (result.any { it.mountPath == canonicalPath }) continue
                    result.add(VolumeInfo(
                        mountPath   = canonicalPath,
                        volumeId    = volumeId,
                        isRemovable = sv.isRemovable
                    ))
                }
            } catch (_: Exception) {}
        }

        // === Phương pháp 2: Environment API fallback ===
        // Đảm bảo luôn có bộ nhớ trong trong danh sách
        if (result.none { !it.isRemovable }) {
            try {
                val path = Environment.getExternalStorageDirectory().safeCanonical()
                val vid = path.substringAfterLast('/')
                result.add(0, VolumeInfo(path, vid.ifBlank { "emulated" }, false))
            } catch (_: Exception) {
                result.add(0, VolumeInfo("/storage/emulated/0", "emulated", false))
            }
        }

        // === Phương pháp 3: Scan hardcoded paths cho SD card ===
        // Kích hoạt khi StorageManager không báo có removable volume
        // (thường gặp trên Oppo/Realme ROM tùy chỉnh, Android 9 trở xuống)
        if (result.none { it.isRemovable }) {
            for (candidatePath in FALLBACK_SD_PATHS) {
                val f = File(candidatePath)
                if (!f.exists() || !f.isDirectory) continue
                if (!f.canRead()) continue
                try {
                    val canonicalPath = f.safeCanonical()
                    // Không trùng với bộ nhớ trong và chưa có trong list
                    if (result.any { it.mountPath == canonicalPath }) continue
                    val volumeId = canonicalPath.substringAfterLast('/')
                    result.add(VolumeInfo(canonicalPath, volumeId, true))
                    break  // Chỉ lấy 1 SD card
                } catch (_: Exception) {}
            }
        }

        return result
    }

    @Suppress("DEPRECATION")
    private fun getVolumeMountPath(sv: android.os.storage.StorageVolume): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                sv.directory?.absolutePath
            } else {
                // Reflection cho Android 7-9 (API 24-28)
                sv.javaClass.getMethod("getPath").invoke(sv) as? String
            }
        } catch (_: Exception) { null }
    }

    private fun File.safeCanonical(): String = try { canonicalPath } catch (_: Exception) { absolutePath }

    /** Các path SD card phổ biến trên các OEM khác nhau (dùng làm fallback) */
    private val FALLBACK_SD_PATHS = listOf(
        "/storage/sdcard1",       // Oppo, Realme, Vivo (ColorOS / OriginOS)
        "/storage/extsdcard",     // Samsung cũ (TouchWiz)
        "/storage/external_sd",   // Samsung cũ
        "/storage/MicroSD",       // Một số thiết bị MediaTek
        "/storage/sdcard0",       // Một số ROM
        "/mnt/sdcard1",           // Android < 6
        "/mnt/extsdcard",         // Android < 6
        "/mnt/external_sd"        // Android < 6
    )
}
