package ru.simonenkov.yandexdisksync.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ru.simonenkov.yandexdisksync.MainActivity
import ru.simonenkov.yandexdisksync.R
import ru.simonenkov.yandexdisksync.YandexDiskApp

class SyncForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var syncJob: Job? = null

    companion object {
        const val CHANNEL_ID = "sync_foreground_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_SYNC = "ru.simonenkov.yandexdisksync.START_SYNC"
        const val ACTION_STOP_SYNC = "ru.simonenkov.yandexdisksync.STOP_SYNC"

        fun startSync(context: Context) {
            val intent = Intent(context, SyncForegroundService::class.java).apply {
                action = ACTION_START_SYNC
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SYNC -> {
                val app = application as YandexDiskApp
                app.syncEngine.cancelSync()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_START_SYNC -> {
                startForeground(NOTIFICATION_ID, buildNotification("Синхронизация файлов..."))
                startSyncProcess()
            }
        }
        return START_NOT_STICKY
    }

    private fun startSyncProcess() {
        val app = application as YandexDiskApp
        syncJob?.cancel()
        syncJob = serviceScope.launch {
            app.syncEngine.syncAll()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
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

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Фоновая синхронизация",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Отображает статус текущей синхронизации файлов"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
