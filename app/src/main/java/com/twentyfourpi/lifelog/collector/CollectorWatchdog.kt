package com.twentyfourpi.lifelog.collector

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.twentyfourpi.lifelog.LifeLogApp
import com.twentyfourpi.lifelog.data.SourceId
import com.twentyfourpi.lifelog.debug.DiagnosticLog

/**
 * WorkManager 与采集服务处于同一应用进程，HyperOS 冻结整个进程时可能一起失效。
 * 这个系统闹钟只负责低频唤醒并检查采集器，不采集数据；获得“闹钟和提醒”授权后
 * 使用精确闹钟，未授权时退化为系统可延后的 idle 闹钟。
 */
object CollectorWatchdogScheduler {
    private const val INTERVAL_MS = 15 * 60_000L
    private const val FIRST_CHECK_MS = 2 * 60_000L
    private const val REQUEST_CODE = 2410

    fun schedule(context: Context, soon: Boolean = false) {
        val app = context.applicationContext as LifeLogApp
        if (!app.settings.collectionEnabled) return
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val triggerAt = System.currentTimeMillis() + if (soon) FIRST_CHECK_MS else INTERVAL_MS
        val operation = operation(context)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        runCatching {
            if (exact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
            }
            DiagnosticLog.event(
                "service",
                "watchdog_scheduled",
                mapOf("triggerAt" to triggerAt, "exact" to exact, "soon" to soon),
            )
        }.onFailure { DiagnosticLog.error("service", "watchdog_schedule_failed", it) }
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(operation(context))
    }

    fun hasExactAlarmAccess(context: Context): Boolean {
        val manager = context.getSystemService(AlarmManager::class.java)
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
    }

    private fun operation(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, CollectorWatchdogReceiver::class.java).setAction(ACTION_WATCHDOG),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private const val ACTION_WATCHDOG = "com.twentyfourpi.lifelog.COLLECTOR_WATCHDOG"
}

class CollectorWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext as LifeLogApp
        if (!app.settings.collectionEnabled) {
            CollectorWatchdogScheduler.cancel(context)
            return
        }
        DiagnosticLog.event("service", "watchdog_fired")
        UsageSyncWorker.schedule(context)
        UsageSyncWorker.enqueueRecovery(context)
        if (app.settings.sourceEnabled(SourceId.NOTIFICATIONS) && context.hasNotificationListenerAccess()) {
            if (!MetadataNotificationListener.isConnected()) {
                MetadataNotificationListener.markDisconnected(app.settings)
                MetadataNotificationListener.requestReconnect(context, force = true)
            }
        }
        if (context.shouldRunCollectorService(app.settings)) {
            runCatching { LifeLogCollectorService.start(context) }
                .onFailure { DiagnosticLog.error("service", "watchdog_collector_start_failed", it) }
        }
        CollectorWatchdogScheduler.schedule(context)
    }
}
