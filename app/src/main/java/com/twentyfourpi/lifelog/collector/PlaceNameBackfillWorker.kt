package com.twentyfourpi.lifelog.collector

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.twentyfourpi.lifelog.LifeLogApp
import com.twentyfourpi.lifelog.debug.DiagnosticLog
import java.util.concurrent.TimeUnit

/**
 * 空地址是可恢复状态。网络不可用、系统 Geocoder 超时或公共服务限流时，
 * 由 WorkManager 在网络恢复后按指数退避继续补全，无需用户关闭再打开地点来源。
 */
class PlaceNameBackfillWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as LifeLogApp
        if (!app.settings.placeNameConsent) return Result.success()
        val result = PlaceNameResolver(applicationContext, app.settings)
            .backfillMissingAddresses(app.databaseProvider.get().dao())
        DiagnosticLog.event(
            "location",
            "reverse_geocode_backfill_worker_finished",
            mapOf(
                "attempted" to result.attempted,
                "updated" to result.updated,
                "failed" to result.failed,
                "remaining" to result.remaining,
                "runAttempt" to runAttemptCount,
            ),
        )
        return if ((result.failed > 0 || result.remaining > 0) && runAttemptCount < 8) {
            Result.retry()
        } else {
            Result.success()
        }
    }
}

object PlaceNameBackfillScheduler {
    private const val UNIQUE_WORK = "place-name-backfill"

    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<PlaceNameBackfillWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.KEEP, request)
    }
}
