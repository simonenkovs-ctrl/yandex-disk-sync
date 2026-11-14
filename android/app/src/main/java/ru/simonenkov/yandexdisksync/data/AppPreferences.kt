package ru.simonenkov.yandexdisksync.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AppPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("yandex_disk_sync_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    private val _foldersFlow = MutableStateFlow<List<SyncFolderPair>>(emptyList())
    val foldersFlow: StateFlow<List<SyncFolderPair>> = _foldersFlow.asStateFlow()

    init {
        _foldersFlow.value = loadFolders()
    }

    var oauthToken: String
        get() = prefs.getString(KEY_TOKEN, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_TOKEN, value.trim()).apply()

    var conflictResolution: String
        get() = prefs.getString(KEY_CONFLICT, "keep_both").orEmpty()
        set(value) = prefs.edit().putString(KEY_CONFLICT, value).apply()

    var syncDeletions: Boolean
        get() = prefs.getBoolean(KEY_SYNC_DELETIONS, true)
        set(value) = prefs.edit().putBoolean(KEY_SYNC_DELETIONS, value).apply()

    var wifiOnly: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ONLY, false)
        set(value) = prefs.edit().putBoolean(KEY_WIFI_ONLY, value).apply()

    fun getFolders(): List<SyncFolderPair> {
        return loadFolders()
    }

    fun saveFolders(folders: List<SyncFolderPair>) {
        val json = gson.toJson(folders)
        prefs.edit().putString(KEY_FOLDERS, json).apply()
        _foldersFlow.value = folders
    }

    fun addOrUpdateFolder(folder: SyncFolderPair) {
        val current = loadFolders().toMutableList()
        val index = current.indexOfFirst { it.id == folder.id }
        if (index >= 0) {
            current[index] = folder
        } else {
            current.add(folder)
        }
        saveFolders(current)
    }

    fun removeFolder(folderId: String) {
        val current = loadFolders().filterNot { it.id == folderId }
        saveFolders(current)
    }

    fun updateFolderSyncResult(folderId: String, status: String, count: Int) {
        val current = loadFolders().map {
            if (it.id == folderId) {
                it.copy(
                    lastSyncTime = System.currentTimeMillis(),
                    lastSyncStatus = status,
                    lastSyncFilesCount = count
                )
            } else {
                it
            }
        }
        saveFolders(current)
    }

    private fun loadFolders(): List<SyncFolderPair> {
        val json = prefs.getString(KEY_FOLDERS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<SyncFolderPair>>() {}.type
            (gson.fromJson<List<SyncFolderPair>>(json, type) ?: emptyList()).map { if (it.jpegQuality in 50..95) it else it.copy(jpegQuality = 82) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    companion object {
        private const val KEY_TOKEN = "oauth_token"
        private const val KEY_FOLDERS = "folder_pairs_json"
        private const val KEY_CONFLICT = "conflict_resolution"
        private const val KEY_SYNC_DELETIONS = "sync_deletions"
        private const val KEY_WIFI_ONLY = "wifi_only"
    }
}

