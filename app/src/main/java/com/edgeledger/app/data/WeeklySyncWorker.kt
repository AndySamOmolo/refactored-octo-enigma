package com.edgeledger.app.data

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.edgeledger.app.EdgeLedgerApp
import com.edgeledger.app.widget.TopPicksWidget
import kotlinx.coroutines.CancellationException
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
        app.prefsRepo.awaitLoaded()
        if (!app.prefsRepo.prefs.value.autoSyncWeekly) return Result.success()
        return try {
            val ok = app.syncRepo.runOnDeviceWeeklySyncWithStatus()
            runCatching { TopPicksWidget().updateAll(applicationContext) }
            if (ok) Result.success()
            else if (runAttemptCount >= 3) Result.failure()
            else Result.retry()
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            if (runAttemptCount >= 3) Result.failure() else Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "edgeledger_weekly_on_device_sync"

        fun scheduleWeekly(
            context: Context,
            enabled: Boolean = true,
            wifiOnly: Boolean = false,
        ) {
            val wm = WorkManager.getInstance(context.applicationContext)
            if (!enabled) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                .build()
            val req = PeriodicWorkRequestBuilder<WeeklySyncWorker>(7, TimeUnit.DAYS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
            wm.enqueueUniquePeriodicWork(
                WORK_NAME,
                if (replaceExisting) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
                req,
            )
        }
    }
}
