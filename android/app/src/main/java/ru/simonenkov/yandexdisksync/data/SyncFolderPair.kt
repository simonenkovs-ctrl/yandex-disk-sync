package ru.simonenkov.yandexdisksync.data

import java.util.UUID

enum class SyncMode(val title: String, val description: String) {
    TWO_WAY(
        "Двусторонняя",
        "Синхронизирует изменения в обе стороны: новые файлы с телефона летят в облако, новые из облака — на телефон"
    ),
    UPLOAD_ONLY(
        "Только бэкап в облако",
        "Выгружает новые и измененные файлы с телефона в облако. Локальные файлы никогда не удаляются и не перезаписываются из облака"
    ),
    DOWNLOAD_ONLY(
        "Только скачивание",
        "Скачивает файлы из облака на телефон, не изменяя файлы в облаке"
    )
}

data class SyncFolderPair(
    val id: String = UUID.randomUUID().toString(),
    val displayName: String = "",
    val localTreeUri: String = "",
    val localReadablePath: String = "",
    val remotePath: String = "",
    val mode: SyncMode = SyncMode.TWO_WAY,
    val compressPhotos: Boolean = false,
    val jpegQuality: Int = 82,
    val maxImageSide: Int = 2560,
    val enabled: Boolean = true,
    val lastSyncTime: Long = 0L,
    val lastSyncStatus: String = "Не выполнялась",
    val lastSyncFilesCount: Int = 0
) {
    val effectiveQuality: Int get() = if (jpegQuality in 50..95) jpegQuality else 82
}

