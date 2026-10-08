package com.twentyfourpi.lifelog

import android.app.Application
import com.twentyfourpi.lifelog.backup.BackupManager
import com.twentyfourpi.lifelog.collector.UsageSyncWorker
import com.twentyfourpi.lifelog.collector.CollectorWatchdogScheduler
import com.twentyfourpi.lifelog.data.DatabaseProvider
import com.twentyfourpi.lifelog.data.LifeLogRepository
import com.twentyfourpi.lifelog.data.SettingsStore
import com.twentyfourpi.lifelog.debug.DiagnosticLog
import com.twentyfourpi.lifelog.debug.DiagnosticsExporter
import com.twentyfourpi.lifelog.export.DataExporter
import com.twentyfourpi.lifelog.ui.EpisodeSummaryCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class LifeLogApp : Application() {
    lateinit var databaseProvider: DatabaseProvider
        private set
    lateinit var repository: LifeLogRepository
        private set
    lateinit var settings: SettingsStore
        private set
    lateinit var backupManager: BackupManager
        private set
    lateinit var diagnosticsExporter: DiagnosticsExporter
        private set
    lateinit var dataExporter: DataExporter
        private set

    /**
     * P3：语义时段投影缓存——**应用级唯一实例**，首页（MainViewModel）与诊断导出共用，
     * 保证「同一个日期的投影」只有一个口径与一个截止时刻（子代理复核 #3）。
     */
    lateinit var episodeSummaryCache: EpisodeSummaryCache
        private set

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.ENABLE_OBSERVATION_TOOLS) {
            DiagnosticLog.initialize(this)
            val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                DiagnosticLog.error(
                    "crash",
                    "uncaught_exception",
                    error,
                    mapOf("threadName" to thread.name, "threadId" to thread.id),
                )
                DiagnosticLog.flushForCrash()
                previousHandler?.uncaughtException(thread, error)
            }
        }
        databaseProvider = DatabaseProvider(this)
        repository = LifeLogRepository(databaseProvider)
        settings = SettingsStore(this)
        backupManager = BackupManager(this, databaseProvider, settings)
        episodeSummaryCache = EpisodeSummaryCache(CoroutineScope(SupervisorJob() + Dispatchers.Default))
        diagnosticsExporter = DiagnosticsExporter(this, databaseProvider, settings, repository, episodeSummaryCache)
        dataExporter = DataExporter(this, databaseProvider)
        if (settings.collectionEnabled) {
            UsageSyncWorker.schedule(this)
            CollectorWatchdogScheduler.schedule(this)
        }
        if (BuildConfig.ENABLE_OBSERVATION_TOOLS) {
            DiagnosticLog.event("app", "application_started", mapOf(
                "versionName" to BuildConfig.VERSION_NAME,
                "versionCode" to BuildConfig.VERSION_CODE,
                "processId" to android.os.Process.myPid(),
                "elapsedRealtimeMs" to android.os.SystemClock.elapsedRealtime(),
                "sdk" to android.os.Build.VERSION.SDK_INT,
                "manufacturer" to android.os.Build.MANUFACTURER,
                "model" to android.os.Build.MODEL,
            ))
        }
    }
}
