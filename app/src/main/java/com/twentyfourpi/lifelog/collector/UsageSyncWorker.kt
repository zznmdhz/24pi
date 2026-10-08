package com.twentyfourpi.lifelog.collector

import android.content.Context
import androidx.work.*
import com.twentyfourpi.lifelog.LifeLogApp
import com.twentyfourpi.lifelog.data.SourceId
import com.twentyfourpi.lifelog.debug.DiagnosticLog
import java.util.concurrent.TimeUnit

class UsageSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as LifeLogApp
        if (!app.settings.collectionEnabled) return Result.success()
        return runCatching {
            UsageCollector(app, app.repository, app.settings).collect()
            CollectionGapDetector(app.databaseProvider, app.settings).backfillRecentPointGaps()
            val heartbeatAt = app.settings.getLong(CollectionGapDetector.HEARTBEAT_KEY)
            if (app.shouldRunCollectorService(app.settings) && System.currentTimeMillis() - heartbeatAt > STALE_COLLECTOR_MS) {
                DiagnosticLog.event(
                    "service",
                    "collector_recovery_requested",
                    mapOf("heartbeatAgeMs" to (System.currentTimeMillis() - heartbeatAt)),
                )
                runCatching { LifeLogCollectorService.start(app) }
                    .onFailure { DiagnosticLog.error("service", "background_collector_start_rejected", it) }
            }
            if (app.settings.sourceEnabled(SourceId.NOTIFICATIONS) && app.hasNotificationListenerAccess() &&
                !MetadataNotificationListener.isConnected()
            ) {
                MetadataNotificationListener.markDisconnected(app.settings)
                MetadataNotificationListener.requestReconnect(app)
                DiagnosticLog.event("notifications", "worker_listener_recovery_requested")
            }
            Result.success()
        }.getOrElse {
            DiagnosticLog.error("service", "collector_recovery_failed", it)
            Result.retry()
        }
    }

    companion object {
        private const val NAME = "periodic-usage-sync"
        private const val RECOVERY_NAME = "collector-recovery"
        private const val STALE_COLLECTOR_MS = 5 * 60_000L
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UsageSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(false).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
        fun enqueueRecovery(context: Context) {
            val request = OneTimeWorkRequestBuilder<UsageSyncWorker>()
                .setInitialDelay(1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(RECOVERY_NAME, ExistingWorkPolicy.REPLACE, request)
        }
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
            WorkManager.getInstance(context).cancelUniqueWork(RECOVERY_NAME)
        }
    }
}
