package ru.simonenkov.yandexdisksync.data

import android.content.Context
import android.os.Environment
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.File

data class ConfigBackup(
    @SerializedName("token") val token: String = "",
    @SerializedName("folders") val folders: List<SyncFolderPair> = emptyList(),
    @SerializedName("syncDeletions") val syncDeletions: Boolean = true,
    @SerializedName("wifiOnly") val wifiOnly: Boolean = false,
    @SerializedName("dailySyncEnabled") val dailySyncEnabled: Boolean = false,
    @SerializedName("dailySyncHour") val dailySyncHour: Int = 3,
    @SerializedName("dailySyncMinute") val dailySyncMinute: Int = 0,
    @SerializedName("dailySyncRequiresCharging") val dailySyncRequiresCharging: Boolean = false,
    @SerializedName("backupTimestamp") val backupTimestamp: Long = System.currentTimeMillis()
)

object BackupManager {
    private const val TAG = "BackupManager"
    private const val BACKUP_FILENAME = "yandex_sync_backup.json"
    private val gson = Gson()

    private fun getBackupFiles(context: Context): List<File> {
        val files = mutableListOf<File>()

        // 1. Внешняя папка приложения: /sdcard/Android/data/ru.simonenkov.yandexdisksync/files
        context.getExternalFilesDir(null)?.let { dir ->
            files.add(File(dir, BACKUP_FILENAME))
        }

        // 2. В общую папку Documents или Download, если доступно
        try {
            val docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            if (docs != null && (docs.exists() || docs.mkdirs())) {
                files.add(File(docs, "yandex_disk_sync_config.json"))
            }
        } catch (_: Exception) {}

        // 3. Внутренняя память (fallback)
        files.add(File(context.filesDir, BACKUP_FILENAME))

        return files
    }

    fun saveBackup(context: Context, preferences: AppPreferences) {
        try {
            val backup = ConfigBackup(
                token = preferences.oauthToken,
                folders = preferences.getFolders(),
                syncDeletions = preferences.syncDeletions,
                wifiOnly = preferences.wifiOnly,
                dailySyncEnabled = preferences.dailySyncEnabled,
                dailySyncHour = preferences.dailySyncHour,
                dailySyncMinute = preferences.dailySyncMinute,
                dailySyncRequiresCharging = preferences.dailySyncRequiresCharging,
                backupTimestamp = System.currentTimeMillis()
            )

            val json = gson.toJson(backup)
            for (file in getBackupFiles(context)) {
                try {
                    file.parentFile?.mkdirs()
                    file.writeText(json)
                    Log.i(TAG, "Резервная копия сохранена в: ${file.absolutePath}")
                } catch (e: Exception) {
                    Log.w(TAG, "Не удалось записать бэкап в ${file.absolutePath}: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка сохранения резервной копии: ${e.message}", e)
        }
    }

    fun restoreIfNeeded(context: Context, preferences: AppPreferences): Boolean {
        // Если уже есть токен или папки, восстановление не требуется
        if (preferences.oauthToken.isNotBlank() || preferences.getFolders().isNotEmpty()) {
            return false
        }

        for (file in getBackupFiles(context)) {
            if (file.exists() && file.length() > 0) {
                try {
                    val json = file.readText()
                    val backup = gson.fromJson(json, ConfigBackup::class.java)
                    if (backup != null && (backup.token.isNotBlank() || backup.folders.isNotEmpty())) {
                        Log.i(TAG, "Найдена резервная копия: ${file.absolutePath}. Восстанавливаем...")
                        if (backup.token.isNotBlank()) preferences.oauthToken = backup.token
                        if (backup.folders.isNotEmpty()) preferences.saveFolders(backup.folders)
                        preferences.syncDeletions = backup.syncDeletions
                        preferences.wifiOnly = backup.wifiOnly
                        preferences.dailySyncEnabled = backup.dailySyncEnabled
                        preferences.dailySyncHour = backup.dailySyncHour
                        preferences.dailySyncMinute = backup.dailySyncMinute
                        preferences.dailySyncRequiresCharging = backup.dailySyncRequiresCharging
                        return true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Ошибка чтения бэкапа из ${file.absolutePath}: ${e.message}")
                }
            }
        }
        return false
    }
}
