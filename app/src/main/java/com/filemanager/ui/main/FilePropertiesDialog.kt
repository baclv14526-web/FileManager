package com.filemanager.ui.main

import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.filemanager.data.model.FileItem
import com.filemanager.databinding.DialogFilePropertiesBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
        val item = FileItem(java.io.File(path))
        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())

        binding.tvName.text = item.name
        binding.tvPath.text = item.path
        binding.tvType.text = if (item.isDirectory) "Thư mục" else "${item.extension.uppercase()} File"
        binding.tvSize.text = if (item.isDirectory) toDirSize(item) else item.formattedSize()
        binding.tvDate.text = sdf.format(Date(item.lastModified))
        binding.tvReadable.text = if (item.file.canRead()) "Có" else "Không"
        binding.tvWritable.text = if (item.file.canWrite()) "Có" else "Không"

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle("Thuộc tính")
            .setView(binding.root)
            .setPositiveButton("Đóng", null)
            .create()
    }

    private fun toDirSize(item: FileItem): String {
        val count = item.file.listFiles()?.size ?: 0
        return "$count mục"
    }
}
