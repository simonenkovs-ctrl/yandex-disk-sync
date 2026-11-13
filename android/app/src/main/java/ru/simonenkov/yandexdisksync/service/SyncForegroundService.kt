package ru.simonenkov.yandexdisksync.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import ru.simonenkov.yandexdisksync.MainActivity
import ru.simonenkov.yandexdisksync.R
import ru.simonenkov.yandexdisksync.YandexDiskApp
import ru.simonenkov.yandexdisksync.engine.SyncProgress

class SyncForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var syncJob: Job? = null
    private var progressCollectorJob: Job? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    companion object {
        const val CHANNEL_ID = "sync_foreground_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_SYNC = "ru.simonenkov.yandexdisksync.START_SYNC"
        const val ACTION_STOP_SYNC = "ru.simonenkov.yandexdisksync.STOP_SYNC"
        const val EXTRA_FOLDER_ID = "extra_folder_id"

        fun startSync(context: Context, folderId: String? = null) {
            val intent = Intent(context, SyncForegroundService::class.java).apply {
                action = ACTION_START_SYNC
                if (folderId != null) {
                    putExtra(EXTRA_FOLDER_ID, folderId)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopSync(context: Context) {
            val intent = Intent(context, SyncForegroundService::class.java).apply {
                action = ACTION_STOP_SYNC
            }
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireLocks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SYNC -> {
                val app = application as YandexDiskApp
                app.syncEngine.cancelSync()
                cleanupAndStop()
            }
            ACTION_START_SYNC -> {
                startForeground(NOTIFICATION_ID, buildNotification("Синхронизация файлов..."))
                val folderId = intent.getStringExtra(EXTRA_FOLDER_ID)
                startSyncProcess(folderId)
            }
        }
        return START_NOT_STICKY
    }

    private fun acquireLocks() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (powerManager != null && (wakeLock == null || !wakeLock!!.isHeld)) {
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "YandexDiskSync:WakeLock"
                ).apply {
                    setReferenceCounted(false)
                    acquire(4 * 60 * 60 * 1000L) // Максимум 4 часа работы
                }
            }
        } catch (e: Exception) {
            // Ignore lock error
        }

        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiManager != null && (wifiLock == null || !wifiLock!!.isHeld)) {
                @Suppress("DEPRECATION")
                wifiLock = wifiManager.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "YandexDiskSync:WifiLock"
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (e: Exception) {
            // Ignore wifi lock error
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            wakeLock = null
        } catch (e: Exception) {
            // Ignore
        }

        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
            wifiLock = null
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun startSyncProcess(targetFolderId: String?) {
        val app = application as YandexDiskApp

        // Отслеживаем прогресс для обновления текста уведомления в шторке
        progressCollectorJob?.cancel()
        progressCollectorJob = serviceScope.launch {
            app.syncEngine.progressFlow.collect { progress ->
                if (progress.isRunning) {
                    val notifManager = getSystemService(NotificationManager::class.java)
                    notifManager.notify(NOTIFICATION_ID, buildProgressNotification(progress))
                }
            }
        }

        syncJob?.cancel()
        syncJob = serviceScope.launch {
            try {
                if (targetFolderId != null) {
                    val pair = app.preferences.getFolders().find { it.id == targetFolderId }
                    if (pair != null) {
                        app.syncEngine.syncSingleFolder(pair)
                    }
                } else {
                    app.syncEngine.syncAll()
                }
            } finally {
                cleanupAndStop()
            }
        }
    }

    private fun cleanupAndStop() {
        progressCollectorJob?.cancel()
        syncJob?.cancel()
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        releaseLocks()
    }

    private fun buildNotification(text: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, SyncForegroundService::class.java).apply {
            action = ACTION_STOP_SYNC
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Яндекс Диск Синхронизатор")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Остановить", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun buildProgressNotification(progress: SyncProgress): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, SyncForegroundService::class.java).apply {
            action = ACTION_STOP_SYNC
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val pct = (progress.percent * 100).toInt()
        val contentText = if (progress.currentFileName.isNotBlank()) {
            "${progress.actionText}: ${progress.currentFileName}"
        } else {
            progress.actionText
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Синхронизация Яндекс Диска (${pct}%)")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setProgress(100, pct, progress.totalFiles == 0 && progress.totalBytes == 0L)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Остановить", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Фоновая синхронизация",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Отображает статус текущей синхронизации файлов"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
