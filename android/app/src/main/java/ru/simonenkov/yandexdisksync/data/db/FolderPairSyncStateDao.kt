package ru.simonenkov.yandexdisksync.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface FolderPairSyncStateDao {
    @Query("SELECT * FROM folder_pair_sync_states WHERE pairId = :pairId LIMIT 1")
    suspend fun get(pairId: String): FolderPairSyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: FolderPairSyncStateEntity)

    @Query("DELETE FROM folder_pair_sync_states WHERE pairId = :pairId")
    suspend fun delete(pairId: String)

    @Query("DELETE FROM folder_pair_sync_states")
    suspend fun clear()
}
