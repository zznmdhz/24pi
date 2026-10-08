package com.twentyfourpi.lifelog.collector

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.*
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.CancellationSignal
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.twentyfourpi.lifelog.LifeLogApp
import com.twentyfourpi.lifelog.MainActivity
import com.twentyfourpi.lifelog.R
import com.twentyfourpi.lifelog.data.SourceId
import com.twentyfourpi.lifelog.data.SourceState
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.twentyfourpi.lifelog.debug.DiagnosticLog
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.math.round

class LifeLogCollectorService : Service(), LocationListener, SensorEventListener {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
            DiagnosticLog.error("service", "collector_coroutine_failed", error)
        },
    )
    private val app get() = application as LifeLogApp
    private lateinit var usageCollector: UsageCollector
    private lateinit var locationRecorder: LocationRecorder
    private lateinit var stepRecorder: StepRecorder
    private lateinit var gapDetector: CollectionGapDetector
    /**
     * P2：定位缺口开/关的串行闸门。系统回调与主动取位可能同时恢复，
     * 两者必须经过同一个入口，不能各关一次、也不能重复写缺口。
     */
    private val locationRecoveryMutex = Mutex()
    private val locationManager by lazy { getSystemService(LocationManager::class.java) }
    private val sensorManager by lazy { getSystemService(SensorManager::class.java) }
    private var collectorsStarted = false
    @Volatile private var lastGoodLocation: Location? = null
    @Volatile private var lastRawLocationCallbackAt = 0L
    @Volatile private var lastAcceptedLocationCallbackAt = 0L
    @Volatile private var lastLocationRequestAt = 0L
    // v0.16.1 接收端节流门控：系统会把其他应用触发的高频定位广播给所有监听者，
    // 不受本应用 minTime 约束（事故日 GPS 主通道 64% 相邻间隔 ≤2s）。落库前统一限速。
    @Volatile private var lastRecordedLocationAt = 0L
    @Volatile private var locationStaleReported = false
    private var locationRecoveryAttempts = 0
    // 主动取位防线状态：probe 失败计数与监听重建退避。
    @Volatile private var lastProbeAt = 0L
    @Volatile private var probeFailures = 0
    @Volatile private var lastRebuildAt = 0L
    private var rebuildAttempts = 0
    // 最近一次有效定位数据的时刻：系统回调或主动取位成功都会刷新。
    // HyperOS 静默期会冻结被动回调，但只要应用自己还能取到位置，就不算断档。
    @Volatile private var lastLocationDataAt = 0L
    // 本次「连续尝试取位但一直拿不到数据」的起点：只在真实测量到达时清零。
    // 它让断档判定在「从未拿到过数据」的会话里也能生效，且不会被每次请求刷新。
    @Volatile private var locationAttemptSinceAt = 0L
    private val locationTraceSequence = AtomicLong(0)

    override fun onCreate() {
        super.onCreate()
        DiagnosticLog.event("service", "collector_created")
        createChannel()
        usageCollector = UsageCollector(this, app.repository, app.settings)
        locationRecorder = LocationRecorder(
            app.databaseProvider,
            app.repository,
            app.settings,
            PlaceNameResolver(this, app.settings),
            scope,
        )
        gapDetector = CollectionGapDetector(app.databaseProvider, app.settings)
        stepRecorder = StepRecorder(this, app.databaseProvider, app.repository, app.settings)
        val restartGap = gapDetector.captureProcessRestart()
        scope.launch {
            restartGap?.let { gap ->
                val interruptedSources = buildSet {
                    if (app.settings.sourceEnabled(SourceId.LOCATION) && hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                        add(SourceId.LOCATION)
                    }
                    if (app.settings.sourceEnabled(SourceId.NOTIFICATIONS) && hasNotificationListenerAccess()) {
                        add(SourceId.NOTIFICATIONS)
                    }
                }
                gapDetector.recordProcessRestart(gap, interruptedSources)
            }
            gapDetector.backfillRecentPointGaps()
        }
        // P2 复核修正：收口上一段未关闭缺口只在**进程首次创建**时做一次。
        // 放在 startCollectors() 里会在 reconfigure（用户只是切换其它采集源、服务从未停止）
        // 时也执行，把一段仍在进行的静默错标成「进程重启」并提前收口，丢掉缺口尾巴。
        settleUnclosedLocationGapFromPreviousSession()
        if (app.settings.placeNameConsent) PlaceNameBackfillScheduler.enqueue(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            app.settings.collectionEnabled = false
            UsageSyncWorker.cancel(this)
            stopCollection()
            return START_NOT_STICKY
        }
        if (!app.settings.collectionEnabled) {
            stopCollection(); return START_NOT_STICKY
        }
        val foregroundType = collectorForegroundServiceType(
            locationEnabled = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) &&
                app.settings.sourceEnabled(SourceId.LOCATION),
            healthEnabled = hasPermission(Manifest.permission.ACTIVITY_RECOGNITION) &&
                app.settings.sourceEnabled(SourceId.STEPS),
        )
        DiagnosticLog.event("service", "collector_started", mapOf("foregroundType" to foregroundType, "startId" to startId))
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), foregroundType)
        if (intent?.action == ACTION_RECONFIGURE && collectorsStarted) {
            DiagnosticLog.event("service", "collector_reconfigure_requested")
            stopActiveCollectors()
            collectorsStarted = false
        }
        if (!collectorsStarted) { collectorsStarted = true; startCollectors() }
        if (foregroundType == ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) scope.launch {
            delay(5_000); stopCollection()
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission") // 调用前已逐项检查，且请求过程由 runCatching 处理权限瞬时撤销。
    private fun startCollectors() {
        // 心跳：15秒写入一次，提高断档检测灵敏度
        scope.launch {
            while (isActive) {
                gapDetector.heartbeat()
                delay(15_000)
            }
        }
        // 低频环境快照用于区分“没有定位样本”和“整个采集进程被系统冻结”。
        scope.launch {
            val power = getSystemService(PowerManager::class.java)
            while (isActive) {
                val now = System.currentTimeMillis()
                DiagnosticLog.event("service", "runtime_health_snapshot", mapOf(
                    "collectorStarted" to collectorsStarted,
                    "collectionEnabled" to app.settings.collectionEnabled,
                    "locationEnabled" to app.settings.sourceEnabled(SourceId.LOCATION),
                    "notificationEnabled" to app.settings.sourceEnabled(SourceId.NOTIFICATIONS),
                    "notificationConnected" to MetadataNotificationListener.isConnected(),
                    "lastRawLocationCallbackAt" to lastRawLocationCallbackAt,
                    "lastRawLocationCallbackAgeMs" to lastRawLocationCallbackAt.takeIf { it > 0L }?.let { now - it },
                    "lastAcceptedLocationCallbackAt" to lastAcceptedLocationCallbackAt,
                    "lastAcceptedLocationCallbackAgeMs" to lastAcceptedLocationCallbackAt.takeIf { it > 0L }?.let { now - it },
                    "lastLocationRequestAt" to lastLocationRequestAt,
                    "lastLocationRequestAgeMs" to lastLocationRequestAt.takeIf { it > 0L }?.let { now - it },
                    "locationRecoveryAttempts" to locationRecoveryAttempts,
                    "probeFailures" to probeFailures,
                    "lastProbeAt" to lastProbeAt,
                    "lastProbeAgeMs" to lastProbeAt.takeIf { it > 0L }?.let { now - it },
                    "lastLocationDataAt" to lastLocationDataAt,
                    "lastLocationDataAgeMs" to lastLocationDataAt.takeIf { it > 0L }?.let { now - it },
                    "gpsProvider" to runCatching { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrNull(),
                    "networkProvider" to runCatching { locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrNull(),
                    "deviceIdleMode" to runCatching { power.isDeviceIdleMode }.getOrNull(),
                    "powerSaveMode" to runCatching { power.isPowerSaveMode }.getOrNull(),
                    "interactive" to runCatching { power.isInteractive }.getOrNull(),
                    "ignoringBatteryOptimizations" to runCatching { power.isIgnoringBatteryOptimizations(packageName) }.getOrNull(),
                ))
                delay(5 * 60_000)
            }
        }
        // 通知监听器健康检查：每 10 分钟检查连接状态，断线自动重连
        scope.launch {
            while (isActive) {
                delay(10 * 60_000)
                runCatching {
                    if (hasNotificationListenerAccess() && app.settings.sourceEnabled(SourceId.NOTIFICATIONS)) {
                        val connected = MetadataNotificationListener.isConnected()
                        if (!connected) {
                            MetadataNotificationListener.requestReconnect(this@LifeLogCollectorService, force = true)
                            DiagnosticLog.event("notifications", "health_check_reconnect")
                        }
                    }
                }.onFailure { DiagnosticLog.error("notifications", "health_check_failed", it) }
            }
        }
        scope.launch {
            while (isActive) {
                runCatching { usageCollector.collect() }
                    .onFailure {
                        DiagnosticLog.error("usage", "collection_failed", it)
                        app.repository.setStatus(SourceId.USAGE, SourceState.ERROR, it.message ?: "采集失败")
                    }
                updatePassiveStatuses()
                delay(15_000)
            }
        }
        if (!app.settings.sourceEnabled(SourceId.LOCATION)) {
            scope.launch { app.repository.setStatus(SourceId.LOCATION, SourceState.PAUSED, "已暂停") }
        } else if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            requestLocationUpdates("collector_start")
            locationAttemptSinceAt = System.currentTimeMillis()
            // 定位健康监控（饱和机制）：
            // 1. 周期主动取位（默认 60 秒一次，静默期自动加密到 30 秒），不依赖系统回调；
            // 2. 回调与主动取位任一成功都刷新“有效数据时间”；
            // 3. 只有连续超过 3 分钟没有任何有效数据，才判定真断档并重建监听（带指数退避）。
            // HyperOS 会冻结被动回调，但 getCurrentLocation 仍可用；本机制保证系统静默时
            // 应用自己持续取位，时间线不再出现“系统静默但被误报为中断”的假断档。
            scope.launch {
                while (isActive) {
                    delay(30_000)
                    val now = System.currentTimeMillis()
                    runCatching {
                        if (!app.settings.sourceEnabled(SourceId.LOCATION)) return@runCatching
                        // 第一层：真实回调新鲜时用缓存合成心跳，维持候选活跃。
                        val cached = lastGoodLocation
                        val acceptedAge = now - lastAcceptedLocationCallbackAt
                        if (cached != null && CollectorRecoveryPolicy.shouldHeartbeatWithCached(acceptedAge)) {
                            val receivedAt = now
                            val traceId = nextLocationTraceId("heartbeat")
                            val sampled = Location("sampled:${cached.provider}").apply {
                                latitude = cached.latitude; longitude = cached.longitude
                                accuracy = cached.accuracy; time = now
                                if (cached.hasSpeed()) speed = cached.speed
                            }
                            DiagnosticLog.event("location", "stationary_heartbeat", mapOf(
                                "traceId" to traceId, "receivedAtMs" to receivedAt,
                                "sourceAgeMs" to acceptedAge, "accuracyM" to cached.accuracy,
                            ))
                            locationRecorder.heartbeat(sampled, traceId, receivedAt)
                        }
                        // 第二层：周期主动取位。无论回调是否正常，都按固定节奏 probe，
                        // 使“有效数据时间”持续刷新；系统静默越久，probe 间隔越短。
                        if (lastLocationDataAt == 0L && locationAttemptSinceAt == 0L) {
                            // 冷启动：从这一刻开始算「试了多久还没数据」，而不是永远不判断档。
                            locationAttemptSinceAt = now
                        }
                        val dataAge = now - CollectorRecoveryPolicy.staleReference(
                            lastLocationDataAt, locationAttemptSinceAt,
                        )
                        val probeInterval = if (dataAge > CollectorRecoveryPolicy.LOCATION_STALE_MS) PROBE_FAST_INTERVAL_MS else PROBE_NORMAL_INTERVAL_MS
                        if (now - lastProbeAt >= probeInterval) {
                            lastProbeAt = now
                            // P2：缺口已开或数据已判静默时，这次取位必须拿到真实测量（缓存心跳不算数）。
                            val needTrustedEvidence = locationStaleReported ||
                                app.settings.getLong(LOCATION_GAP_START_KEY) > 0L ||
                                dataAge > CollectorRecoveryPolicy.LOCATION_STALE_MS
                            val outcome = probeLocation(
                                reason = if (dataAge > CollectorRecoveryPolicy.LOCATION_STALE_MS) "data_stale" else "periodic",
                                needTrustedEvidence = needTrustedEvidence,
                            )
                            // P2 复核修正（审核 #2）：只有真实测量清零失败计数、取位失败（null）累计；
                            // 缓存心跳与陈旧测量不改变计数——把它们当失败会让监听在静默夜里
                            // 每 3~18 分钟重建一次，既耗电又会打断正在进行的 GPS 首次定位。
                            probeFailures = CollectorRecoveryPolicy.nextProbeFailures(probeFailures, outcome)
                        }
                        // 第三层：真断档判定——最近 3 分钟没有任何真实测量（回调或实时取位）。
                        if (CollectorRecoveryPolicy.locationNeedsRestart(
                                now,
                                CollectorRecoveryPolicy.staleReference(lastLocationDataAt, locationAttemptSinceAt),
                            )
                        ) {
                            if (!locationStaleReported) {
                                // P2：起点取「最后一次真实数据」，而不是「最后一次发出请求」——
                                // 发请求不等于拿到位置，用请求时刻会把起点往后推、漏报缺失时间。
                                val gapStart = CollectorRecoveryPolicy.gapStartAt(
                                    lastDataMs = lastLocationDataAt,
                                    lastAcceptedCallbackMs = lastAcceptedLocationCallbackAt,
                                    lastRequestMs = lastLocationRequestAt,
                                    nowMs = now,
                                )
                                openLocationGap(gapStart)
                            }
                            locationStaleReported = true
                            locationRecoveryAttempts++
                            app.repository.setStatus(
                                SourceId.LOCATION,
                                SourceState.SYSTEM_BLOCKED,
                                "定位数据中断，正在自动重连（第${locationRecoveryAttempts}次）",
                            )
                            // 主动取位连续失败后重建监听，带指数退避，避免反复打断 GPS 锁定。
                            if (CollectorRecoveryPolicy.shouldRebuildListeners(probeFailures) &&
                                now - lastRebuildAt >= CollectorRecoveryPolicy.nextRebuildDelayMs(rebuildAttempts)
                            ) {
                                lastRebuildAt = now
                                rebuildAttempts++
                                probeFailures = 0
                                DiagnosticLog.event("location", "listener_rebuild_requested", mapOf(
                                    "attempt" to rebuildAttempts,
                                    "backoffMs" to CollectorRecoveryPolicy.nextRebuildDelayMs(rebuildAttempts),
                                ))
                                requestLocationUpdates("listener_rebuild")
                            }
                            DiagnosticLog.event("location", "updates_stale", mapOf(
                                "ageMs" to (now - CollectorRecoveryPolicy.staleReference(
                                    lastLocationDataAt, locationAttemptSinceAt,
                                )),
                                "recoveryAttempt" to locationRecoveryAttempts,
                                "probeFailures" to probeFailures,
                            ))
                        } else if (locationStaleReported) {
                            // 已恢复（回调或主动取位成功刷新了数据时间），复位恢复状态。
                            locationStaleReported = false
                            locationRecoveryAttempts = 0
                            rebuildAttempts = 0
                            probeFailures = 0
                        }
                    }.onFailure { DiagnosticLog.error("location", "gps_health_check_failed", it) }
                }
            }
            // GPS 提供者健康监控：每 5 分钟检查系统定位服务是否仍开启。
            scope.launch {
                while (isActive) {
                    delay(5 * 60_000)
                    runCatching {
                        if (!app.settings.sourceEnabled(SourceId.LOCATION)) return@runCatching
                        val gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                        val networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                        if (!gpsEnabled && !networkEnabled) {
                            DiagnosticLog.event("location", "providers_all_disabled", mapOf("gps" to gpsEnabled, "network" to networkEnabled))
                            app.repository.setStatus(SourceId.LOCATION, SourceState.SYSTEM_BLOCKED, "系统定位服务已关闭")
                        } else if (lastRawLocationCallbackAt == 0L && System.currentTimeMillis() - lastLocationRequestAt > 10 * 60_000) {
                            // 超过 10 分钟没有收到任何位置，主动重新请求
                            DiagnosticLog.event("location", "no_location_after_start", mapOf("ageMs" to (System.currentTimeMillis() - lastLocationRequestAt)))
                            requestLocationUpdates("periodic_recovery")
                        }
                    }.onFailure { DiagnosticLog.error("location", "gps_health_check_failed", it) }
                }
            }
        } else scope.launch { app.repository.setStatus(SourceId.LOCATION, SourceState.PERMISSION_REQUIRED, "需要始终允许精确位置") }

        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (!app.settings.sourceEnabled(SourceId.STEPS)) scope.launch { app.repository.setStatus(SourceId.STEPS, SourceState.PAUSED, "已暂停") }
        else if (sensor == null) scope.launch { app.repository.setStatus(SourceId.STEPS, SourceState.UNSUPPORTED, "设备没有计步传感器") }
        else if (hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)) sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        else scope.launch { app.repository.setStatus(SourceId.STEPS, SourceState.PERMISSION_REQUIRED, "需要身体活动权限") }
        scope.launch { app.repository.setStatus(SourceId.SLEEP, SourceState.UNSUPPORTED, "小米健康未向个人应用开放读取接口") }
    }

    private suspend fun updatePassiveStatuses() {
        if (!hasNotificationListenerAccess()) {
            app.repository.setStatus(SourceId.NOTIFICATIONS, SourceState.PERMISSION_REQUIRED, "请开启通知使用权")
        } else if (app.settings.sourceEnabled(SourceId.NOTIFICATIONS)) {
            val connected = MetadataNotificationListener.isConnected()
            if (!connected) MetadataNotificationListener.requestReconnect(this)
            app.repository.setStatus(
                SourceId.NOTIFICATIONS,
                if (connected) SourceState.ACTIVE else SourceState.SYSTEM_BLOCKED,
                if (connected) {
                    if (app.settings.notificationContentEnabled) "统计普通通知，并保存标题和正文" else "仅统计可清除的普通通知，不读取内容"
                } else "通知监听已失联，正在自动重连",
                touched = connected,
            )
        }
        if (app.settings.sourceEnabled(SourceId.STEPS) && hasPermission(Manifest.permission.ACTIVITY_RECOGNITION) &&
            sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null
        ) app.repository.setStatus(SourceId.STEPS, SourceState.ACTIVE, "计步传感器正常", touched = true)
    }

    /** v0.16.1 接收端节流门：距上次原始点落库不足 [RECORD_MIN_INTERVAL_MS] 时返回 false。 */
    private fun shouldRecordNow(nowMs: Long): Boolean {
        val last = lastRecordedLocationAt
        if (last != 0L && nowMs - last < RECORD_MIN_INTERVAL_MS) return false
        lastRecordedLocationAt = nowMs
        return true
    }

    override fun onLocationChanged(location: Location) {
        val receivedAt = System.currentTimeMillis()
        val previousRawCallbackAt = lastRawLocationCallbackAt
        val previousAcceptedCallbackAt = lastAcceptedLocationCallbackAt
        lastRawLocationCallbackAt = receivedAt
        val traceId = nextLocationTraceId("callback")
        val locationAgeMs = (receivedAt - location.time).coerceAtLeast(0L)
        val freshEnough = location.time <= 0L || locationAgeMs <= MAX_CALLBACK_AGE_MS
        val accepted = isTrustedLocationCallback(location.accuracy, location.time, receivedAt)
        DiagnosticLog.event("location", "location_callback", mapOf(
            "traceId" to traceId,
            "receivedAtMs" to receivedAt,
            "locationTimeMs" to location.time,
            "locationAgeMs" to locationAgeMs,
            "elapsedRealtimeNanos" to location.elapsedRealtimeNanos,
            "provider" to location.provider,
            "accuracyM" to location.accuracy,
            "hasSpeed" to location.hasSpeed(),
            "speedMps" to location.speed.takeIf { location.hasSpeed() },
            "latitudeApprox" to round3(location.latitude),
            "longitudeApprox" to round3(location.longitude),
            "accepted" to accepted,
            "freshEnough" to freshEnough,
            "decision" to when {
                accepted -> "ACCEPTED"
                !freshEnough -> "REJECTED_STALE_CALLBACK"
                else -> "REJECTED_ACCURACY_OVER_200M"
            },
            "sinceLastRawCallbackMs" to previousRawCallbackAt.takeIf { it > 0L }?.let { receivedAt - it },
            "sinceLastAcceptedMs" to previousAcceptedCallbackAt.takeIf { it > 0L }?.let { receivedAt - it },
            "sinceLastRequestMs" to lastLocationRequestAt.takeIf { it > 0L }?.let { receivedAt - it },
        ))
        if (accepted) {
            // P2：与主动取位共用同一个恢复入口（串行关闭，只关一次）。
            if (locationStaleReported || app.settings.getLong(LOCATION_GAP_START_KEY) > 0L) scope.launch {
                closeLocationGap(
                    source = CollectorRecoveryPolicy.TrustedRecoverySource.SYSTEM_CALLBACK,
                    provider = location.provider,
                    receivedAtMs = receivedAt,
                    measuredAtMs = location.time.takeIf { it > 0L } ?: receivedAt,
                    traceId = traceId,
                )
            }
            locationStaleReported = false
            locationRecoveryAttempts = 0
            rebuildAttempts = 0
            probeFailures = 0
            locationAttemptSinceAt = 0L
            lastGoodLocation = Location(location)
            lastAcceptedLocationCallbackAt = receivedAt
            lastLocationDataAt = receivedAt
            // v0.16.1 接收端节流：健康/恢复逻辑保持每个可信回调都执行，
            // 但原始点落库限速，防止系统广播的高频回调灌爆数据库与日志。
            val shouldRecord = shouldRecordNow(receivedAt)
            DiagnosticLog.event("location", "location_accepted", mapOf(
                "traceId" to traceId, "provider" to location.provider, "accuracyM" to location.accuracy,
                "recordThrottled" to !shouldRecord,
            ))
            if (shouldRecord) scope.launch { locationRecorder.record(location, traceId, receivedAt) }
        } else if (freshEnough) {
            // 低精度回调只能证明 LocationManager 仍有回调，不能证明已经恢复可信位置。
            // 它不会关闭断档、清零恢复状态，也不会刷新 lastLocationDataAt。
            scope.launch {
                locationRecorder.heartbeat(location, traceId, receivedAt)
            }
            DiagnosticLog.event("location", "location_rejected", mapOf(
                "traceId" to traceId, "provider" to location.provider,
                "accuracyM" to location.accuracy, "reason" to "accuracy_over_200m",
                "downgradedToHeartbeat" to true,
                "keptGapOpen" to (app.settings.getLong(LOCATION_GAP_START_KEY) > 0L),
                "locationStaleReported" to locationStaleReported,
                "recoveryAttempts" to locationRecoveryAttempts,
                "lastTrustedDataAgeMs" to lastLocationDataAt.takeIf { it > 0L }?.let { receivedAt - it },
            ))
            scope.launch {
                app.repository.setStatus(
                    SourceId.LOCATION,
                    SourceState.ACTIVE,
                    "定位服务仍在更新，但当前精度约 ±${location.accuracy.toInt()} 米，仅维持记录不更新地点",
                )
            }
        } else {
            DiagnosticLog.event("location", "stale_callback_discarded", mapOf(
                "traceId" to traceId,
                "provider" to location.provider,
                "locationAgeMs" to locationAgeMs,
                "accuracyM" to location.accuracy,
                "keptGapOpen" to (app.settings.getLong(LOCATION_GAP_START_KEY) > 0L),
            ))
        }
    }

    /**
     * P2：关闭当前定位缺口的**唯一入口**（串行、只关一次、可审计）。
     *
     * 允许调用的来源只有 [CollectorRecoveryPolicy.closesLocationGap] 认可的两种：
     * 新鲜且精度达标的系统回调，或主动取位拿到的实时位置。
     * 系统缓存位置、重建监听、发出请求都不是「已恢复」的证据，不在这里关闭缺口。
     */
    private suspend fun closeLocationGap(
        source: CollectorRecoveryPolicy.TrustedRecoverySource,
        provider: String?,
        receivedAtMs: Long,
        measuredAtMs: Long,
        traceId: String,
        reason: String = CollectorRecoveryPolicy.TRUSTED_RECOVERED_GAP_REASON,
        expectedGapStart: Long? = null,
    ) {
        // 唯一入口的来源闸门：注释里写的「只接受两种来源」现在是代码约束，不是期望。
        if (!CollectorRecoveryPolicy.closesLocationGap(source)) {
            DiagnosticLog.event("location", "gap_close_rejected", mapOf(
                "traceId" to traceId, "source" to source.name,
            ))
            return
        }
        locationRecoveryMutex.withLock {
            val gapStartedAt = app.settings.getLong(LOCATION_GAP_START_KEY)
            if (!CollectorRecoveryPolicy.shouldCloseGap(gapStartedAt)) return@withLock
            // 别的入口已经先收口了这一条（起点已被替换/清掉）→ 不重复写缺口。
            if (expectedGapStart != null && expectedGapStart != gapStartedAt) return@withLock
            app.settings.remove(LOCATION_GAP_START_KEY)
            val healedMs = receivedAtMs - gapStartedAt
            if (healedMs <= 0L) {
                // 时钟回拨等异常：起点已经没意义，丢掉起点但不写负长度缺口。
                DiagnosticLog.event("location", "gap_start_discarded", mapOf(
                    "reason" to "clock_rollback",
                    "traceId" to traceId, "gapStartedAt" to gapStartedAt, "receivedAtMs" to receivedAtMs,
                ))
                return@withLock
            }
            DiagnosticLog.event("location", "trusted_location_recovered", mapOf(
                "traceId" to traceId,
                "source" to source.name,
                "provider" to provider,
                "gapStartedAt" to gapStartedAt,
                "gapDurationMs" to healedMs,
                "trustedEvidence" to CollectorRecoveryPolicy.providesTrustedEvidence(source),
                "measurementAgeMs" to (receivedAtMs - measuredAtMs).coerceAtLeast(0L),
                "recoveryAttempts" to locationRecoveryAttempts,
                "probeFailures" to probeFailures,
            ))
            gapDetector.recordGap(SourceId.LOCATION, gapStartedAt, receivedAtMs, reason)
        }
    }

    /**
     * P2 复核修正（审核 #7）：开缺口与关缺口共用同一把锁，避免「起点被替换/丢失」只能靠
     * 「只在键为 0 时写」侥幸成立；将来新增缺口来源时不会再回到 2026-08 的缺口错乱。
     */
    private suspend fun openLocationGap(startMs: Long) {
        if (startMs <= 0L) return
        locationRecoveryMutex.withLock {
            if (app.settings.getLong(LOCATION_GAP_START_KEY) == 0L) {
                app.settings.putLong(LOCATION_GAP_START_KEY, startMs)
                DiagnosticLog.event("location", "gap_opened", mapOf("gapStartedAt" to startMs))
            }
        }
    }

    /**
     * P2：进程重启时把上一段留下的未关闭缺口就地收口到本次启动时刻。
     * 采集真的停过（关服务、被系统杀）要如实留下缺口，但不能让一条缺口横跨多次重启无限延长。
     */
    private fun settleUnclosedLocationGapFromPreviousSession() {
        val gapStartedAt = app.settings.getLong(LOCATION_GAP_START_KEY)
        if (gapStartedAt <= 0L) return
        val settledAt = System.currentTimeMillis()
        scope.launch {
            closeLocationGap(
                source = CollectorRecoveryPolicy.TrustedRecoverySource.PROCESS_RESTART_SETTLE,
                provider = null,
                receivedAtMs = settledAt,
                measuredAtMs = settledAt,
                traceId = nextLocationTraceId("gap_settle"),
                reason = CollectorRecoveryPolicy.RESTART_SETTLED_GAP_REASON,
                expectedGapStart = gapStartedAt,
            )
        }
    }

    /**
     * 主动取位：真实回调断供时由应用自己拿一次位置，不打断现有监听。
     * 优先读系统缓存（零成本），失败后依次尝试 GPS / 网络的 getCurrentLocation（最多各 4 秒）。
     *
     * P2：返回值区分「缓存心跳」和「真实测量」——缓存只喂心跳保候选，不刷新有效数据时间、
     * 不关闭缺口；只有实时取位才按可信恢复处理。
     *
     * [needTrustedEvidence] 为真时（缺口已开/已判定静默），即使命中系统缓存也要继续做一次
     * 实时取位：否则缓存永远新鲜、缺口一旦打开就再也等不到能关它的证据（审核阻断 #1）。
     */
    @SuppressLint("MissingPermission")
    private suspend fun probeLocation(
        reason: String,
        needTrustedEvidence: Boolean = false,
    ): CollectorRecoveryPolicy.TrustedRecoverySource? {
        val requestedAt = System.currentTimeMillis()
        // 1. 系统缓存位置（任何应用定位都会刷新系统缓存，成本为零）。
        //    只接受 90 秒内的新鲜缓存：太旧的缓存可能仍是移动前的地点，
        //    喂给状态机会把用户错误地“钉”在旧地点，延误新地点落定。
        val cached = bestKnownLocation(requestedAt, maxAgeMs = CollectorRecoveryPolicy.HEARTBEAT_MAX_SOURCE_AGE_MS)
        if (cached != null) {
            // 取位时间用它自己的时间戳，不伪造成「现在」——否则缓存点看起来永远新鲜。
            val measuredAt = cached.time.takeIf { it > 0L } ?: requestedAt
            val sampled = Location("probe:lastknown:${cached.provider}").apply {
                latitude = cached.latitude; longitude = cached.longitude
                accuracy = cached.accuracy; time = measuredAt
                if (cached.hasSpeed()) speed = cached.speed
            }
            val traceId = nextLocationTraceId("probe")
            DiagnosticLog.event("location", "location_probed", mapOf(
                "source" to "last_known", "provider" to cached.provider, "accuracyM" to cached.accuracy,
                "reason" to reason, "traceId" to traceId,
                "measurementAgeMs" to (requestedAt - measuredAt).coerceAtLeast(0L),
                "closesGap" to false,
            ))
            locationRecorder.heartbeat(sampled, traceId, requestedAt)
            // 只喂心跳时要收紧：缓存不是「我们拿到的真实测量」，既不能算成功恢复，
            // 也不能在没有其它证据时把这次取位当成「已经取过位」。
            if (CollectorRecoveryPolicy.canShortCircuitOnCacheHit(needTrustedEvidence)) {
                return CollectorRecoveryPolicy.TrustedRecoverySource.PROBE_CACHE
            }
        }
        // 2. 主动请求一次实时位置（GPS 优先，失败退网络；各自独立 4 秒预算，
        //    避免 GPS 无信号时耗尽整个取位窗口导致网络定位没有机会）。
        val live = withTimeoutOrNull(PROBE_SINGLE_TIMEOUT_MS) {
            currentLocationSuspending(LocationManager.GPS_PROVIDER)
        }?.takeIf { it.accuracy <= 200f } ?: withTimeoutOrNull(PROBE_SINGLE_TIMEOUT_MS) {
            currentLocationSuspending(LocationManager.NETWORK_PROVIDER)
        }?.takeIf { it.accuracy <= 200f }
        if (live != null) {
            // 等待最多 8 秒后必须重新取接收时刻：沿用请求前的时间会让日志年龄出现负值，
            // 也会把落库与恢复时刻写成过去。
            val receivedAt = System.currentTimeMillis()
            val measuredAt = live.time.takeIf { it > 0L } ?: receivedAt
            val traceId = nextLocationTraceId("probe")
            // P2 复核修正（审核 #5）：与回调路径同源的新鲜度闸门。
            // OEM 可能把 getCurrentLocation 实现成「直接回系统缓存的旧点」，此时若照旧
            // 关闭缺口，会得到一个漂亮的假结束时刻、并用旧测量刷新有效数据时间。
            if (!CollectorRecoveryPolicy.isTrustedLiveFix(receivedAt, measuredAt, live.accuracy)) {
                DiagnosticLog.event("location", "location_probed", mapOf(
                    "source" to "current", "provider" to live.provider, "accuracyM" to live.accuracy,
                    "reason" to reason, "traceId" to traceId,
                    "measurementAgeMs" to (receivedAt - measuredAt).coerceAtLeast(0L),
                    "freshEnough" to false, "closesGap" to false,
                ))
                locationRecorder.heartbeat(live, traceId, receivedAt)
                return CollectorRecoveryPolicy.TrustedRecoverySource.PROBE_LIVE_STALE
            }
            DiagnosticLog.event("location", "location_probed", mapOf(
                "source" to "current", "provider" to live.provider, "accuracyM" to live.accuracy,
                "reason" to reason, "traceId" to traceId,
                "measurementAgeMs" to (receivedAt - measuredAt).coerceAtLeast(0L),
                "closesGap" to true,
            ))
            lastGoodLocation = Location(live)
            lastAcceptedLocationCallbackAt = receivedAt
            lastLocationDataAt = receivedAt
            locationAttemptSinceAt = 0L
            // 与系统回调共用同一个恢复入口（串行关闭缺口）。
            closeLocationGap(
                source = CollectorRecoveryPolicy.TrustedRecoverySource.PROBE_LIVE,
                provider = live.provider,
                receivedAtMs = receivedAt,
                measuredAtMs = measuredAt,
                traceId = traceId,
            )
            if (shouldRecordNow(receivedAt)) locationRecorder.record(live, traceId, receivedAt)
            else DiagnosticLog.event("location", "record_throttled", mapOf(
                "traceId" to traceId, "source" to "probe", "provider" to live.provider,
            ))
            return CollectorRecoveryPolicy.TrustedRecoverySource.PROBE_LIVE
        }
        DiagnosticLog.event("location", "location_probe_failed", mapOf(
            "reason" to reason,
            "hadCachedHeartbeat" to (cached != null),
            "neededTrustedEvidence" to needTrustedEvidence,
        ))
        return if (cached != null) CollectorRecoveryPolicy.TrustedRecoverySource.PROBE_CACHE else null
    }

    @SuppressLint("MissingPermission")
    private fun bestKnownLocation(nowMs: Long, maxAgeMs: Long): Location? =
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { provider -> runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull() }
            .filter { it.accuracy <= 200f && nowMs - it.time <= maxAgeMs }
            .maxByOrNull { it.time }

    @SuppressLint("MissingPermission")
    private suspend fun currentLocationSuspending(provider: String): Location? =
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            try {
                locationManager.getCurrentLocation(
                    provider,
                    signal,
                    ContextCompat.getMainExecutor(this),
                ) { location ->
                    if (continuation.isActive) continuation.resume(location)
                }
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resume(null)
            }
        }

    override fun onProviderDisabled(provider: String) {
        DiagnosticLog.event("location", "provider_disabled", mapOf("provider" to provider))
        scope.launch { app.repository.setStatus(SourceId.LOCATION, SourceState.SYSTEM_BLOCKED, "$provider 已关闭") }
    }
    override fun onProviderEnabled(provider: String) {
        DiagnosticLog.event("location", "provider_enabled", mapOf("provider" to provider))
        requestLocationUpdates("provider_enabled")
    }
    @Deprecated("Deprecated in Android") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_STEP_COUNTER) scope.launch { stepRecorder.record(event.values[0].toLong()) }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    @SuppressLint("MissingPermission")
    private fun requestLocationUpdates(reason: String) {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) || !app.settings.sourceEnabled(SourceId.LOCATION)) return
        // 看门狗运行在 Dispatchers.IO。LocationManager 的四参数重载会尝试使用调用线程的
        // Looper；后台协程线程没有 Looper，导致每一次自动恢复都必然抛异常。
        // 明确绑定主 Looper，使首次请求、系统回调和后台看门狗使用完全相同的监听线程。
        lastLocationRequestAt = System.currentTimeMillis()
        runCatching {
            locationManager.removeUpdates(this)
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter(locationManager::isProviderEnabled)
            if (providers.isEmpty()) error("系统定位服务已关闭")
            providers.forEach { provider ->
                locationManager.requestLocationUpdates(provider, 30_000, 0f, this, Looper.getMainLooper())
            }
            // v0.16.1 移除 PASSIVE_PROVIDER 实时监听：0/0 参数会把其他应用触发的高频定位
            // 原样灌入（事故日实测 60 点/分钟），是定位洪水推手之一。被动信息仍可通过
            // probeLocation 的 getLastKnownLocation(PASSIVE_PROVIDER) 零成本获取。
            DiagnosticLog.event("location", "updates_requested", mapOf(
                "providers" to providers.joinToString(","),
                "minDistanceM" to 0,
                "reason" to reason,
                "recoveryAttempt" to locationRecoveryAttempts,
            ))
        }.onFailure {
            DiagnosticLog.error("location", "updates_request_failed", it, mapOf("reason" to reason))
            scope.launch { app.repository.setStatus(SourceId.LOCATION, SourceState.SYSTEM_BLOCKED, it.message ?: "定位启动失败") }
        }
    }

    /**
     * P2 复核修正（审核 #9）：停止路径过去直接删掉未关闭的缺口起点、不留任何痕迹，
     * 事故复盘时无法区分「用户停用采集」和「缺口被吞」。用户主动停止/停用期间不落缺口
     * （那是有意的空白，落成缺口会制造假告警），但必须留下可审计记录。
     */
    private fun discardOpenLocationGap(reason: String) {
        val gapStartedAt = app.settings.getLong(LOCATION_GAP_START_KEY)
        if (gapStartedAt > 0L) {
            DiagnosticLog.event("location", "gap_start_discarded", mapOf(
                "reason" to reason,
                "gapStartedAt" to gapStartedAt,
                "discardedAtMs" to System.currentTimeMillis(),
                "unrecordedMs" to (System.currentTimeMillis() - gapStartedAt),
            ))
        }
    }

    private fun stopCollection() {
        gapDetector.clearHeartbeat()
        discardOpenLocationGap("collection_stopped")
        app.settings.remove(LOCATION_GAP_START_KEY)
        stopActiveCollectors()
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    private fun stopActiveCollectors() {
        locationManager.removeUpdates(this)
        sensorManager.unregisterListener(this)
        scope.coroutineContext.cancelChildren()
        lastGoodLocation = null
        lastRawLocationCallbackAt = 0L
        lastAcceptedLocationCallbackAt = 0L
        lastLocationRequestAt = 0L
        locationStaleReported = false
        locationRecoveryAttempts = 0
        lastProbeAt = 0L
        probeFailures = 0
        lastRebuildAt = 0L
        rebuildAttempts = 0
        lastLocationDataAt = 0L
        locationAttemptSinceAt = 0L
        if (!app.settings.sourceEnabled(SourceId.LOCATION)) {
            discardOpenLocationGap("location_source_disabled")
            app.settings.remove(LOCATION_GAP_START_KEY)
        }
    }
    override fun onDestroy() {
        DiagnosticLog.event("service", "collector_destroyed")
        locationManager.removeUpdates(this); sensorManager.unregisterListener(this); scope.cancel()
        if (!app.settings.collectionEnabled || !app.settings.sourceEnabled(SourceId.LOCATION)) {
            discardOpenLocationGap("service_destroyed")
            app.settings.remove(LOCATION_GAP_START_KEY)
        }
        if (app.settings.collectionEnabled) CollectorWatchdogScheduler.schedule(this, soon = true)
        super.onDestroy()
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (app.settings.collectionEnabled) {
            UsageSyncWorker.enqueueRecovery(this)
            CollectorWatchdogScheduler.schedule(this, soon = true)
        }
        DiagnosticLog.event("service", "collector_task_removed")
        super.onTaskRemoved(rootIntent)
    }
    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(
            CHANNEL_ID, getString(R.string.collector_channel), NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "显示后台定位和人生记录的采集状态" })
    }
    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, javaClass).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pi).setContentTitle(getString(R.string.collector_notification_title))
            .setContentText("地点、应用使用与步数仅保存在本机").setOngoing(true).setContentIntent(open)
            .addAction(0, "暂停", stop).build()
    }

    companion object {
        private const val CHANNEL_ID = "life_log_collection"
        private const val NOTIFICATION_ID = 2401
        private const val ACTION_STOP = "com.twentyfourpi.lifelog.STOP"
        private const val ACTION_RECONFIGURE = "com.twentyfourpi.lifelog.RECONFIGURE"
        private const val LOCATION_GAP_START_KEY = "location_callback_gap_started_at"
        private const val MAX_CALLBACK_AGE_MS = CollectorRecoveryPolicy.TRUSTED_FIX_MAX_AGE_MS
        /** v0.16.1 接收端节流：任意来源的两次原始点落库最小间隔（验收：任意 10 分钟 ≤60 点）。 */
        private const val RECORD_MIN_INTERVAL_MS = 10_000L
        /** 主动取位的最小间隔：系统静默期间每 30 秒取一次位，保持数据流不中断。 */
        private const val PROBE_FAST_INTERVAL_MS = 30_000L
        /** 主动取位的常规间隔：回调正常时每 60 秒一次作为兜底，兼顾耗电。 */
        private const val PROBE_NORMAL_INTERVAL_MS = 60_000L
        /** getCurrentLocation 单个 provider 的等待上限，避免占用看门狗循环太久。 */
        private const val PROBE_SINGLE_TIMEOUT_MS = 4_000L
        fun start(context: Context) = context.startForegroundService(Intent(context, LifeLogCollectorService::class.java))
        fun reconfigure(context: Context) = context.startForegroundService(
            Intent(context, LifeLogCollectorService::class.java).setAction(ACTION_RECONFIGURE),
        )
        fun stop(context: Context) = context.stopService(Intent(context, LifeLogCollectorService::class.java))
    }

    private fun nextLocationTraceId(origin: String): String = "$origin-${System.currentTimeMillis()}-${locationTraceSequence.incrementAndGet()}"
    private fun round3(value: Double): Double = round(value * 1_000.0) / 1_000.0
}

internal fun collectorForegroundServiceType(locationEnabled: Boolean, healthEnabled: Boolean): Int {
    var type = 0
    if (locationEnabled) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
    if (healthEnabled) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
    return type.takeIf { it != 0 } ?: ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
}

internal fun isTrustedLocationCallback(
    accuracyM: Float,
    locationTimeMs: Long,
    receivedAtMs: Long,
    maximumAgeMs: Long = 2 * 60_000L,
): Boolean {
    val freshEnough = locationTimeMs <= 0L || (receivedAtMs - locationTimeMs).coerceAtLeast(0L) <= maximumAgeMs
    return accuracyM <= 200f && freshEnough
}
