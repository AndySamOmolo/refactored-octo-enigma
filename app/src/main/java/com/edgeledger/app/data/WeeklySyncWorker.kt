package com.edgeledger.app.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.edgeledger.app.EdgeLedgerApp
import java.util.concurrent.TimeUnit

/**
 * WorkManager periodic background worker that refreshes free sports datasets
 * directly on-device once every 7 days when network is available (DT-07).
 */
class WeeklySyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? EdgeLedgerApp ?: return Result.success()
        if (!app.prefsRepo.prefs.value.autoSyncWeekly) return Result.success()
        return runCatching {
            app.syncRepo.runOnDeviceWeeklySync()
            Result.success()
        }.getOrElse { Result.retry() }
    }

    companion object {
        private const val WORK_NAME = "edgeledger_weekly_on_device_sync"

        fun scheduleWeekly(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val req = PeriodicWorkRequestBuilder<WeeklySyncWorker>(7, TimeUnit.DAYS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                req,
            )
        }
    }
}
