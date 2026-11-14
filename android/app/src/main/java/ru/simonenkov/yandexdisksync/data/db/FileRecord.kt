package ru.simonenkov.yandexdisksync.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "file_records")
data class FileRecord(
    @PrimaryKey val id: String, // pairId + ":" + relPath
    val pairId: String,
    val relPath: String,
    val localMd5: String,
    val remoteMd5: String,
    val localMtime: Long,
    val remoteMtime: Long,
    val size: Long,
    val lastSyncTime: Long,
    val compressionSig: String? = null
)

