package com.twentyfourpi.lifelog.data

import androidx.room.withTransaction
import com.twentyfourpi.lifelog.debug.DiagnosticLog
import com.twentyfourpi.lifelog.ui.DayEpisode
import com.twentyfourpi.lifelog.ui.DayEpisodeBuilder
import com.twentyfourpi.lifelog.ui.DayEpisodeInput
import com.twentyfourpi.lifelog.util.calculateSteps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class LifeLogRepository(private val provider: DatabaseProvider) {
    private val dao get() = provider.get().dao()

    fun observeDay(date: LocalDate): Flow<TimelineDay> {
        val zone = ZoneId.systemDefault()
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val first = combine(
            dao.observeAppSessions(start, end), dao.observeScreenSessions(start, end),
            dao.observeNotifications(start, end), dao.observeVisits(start, end),
        ) { apps, screens, notifications, visits -> TimelineDay(apps, screens, notifications, visits) }
        val activity = combine(first, dao.observeSteps(start, end), dao.observeSleeps(start, end)) { day, steps, sleeps ->
            day.copy(steps = steps, sleeps = sleeps)
        }
        val withGaps = combine(activity, dao.observeCollectionGaps(start, end)) { day, gaps ->
            day.copy(gaps = gaps)
        }
        // v0.16.1：TimelineDay 不再携带当天全量原始定位点。原始点 Flow 会让每个新点
        // 触发整天重发与全量重算（v0.16.0 事故根因），且时间页其他区块并不使用它。
        // 轨迹改为按需一次性加载：见 [dailyRoute]。
        return withGaps
    }

    /** 一次性加载某天的原始定位点并构建全天轨迹（用户展开轨迹面板时调用）。 */
    suspend fun dailyRoute(date: LocalDate): DailyRoute? = withContext(Dispatchers.Default) {
        val zone = ZoneId.systemDefault()
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val points = dao.locationPointsInRange(start, end)
        if (points.isEmpty()) return@withContext DailyRoute(rejectedPointCount = 0)
        DailyRouteBuilder.build(
            rawPoints = points,
            visits = dao.visitsInRange(start, end).filter { it.startMs < end && it.endMs > start },
            gaps = dao.collectionGapsInRange(start, end).filter { it.startMs < end && it.endMs > start },
        )
    }

    /**
     * F 批（方案 16.6）：一次性加载某天的首页“日常时段”投影。
     *
     * 只读取有界范围（应用/通知/到访/缺口/地点），复用 [dailyRoute] 的行程证据，
     * 依次过 [PlaceAttribution]（稳定归属）与 [DayEpisodeBuilder]（语义时段）。
     * 由 [EpisodeSummaryCache] 在后台调用；本函数不做缓存、可随时重算。
     */
    suspend fun dailyEpisodes(date: LocalDate, placeNames: Map<Long, String> = emptyMap()): List<DayEpisode> {
        val zone = ZoneId.systemDefault()
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val naturalEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        // R11：今天尚未发生的时间不该变成“未知”时段——end 截到当前时刻。
        val end = if (date == LocalDate.now()) minOf(naturalEnd, System.currentTimeMillis()) else naturalEnd
        if (end <= start) return emptyList()
        val apps = dao.appSessionsInRange(start, end)
        val notifications = dao.notificationsInRange(start, end)
        val visits = dao.visitsInRange(start, end).filter { it.startMs < end && it.endMs > start }
        val gaps = dao.collectionGapsInRange(start, end).filter { it.startMs < end && it.endMs > start }
        val screens = dao.screenSessionsInRange(start, end)
        // 行程证据：复用 dailyRoute（内部有 DailyRouteBuilder；缓存命中则秒回）。
        val route = dailyRoute(date)
        val day = TimelineDay(
            apps = apps,
            screens = screens,
            notifications = notifications,
            visits = visits,
            gaps = gaps,
        )
        return DayEpisodeBuilder.build(
            DayEpisodeInput(
                day = day,
                route = route,
                dayStartMs = start,
                dayEndMs = end,
                placeNames = placeNames,
                confirmedVisitIds = dao.attributionRevisions().filter { it.revertedBy == null }.distinctBy { it.targetKey }
                    .filter { it.toPlaceId != null }.mapNotNull { it.targetKey.removePrefix("visit:").toLongOrNull() }.toSet(),
            ),
        )
    }

    fun observePlaces() = dao.observePlaces()
    fun observeLatestLocation() = dao.observeLatestLocationPoint()
    fun observeRecentVisits(limit: Int = 100) = dao.observeRecentVisits(limit)
    fun observeAllVisits() = dao.observeAllVisits()
    suspend fun affectedVisitsForMerge(sourceId: Long) = dao.affectedVisitsForMerge(sourceId)
    fun observeAllCollectionGaps() = dao.observeAllCollectionGaps()
    fun observeStatuses() = dao.observeSourceStatuses()
    suspend fun sourceStatus(source: SourceId) = dao.sourceStatus(source.name)

    suspend fun notificationsAllInRange(startMs: Long, endMs: Long) = dao.notificationsAllInRange(startMs, endMs)
    suspend fun notificationAppSummary(fromMs: Long, toMs: Long) = dao.notificationAppSummary(fromMs, toMs)
    suspend fun notificationSearch(fromMs: Long, toMs: Long, query: String) = dao.notificationSearch(fromMs, toMs, query)
    suspend fun placeStaySummary(fromMs: Long, toMs: Long) = dao.placeStaySummary(fromMs, toMs)
    suspend fun appSessionsInRange(startMs: Long, endMs: Long) = dao.appSessionsInRange(startMs, endMs)
    suspend fun knownRecordedPackages() = dao.knownRecordedPackages()

    suspend fun updateRecordedAppLabel(packageName: String, label: String) {
        if (packageName.isBlank() || label.isBlank()) return
        provider.get().withTransaction {
            dao.updateAppSessionLabels(packageName, label)
            dao.updateNotificationLabels(packageName, label)
        }
    }

    suspend fun appendAppSession(pkg: String, label: String, start: Long, end: Long) {
        if (end <= start) return
        val previous = dao.latestAppSession(pkg, start)
        if (previous != null && start - previous.endMs <= 30_000) {
            val updatedEnd = maxOf(previous.endMs, end)
            dao.updateAppSession(previous.copy(endMs = updatedEnd))
            DiagnosticLog.event("usage", "app_session_persisted", mapOf(
                "decision" to "MERGE",
                "sessionId" to previous.id,
                "package" to pkg,
                "label" to label,
                "inputStartMs" to start,
                "inputEndMs" to end,
                "previousEndMs" to previous.endMs,
                "gapMs" to start - previous.endMs,
                "resultStartMs" to previous.startMs,
                "resultEndMs" to updatedEnd,
            ))
        } else {
            val insertedId = dao.insertAppSession(AppSessionEntity(packageName = pkg, appLabel = label, startMs = start, endMs = end))
            DiagnosticLog.event("usage", "app_session_persisted", mapOf(
                "decision" to "INSERT",
                "sessionId" to insertedId,
                "package" to pkg,
                "label" to label,
                "inputStartMs" to start,
                "inputEndMs" to end,
                "previousSessionId" to previous?.id,
                "previousEndMs" to previous?.endMs,
                "gapMs" to previous?.let { start - it.endMs },
            ))
        }
    }

    suspend fun appendScreenSession(start: Long, end: Long) {
        if (end <= start) return
        val previous = dao.latestScreenSession(start)
        if (previous != null && start - previous.endMs <= 60_000) {
            val updatedEnd = maxOf(previous.endMs, end)
            dao.updateScreenSession(previous.copy(endMs = updatedEnd))
            DiagnosticLog.event("usage", "screen_session_persisted", mapOf(
                "decision" to "MERGE", "sessionId" to previous.id,
                "inputStartMs" to start, "inputEndMs" to end,
                "previousEndMs" to previous.endMs, "gapMs" to start - previous.endMs,
                "resultStartMs" to previous.startMs, "resultEndMs" to updatedEnd,
            ))
        } else {
            val insertedId = dao.insertScreenSession(ScreenSessionEntity(startMs = start, endMs = end))
            DiagnosticLog.event("usage", "screen_session_persisted", mapOf(
                "decision" to "INSERT", "sessionId" to insertedId,
                "inputStartMs" to start, "inputEndMs" to end,
                "previousSessionId" to previous?.id, "previousEndMs" to previous?.endMs,
            ))
        }
    }

    suspend fun setStatus(source: SourceId, state: SourceState, detail: String = "", touched: Boolean = false) {
        val previous = dao.sourceStatus(source.name)
        dao.setSourceStatus(SourceStatusEntity(
            source = source.name,
            state = state.name,
            lastUpdatedMs = if (touched) System.currentTimeMillis() else previous?.lastUpdatedMs,
            detail = detail,
        ))
    }

    suspend fun search(
        query: String,
        from: LocalDate,
        to: LocalDate,
        type: String,
        limit: Int = 200,
        after: SearchRow? = null,
    ): List<SearchRow> {
        val zone = ZoneId.systemDefault()
        return dao.search(
            query.trim(), from.atStartOfDay(zone).toInstant().toEpochMilli(),
            to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), type,
            limit = limit,
            cursorStartMs = after?.startMs,
            cursorKind = after?.kind,
            cursorId = after?.id,
        )
    }

    /** U08：按地点精确筛选（点击地点索引时调用；重名地点不混查）。 */
    suspend fun searchByPlace(placeId: Long, from: LocalDate, to: LocalDate, limit: Int = 200, after: SearchRow? = null): List<SearchRow> {
        val zone = ZoneId.systemDefault()
        return dao.searchByPlace(
            placeId,
            from.atStartOfDay(zone).toInstant().toEpochMilli(),
            to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
            limit,
            after?.startMs,
            after?.id,
        )
    }

    /** U08/P1-C：按应用包名 + 关键词精确筛选（关键词按应用名/包名匹配，与通用搜索同规则）。 */
    suspend fun searchByPackage(packageName: String, query: String = "", from: LocalDate, to: LocalDate, limit: Int = 200, after: SearchRow? = null): List<SearchRow> {
        val zone = ZoneId.systemDefault()
        return dao.searchByPackage(
            packageName,
            query.trim(),
            from.atStartOfDay(zone).toInstant().toEpochMilli(),
            to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
            limit,
            after?.startMs,
            after?.id,
        )
    }

    /** R01/P1-C：按包名 + 关键词查通知（与通知索引同可见规则，不拿应用会话凑数）。 */
    suspend fun searchByPackageNotifications(packageName: String, query: String = "", from: LocalDate, to: LocalDate, limit: Int = 200, after: SearchRow? = null): List<SearchRow> {
        val zone = ZoneId.systemDefault()
        return dao.searchByPackageNotifications(
            packageName,
            query.trim(),
            from.atStartOfDay(zone).toInstant().toEpochMilli(),
            to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
            limit,
            after?.startMs,
            after?.id,
        )
    }

    /**
     * 第四批：有效缺口投影（规则见 [projectCollectionGaps]）。
     *
     * 四个出口——首页/详情、档案汇总、诊断导出——都必须从这里取**同一份**投影和**同一个**
     * [GapProjection.asOfMs] 截止时刻，否则同一天会出现两套说法（复核稿第 9 节第 4 条）。
     * [ProjectionMode.RAW] 即"撤销投影"，回到原始缺口解释；原始记录从不删除。
     */
    suspend fun dayProjection(
        date: LocalDate,
        mode: ProjectionMode = ProjectionMode.EFFECTIVE,
    ): GapProjection = projectionFor(date, date, mode)

    suspend fun projectionFor(
        from: LocalDate,
        to: LocalDate,
        mode: ProjectionMode = ProjectionMode.EFFECTIVE,
    ): GapProjection {
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        if (to < from) return GapProjection.empty(0L, 0L, now, now).copy(mode = mode)
        val rangeStart = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val rangeEnd = minOf(
            to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
            maxOf(now, rangeStart),
        )
        val points = dao.locationPointsInRange(rangeStart, rangeEnd)
        val visits = dao.visitsInRange(rangeStart, rangeEnd).filter { it.startMs < rangeEnd && it.endMs > rangeStart }
        val gaps = dao.collectionGapsInRange(rangeStart, rangeEnd).filter { it.startMs < rangeEnd && it.endMs > rangeStart }
        return projectCollectionGaps(
            gaps = gaps,
            points = points,
            visits = visits,
            windowStartMs = rangeStart,
            windowEndMs = rangeEnd,
            asOfMs = now,
            generatedAtMs = now,
            mode = mode,
        )
    }

    suspend fun archiveSummaries(
        from: LocalDate,
        to: LocalDate,
        mode: ProjectionMode = ProjectionMode.EFFECTIVE,
    ): List<DailyArchiveSummary> {
        if (to < from) return emptyList()
        val zone = ZoneId.systemDefault()
        val rangeStart = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val rangeEnd = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val apps = dao.appSessionsInRange(rangeStart, rangeEnd)
        val notifications = dao.notificationsInRange(rangeStart, rangeEnd).filter { it.action == "POSTED" }
        val visits = dao.visitsInRange(rangeStart, rangeEnd)
        val steps = dao.stepsInRange(rangeStart, rangeEnd)
        val gaps = dao.collectionGapsInRange(rangeStart, rangeEnd)
        // 第四批：汇总与首页/导出共用同一份投影（同一规则版本、同一截止时刻）。
        val projection = projectionFor(from, to, mode)
        return generateSequence(from) { current -> current.plusDays(1).takeIf { it <= to } }
            .map { date ->
                val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
                val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val dayApps = apps.filter { it.startMs < end && it.endMs > start }
                val appDurations = dayApps.groupBy { it.appLabel }.mapValues { (_, sessions) ->
                    sessions.sumOf { (minOf(it.endMs, end) - maxOf(it.startMs, start)).coerceAtLeast(0L) }
                }
                val dayNotifications = notifications.filter { it.occurredMs in start until end }
                val dayVisits = visits.filter { it.startMs < end && it.endMs > start }
                val daySteps = steps.filter { it.recordedMs in start until end }
                val dayGaps = gaps.filter { it.startMs < end && it.endMs > start }
                // 只有"真正未知"的部分算缺口；实测停留支撑过的原始缺口不再整段计入。
                fun gapDuration(source: SourceId): Long = projection.unknownOverlapMs(start, end, source.name)
                // 地点时长一律按裁决后的区间并集计：旧实现直接加总到访时长，重叠时会重复计时，
                // 并与定位缺口互相矛盾（复核稿第 6.3 节）。
                val placeUnion = adjudicatedPlaceUnionMs(dayVisits, start, end)
                val activityStarts = buildList {
                    addAll(dayApps.map { maxOf(it.startMs, start) })
                    addAll(dayVisits.map { maxOf(it.startMs, start) })
                    addAll(dayNotifications.map { it.occurredMs })
                }
                val activityEnds = buildList {
                    addAll(dayApps.map { minOf(it.endMs, end) })
                    addAll(dayVisits.map { minOf(it.endMs, end) })
                    addAll(dayNotifications.map { it.occurredMs })
                }
                DailyArchiveSummary(
                    date = date,
                    // Sessions from different apps can overlap because Android usage events arrive late or
                    // are repaired after a process restart. Daily phone usage is elapsed time, not the sum
                    // of competing sessions, so count the interval union once. Per-app ranking above keeps
                    // its original session-based definition.
                    appUsageMs = intervalUnionDurationMs(
                        dayApps.map { MillisInterval(it.startMs, it.endMs) },
                        windowStartMs = start,
                        windowEndMs = end,
                    ),
                    switchCount = dayApps.size,
                    notificationCount = dayNotifications.size,
                    placeCount = dayVisits.map { it.placeId }.distinct().size,
                    stepCount = calculateSteps(daySteps),
                    topApp = appDurations.maxByOrNull { it.value }?.key,
                    mainPlace = placeUnion.maxByOrNull { it.value }?.let { (placeId, _) ->
                        dayVisits.firstOrNull { it.placeId == placeId }?.name
                    },
                    firstActivityMs = activityStarts.minOrNull(),
                    lastActivityMs = activityEnds.maxOrNull(),
                    usageGapMs = gapDuration(SourceId.USAGE),
                    notificationGapMs = gapDuration(SourceId.NOTIFICATIONS),
                    locationGapMs = gapDuration(SourceId.LOCATION),
                    stepGapMs = gapDuration(SourceId.STEPS),
                    sleepGapMs = gapDuration(SourceId.SLEEP),
                )
            }
            .toList()
    }

    suspend fun recordExplorer(from: LocalDate, to: LocalDate): RecordExplorerData {
        if (to < from) return RecordExplorerData()
        val zone = ZoneId.systemDefault()
        val rangeStart = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val rangeEnd = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val apps = dao.appSessionsInRange(rangeStart, rangeEnd)
        val notifications = dao.notificationsInRange(rangeStart, rangeEnd)
            .filter { it.action == "POSTED" }
        val visits = dao.visitsInRange(rangeStart, rangeEnd)
        val gaps = dao.collectionGapsInRange(rangeStart, rangeEnd)

        val dayOverviews = generateSequence(from) { current -> current.plusDays(1).takeIf { it <= to } }
            .map { date ->
                val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
                val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val dayApps = apps.filter { it.startMs < end && it.endMs > start }
                val appDurations = dayApps.groupBy { it.appLabel }.mapValues { (_, sessions) ->
                    sessions.sumOf { (minOf(it.endMs, end) - maxOf(it.startMs, start)).coerceAtLeast(0L) }
                }
                val dayNotifications = notifications.filter { it.occurredMs in start until end }
                val dayVisits = visits.filter { it.startMs < end && it.endMs > start }
                val placeTrail = dayVisits.sortedBy { it.startMs }.fold(mutableListOf<DayPlaceStop>()) { trail, visit ->
                    val stop = DayPlaceStop(
                        placeId = visit.placeId,
                        name = visit.name,
                        startMs = maxOf(visit.startMs, start),
                        endMs = minOf(visit.endMs, end),
                    )
                    val previous = trail.lastOrNull()
                    if (previous != null && previous.placeId == stop.placeId && stop.startMs - previous.endMs <= 5 * 60_000L) {
                        trail[trail.lastIndex] = previous.copy(endMs = maxOf(previous.endMs, stop.endMs))
                    } else trail += stop
                    trail
                }
                val activityStarts = dayApps.map { maxOf(it.startMs, start) } +
                    dayVisits.map { maxOf(it.startMs, start) } + dayNotifications.map { it.occurredMs }
                val activityEnds = dayApps.map { minOf(it.endMs, end) } +
                    dayVisits.map { minOf(it.endMs, end) } + dayNotifications.map { it.occurredMs }
                val dayGapAssessment = assessCollectionGaps(
                    gaps.filter { it.startMs < end && it.endMs > start },
                    start,
                    end,
                )
                RecordDayOverview(
                    date = date,
                    firstActivityMs = activityStarts.minOrNull(),
                    lastActivityMs = activityEnds.maxOrNull(),
                    phoneUsageMs = appDurations.values.sum(),
                    switchCount = dayApps.size,
                    notificationCount = dayNotifications.size,
                    topApps = appDurations.entries.sortedByDescending { it.value }.take(3)
                        .map { NamedDuration(it.key, it.value) },
                    notificationApps = dayNotifications.groupingBy { it.appLabel }.eachCount().entries
                        .sortedByDescending { it.value }.take(3).map { NamedCount(it.key, it.value) },
                    placeTrail = placeTrail,
                    gapSources = dayGapAssessment.importantGaps.map { it.source }.toSet(),
                )
            }
            .filter { it.phoneUsageMs > 0L || it.notificationCount > 0 || it.placeTrail.isNotEmpty() || it.gapSources.isNotEmpty() }
            .toList()
            .asReversed()

        val appOverviews = apps.groupBy { it.packageName }.map { (packageName, sessions) ->
            AppUsageOverview(
                packageName = packageName,
                appLabel = sessions.maxByOrNull { it.endMs }?.appLabel ?: packageName.substringAfterLast('.'),
                totalDurationMs = sessions.sumOf {
                    (minOf(it.endMs, rangeEnd) - maxOf(it.startMs, rangeStart)).coerceAtLeast(0L)
                },
                sessionCount = sessions.size,
                activeDays = sessions.map {
                    Instant.ofEpochMilli(maxOf(it.startMs, rangeStart)).atZone(zone).toLocalDate()
                }.distinct().size,
                lastUsedMs = sessions.maxOf { it.endMs },
            )
        }.sortedByDescending { it.totalDurationMs }

        return RecordExplorerData(
            days = dayOverviews,
            apps = appOverviews,
            appSessions = apps.sortedByDescending { it.startMs },
            notifications = notifications.sortedByDescending { it.occurredMs },
        )
    }

    suspend fun renamePlace(place: PlaceEntity, name: String) {
        val clean = name.trim()
        require(clean.isNotEmpty() && clean.length <= 60) { "地点名称须为 1 至 60 个字" }
        provider.get().withTransaction {
            val current = requireNotNull(dao.placeById(place.id)) { "地点已不存在" }
            dao.updatePlace(current.copy(name = clean, isCustomName = true))
        }
    }
    suspend fun ignorePlace(place: PlaceEntity, ignored: Boolean) = provider.get().withTransaction {
        val current = requireNotNull(dao.placeById(place.id)) { "地点已不存在" }
        dao.updatePlace(current.copy(ignored = ignored))
    }
    suspend fun setPlaceKind(placeId: Long, kind: String) = provider.get().withTransaction {
        require(kind in PlaceKind.ALL) { "未知地点标记" }
        val place = requireNotNull(dao.placeById(placeId)) { "地点已不存在" }
        dao.updatePlace(place.copy(kind = kind))
    }
    suspend fun updatePlaceMetadata(placeId: Long, name: String, kind: String) = provider.get().withTransaction {
        val clean = name.trim()
        require(clean.isNotEmpty() && clean.length <= 60) { "地点名称须为 1 至 60 个字" }
        require(kind in PlaceKind.ALL) { "未知地点标记" }
        val place = requireNotNull(dao.placeById(placeId)) { "地点已不存在" }
        dao.updatePlace(place.copy(name = clean, isCustomName = true, kind = kind))
    }
    suspend fun createPlaceAtLocation(point: LocationPointEntity, name: String, kind: String): Long = provider.get().withTransaction {
        val clean = name.trim()
        require(clean.isNotEmpty() && clean.length <= 60) { "地点名称须为 1 至 60 个字" }
        require(kind in PlaceKind.ALL) { "未知地点标记" }
        val now = System.currentTimeMillis()
        val measured = point.measuredMs.takeIf { it > 0 } ?: point.recordedMs
        require(!point.isMock && point.accuracyM.isFinite() && point.accuracyM in 0f..150f &&
            measured in (now - 15 * 60_000L)..now) { "最近定位已过期或精度不足，请等待新的定位" }
        require(point.latitude in -90.0..90.0 && point.longitude in -180.0..180.0) { "定位坐标无效" }
        dao.insertPlace(PlaceEntity(name = clean, latitude = point.latitude,
            longitude = point.longitude, isCustomName = true, kind = kind))
    }
    suspend fun mergePlaces(source: PlaceEntity, target: PlaceEntity) = provider.get().withTransaction {
        require(source.id != target.id) { "不能归入同一地点" }
        val sourceNow = requireNotNull(dao.placeById(source.id)) { "来源地点已不存在" }
        val targetNow = requireNotNull(dao.placeById(target.id)) { "目标地点已不存在" }
        require(sourceNow.mergedIntoPlaceId == null) { "此地点已归入其他地点，请先撤销" }
        require(!sourceNow.ignored && !targetNow.ignored) { "隐藏的地点不能参与归并" }
        require(targetNow.mergedIntoPlaceId == null) { "请选择尚未归并的主地点" }
        val places = dao.allPlaces().associateBy { it.id }
        var cursor: PlaceEntity? = targetNow
        val seen = mutableSetOf<Long>()
        while (cursor != null) {
            require(cursor.id != source.id && seen.add(cursor.id)) { "归并会形成循环" }
            cursor = cursor.mergedIntoPlaceId?.let(places::get)
        }
        dao.updatePlace(sourceNow.copy(mergedIntoPlaceId = target.id))
        addAttributionRevision("place:${source.id}", source.id, target.id,
            AttributionRevisionEntity.SOURCE_USER, "将地点归入 ${targetNow.name}")
    }

    suspend fun undoPlaceMerge(sourceId: Long) = provider.get().withTransaction {
        val source = requireNotNull(dao.placeById(sourceId)) { "地点已不存在" }
        require(source.mergedIntoPlaceId != null) { "此地点尚未归并" }
        val revision = dao.attributionRevisionsFor("place:$sourceId")
            .firstOrNull { it.revertedBy == null && it.toPlaceId == source.mergedIntoPlaceId }
        val undoId = addAttributionRevision("place:$sourceId", source.mergedIntoPlaceId, null,
            AttributionRevisionEntity.SOURCE_USER, "撤销地点归并")
        revision?.let { dao.revertAttributionRevision(it.id, undoId) }
        dao.revertAttributionRevision(undoId, undoId)
        dao.updatePlace(source.copy(mergedIntoPlaceId = null))
    }

    // ── F 批：可追溯地点修订 ─────────────────────────────────────

    /** 新增一条用户/算法地点修订（“这段其实在家”等）；返回新修订 id。 */
    suspend fun addAttributionRevision(
        targetKey: String,
        fromPlaceId: Long?,
        toPlaceId: Long?,
        source: String,
        reason: String,
        ruleVersion: Int = 1,
    ): Long = dao.insertAttributionRevision(
        AttributionRevisionEntity(
            targetKey = targetKey,
            fromPlaceId = fromPlaceId,
            toPlaceId = toPlaceId,
            createdMs = System.currentTimeMillis(),
            source = source,
            ruleVersion = ruleVersion,
            reason = reason,
        ),
    )

    /** 撤销某条修订：以撤销者 id 标记被替代修订；不删除原始档案。 */
    suspend fun revertAttributionRevision(id: Long, revertedBy: Long) =
        dao.revertAttributionRevision(id, revertedBy)

    suspend fun attributionRevisions() = dao.attributionRevisions()

    fun observeAttributionRevisions() = dao.observeAttributionRevisions()

    suspend fun correctVisit(visitId: Long, targetPlaceId: Long?) = provider.get().withTransaction {
        val original = requireNotNull(dao.visitById(visitId)) { "这条停留已不存在" }
        if (targetPlaceId != null) require(dao.placeById(targetPlaceId)?.ignored == false) { "目标地点不可用" }
        addAttributionRevision("visit:$visitId", original.placeId, targetPlaceId,
            AttributionRevisionEntity.SOURCE_USER, if (targetPlaceId == null) "恢复原始归属" else "用户纠正本次停留")
    }

    /** Undo the latest action, restoring the previous overlay without deleting either action. */
    suspend fun undoVisitCorrection(visitId: Long) = provider.get().withTransaction {
        val revisions = dao.attributionRevisionsFor("visit:$visitId").filter { it.revertedBy == null }
        val latest = revisions.firstOrNull() ?: return@withTransaction
        val undoId = addAttributionRevision("visit:$visitId", latest.toPlaceId,
            revisions.getOrNull(1)?.toPlaceId, AttributionRevisionEntity.SOURCE_USER, "撤销上次纠正")
        dao.revertAttributionRevision(latest.id, undoId)
        // Tombstone the audit-only undo marker so the previous action becomes active again.
        dao.revertAttributionRevision(undoId, undoId)
    }

    fun dayFor(epochMs: Long): LocalDate = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()
}
