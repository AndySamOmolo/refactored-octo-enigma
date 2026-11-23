package com.edgeledger.app.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.edgeledger.app.EdgeLedgerApp
import com.edgeledger.app.MainActivity
import kotlinx.coroutines.CancellationException
import java.time.LocalTime
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * WorkManager periodic background worker that refreshes free sports datasets
 * directly on-device once every 7 days when network is available (DT-07),
 * prunes expired Room/disk caches, and dispatches optional quiet-hours-aware +EV alerts.
 */
class WeeklySyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? EdgeLedgerApp ?: return Result.success()
        app.prefsRepo.awaitLoaded()
        val prefs = app.prefsRepo.prefs.value
        if (!prefs.autoSyncWeekly) return Result.success()
        return try {
            val ok = app.syncRepo.runOnDeviceWeeklySyncWithStatus()
            if (ok) {
                app.syncRepo.pruneExpiredCaches()
                maybeNotifyValuePicks(app, prefs)
                Result.success()
            } else if (runAttemptCount >= 3) {
                Result.failure()
            } else {
                Result.retry()
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            app.recordDiagnostic("WeeklySyncWorker", e.message ?: e::class.simpleName.orEmpty())
            if (runAttemptCount >= 3) Result.failure() else Result.retry()
        }
    }

    private fun maybeNotifyValuePicks(app: EdgeLedgerApp, prefs: AppUserPrefs) {
        if (!prefs.alertsEnabled) return
        if (isWithinQuietHours(LocalTime.now().hour, prefs.quietHoursStart, prefs.quietHoursEnd)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val threshold = prefs.minEvPct.coerceAtLeast(1.0)
        val nowMs = System.currentTimeMillis()
        val topValue = app.syncRepo.snapshot.value.fixtures
            .filter { fx ->
                val edgeOk = (fx.bestValueEdgePct ?: 0.0) >= threshold
                val koMs = runCatching { java.time.OffsetDateTime.parse(fx.kickoffIso.trim()).toInstant().toEpochMilli() }
                    .getOrElse { runCatching { java.time.Instant.parse(fx.kickoffIso.trim()).toEpochMilli() }.getOrNull() }
                edgeOk && (koMs == null || koMs > nowMs)
            }
            .sortedByDescending { it.bestValueEdgePct ?: 0.0 }
        val lead = topValue.firstOrNull() ?: return

        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channel = NotificationChannel(
            ALERTS_CHANNEL_ID,
            "EdgeLedger Value Alerts",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "High-value +EV picks and odds updates discovered during background sync."
        }
        nm.createNotificationChannel(channel)

        val openIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_MATCH_ID, lead.id)
        }
        val pi = PendingIntent.getActivity(
            applicationContext,
            ALERTS_NOTIFICATION_ID,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val edgeStr = String.format(Locale.US, "+%.1f%% EV", lead.bestValueEdgePct ?: 0.0)
        val pickLabel = lead.bestValueLabel ?: lead.markets.topPicks.firstOrNull()?.label ?: "Top Pick"
        val extraCount = (topValue.size - 1).coerceAtLeast(0)
        val body = if (extraCount > 0) {
            "${lead.homeTeam} vs ${lead.awayTeam}: $pickLabel ($edgeStr) + $extraCount more"
        } else {
            "${lead.homeTeam} vs ${lead.awayTeam}: $pickLabel ($edgeStr)"
        }

        val notif = NotificationCompat.Builder(applicationContext, ALERTS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("EdgeLedger: ${topValue.size} +EV Pick${if (topValue.size == 1) "" else "s"} Found")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        runCatching { nm.notify(ALERTS_NOTIFICATION_ID, notif) }
    }

    companion object {
        private const val WORK_NAME = "edgeledger_weekly_on_device_sync"
        private const val ALERTS_CHANNEL_ID = "edgeledger_value_alerts"
        private const val ALERTS_NOTIFICATION_ID = 8701

        internal fun isWithinQuietHours(currentHour: Int, startHour: Int, endHour: Int): Boolean {
            if (startHour == endHour) return false
            return if (startHour < endHour) {
                currentHour in startHour until endHour
            } else {
                currentHour >= startHour || currentHour < endHour
            }
        }

        fun scheduleWeekly(
            context: Context,
            enabled: Boolean = true,
            wifiOnly: Boolean = false,
            replaceExisting: Boolean = true,
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
