package com.filemanager.ui.main

import android.app.Dialog
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import com.filemanager.data.model.FileItem
import com.filemanager.data.model.FileType
import com.filemanager.databinding.DialogFilePropertiesBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class FilePropertiesDialog : DialogFragment() {

    companion object {
        private const val ARG_PATH = "path"
        fun newInstance(item: FileItem): FilePropertiesDialog {
            return FilePropertiesDialog().apply {
                arguments = Bundle().apply { putString(ARG_PATH, item.path) }
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val path = arguments?.getString(ARG_PATH) ?: return super.onCreateDialog(savedInstanceState)
        val binding = DialogFilePropertiesBinding.inflate(layoutInflater)
        val item = FileItem(File(path))
        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())

        binding.tvName.text = item.name
        binding.tvPath.text = item.path
        binding.tvType.text = if (item.isDirectory) "Thư mục" else "${item.extension.uppercase()} File"
        binding.tvSize.text = if (item.isDirectory) toDirSize(item) else item.formattedSize()
        binding.tvDate.text = sdf.format(Date(item.lastModified))

        val read = if (item.file.canRead()) "Đọc: Có" else "Đọc: Không"
        val write = if (item.file.canWrite()) "Ghi: Có" else "Ghi: Không"
        binding.tvPermissions.text = "$read · $write"

        // Đọc metadata chi tiết (Độ phân giải, Thiết bị, Vị trí GPS, Thời lượng)
        when (item.fileType) {
            FileType.IMAGE -> extractImageMetadata(item.file, binding)
            FileType.VIDEO -> extractVideoMetadata(item.file, binding)
            FileType.AUDIO -> extractAudioMetadata(item.file, binding)
            else -> { /* Các loại khác không có metadata media */ }
        }

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle("Chi tiết thuộc tính")
            .setView(binding.root)
            .setPositiveButton("Đóng", null)
            .create()
    }

    private fun extractImageMetadata(file: File, binding: DialogFilePropertiesBinding) {
        try {
            val exif = ExifInterface(file.absolutePath)

            // 1. Độ phân giải
            val width = exif.getAttribute(ExifInterface.TAG_IMAGE_WIDTH)
            val length = exif.getAttribute(ExifInterface.TAG_IMAGE_LENGTH)
            if (!width.isNullOrEmpty() && !length.isNullOrEmpty() && width != "0" && length != "0") {
                binding.rowResolution.visibility = View.VISIBLE
                binding.divResolution.visibility = View.VISIBLE
                binding.tvResolution.text = "${width} × ${length}"
            }

            // 2. Thiết bị chụp
            val make = exif.getAttribute(ExifInterface.TAG_MAKE) ?: ""
            val model = exif.getAttribute(ExifInterface.TAG_MODEL) ?: ""
            val camera = "$make $model".trim()
            if (camera.isNotEmpty()) {
                binding.rowCamera.visibility = View.VISIBLE
                binding.divCamera.visibility = View.VISIBLE
                binding.tvCamera.text = camera
            }

            // 3. Vị trí GPS
            val latLong = FloatArray(2)
            if (exif.getLatLong(latLong)) {
                val lat = latLong[0].toDouble()
                val lon = latLong[1].toDouble()
                val locText = "${"%.6f".format(Locale.US, lat)}, ${"%.6f".format(Locale.US, lon)}"
                binding.rowLocation.visibility = View.VISIBLE
                binding.divLocation.visibility = View.VISIBLE
                binding.tvLocation.text = locText

                // Bấm vào vị trí để mở Google Maps / Bản đồ
                binding.rowLocation.setOnClickListener {
                    try {
                        val gmmIntentUri = Uri.parse("geo:$lat,$lon?q=$lat,$lon(Vị trí chụp)")
                        val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
                        startActivity(mapIntent)
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "Không tìm thấy ứng dụng bản đồ", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun extractVideoMetadata(file: File, binding: DialogFilePropertiesBinding) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)

            // 1. Độ phân giải
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            if (!width.isNullOrEmpty() && !height.isNullOrEmpty()) {
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                val isRotated = rotation == "90" || rotation == "270"
                val res = if (isRotated) "${height} × ${width}" else "${width} × ${height}"
                binding.rowResolution.visibility = View.VISIBLE
                binding.divResolution.visibility = View.VISIBLE
                binding.tvResolution.text = res
            }

            // 2. Thời lượng
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (durationMs > 0) {
                binding.rowDuration.visibility = View.VISIBLE
                binding.divDuration.visibility = View.VISIBLE
                binding.tvDuration.text = formatDuration(durationMs)
            }

            // 3. Vị trí GPS (ISO 6709 string: ví dụ "+10.762622+106.660172/")
            val locationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)
            if (!locationStr.isNullOrEmpty()) {
                val parsed = parseIso6709(locationStr)
                if (parsed != null) {
                    val (lat, lon) = parsed
                    val locText = "${"%.6f".format(Locale.US, lat)}, ${"%.6f".format(Locale.US, lon)}"
                    binding.rowLocation.visibility = View.VISIBLE
                    binding.divLocation.visibility = View.VISIBLE
                    binding.tvLocation.text = locText

                    binding.rowLocation.setOnClickListener {
                        try {
                            val gmmIntentUri = Uri.parse("geo:$lat,$lon?q=$lat,$lon(Vị trí video)")
                            val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
                            startActivity(mapIntent)
                        } catch (e: Exception) {
                            Toast.makeText(requireContext(), "Không tìm thấy ứng dụng bản đồ", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { retriever.release() } catch (e: Exception) {}
        }
    }

    private fun extractAudioMetadata(file: File, binding: DialogFilePropertiesBinding) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (durationMs > 0) {
                binding.rowDuration.visibility = View.VISIBLE
                binding.divDuration.visibility = View.VISIBLE
                binding.tvDuration.text = formatDuration(durationMs)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { retriever.release() } catch (e: Exception) {}
        }
    }

    private fun parseIso6709(loc: String): Pair<Double, Double>? {
        return try {
            // Định dạng phổ biến: +10.7626+106.6601/ hoặc +10.7626-106.6601/
            val cleaned = loc.trimEnd('/')
            val matcher = Regex("([+-]\\d+\\.?\\d*)([+-]\\d+\\.?\\d*)").find(cleaned)
            if (matcher != null && matcher.groupValues.size >= 3) {
                val lat = matcher.groupValues[1].toDouble()
                val lon = matcher.groupValues[2].toDouble()
                Pair(lat, lon)
            } else null
        } catch (e: Exception) { null }
    }

    private fun formatDuration(ms: Long): String {
        val seconds = (ms / 1000) % 60
        val minutes = (ms / (1000 * 60)) % 60
        val hours = (ms / (1000 * 60 * 60))
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    private fun toDirSize(item: FileItem): String {
        val count = item.file.listFiles()?.size ?: 0
        return "$count mục"
    }
}
