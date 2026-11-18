package ru.simonenkov.yandexdisksync.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sync_logs",
    indices = [Index("createdAt"), Index("runId")]
)
data class SyncLogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: String,
    val createdAt: Long,
    val level: String,
    val message: String
)
