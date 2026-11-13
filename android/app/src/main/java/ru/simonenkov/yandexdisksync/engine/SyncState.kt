package ru.simonenkov.yandexdisksync.engine

data class SyncLogItem(
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel = LogLevel.INFO,
    val message: String = ""
)

enum class LogLevel {
    INFO, SUCCESS, WARNING, ERROR
}

data class SyncProgress(
    val isRunning: Boolean = false,
    val currentFolderName: String = "",
    val currentFileName: String = "",
    val actionText: String = "",
    val currentBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val processedFiles: Int = 0,
    val totalFiles: Int = 0
) {
    val percent: Float
        get() = when {
            totalBytes > 0 -> (currentBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            totalFiles > 0 -> (processedFiles.toFloat() / totalFiles.toFloat()).coerceIn(0f, 1f)
            else -> 0f
        }
}
