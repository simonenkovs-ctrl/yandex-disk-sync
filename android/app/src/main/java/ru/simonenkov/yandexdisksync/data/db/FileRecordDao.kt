package ru.simonenkov.yandexdisksync.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface FileRecordDao {
    @Query("SELECT * FROM file_records WHERE pairId = :pairId")
    suspend fun getRecordsForPair(pairId: String): List<FileRecord>

    @Query("SELECT * FROM file_records WHERE pairId = :pairId AND relPath = :relPath LIMIT 1")
    suspend fun getRecord(pairId: String, relPath: String): FileRecord?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(record: FileRecord)

    @Query("DELETE FROM file_records WHERE pairId = :pairId AND relPath = :relPath")
    suspend fun deleteRecord(pairId: String, relPath: String)

    @Query("DELETE FROM file_records WHERE pairId = :pairId")
    suspend fun deleteAllForPair(pairId: String)
}
