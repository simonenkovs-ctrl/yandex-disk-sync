package ru.simonenkov.yandexdisksync.service

import android.content.Context
import android.util.Log
import androidx.work.*
import ru.simonenkov.yandexdisksync.YandexDiskApp
import ru.simonenkov.yandexdisksync.data.AppPreferences
import java.util.Calendar
import java.util.concurrent.TimeUnit

class DailySyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.i(TAG, "DailySyncWorker запущен по расписанию")
        val app = applicationContext as? YandexDiskApp ?: return Result.failure()
        val prefs = app.preferences

        if (!prefs.dailySyncEnabled) {
            Log.i(TAG, "Ежедневная автосинхронизация отключена в настройках")
            return Result.success()
        }

        if (prefs.oauthToken.isBlank()) {
            Log.w(TAG, "OAuth-токен не задан, пропуск автосинхронизации")
            return Result.failure()
        }

        try {
            // Запускаем фоновую службу синхронизации
            SyncForegroundService.startSync(applicationContext)
            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка запуска синхронизации через DailySyncWorker: ${e.message}", e)
            return Result.retry()
        }
    }

    companion object {
        private const val TAG = "DailySyncWorker"
        const val WORK_NAME = "daily_yandex_disk_sync"

        fun schedule(context: Context, preferences: AppPreferences) {
            val workManager = WorkManager.getInstance(context)

            if (!preferences.dailySyncEnabled) {
                workManager.cancelUniqueWork(WORK_NAME)
                Log.i(TAG, "Ежедневная автосинхронизация отменена")
                return
            }

            // Вычисляем задержку до целевого времени (например, 03:00)
            val now = Calendar.getInstance()
            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, preferences.dailySyncHour)
                set(Calendar.MINUTE, preferences.dailySyncMinute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            if (target.before(now)) {
                // Если время сегодня уже прошло, переносим на завтра
                target.add(Calendar.DAY_OF_YEAR, 1)
            }

            val initialDelayMillis = target.timeInMillis - now.timeInMillis

            // Ограничения
            val constraintsBuilder = Constraints.Builder()
                .setRequiredNetworkType(
                    if (preferences.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
                )

            if (preferences.dailySyncRequiresCharging) {
                constraintsBuilder.setRequiresCharging(true)
            }

            val constraints = constraintsBuilder.build()

            // Периодическая работа раз в 24 часа
            val dailyWorkRequest = PeriodicWorkRequestBuilder<DailySyncWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()

            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                dailyWorkRequest
            )

            Log.i(TAG, "Ежедневная автосинхронизация запланирована через ${initialDelayMillis / 1000 / 60} мин. на ${preferences.dailySyncHour}:${preferences.dailySyncMinute}")
        }
    }
}
