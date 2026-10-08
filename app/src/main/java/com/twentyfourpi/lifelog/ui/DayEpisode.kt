package com.twentyfourpi.lifelog.ui

import com.twentyfourpi.lifelog.data.AppSessionEntity
import com.twentyfourpi.lifelog.data.CollectionGapEntity
import com.twentyfourpi.lifelog.data.DailyRoute
import com.twentyfourpi.lifelog.data.NotificationEventEntity
import com.twentyfourpi.lifelog.data.PlaceVisitView
import com.twentyfourpi.lifelog.data.TimelineDay

/**
 * F 批（v1.2 方案 16 节）：日常时段层（day episode）。
 *
 * 与原始点/到访/应用/通知永久保留层、路线层（DailyRoute：地图与里程）不同，
 * 本层只负责“首页按日常过程组织”——把通勤短停、红灯、地点未确认产生的几十张
 * 碎片卡收敛为几次主要停留加少量紧凑的路途行。它不删除原始记录、不补造轨迹、
 * 不修改路线阈值/里程/行程边界（T49 回归样本保证）。
 */
enum class DayEpisodeKind { STAY, MOVE, UNKNOWN }

/**
 * 一个日常时段。区间采用左闭右开 [startMs, endMs)；相邻时段不重叠。
 *
 * - [observedRanges]：该时段内实际已观测的区间并集；[unknownRanges]：未观测区间。
 *   卡片跨度 ≠ 确定停留时长，统计不得把未知时长全算进停留（方案 16.3 边界规则）。
 * - [visitRefs]/[tripRefs]/[appRefs]/[notificationRefs]/[gapRefs] 都是对原始事实
 *   的引用，不复制、不改写原始时间戳。
 * - [reason] 记录判定理由（“行程强证据 / 稳定停留 / 用户确认 / 证据不足”），
 *   供诊断与可追溯修订。
 */
data class DayEpisode(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    val kind: DayEpisodeKind,
    /** STAY 时的规范地点 ID；MOVE/UNKNOWN 为 null。 */
    val placeId: Long?,
    /** 两端已知时展示用：如 家 → 公司。 */
    val fromPlaceName: String? = null,
    val toPlaceName: String? = null,
    val observedRanges: List<TimeChapterRange> = emptyList(),
    val unknownRanges: List<TimeChapterRange> = emptyList(),
    val visitRefs: List<Long> = emptyList(),
    val tripRefs: List<Int> = emptyList(),
    val appRefs: List<Long> = emptyList(),
    val notificationRefs: List<Long> = emptyList(),
    val gapRefs: List<Long> = emptyList(),
    val reason: String = "composed",
    val projectionVersion: Int = DayEpisodeBuilder.PROJECTION_VERSION,
) {
    /** 已确认停留的时长（只累加已观测区间，未知缺口不计入）。 */
    val observedDurationMs: Long
        get() = observedRanges.sumOf { (it.endMs - it.startMs).coerceAtLeast(0L) }
}

/** 输入聚合：首页聚合需要到访、行程与活动，但输入始终是可被回放重建的纯函数参数。 */
data class DayEpisodeInput(
    val day: TimelineDay,
    val route: DailyRoute?,
    val dayStartMs: Long,
    val dayEndMs: Long,
    /** 规范地点展示名：placeId → 名称（来自主数据，避免依赖可变的临时地点名）。 */
    val placeNames: Map<Long, String> = emptyMap(),
    /** 用户确认过的原始到访 id（“这段其实在家”等修订落位后）：永不折叠、永不消失。 */
    val confirmedVisitIds: Set<Long> = emptySet(),
)

/**
 * 首页“日常时段”纯函数构建器（F 批核心）。
 *
 * 设计要点（方案 16.1–16.3）：
 * 1. 已有行程是“在路上”的强证据：trip 引用直接构成 MOVE 时段，不打散。
 * 2. 夹在两行程之间的短停留按上下文裁决，不只看分钟数：
 *    - 用户确认的短到访（[DayEpisodeInput.confirmedVisitIds]）永远保留独立 STAY；
 *    - ≤3 分钟的小停顿默认作为路途内部短停，并入“在路上”时段；
 *    - 3–10 分钟未确认夹停（堵车/修路等）同样不另建首页地点卡，保留为途中短暂
 *      停留（并入 MOVE；折叠停留不做 visit 级明细保留——时段粒度可查，原始
 *      visit 仍永久保存在数据库，展开全天详情可回到原始记录）。只有夹停 ≥10
 *      分钟才成为主要停留。
 *    - 非夹停的短停留保留为 short STAY（用户真实到访）。
 * 3. 未命名新地点以 [STAY_MIN_STABLE_MS]=8 分钟为首页主要停留试验门槛；
 *    原始 5 分钟到访照常保存，这只是展示归属。
 * 4. 同地点且间隔 ≤10 分钟（与 TimeChapterComposer 同规则）归并为一张停留卡；
 *    内部仍记未观测时长（unknownRanges），统计不得把缺口全算在家。
 * 5. 通勤中缺名称但有移动证据 → “在路上”；长时间无定位且无法确认活动类型
 *    → 中性“这段时间”（UNKNOWN），不因两端是家就填成在家。
 */
object DayEpisodeBuilder {
    const val PROJECTION_VERSION = 2

    /** 通勤中 1–3 分钟小停顿视为路途内部短停的上限。 */
    const val ROUTE_INTERNAL_STOP_MAX_MS = 3 * 60_000L

    /** 未命名新地点成为首页主要停留的稳定门槛（仅展示层；原始 5 分钟照常保存）。 */
    const val STAY_MIN_STABLE_MS = 8 * 60_000L

    /** 夹在两行程之间且未确认的停留，需要超过此门槛才成为独立停留（防堵车成新地点）。 */
    const val CONFIRMED_TRIP_GAP_STABLE_MS = 10 * 60_000L

    /** 合成一条完整“在路上”时段后，把相邻的短停/移动合并（结果行数减少的关键）。 */
    private const val FOLD_MERGES_FOLLOWING_TRIP = true

    fun build(input: DayEpisodeInput): List<DayEpisode> {
        val day = input.day
        val startMs = input.dayStartMs
        val endMs = input.dayEndMs
        require(endMs > startMs) { "dayEndMs must be after dayStartMs" }

        // 1. 停留候选（有确认到访的地点）。
        val trips = input.route?.trips?.mapIndexed { index, trip ->
            TripRef(index, trip.first.timeMs, trip.last.timeMs)
        }.orEmpty().filter { it.endMs > it.startMs }
        val stays = buildStayDrafts(day, startMs, endMs, input.confirmedVisitIds, trips)

        // 2. 行程（trip）是“在路上”强证据（已在上方构建）。

        // 3. 停留与移动裁剪成互不重叠切片的完整时间线。
        val timeline = sliceTimeline(startMs, endMs, stays, trips)

        // 4. 片段 → 时段；把碎片短停留折叠进相邻移动段。
        return finalizeEpisodes(timeline, day, input, startMs, endMs)
    }

    // ── 停留候选 ────────────────────────────────────────────

    private data class StayDraft(
        val startMs: Long,
        val endMs: Long,
        val placeId: Long,
        val visitIds: List<Long>,
        val confirmed: Boolean,
        val reason: String,
    )

    private data class TripRef(val index: Int, val startMs: Long, val endMs: Long)

    private fun buildStayDrafts(
        day: TimelineDay,
        startMs: Long,
        endMs: Long,
        confirmedVisitIds: Set<Long>,
        trips: List<TripRef> = emptyList(),
    ): List<StayDraft> {
        val visits = day.visits
            .filter { it.startMs < endMs && it.endMs > startMs }
            .sortedWith(compareBy<PlaceVisitView> { it.startMs }.thenBy { it.id })
        if (visits.isEmpty()) return emptyList()

        val groups = mutableListOf<MutableList<PlaceVisitView>>()
        visits.forEach { visit ->
            val current = groups.lastOrNull()
            val currentEnd = current?.maxOfOrNull { it.endMs }
            val gapStart = currentEnd ?: Long.MIN_VALUE
            // 归并只在“中间没有离开证据”时成立：若两段到访之间存在行程（trip 强证据），
            // 说明人确实离开又回来，不能合成一张连续停留卡（方案 16.3-6）。
            val hasTripBetween = trips.any { it.startMs < visit.startMs && it.endMs > gapStart }
            if (
                current != null && currentEnd != null &&
                current.first().placeId == visit.placeId &&
                visit.startMs - currentEnd <= TimeChapterComposer.PLACE_MERGE_GAP_MS &&
                !hasTripBetween
            ) {
                current += visit
            } else {
                groups += mutableListOf(visit)
            }
        }

        return groups.mapNotNull { group ->
            val gStart = maxOf(startMs, group.minOf { it.startMs })
            val gEnd = minOf(endMs, group.maxOf { it.endMs })
            if (gEnd <= gStart) return@mapNotNull null
            val placeId = group.first().placeId
            val durationMs = gEnd - gStart
            // 用户确认或达到稳定门槛即视为确定停留；否则为“短/待确认”草稿，
            // 由 finalizeEpisodes 结合夹停上下文裁决（折叠或保留）。
            val userConfirmed = group.any { it.id in confirmedVisitIds }
            val stableEnough = userConfirmed || durationMs >= STAY_MIN_STABLE_MS
            StayDraft(
                startMs = gStart,
                endMs = gEnd,
                placeId = placeId,
                visitIds = group.map { it.id },
                confirmed = stableEnough,
                reason = when {
                    userConfirmed -> "user-confirmed"
                    stableEnough -> "stable-${durationMs / 60_000L}min"
                    else -> "short-${durationMs / 60_000L}min"
                },
            )
        }
    }

    // ── 时间线切片：停留 + 移动 互不重叠 ───────────────────────

    private data class Slice(
        val startMs: Long,
        val endMs: Long,
        val stay: StayDraft? = null,
        val trip: TripRef? = null,
    )

    private fun sliceTimeline(
        startMs: Long,
        endMs: Long,
        stays: List<StayDraft>,
        trips: List<TripRef>,
    ): List<Slice> {
        val boundaries = sortedSetOf<Long>()
        stays.forEach { boundaries += it.startMs; boundaries += it.endMs }
        trips.forEach { boundaries += it.startMs; boundaries += it.endMs }
        boundaries += startMs
        boundaries += endMs

        val slices = mutableListOf<Slice>()
        boundaries.filter { it in startMs..endMs }.zipWithNext().forEach { (a, b) ->
            if (b <= a) return@forEach
            val stayOwner = stays.firstOrNull { s -> a >= s.startMs && b <= s.endMs }
            val tripOwner = trips.firstOrNull { t -> a >= t.startMs && b <= t.endMs }
            slices += Slice(a, b, stay = stayOwner, trip = tripOwner)
        }
        return slices
    }

    // ── 切片 → 时段 ──────────────────────────────────────────

    private fun finalizeEpisodes(
        slices: List<Slice>,
        day: TimelineDay,
        input: DayEpisodeInput,
        dayStartMs: Long,
        dayEndMs: Long,
    ): List<DayEpisode> {
        if (slices.isEmpty()) return emptyList()
        // R11/探针 P04：完全空的天（无活动、无停留、无行程）不生成虚构 UNKNOWN 卡，
        // 由 UI 显示正常空态；只有确实有活动/证据的时段才产生时段。
        val hasAnyEvidence = day.apps.isNotEmpty() || day.notifications.isNotEmpty() ||
            day.gaps.isNotEmpty() || day.visits.isNotEmpty() ||
            slices.any { it.trip != null }
        if (!hasAnyEvidence) return emptyList()
        val result = mutableListOf<DayEpisode>()
        var index = 0
        var i = 0
        while (i < slices.size) {
            val slice = slices[i]
            val isStay = slice.stay != null
            val isMove = slice.trip != null

            when {
                isStay -> {
                    val stay = slice.stay!!
                    val durationMs = stay.endMs - stay.startMs
                    val userConfirmed = stay.reason == "user-confirmed"
                    val betweenTrips = i > 0 && i < slices.size - 1 &&
                        slices[i - 1].trip != null && slices[i + 1].trip != null
                    val foldCandidate = !stay.confirmed || (
                        // 夹停的短停留（即使 8 分钟堵车）在上下文确认前不另建新地点卡。
                        betweenTrips && durationMs < CONFIRMED_TRIP_GAP_STABLE_MS
                        )
                    if (foldCandidate && betweenTrips && !userConfirmed) {
                        // 折叠为移动段内部停留：优先把“前 MOVE + 停 + 后 MOVE”合成单条“在路上”，
                        // 使“一条路途过渡”在纯函数层即成立（方案 16.2 示例）；否则并入前一段 MOVE。
                        if (FOLD_MERGES_FOLLOWING_TRIP && i + 1 < slices.size && slices[i + 1].trip != null) {
                            val merged = mergeMoveAroundStop(result, slice, slices[i + 1], input, dayStartMs)
                            if (merged != null) {
                                result[result.lastIndex] = merged
                                i += 2
                                continue
                            }
                        }
                        // 合并失败（prev 不是相接 MOVE）时退化为只并入前 MOVE，不跳过后续 trip。
                        foldIntoPreviousMove(result, slice, dayStartMs)?.let { folded ->
                            result[result.lastIndex] = folded
                        } ?: run {
                            result += makeEpisode(index++, slice, DayEpisodeKind.MOVE, null,
                                reason = "internal-stop", names = input.placeNames)
                        }
                    } else {
                        // R11/探针 P03：停留期间的应用/通知/缺口必须挂到卡片上，
                        // 否则用户看到停留卡却查不到当时的活动明细。
                        val stayAppRefs = day.apps.filter { overlaps(it.startMs, it.endMs, slice.startMs, slice.endMs) }.map { it.id }
                        val stayNotifRefs = day.notifications.filter { it.occurredMs in slice.startMs until slice.endMs }.map { it.id }
                        val stayGapRefs = day.gaps.filter { overlaps(it.startMs, it.endMs, slice.startMs, slice.endMs) }.map { it.id }
                        result += makeEpisode(index++, slice, DayEpisodeKind.STAY, stay.placeId,
                            reason = stay.reason, visitIds = stay.visitIds, visits = day.visits,
                            appIds = stayAppRefs, notificationIds = stayNotifRefs, gapIds = stayGapRefs,
                            names = input.placeNames)
                    }
                }
                isMove -> {
                    // R11：行程期间的应用/通知也挂到 MOVE 卡片上（引用守恒）。
                    val moveAppRefs = day.apps.filter { overlaps(it.startMs, it.endMs, slice.startMs, slice.endMs) }.map { it.id }
                    val moveNotifRefs = day.notifications.filter { it.occurredMs in slice.startMs until slice.endMs }.map { it.id }
                    val moveGapRefs = day.gaps.filter { overlaps(it.startMs, it.endMs, slice.startMs, slice.endMs) }.map { it.id }
                    result += makeEpisode(index++, slice, DayEpisodeKind.MOVE, null,
                        reason = "trip", trip = slice.trip, names = input.placeNames,
                        appIds = moveAppRefs, notificationIds = moveNotifRefs, gapIds = moveGapRefs)
                }
                else -> {
                    val hasActivity = day.apps.any { overlaps(it.startMs, it.endMs, slice.startMs, slice.endMs) } ||
                        day.notifications.any { it.occurredMs in slice.startMs until slice.endMs } ||
                        day.gaps.any { overlaps(it.startMs, it.endMs, slice.startMs, slice.endMs) }
                    val activityAppRefs = day.apps.filter { overlaps(it.startMs, it.endMs, slice.startMs, slice.endMs) }.map { it.id }
                    val activityNotifRefs = day.notifications.filter { it.occurredMs in slice.startMs until slice.endMs }.map { it.id }
                    val activityGapRefs = day.gaps.filter { overlaps(it.startMs, it.endMs, slice.startMs, slice.endMs) }.map { it.id }
                    result += makeEpisode(
                        index++,
                        slice,
                        DayEpisodeKind.UNKNOWN,
                        null,
                        reason = if (hasActivity) "unknown-activity" else "unknown-empty",
                        appIds = activityAppRefs,
                        notificationIds = activityNotifRefs,
                        gapIds = activityGapRefs,
                        names = input.placeNames,
                    )
                }
            }
            i++
        }
        // 补 from/to 名称：MOVE 两端若有相邻 STAY，展示“家 → 公司”。
        val named = result.mapIndexed { idx, ep ->
            if (ep.kind != DayEpisodeKind.MOVE) return@mapIndexed ep
            val from = result.getOrNull(idx - 1)?.takeIf { it.kind == DayEpisodeKind.STAY }?.placeId
            val to = result.getOrNull(idx + 1)?.takeIf { it.kind == DayEpisodeKind.STAY }?.placeId
            ep.copy(
                fromPlaceName = from?.let { placeName(it, input.placeNames) },
                toPlaceName = to?.let { placeName(it, input.placeNames) },
            )
        }
        return named
    }

    /** 前 MOVE + 夹停短停留 + 后 MOVE → 单条“在路上”时段。 */
    private fun mergeMoveAroundStop(
        result: MutableList<DayEpisode>,
        stopSlice: Slice,
        nextTripSlice: Slice,
        input: DayEpisodeInput,
        dayStartMs: Long,
    ): DayEpisode? {
        val prev = result.lastOrNull() ?: return null
        if (prev.kind != DayEpisodeKind.MOVE || prev.endMs != stopSlice.startMs) return null
        if (nextTripSlice.startMs != stopSlice.endMs) return null
        val nextTrip = nextTripSlice.trip ?: return null
        val stopRange = TimeChapterRange(stopSlice.startMs, stopSlice.endMs)
        val nextRange = TimeChapterRange(nextTripSlice.startMs, nextTripSlice.endMs)
        return prev.copy(
            endMs = nextTripSlice.endMs,
            observedRanges = prev.observedRanges + stopRange + nextRange,
            tripRefs = prev.tripRefs + nextTrip.index,
            reason = prev.reason + ";internal-stop",
        )
    }

    /** 把短停留并入前一个 MOVE 时段（更新 endMs 与观测区间）。 */
    private fun foldIntoPreviousMove(
        result: MutableList<DayEpisode>,
        slice: Slice,
        dayStartMs: Long,
    ): DayEpisode? {
        val prev = result.lastOrNull() ?: return null
        if (prev.kind != DayEpisodeKind.MOVE || prev.endMs != slice.startMs) return null
        val observed = prev.observedRanges + TimeChapterRange(slice.startMs, slice.endMs)
        return prev.copy(
            endMs = slice.endMs,
            observedRanges = observed,
            reason = prev.reason + ";internal-stop",
        )
    }

    private fun makeEpisode(
        index: Int,
        slice: Slice,
        kind: DayEpisodeKind,
        placeId: Long?,
        reason: String,
        visitIds: List<Long> = emptyList(),
        visits: List<PlaceVisitView> = emptyList(),
        trip: TripRef? = null,
        appIds: List<Long> = emptyList(),
        notificationIds: List<Long> = emptyList(),
        gapIds: List<Long> = emptyList(),
        names: Map<Long, String>,
    ): DayEpisode {
        // observed/unknown 分离：STAY 只把原始 visit 区间计为观测；MOVE 计行程区间；
        // UNKNOWN 无观测。卡片的完整跨度中未覆盖部分一律进 unknownRanges。
        val observed = when (kind) {
            DayEpisodeKind.STAY -> observedRangesFor(visits, visitIds, slice.startMs, slice.endMs)
            DayEpisodeKind.MOVE -> listOf(TimeChapterRange(slice.startMs, slice.endMs))
            DayEpisodeKind.UNKNOWN -> emptyList()
        }
        val unknown = listOf(TimeChapterRange(slice.startMs, slice.endMs)).subtractRanges(observed)
        return DayEpisode(
            id = "ep:$index:${slice.startMs}:${slice.endMs}",
            startMs = slice.startMs,
            endMs = slice.endMs,
            kind = kind,
            placeId = placeId,
            observedRanges = observed,
            unknownRanges = unknown,
            visitRefs = visitIds,
            tripRefs = if (trip != null) listOf(trip.index) else emptyList(),
            appRefs = appIds,
            notificationRefs = notificationIds,
            gapRefs = gapIds,
            reason = reason,
        )
    }

    /** 从原始 visit 集合生成已观测区间（交集裁剪到时段，重叠合并）。 */
    private fun observedRangesFor(
        allVisits: List<PlaceVisitView>,
        visitIds: List<Long>,
        sliceStartMs: Long,
        sliceEndMs: Long,
    ): List<TimeChapterRange> {
        val selected = allVisits.filter { it.id in visitIds && it.startMs < sliceEndMs && it.endMs > sliceStartMs }
        if (selected.isEmpty()) return emptyList()
        val clipped = selected
            .map { TimeChapterRange(maxOf(it.startMs, sliceStartMs), minOf(it.endMs, sliceEndMs)) }
            .filter { it.endMs > it.startMs }
            .sortedBy { it.startMs }
        val merged = mutableListOf<TimeChapterRange>()
        for (r in clipped) {
            val last = merged.lastOrNull()
            if (last != null && r.startMs <= last.endMs) {
                merged[merged.lastIndex] = last.copy(endMs = maxOf(last.endMs, r.endMs))
            } else {
                merged += r
            }
        }
        return merged
    }

    private fun placeName(placeId: Long, names: Map<Long, String>): String? = names[placeId]

    private fun overlaps(startMs: Long, endMs: Long, rangeStartMs: Long, rangeEndMs: Long): Boolean =
        startMs < rangeEndMs && endMs > rangeStartMs
}

// ── R06：新聚合（DayEpisode）→ 旧 UI 结构（TimeChapter）适配层 ──────────────
// 让首页/详情不重写即消费 DayEpisodeBuilder 的语义时段（短停折叠、夹停裁决、
// 稳定停留、事件引用守恒），同时保留 TimeChapter 组件的展示能力。

/** 把语义时段转换为 UI 章卡（按时间顺序；STAY 带地点，MOVE 带起止名）。 */
fun episodesToChapters(
    episodes: List<DayEpisode>,
    day: TimelineDay,
    placeNames: Map<Long, String> = emptyMap(),
): List<TimeChapter> {
    val visitById = day.visits.associateBy { it.id }
    val appById = day.apps.associateBy { it.id }
    val notificationById = day.notifications.associateBy { it.id }
    val gapById = day.gaps.associateBy { it.id }
    return episodes.mapIndexed { index, ep ->
        val place = ep.placeId
            ?.let { id -> day.visits.filter { it.placeId == id }.maxByOrNull { it.endMs } }
        val placeVisits = ep.visitRefs.mapNotNull { visitById[it] }
        val apps = ep.appRefs.mapNotNull { appById[it] }
        val notifications = ep.notificationRefs.mapNotNull { notificationById[it] }
        val gaps = ep.gapRefs.mapNotNull { gapById[it] }
        TimeChapter(
            key = ep.id.ifEmpty { "chapter:$index:${ep.startMs}" },
            startMs = ep.startMs,
            endMs = ep.endMs,
            place = place,
            placeVisits = placeVisits,
            placeContinuityGaps = ep.unknownRanges,
            apps = apps,
            notifications = notifications,
            gaps = gaps,
            topApps = summarizeEpisodeApps(apps, ep.startMs, ep.endMs),
            notificationCount = notifications.count { it.action == "POSTED" },
        )
    }
}

/** 与 TimeChapterComposer.summarizeApps 同语义的 Top 应用摘要（适配层内联）。 */
private fun summarizeEpisodeApps(
    apps: List<AppSessionEntity>,
    startMs: Long,
    endMs: Long,
    limit: Int = 3,
): List<TimeChapterAppSummary> {
    if (limit == 0) return emptyList()
    return apps.groupBy { it.packageName }.mapNotNull { (packageName, sessions) ->
        val durationMs = sessions.sumOf {
            (minOf(it.endMs, endMs) - maxOf(it.startMs, startMs)).coerceAtLeast(0L)
        }
        if (durationMs <= 0L) null else TimeChapterAppSummary(
            packageName = packageName,
            appLabel = sessions.maxByOrNull { it.endMs }?.appLabel ?: packageName,
            durationMs = durationMs,
            sessionCount = sessions.size,
        )
    }.sortedByDescending { it.durationMs }.take(limit)
}

/** 从原始的 [a,b) 区间列表减去 [omit] 并集，返回剩余区间（不合并）。 */
private fun List<TimeChapterRange>.subtractRanges(omit: List<TimeChapterRange>): List<TimeChapterRange> {
    if (omit.isEmpty()) return this
    val omitSorted = omit.sortedBy { it.startMs }
    val out = mutableListOf<TimeChapterRange>()
    for (range in this.sortedBy { it.startMs }) {
        var cursor = range.startMs
        for (o in omitSorted) {
            if (o.endMs <= cursor || o.startMs >= range.endMs) continue
            if (o.startMs > cursor) out += TimeChapterRange(cursor, minOf(o.startMs, range.endMs))
            cursor = maxOf(cursor, o.endMs)
            if (cursor >= range.endMs) break
        }
        if (cursor < range.endMs) out += TimeChapterRange(cursor, range.endMs)
    }
    return out
}