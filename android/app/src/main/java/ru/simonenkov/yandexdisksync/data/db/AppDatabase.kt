package ru.simonenkov.yandexdisksync.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [FileRecord::class, SyncLogEntry::class, FolderPairSyncStateEntity::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun fileRecordDao(): FileRecordDao
    abstract fun syncLogDao(): SyncLogDao
    abstract fun folderPairSyncStateDao(): FolderPairSyncStateDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE file_records ADD COLUMN compressionSig TEXT")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_logs` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`runId` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`level` TEXT NOT NULL, " +
                        "`message` TEXT NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sync_logs_createdAt` " +
                        "ON `sync_logs` (`createdAt`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sync_logs_runId` " +
                        "ON `sync_logs` (`runId`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `folder_pair_sync_states` (" +
                        "`pairId` TEXT NOT NULL, " +
                        "`baselineSynced` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`pairId`))"
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO `folder_pair_sync_states` (`pairId`, `baselineSynced`) " +
                        "SELECT DISTINCT `pairId`, 1 FROM `file_records`"
                )
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "yandex_disk_sync.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
            }
        }
    }
}
