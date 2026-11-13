package ru.simonenkov.yandexdisksync

import android.app.Application
import ru.simonenkov.yandexdisksync.data.AppPreferences
import ru.simonenkov.yandexdisksync.data.db.AppDatabase
import ru.simonenkov.yandexdisksync.engine.SyncEngine

class YandexDiskApp : Application() {

    lateinit var preferences: AppPreferences
        private set

    lateinit var database: AppDatabase
        private set

    lateinit var syncEngine: SyncEngine
        private set

    override fun onCreate() {
        super.onCreate()
        preferences = AppPreferences(this)
        database = AppDatabase.getInstance(this)
        syncEngine = SyncEngine(this, preferences, database)
    }
}
