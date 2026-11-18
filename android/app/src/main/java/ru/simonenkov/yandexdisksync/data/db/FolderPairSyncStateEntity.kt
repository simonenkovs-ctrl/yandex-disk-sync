package ru.simonenkov.yandexdisksync.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "folder_pair_sync_states")
data class FolderPairSyncStateEntity(
    @PrimaryKey val pairId: String,
    val baselineSynced: Boolean = false
)
