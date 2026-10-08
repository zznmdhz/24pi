package com.twentyfourpi.lifelog.debug

import android.Manifest
import android.app.ApplicationExitInfo
import android.app.NotificationManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.app.ActivityManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import com.twentyfourpi.lifelog.collector.CollectionGapDetector
import com.twentyfourpi.lifelog.collector.CollectorWatchdogScheduler
import com.twentyfourpi.lifelog.collector.MetadataNotificationListener
import com.twentyfourpi.lifelog.collector.hasNotificationListenerAccess
import com.twentyfourpi.lifelog.collector.hasPermission
import com.twentyfourpi.lifelog.collector.hasUsageAccess
import com.twentyfourpi.lifelog.data.ProjectionMode
import com.twentyfourpi.lifelog.data.GAP_PROJECTION_RAW_SETTING_KEY
import com.twentyfourpi.lifelog.data.DatabaseProvider
import com.twentyfourpi.lifelog.data.DailyRouteBuilder
import com.twentyfourpi.lifelog.data.LifeLogRepository
import com.twentyfourpi.lifelog.data.SettingsStore
import com.twentyfourpi.lifelog.data.SourceId
import com.twentyfourpi.lifelog.data.TimelineDay
import com.twentyfourpi.lifelog.data.assessCollectionGaps
import com.twentyfourpi.lifelog.data.classifyGapImpact
import com.twentyfourpi.lifelog.data.durationMs
import com.twentyfourpi.lifelog.export.CsvEscaper
import com.twentyfourpi.lifelog.ui.DayEpisodeBuilder
import com.twentyfourpi.lifelog.ui.EpisodeSummaryCache
import com.twentyfourpi.lifelog.ui.episodesToChapters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.round

class DiagnosticsExporter(
    private val context: Context,
    private val databaseProvider: DatabaseProvider,
    private val settings: SettingsStore,
    /** P3：投影导出与首页同源（同一版 DayEpisode 投影）。 */
    private val repository: LifeLogRepository,
    private val episodeSummaryCache: EpisodeSummaryCache,
) {
    /** 校验用黑洞输出流：只让 ZipFile 完整读取 entry 以触发 CRC 校验，不占内存。 */
    private val NULL_SINK = object : java.io.OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
    }

    suspend fun export(uri: Uri) = withContext(Dispatchers.IO) {
        DiagnosticLog.event("debug", "export_started")
        val exportedAt = System.currentTimeMillis()
        val since = exportedAt - EXPORT_WINDOW_MS
        // v0.16.1 导出原子化：先写本地临时文件并完整校验中央目录与 CRC，全部成功后才
        // 复制到分享 URI。v0.16.0 事故中导出进程死在崩溃循环里，直写分享流的 ZIP 没有
        // 中央目录，事故窗口的日志因此永久丢失（见 docs/INCIDENT_HANDOFF_V0.16.0_2026-08-26.md §3）。
        val tempFile = File.createTempFile("diagnostics-", ".zip", context.cacheDir)
        try {
            ZipOutputStream(tempFile.outputStream().buffered()).use { zip ->
                zip.writeText("README.txt", """
                    24π·人生记录观察版诊断包
                    生成时间：${Instant.now()}

                    用途：还原系统输入、24π 内部决策、数据库结果和界面投影之间的完整链路。
                    隐私：不包含通知标题、正文、联系人、备份密码或高德 Key。
                    注意：包含最近 7 天的应用包名、通知匿名标识、完整错误堆栈，以及约 100 米精度的位置点，请只发送给你信任的人。
                """.trimIndent())
                zip.writeText("device-and-permissions.txt", snapshot())
                writeProcessExitReasons(zip)
                val usageEvents = writeUsageEvents(zip, since, exportedAt)
                val usageAggregateCount = writeUsageAggregate(zip, since, exportedAt)
                val databaseCounts = writeDatabaseSnapshots(zip, since)
                val projectedChapterCount = writeArchiveProjection(zip, since, exportedAt)
                val projectedRouteCount = writeDailyRouteProjection(zip, since, exportedAt)
                val logFiles = DiagnosticLog.files()
                logFiles.forEachIndexed { index, file ->
                    zip.putNextEntry(ZipEntry("runtime-${index.toString().padStart(3, '0')}-${file.name}"))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                zip.writeText("manifest.txt", buildString {
                    appendLine("formatVersion=4")
                    appendLine("windowStartMs=$since")
                    appendLine("windowEndMs=$exportedAt")
                    appendLine("usageEvents=${usageEvents.count}")
                    appendLine("usageEventsTruncated=${usageEvents.truncated}")
                    appendLine("usageAggregateRows=$usageAggregateCount")
                    appendLine("projectedTimeChapters=$projectedChapterCount")
                    appendLine("projectedDailyRoutes=$projectedRouteCount")
                    databaseCounts.forEach { (name, count) -> appendLine("$name=$count") }
                    appendLine("runtimeSegments=${logFiles.size}")
                    appendLine("runtimeBytes=${logFiles.sumOf(File::length)}")
                    logFiles.forEachIndexed { index, file ->
                        appendLine("runtime.$index=${file.name}|${file.length()}|${file.lastModified()}|${sha256(file)}")
                    }
                })
            }
            verifyZip(tempFile)
            context.contentResolver.openOutputStream(uri, "w")?.use { raw ->
                tempFile.inputStream().use { it.copyTo(raw) }
            } ?: error("无法创建诊断文件")
            DiagnosticLog.event("debug", "export_completed", mapOf(
                "verifiedBytes" to tempFile.length(),
                "verifiedEntries" to verifiedEntryCount,
            ))
        } finally {
            tempFile.delete()
        }
    }

    /**
     * v0.16.1：读取整个 ZIP 的中央目录并流式通过每个 entry（ZipFile 在读取时校验 CRC），
     * 任何截断/损坏都会在这里抛错，而不是把坏文件交给用户。
     */
    private fun verifyZip(file: File) {
        java.util.zip.ZipFile(file).use { zf ->
            val entries = zf.entries().asSequence().filterNot { it.isDirectory }.toList()
            check(entries.isNotEmpty()) { "诊断包没有任何条目" }
            check(entries.any { it.name == "manifest.txt" }) { "诊断包缺少 manifest.txt" }
            entries.forEach { entry ->
                zf.getInputStream(entry).use { it.copyTo(NULL_SINK) }
            }
            verifiedEntryCount = entries.size
        }
    }

    private var verifiedEntryCount: Int = 0

    private fun writeProcessExitReasons(zip: ZipOutputStream) {
        var rowFailures = 0
        val retrieval = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Result.success(emptyList())
        } else runCatching {
            context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, 0, 30)
                .toList()
        }.onFailure {
            DiagnosticLog.error("debug", "process_exit_reasons_read_failed", it)
        }
        zip.writeText("process-exit-reasons.csv", buildString {
            appendLine("timestamp,reason,reasonName,status,importance,pssKb,rssKb,description")
            retrieval.getOrDefault(emptyList()).forEach { info ->
                runCatching {
                    val reason = info.reason
                    appendLine(
                        "${info.timestamp},$reason,${csv(processExitReasonName(reason))},${info.status},${info.importance},${info.pss},${info.rss},${csv(info.description.orEmpty())}",
                    )
                }.onFailure {
                    rowFailures++
                    DiagnosticLog.error("debug", "process_exit_reason_row_failed", it)
                }
            }
        })
        val status = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R -> "unsupported: Android ${Build.VERSION.SDK_INT}"
            retrieval.isFailure -> "unavailable: ${retrieval.exceptionOrNull()?.javaClass?.simpleName}: ${retrieval.exceptionOrNull()?.message.orEmpty()}"
            else -> "ok: ${retrieval.getOrDefault(emptyList()).size} rows, $rowFailures skipped"
        }
        // 厂商 ROM 偶尔会拒绝或破坏 historical exit API；状态单独落盘，不能让它
        // 阻断其余更重要的权限、心跳和数据库诊断文件。
        zip.writeText("process-exit-reasons-status.txt", status)
    }

    private suspend fun snapshot(): String {
        val snapshotAt = System.currentTimeMillis()
        val pm = context.packageManager
        val packageInfo = pm.getPackageInfo(context.packageName, 0)
        val install = runCatching { pm.getInstallSourceInfo(context.packageName) }.getOrNull()
        val location = context.getSystemService(LocationManager::class.java)
        val sensors = context.getSystemService(SensorManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val statusesResult = runCatching { databaseProvider.get().dao().debugStatuses() }
            .onFailure { DiagnosticLog.error("debug", "source_status_snapshot_failed", it) }
        val collectorHeartbeatAt = settings.getLong(CollectionGapDetector.HEARTBEAT_KEY)
        val locationGapStartedAt = settings.getLong(LOCATION_GAP_START_KEY)
        val notificationHeartbeatAt = MetadataNotificationListener.persistedHeartbeatAt(settings)
        val notificationGapStartedAt = MetadataNotificationListener.pendingGapStartedAt(settings)
        val listenerCallbackAt = MetadataNotificationListener.lastCallbackTimestamp()
        val logStats = DiagnosticLog.stats()
        return buildString {
            appendLine("snapshotAt=$snapshotAt")
            appendLine("appVersion=${packageInfo.versionName} (${packageInfo.longVersionCode})")
            appendLine("package=${context.packageName}")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT} display=${Build.DISPLAY}")
            appendLine("installingPackage=${install?.installingPackageName}")
            appendLine("initiatingPackage=${install?.initiatingPackageName}")
            if (Build.VERSION.SDK_INT >= 33) appendLine("packageSource=${install?.packageSource}")
            appendLine("collectionEnabled=${settings.collectionEnabled}")
            appendLine("collectorHeartbeatAt=$collectorHeartbeatAt")
            appendLine("collectorHeartbeatAgeMs=${ageMs(snapshotAt, collectorHeartbeatAt)}")
            appendLine("locationGapStartedAt=$locationGapStartedAt")
            appendLine("locationGapAgeMs=${ageMs(snapshotAt, locationGapStartedAt)}")
            appendLine("notificationListenerRuntimeConnected=${MetadataNotificationListener.isConnected()}")
            appendLine("notificationListenerLastCallbackAt=$listenerCallbackAt")
            appendLine("notificationListenerLastCallbackAgeMs=${ageMs(snapshotAt, listenerCallbackAt)}")
            appendLine("notificationListenerHeartbeatAt=$notificationHeartbeatAt")
            appendLine("notificationListenerHeartbeatAgeMs=${ageMs(snapshotAt, notificationHeartbeatAt)}")
            appendLine("notificationGapStartedAt=$notificationGapStartedAt")
            appendLine("notificationGapAgeMs=${ageMs(snapshotAt, notificationGapStartedAt)}")
            appendLine("diagnosticSegments=${logStats.segmentCount}")
            appendLine("diagnosticBytes=${logStats.totalBytes}")
            appendLine("diagnosticPendingWrites=${logStats.pendingWrites}")
            appendLine("diagnosticDroppedEvents=${logStats.droppedEvents}")
            appendLine("diagnosticRetentionHours=${logStats.retentionHours}")
            appendLine("diagnosticMaximumBytes=${logStats.maximumBytes}")
            appendLine("watchdogExactAlarmAccess=${runCatching { CollectorWatchdogScheduler.hasExactAlarmAccess(context) }.getOrNull()}")
            appendLine("ignoringBatteryOptimizations=${runCatching { power.isIgnoringBatteryOptimizations(context.packageName) }.getOrNull()}")
            appendLine("startedAt=${settings.startedAt}")
            appendLine("usageAccess=${context.hasUsageAccess()}")
            appendLine("notificationListener=${runCatching { context.hasNotificationListenerAccess() }.getOrNull()}")
            appendLine("postNotificationsPermission=${context.hasPermission(Manifest.permission.POST_NOTIFICATIONS)}")
            appendLine("appNotificationsEnabled=${runCatching { notificationManager.areNotificationsEnabled() }.getOrNull()}")
            appendLine("collectorNotificationChannelImportance=${runCatching { notificationManager.getNotificationChannel(COLLECTOR_CHANNEL_ID)?.importance }.getOrNull()}")
            appendLine("notificationContentEnabled=${settings.notificationContentEnabled}")
            appendLine("fineLocation=${context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)}")
            appendLine("backgroundLocation=${context.hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)}")
            appendLine("activityRecognition=${context.hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)}")
            appendLine("gpsProvider=${runCatching { location.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)}")
            appendLine("networkProvider=${runCatching { location.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)}")
            appendLine("stepCounterSensor=${runCatching { sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.name }.getOrNull() ?: "unavailable"}")
            appendLine("placeNameConsent=${settings.placeNameConsent}")
            appendLine("deviceGeocoderPresent=${runCatching { android.location.Geocoder.isPresent() }.getOrNull()}")
            appendLine("amapConsent=${settings.amapConsent} keyConfigured=${settings.amapKey.isNotBlank()}")
            SourceId.entries.forEach { appendLine("source.${it.name}.enabled=${settings.sourceEnabled(it)}") }
            statusesResult.getOrDefault(emptyList()).forEach { appendLine("status.${it.source}=${it.state}|${it.lastUpdatedMs}|${it.detail}") }
            statusesResult.exceptionOrNull()?.let { appendLine("statusReadError=${it.javaClass.simpleName}|${it.message.orEmpty()}") }
        }
    }

    private data class UsageEventExport(val count: Int, val truncated: Boolean)

    private fun writeUsageEvents(zip: ZipOutputStream, since: Long, until: Long): UsageEventExport {
        val manager = context.getSystemService(UsageStatsManager::class.java)
        var count = 0
        var truncated = false
        zip.putNextEntry(ZipEntry("usage-events-7d.csv"))
        zip.writer(Charsets.UTF_8).apply {
            write("timestamp,eventType,eventName,package\n")
            val events = manager.queryEvents(since, until)
            val event = UsageEvents.Event()
            while (events.hasNextEvent() && count < MAX_USAGE_EVENTS) {
                events.getNextEvent(event); count++
                write("${event.timeStamp},${event.eventType},${csv(eventName(event.eventType))},${csv(event.packageName.orEmpty())}\n")
            }
            truncated = events.hasNextEvent()
            flush()
        }
        zip.closeEntry()
        return UsageEventExport(count, truncated)
    }

    private fun writeUsageAggregate(zip: ZipOutputStream, since: Long, until: Long): Int {
        val manager = context.getSystemService(UsageStatsManager::class.java)
        var count = 0
        zip.putNextEntry(ZipEntry("usage-aggregate-7d.csv"))
        zip.writer(Charsets.UTF_8).apply {
            write("package,totalForegroundMs,lastUsedMs\n")
            manager.queryAndAggregateUsageStats(since, until).forEach { (pkg, stat) ->
                if (stat.totalTimeInForeground > 0) {
                    count++
                    write("${csv(pkg)},${stat.totalTimeInForeground},${stat.lastTimeUsed}\n")
                }
            }
            flush()
        }
        zip.closeEntry()
        return count
    }

    private suspend fun writeDatabaseSnapshots(zip: ZipOutputStream, since: Long): Map<String, Int> {
        val dao = databaseProvider.get().dao()
        val apps = dao.debugAppSessions(since)
        val notifications = dao.debugNotificationEvents(since)
        val points = dao.debugLocationPoints(since)
        val places = dao.debugPlaces()
        val visits = dao.debugVisits(since)
        val gaps = dao.debugCollectionGaps(since)
        zip.writeText("recorded-app-sessions.csv", buildString {
            appendLine("startMs,endMs,package,label")
            apps.forEach { appendLine("${it.startMs},${it.endMs},${csv(it.packageName)},${csv(it.appLabel)}") }
        })
        zip.writeText("recorded-notification-metadata.csv", buildString {
            appendLine("occurredMs,removedMs,package,label,action,captureOrigin,reason,channel,keyHash")
            notifications.forEach {
                appendLine("${it.occurredMs},${it.removedMs ?: ""},${csv(it.packageName)},${csv(it.appLabel)},${it.action},${it.captureOrigin},${it.reasonCode ?: ""},${csv(it.channelId.orEmpty())},${csv(it.notificationKeyHash.orEmpty())}")
            }
        })
        zip.writeText("recorded-location-points.csv", buildString {
            appendLine("id,recordedMs,measuredMs,elapsedRealtimeNanos,latitudeApprox,longitudeApprox,accuracyM,provider,speedMps,isMock")
            points.forEach {
                appendLine("${it.id},${it.recordedMs},${it.measuredMs},${it.elapsedRealtimeNanos},${round3(it.latitude)},${round3(it.longitude)},${it.accuracyM},${csv(it.provider)},${it.speedMps ?: ""},${it.isMock}")
            }
        })
        zip.writeText("recorded-place-visits.csv", buildString {
            // 诊断地点聚合只需要匿名 placeId 与模糊坐标；自定义名称和完整地址可能直接
            // 暴露住宅或公司，不能因为用户导出调试包就一并泄露。
            appendLine("startMs,endMs,placeId,latitudeApprox,longitudeApprox,confidence")
            visits.forEach {
                appendLine("${it.startMs},${it.endMs},${it.placeId},${round3(it.latitude)},${round3(it.longitude)},${it.confidence}")
            }
        })
        zip.writeText("recorded-place-stats.csv", buildString {
            appendLine("placeId,latitudeApprox,longitudeApprox,visitCount,lastVisitMs,ignored,customName")
            places.forEach {
                appendLine("${it.id},${round3(it.latitude)},${round3(it.longitude)},${it.visitCount},${it.lastVisitMs},${it.ignored},${it.isCustomName}")
            }
        })
        zip.writeText("recorded-collection-gaps.csv", buildString {
            appendLine("source,startMs,endMs,durationMs,provisionalUserImpact,reason")
            gaps.forEach {
                appendLine("${it.source},${it.startMs},${it.endMs},${it.durationMs},${classifyGapImpact(it)},${it.reason}")
            }
        })
        writeGapQualitySummary(zip, gaps, since, System.currentTimeMillis())
        return linkedMapOf(
            "recordedAppSessions" to apps.size,
            "recordedNotifications" to notifications.size,
            "recordedLocationPoints" to points.size,
            "recordedPlaces" to places.size,
            "recordedPlaceVisits" to visits.size,
            "recordedCollectionGaps" to gaps.size,
        )
    }

    private fun writeGapQualitySummary(
        zip: ZipOutputStream,
        gaps: List<com.twentyfourpi.lifelog.data.CollectionGapEntity>,
        since: Long,
        until: Long,
    ) {
        val zone = ZoneId.systemDefault()
        val firstDate = Instant.ofEpochMilli(since).atZone(zone).toLocalDate()
        val lastDate = Instant.ofEpochMilli(until).atZone(zone).toLocalDate()
        zip.writeText("gap-quality-summary.csv", buildString {
            appendLine("date,source,rawCount,mergedCount,hiddenCount,briefCount,importantCount,hiddenMs,briefMs,importantMs")
            generateSequence(firstDate) { date -> date.plusDays(1).takeIf { it <= lastDate } }.forEach { date ->
                val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
                val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                SourceId.entries.forEach { source ->
                    val raw = gaps.filter { it.source == source.name && it.startMs < dayEnd && it.endMs > dayStart }
                    if (raw.isNotEmpty()) {
                        val assessment = assessCollectionGaps(raw, dayStart, dayEnd)
                        appendLine(
                            "$date,${source.name},${raw.size},${assessment.mergedGaps.size}," +
                                "${assessment.hiddenGaps.size},${assessment.briefGaps.size},${assessment.importantGaps.size}," +
                                "${assessment.hiddenDurationMs},${assessment.briefDurationMs},${assessment.importantDurationMs}",
                        )
                    }
                }
            }
        })
    }

    /** Captures the exact derived chapter boundaries the current UI would build from raw rows. */
    private suspend fun writeArchiveProjection(zip: ZipOutputStream, since: Long, until: Long): Int {
        val dao = databaseProvider.get().dao()
        val apps = dao.appSessionsInRange(since, until)
        val notifications = dao.notificationsInRange(since, until)
        val visits = dao.visitsInRange(since, until)
        val gaps = dao.collectionGapsInRange(since, until)
        val zone = ZoneId.systemDefault()
        val firstDate = Instant.ofEpochMilli(since).atZone(zone).toLocalDate()
        val lastDate = Instant.ofEpochMilli(until).atZone(zone).toLocalDate()
        // 第四批：缺口投影与首页/档案汇总共用同一份——同一规则版本、同一截止时刻、同一撤销开关。
        // 外部复核才能拿导出包对照界面，不必猜「这一行是哪一套解释」（复核稿第 9 节）。
        val gapMode = if (settings.getBoolean(GAP_PROJECTION_RAW_SETTING_KEY, false)) {
            ProjectionMode.RAW
        } else {
            ProjectionMode.EFFECTIVE
        }
        val gapProjection = repository.projectionFor(firstDate, lastDate, gapMode)
        var count = 0
        var cacheHits = 0
        var recomputes = 0
        zip.putNextEntry(ZipEntry("projected-time-chapters.csv"))
        zip.writer(Charsets.UTF_8).apply {
            write(
                "date,chapterKey,startMs,endMs,placeId,placeVisitIds,appSessionIds,notificationIds,gapIds," +
                    "unknownRangeCount,projectionSource,projectionVersion,projectionCutoffMs," +
                    "locationUnknownOverlapMs,locationUnknownSevere,gapRuleVersion,gapMode,gapAsOfMs\n",
            )
            generateSequence(firstDate) { date -> date.plusDays(1).takeIf { it <= lastDate } }.forEach { date ->
                val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
                val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val day = TimelineDay(
                    apps = apps.filter { it.startMs < dayEnd && it.endMs > dayStart },
                    notifications = notifications.filter { it.occurredMs in dayStart until dayEnd },
                    visits = visits.filter { it.startMs < dayEnd && it.endMs > dayStart },
                    gaps = gaps.filter { it.startMs < dayEnd && it.endMs > dayStart },
                )
                // P3：与首页同源——同一版 DayEpisode 投影，不再用旧 TimeChapterComposer。
                // 优先复用应用级缓存里已经算好的那一天（截止时刻与首页一致）；未命中才在这里补算。
                // 每一行都写明来源与截止时刻，外部复核才不用猜「这一行到底是哪一套算法/哪个时刻」（复核 #3/#6）。
                val cached = episodeSummaryCache.entryOrNull(date)
                val episodes = cached?.episodes ?: repository.dailyEpisodes(date)
                val cutoffMs = cached?.dataCutoffMs ?: (episodes.maxOfOrNull { it.endMs } ?: dayEnd)
                val source = if (cached != null) "day-episode-cache" else "day-episode-recompute"
                if (cached != null) cacheHits++ else recomputes++
                episodesToChapters(episodes, day).forEach { chapter ->
                    count++
                    write(
                        "${date},${csv(chapter.key)},${chapter.startMs},${chapter.endMs},${chapter.place?.placeId ?: ""}," +
                            "${csv(chapter.placeVisits.joinToString("|") { it.id.toString() })}," +
                            "${csv(chapter.apps.joinToString("|") { it.id.toString() })}," +
                            "${csv(chapter.notifications.joinToString("|") { it.id.toString() })}," +
                            "${csv(chapter.gaps.joinToString("|") { it.id.toString() })}," +
                            // 口径变更：这一列来自 DayEpisode.unknownRanges（未观测区间），
                            // 不再是旧 TimeChapterComposer 的「地点连续性缺口」（子代理复核 #5）。
                            "${chapter.placeContinuityGaps.size}," +
                            "$source,${DayEpisodeBuilder.PROJECTION_VERSION},$cutoffMs," +
                            // 第四批：这张卡与有效投影的交集时长（界面上的分钟数就是它），
                            // 以及是否达到"实质缺失"红卡门槛——外部可逐卡对照界面文案。
                            "${gapProjection.unknownOverlapMs(chapter.startMs, chapter.endMs, SourceId.LOCATION.name)}," +
                            "${gapProjection.isSevereCard(chapter.startMs, chapter.endMs, SourceId.LOCATION.name)}," +
                            "${gapProjection.ruleVersion},${gapProjection.mode},${gapProjection.asOfMs}\n",
                    )
                }
            }
            flush()
            DiagnosticLog.event("debug", "archive_projection_exported", mapOf(
                "chapters" to count,
                "projection" to "day-episode",
                "projection_version" to DayEpisodeBuilder.PROJECTION_VERSION,
                "days_from_cache" to cacheHits,
                "days_recomputed" to recomputes,
                "gap_rule_version" to gapProjection.ruleVersion,
                "gap_mode" to gapProjection.mode,
                "gap_as_of_ms" to gapProjection.asOfMs,
            ))
        }
        zip.closeEntry()

        // 第四批：缺口投影逐段导出——原始区间、重建后的分片（实测/推断/未知）、证据引用
        // 与分析版本。外部拿到包就能重放"为什么这段算未知、凭什么算有依据"（复核稿第 9 节）。
        zip.putNextEntry(ZipEntry("gap-projection.csv"))
        zip.writer(Charsets.UTF_8).apply {
            write(
                "gapRuleVersion,gapMode,asOfMs,generatedAtMs,inputDigest,windowStartMs,windowEndMs," +
                    "rawGapId,source,rawStartMs,rawEndMs,rawReason,supersededByEvidence,actionableReason," +
                    "segmentIndex,support,segmentStartMs,segmentEndMs,evidence\n",
            )
            gapProjection.gaps.forEach { gap ->
                if (gap.intervals.isEmpty()) {
                    write(
                        "${gapProjection.ruleVersion},${gapProjection.mode},${gapProjection.asOfMs}," +
                            "${gapProjection.generatedAtMs},${csv(gapProjection.inputDigest)}," +
                            "${gapProjection.windowStartMs},${gapProjection.windowEndMs}," +
                            "${gap.rawGapId},${gap.source},${gap.rawStartMs},${gap.rawEndMs}," +
                            "${csv(gap.rawReason)},${gap.supersededByEvidence},${gap.actionableReason},,,\n",
                    )
                }
                gap.intervals.forEachIndexed { index, segment ->
                    write(
                        "${gapProjection.ruleVersion},${gapProjection.mode},${gapProjection.asOfMs}," +
                            "${gapProjection.generatedAtMs},${csv(gapProjection.inputDigest)}," +
                            "${gapProjection.windowStartMs},${gapProjection.windowEndMs}," +
                            "${gap.rawGapId},${gap.source},${gap.rawStartMs},${gap.rawEndMs}," +
                            "${csv(gap.rawReason)},${gap.supersededByEvidence},${gap.actionableReason}," +
                            "$index,${segment.support},${segment.startMs},${segment.endMs}," +
                            "${csv(segment.evidence.joinToString("|") { "${it.kind}:${it.refId}@${it.atMs}" })}\n",
                    )
                }
            }
            flush()
        }
        zip.closeEntry()
        return count
    }

    /** Replays the exact route algorithm without requiring the user to have opened each day. */
    private suspend fun writeDailyRouteProjection(zip: ZipOutputStream, since: Long, until: Long): Int {
        val dao = databaseProvider.get().dao()
        val points = dao.locationPointsInRange(since, until)
        val visits = dao.visitsInRange(since, until)
        val gaps = dao.collectionGapsInRange(since, until)
        val zone = ZoneId.systemDefault()
        val firstDate = Instant.ofEpochMilli(since).atZone(zone).toLocalDate()
        val lastDate = Instant.ofEpochMilli(until).atZone(zone).toLocalDate()
        var count = 0
        zip.putNextEntry(ZipEntry("projected-daily-routes.csv"))
        zip.writer(Charsets.UTF_8).apply {
            write("date,rawPoints,rejectedPoints,sections,interruptions,observedDistanceMeters,firstMs,lastMs,sectionDetails,interruptionDetails\n")
            generateSequence(firstDate) { date -> date.plusDays(1).takeIf { it <= lastDate } }.forEach { date ->
                val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
                val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val dayPoints = points.filter { it.recordedMs in dayStart until dayEnd }
                val route = DailyRouteBuilder.build(
                    rawPoints = dayPoints,
                    visits = visits.filter { it.startMs < dayEnd && it.endMs > dayStart },
                    gaps = gaps.filter { it.startMs < dayEnd && it.endMs > dayStart },
                )
                if (dayPoints.isNotEmpty()) count++
                val sectionDetails = route.sections.mapIndexed { index, section ->
                    "${index + 1}:${section.first.timeMs}-${section.last.timeMs}:${section.distanceMeters.toLong()}m:${section.points.size}points"
                }.joinToString("|")
                val interruptionDetails = route.interruptions.joinToString("|") {
                    "${it.lostAt.timeMs}-${it.recoveredAt.timeMs}:${it.durationMs}ms:${it.reason}"
                }
                write(
                    "$date,${dayPoints.size},${route.rejectedPointCount},${route.sections.size}," +
                        "${route.interruptions.size},${route.observedDistanceMeters.toLong()}," +
                        "${route.firstRecorded?.timeMs ?: ""},${route.lastRecorded?.timeMs ?: ""}," +
                        "${csv(sectionDetails)},${csv(interruptionDetails)}\n",
                )
            }
            flush()
        }
        zip.closeEntry()
        return count
    }

    private fun ZipOutputStream.writeText(name: String, text: String) {
        putNextEntry(ZipEntry(name)); write(text.toByteArray(Charsets.UTF_8)); closeEntry()
    }

    private fun csv(value: String): String = CsvEscaper.cell(value)
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun ageMs(nowMs: Long, timestampMs: Long): String =
        timestampMs.takeIf { it > 0L }?.let { (nowMs - it).coerceAtLeast(0L).toString() }.orEmpty()

    private fun processExitReasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        else -> "UNKNOWN_$reason"
    }
    private fun round3(value: Double): Double = round(value * 1_000.0) / 1_000.0
    private fun eventName(type: Int): String = when (type) {
        UsageEvents.Event.ACTIVITY_RESUMED -> "ACTIVITY_RESUMED"
        UsageEvents.Event.ACTIVITY_PAUSED -> "ACTIVITY_PAUSED"
        UsageEvents.Event.ACTIVITY_STOPPED -> "ACTIVITY_STOPPED"
        UsageEvents.Event.SCREEN_INTERACTIVE -> "SCREEN_INTERACTIVE"
        UsageEvents.Event.SCREEN_NON_INTERACTIVE -> "SCREEN_NON_INTERACTIVE"
        UsageEvents.Event.KEYGUARD_SHOWN -> "KEYGUARD_SHOWN"
        UsageEvents.Event.KEYGUARD_HIDDEN -> "KEYGUARD_HIDDEN"
        UsageEvents.Event.USER_INTERACTION -> "USER_INTERACTION"
        else -> "TYPE_$type"
    }

    companion object {
        private const val EXPORT_WINDOW_MS = 7L * 24 * 60 * 60_000
        private const val MAX_USAGE_EVENTS = 500_000
        private const val LOCATION_GAP_START_KEY = "location_callback_gap_started_at"
        private const val COLLECTOR_CHANNEL_ID = "life_log_collection"
    }
}
