package com.filemanager.ui.main

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.filemanager.data.model.FileItem
import com.filemanager.data.model.SortType
import com.filemanager.data.repository.FileRepository
import com.filemanager.databinding.ActivityTrashBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import com.filemanager.utils.LoadingHelper
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class TrashActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTrashBinding
    private lateinit var repository: FileRepository
    private lateinit var adapter: FileListAdapter
    private val selectedPaths = mutableSetOf<String>()
    private var trashItems = listOf<FileItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTrashBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Thùng rác"

        repository = FileRepository(this)

        adapter = FileListAdapter(
            onItemClick = { item ->
                if (selectedPaths.isNotEmpty()) toggleSelect(item)
            },
            onItemLongClick = { item -> toggleSelect(item) },
            onSelectionChange = { item -> toggleSelect(item) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.btnRestoreSelected.setOnClickListener { restoreSelected() }
        binding.btnDeleteSelected.setOnClickListener { deleteSelectedPermanently() }
        binding.btnEmptyTrash.setOnClickListener { confirmEmptyTrash() }

        loadTrashFiles()
    }

    private fun loadTrashFiles() {
        lifecycleScope.launch {
            val trashDir = File(getExternalFilesDir(null), ".trash")
            trashItems = withContext(Dispatchers.IO) {
                if (!trashDir.exists()) emptyList()
                else trashDir.listFiles()?.map { FileItem(it) } ?: emptyList()
            }
            adapter.submitList(trashItems)
            binding.emptyView.visibility = if (trashItems.isEmpty()) View.VISIBLE else View.GONE
            binding.tvCount.text = "${trashItems.size} mục trong thùng rác"
        }
    }

    private fun toggleSelect(item: FileItem) {
        if (item.path in selectedPaths) selectedPaths.remove(item.path)
        else selectedPaths.add(item.path)
        val updated = trashItems.map { it.copy(isSelected = it.path in selectedPaths) }
        adapter.submitList(updated)
        val count = selectedPaths.size
        binding.bottomBar.visibility = if (count > 0) View.VISIBLE else View.GONE
        supportActionBar?.title = if (count > 0) "$count đã chọn" else "Thùng rác"
    }

    private fun restoreSelected() {
        val toRestore = trashItems.filter { it.path in selectedPaths }
        LoadingHelper.showOverlay(this, "Đang khôi phục...", "${toRestore.size} mục")
        lifecycleScope.launch(Dispatchers.IO) {
            toRestore.forEach { item ->
                // Restore: remove the timestamp prefix and move back to original location
                // Here we just move to Downloads as fallback
                val origName = item.name.substringAfter("_")
                val dest = File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS
                    ), origName
                )
                item.file.renameTo(dest)
            }
            withContext(Dispatchers.Main) {
                LoadingHelper.hideOverlay(this@TrashActivity)
                selectedPaths.clear()
                Toast.makeText(this@TrashActivity, "Đã khôi phục ${toRestore.size} mục", Toast.LENGTH_SHORT).show()
                loadTrashFiles()
            }
        }
    }

    private fun deleteSelectedPermanently() {
        val toDelete = trashItems.filter { it.path in selectedPaths }
        MaterialAlertDialogBuilder(this)
            .setTitle("Xóa vĩnh viễn")
            .setMessage("Xóa vĩnh viễn ${toDelete.size} mục? Không thể hoàn tác.")
            .setPositiveButton("Xóa") { _, _ ->
                LoadingHelper.showOverlay(this, "Đang xóa vĩnh viễn...")
                lifecycleScope.launch(Dispatchers.IO) {
                    toDelete.forEach { it.file.delete() }
                    withContext(Dispatchers.Main) {
                        LoadingHelper.hideOverlay(this@TrashActivity)
                        selectedPaths.clear()
                        Toast.makeText(this@TrashActivity, "Đã xóa vĩnh viễn", Toast.LENGTH_SHORT).show()
                        loadTrashFiles()
                    }
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun confirmEmptyTrash() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Dọn thùng rác")
            .setMessage("Xóa vĩnh viễn tất cả ${trashItems.size} mục? Không thể hoàn tác.")
            .setPositiveButton("Dọn sạch") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    trashItems.forEach { it.file.delete() }
                    withContext(Dispatchers.Main) {
                        selectedPaths.clear()
                        Toast.makeText(this@TrashActivity, "Đã dọn thùng rác", Toast.LENGTH_SHORT).show()
                        loadTrashFiles()
                    }
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { onBackPressed(); return true }
        return super.onOptionsItemSelected(item)
    }
}
