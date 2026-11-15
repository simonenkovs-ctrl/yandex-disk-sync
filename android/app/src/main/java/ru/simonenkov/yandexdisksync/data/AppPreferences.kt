package ru.simonenkov.yandexdisksync.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AppPreferences(private val context: Context) {

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
        set(value) {
            prefs.edit().putString(KEY_TOKEN, value.trim()).apply()
            BackupManager.saveBackup(context, this)
        }

    var conflictResolution: String
        get() = prefs.getString(KEY_CONFLICT, "keep_both").orEmpty()
        set(value) = prefs.edit().putString(KEY_CONFLICT, value).apply()

    var syncDeletions: Boolean
        get() = prefs.getBoolean(KEY_SYNC_DELETIONS, true)
        set(value) {
            prefs.edit().putBoolean(KEY_SYNC_DELETIONS, value).apply()
            BackupManager.saveBackup(context, this)
        }

    var wifiOnly: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ONLY, false)
        set(value) {
            prefs.edit().putBoolean(KEY_WIFI_ONLY, value).apply()
            BackupManager.saveBackup(context, this)
        }

    var dailySyncEnabled: Boolean
        get() = prefs.getBoolean(KEY_DAILY_SYNC_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_DAILY_SYNC_ENABLED, value).apply()
            BackupManager.saveBackup(context, this)
        }

    var dailySyncHour: Int
        get() = prefs.getInt(KEY_DAILY_SYNC_HOUR, 3) // по умолчанию 03:00 ночи
        set(value) {
            prefs.edit().putInt(KEY_DAILY_SYNC_HOUR, value).apply()
            BackupManager.saveBackup(context, this)
        }

    var dailySyncMinute: Int
        get() = prefs.getInt(KEY_DAILY_SYNC_MINUTE, 0)
        set(value) {
            prefs.edit().putInt(KEY_DAILY_SYNC_MINUTE, value).apply()
            BackupManager.saveBackup(context, this)
        }

    var dailySyncRequiresCharging: Boolean
        get() = prefs.getBoolean(KEY_DAILY_SYNC_REQUIRES_CHARGING, false)
        set(value) {
            prefs.edit().putBoolean(KEY_DAILY_SYNC_REQUIRES_CHARGING, value).apply()
            BackupManager.saveBackup(context, this)
        }

    fun getFolders(): List<SyncFolderPair> {
        return loadFolders()
    }

    fun saveFolders(folders: List<SyncFolderPair>) {
        val json = gson.toJson(folders)
        prefs.edit().putString(KEY_FOLDERS, json).apply()
        _foldersFlow.value = folders
        BackupManager.saveBackup(context, this)
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
        private const val KEY_DAILY_SYNC_ENABLED = "daily_sync_enabled"
        private const val KEY_DAILY_SYNC_HOUR = "daily_sync_hour"
        private const val KEY_DAILY_SYNC_MINUTE = "daily_sync_minute"
        private const val KEY_DAILY_SYNC_REQUIRES_CHARGING = "daily_sync_requires_charging"
    }
}

