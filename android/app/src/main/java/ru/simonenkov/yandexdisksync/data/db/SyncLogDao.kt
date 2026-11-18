package ru.simonenkov.yandexdisksync.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface SyncLogDao {
    @Insert
    suspend fun insert(entry: SyncLogEntry)

    @Query("SELECT * FROM sync_logs ORDER BY createdAt DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<SyncLogEntry>

    @Query("SELECT COUNT(*) FROM sync_logs")
    suspend fun count(): Int

    @Query(
        "DELETE FROM sync_logs WHERE runId NOT IN (" +
            "SELECT runId FROM sync_logs ORDER BY createdAt DESC, id DESC LIMIT :keepRuns" +
            ")"
    )
    suspend fun trimToRuns(keepRuns: Int)

    @Query("DELETE FROM sync_logs")
    suspend fun clear()
}
