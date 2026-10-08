package com.twentyfourpi.lifelog.collector

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.twentyfourpi.lifelog.data.LifeLogRepository
import com.twentyfourpi.lifelog.data.SettingsStore
import com.twentyfourpi.lifelog.data.SourceId
import com.twentyfourpi.lifelog.data.SourceState
import com.twentyfourpi.lifelog.debug.DiagnosticLog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId

class UsageCollector(
    private val context: Context,
    private val repository: LifeLogRepository,
    private val settings: SettingsStore,
) : LifeLogDataSource {
    override val id = SourceId.USAGE
    private val manager = context.getSystemService(UsageStatsManager::class.java)
    private val homePackages: Set<String> by lazy {
        buildSet {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).forEach { add(it.activityInfo.packageName) }
            add("com.miui.home"); add("com.android.launcher3")
        }
    }
    private val infrastructurePackages = setOf(
        "android", "com.android.systemui", "com.android.permissioncontroller",
        "com.google.android.permissioncontroller", context.packageName,
    )
    private val appLabels = AppLabelResolver(context)
    private val persistedLabels = mutableMapOf<String, String>()

    override suspend fun health(): DataSourceHealth = if (context.hasUsageAccess())
        DataSourceHealth(id, SourceState.ACTIVE, "应用与屏幕使用记录正常")
    else DataSourceHealth(id, SourceState.PERMISSION_REQUIRED, "请开启使用情况访问权限")

    override suspend fun collect(nowMs: Long) = collectionMutex.withLock {
        collectLocked(nowMs)
    }

    private suspend fun collectLocked(nowMs: Long) {
        if (!settings.sourceEnabled(id) || !context.hasUsageAccess()) {
            DiagnosticLog.event("usage", "collection_skipped", mapOf(
                "sourceEnabled" to settings.sourceEnabled(id), "usageAccess" to context.hasUsageAccess(),
            ))
            repository.setStatus(id, if (settings.sourceEnabled(id)) SourceState.PERMISSION_REQUIRED else SourceState.PAUSED,
                if (settings.sourceEnabled(id)) "请开启使用情况访问权限" else "已暂停")
            return
        }
        if (!settings.getBoolean("usage_filter_v2_migrated")) {
            val removedPackages = (infrastructurePackages + homePackages).filter(String::isNotBlank)
            // 永久档案不再清理既有行。采集器只从这一刻起过滤基础设施包，旧数据原样保留。
            settings.putBoolean("usage_filter_v2_migrated", true)
            DiagnosticLog.event("usage", "legacy_infrastructure_sessions_retained", mapOf("packages" to removedPackages.joinToString(",")))
        }
        if (!settings.getBoolean("app_labels_v1_backfilled")) {
            var repairedPackages = 0
            repository.knownRecordedPackages().forEach { packageName ->
                val resolved = appLabels.resolve(packageName)
                if (resolved.authoritative) {
                    repository.updateRecordedAppLabel(packageName, resolved.value)
                    persistedLabels[packageName] = resolved.value
                    repairedPackages++
                }
            }
            settings.putBoolean("app_labels_v1_backfilled", true)
            DiagnosticLog.event(
                "usage",
                "historical_app_labels_backfilled",
                mapOf("repairedPackages" to repairedPackages),
            )
        }
        val cursorKey = "usage_cursor"
        val begin = maxOf(settings.startedAt, settings.getLong(cursorKey, nowMs - 60_000) - 1_000)
        val events = manager.queryEvents(begin, nowMs)
        val event = UsageEvents.Event()
        var activePkg = settings.getString("usage_active_pkg")
        var activeStart = settings.getLong("usage_active_start")
        var screenStart = settings.getLong("screen_active_start")
        if (isInfrastructure(activePkg)) { activePkg = ""; activeStart = 0 }
        var eventCount = 0
        var resumeCount = 0
        val resumedPackages = linkedSetOf<String>()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            eventCount++
            val packageName = event.packageName.orEmpty()
            val activeBefore = activePkg
            val activeStartBefore = activeStart
            var decision = "IGNORED_EVENT_TYPE"
            try {
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        resumeCount++
                        if (packageName.isNotBlank()) resumedPackages += packageName
                        if (isInfrastructure(packageName)) {
                            decision = "INFRASTRUCTURE_RESUMED"
                            if ((packageName == context.packageName || packageName in homePackages) && activePkg.isNotEmpty() && activeStart > 0) {
                                repository.appendAppSession(activePkg, appLabel(activePkg), activeStart, event.timeStamp)
                                activePkg = ""; activeStart = 0
                                decision = "INFRASTRUCTURE_CLOSED_ACTIVE"
                            }
                            continue
                        }
                        if (activePkg.isNotEmpty() && activePkg != packageName && activeStart > 0) {
                            repository.appendAppSession(activePkg, appLabel(activePkg), activeStart, event.timeStamp)
                            decision = "SWITCHED_APPLICATION"
                        } else decision = if (activePkg == packageName) "SAME_APPLICATION_RESUMED" else "APPLICATION_STARTED"
                        if (activePkg != packageName) activeStart = event.timeStamp
                        activePkg = packageName
                    }
                    UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> {
                        if (activePkg == packageName && activeStart > 0) {
                            repository.appendAppSession(activePkg, appLabel(activePkg), activeStart, event.timeStamp)
                            activePkg = ""; activeStart = 0
                            decision = "ACTIVE_APPLICATION_CLOSED"
                        } else decision = "NON_ACTIVE_APPLICATION_STOPPED"
                    }
                    UsageEvents.Event.KEYGUARD_HIDDEN -> {
                        if (screenStart == 0L) screenStart = event.timeStamp
                        decision = "SCREEN_SESSION_STARTED"
                    }
                    UsageEvents.Event.KEYGUARD_SHOWN, UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                        if (activePkg.isNotEmpty() && activeStart > 0) {
                            repository.appendAppSession(activePkg, appLabel(activePkg), activeStart, event.timeStamp)
                            activePkg = ""; activeStart = 0
                        }
                        if (screenStart > 0) repository.appendScreenSession(screenStart, event.timeStamp)
                        screenStart = 0
                        decision = "SCREEN_LOCKED"
                    }
                }
            } finally {
                DiagnosticLog.event("usage", "usage_event_decision", mapOf(
                    "eventTimeMs" to event.timeStamp,
                    "eventType" to event.eventType,
                    "package" to packageName,
                    "decision" to decision,
                    "activePackageBefore" to activeBefore,
                    "activeStartBeforeMs" to activeStartBefore,
                    "activePackageAfter" to activePkg,
                    "activeStartAfterMs" to activeStart,
                    "screenStartAfterMs" to screenStart,
                ))
            }
        }
        // 把仍在前台的会话结算到“现在”，下次从现在继续。这样长时间使用时也能实时看到累计时长。
        if (activePkg.isNotEmpty() && activeStart > 0 && nowMs > activeStart) {
            repository.appendAppSession(activePkg, appLabel(activePkg), activeStart, nowMs)
            activeStart = nowMs
        }
        if (screenStart > 0 && nowMs > screenStart) {
            repository.appendScreenSession(screenStart, nowMs)
            screenStart = nowMs
        }
        settings.putLong(cursorKey, nowMs)
        settings.putString("usage_active_pkg", activePkg)
        settings.putLong("usage_active_start", activeStart)
        settings.putLong("screen_active_start", screenStart)
        val dayStart = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).toLocalDate()
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val aggregateTop = runCatching {
            manager.queryAndAggregateUsageStats(dayStart, nowMs).entries
                .filter { it.value.totalTimeInForeground > 0 && !isInfrastructure(it.key) }
                .sortedByDescending { it.value.totalTimeInForeground }.take(8)
                .joinToString(",") { "${it.key}:${it.value.totalTimeInForeground}" }
        }.getOrDefault("unavailable")
        DiagnosticLog.event("usage", "events_collected", mapOf(
            "beginMs" to begin, "endMs" to nowMs, "eventCount" to eventCount,
            "resumeCount" to resumeCount, "resumedPackages" to resumedPackages.take(20).joinToString(","),
            "activePackage" to activePkg, "aggregateTop" to aggregateTop,
        ))
        repository.setStatus(id, SourceState.ACTIVE, "应用与屏幕使用记录正常", touched = true)
    }

    private suspend fun appLabel(pkg: String): String {
        val resolved = appLabels.resolve(pkg)
        if (resolved.authoritative && persistedLabels[pkg] != resolved.value) {
            repository.updateRecordedAppLabel(pkg, resolved.value)
            persistedLabels[pkg] = resolved.value
        }
        return resolved.value
    }

    private fun isInfrastructure(pkg: String): Boolean = pkg.isBlank() || pkg in infrastructurePackages || pkg in homePackages

    companion object {
        // 前台服务、WorkManager 与界面即时同步共享同一游标，必须串行避免重复或漏记。
        private val collectionMutex = Mutex()
    }
}
