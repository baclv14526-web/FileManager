package com.filemanager.utils

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.io.File

object FolderHistoryManager {
    private const val PREF_NAME = "fm_folder_history"
    private const val KEY_VISITS = "folder_visit_counts"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Ghi nhận 1 lần người dùng truy cập vào thư mục folderPath
     */
    fun recordFolderVisit(context: Context, folderPath: String) {
        if (folderPath.isBlank()) return
        val file = File(folderPath)
        if (!file.exists() || !file.isDirectory) return

        try {
            val prefs = getPrefs(context)
            val jsonStr = prefs.getString(KEY_VISITS, "{}") ?: "{}"
            val jsonObj = JSONObject(jsonStr)

            val currentCount = jsonObj.optInt(folderPath, 0)
            jsonObj.put(folderPath, currentCount + 1)

            prefs.edit().putString(KEY_VISITS, jsonObj.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Lấy danh sách tối đa [limit] (mặc định 3) thư mục được truy cập nhiều nhất (còn tồn tại).
     * Trả về danh sách Pair(FolderName, FolderAbsolutePath).
     */
    fun getTopVisitedFolders(context: Context, limit: Int = 3): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, Int>>() // Pair(path, count)
        try {
            val prefs = getPrefs(context)
            val jsonStr = prefs.getString(KEY_VISITS, "{}") ?: "{}"
            val jsonObj = JSONObject(jsonStr)

            val keys = jsonObj.keys()
            while (keys.hasNext()) {
                val path = keys.next()
                val count = jsonObj.optInt(path, 0)
                if (count > 0 && File(path).let { it.exists() && it.isDirectory }) {
                    list.add(path to count)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Sắp xếp giảm dần theo lượt truy cập
        list.sortByDescending { it.second }

        val topList = list.take(limit).map { (path, _) ->
            val name = File(path).name.ifBlank { path }
            name to path
        }

        return topList
    }
}
