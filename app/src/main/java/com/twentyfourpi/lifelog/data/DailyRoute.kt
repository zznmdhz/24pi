package com.twentyfourpi.lifelog.data

import com.twentyfourpi.lifelog.util.distanceMeters

/** A point retained for presentation. Raw database rows are never mutated or deleted. */
data class RoutePoint(
    val sourceId: Long,
    val timeMs: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Float,
    val speedMps: Float? = null,
)

data class RouteSection(
    val points: List<RoutePoint>,
    val distanceMeters: Double,
    val interruptions: List<RouteInterruption> = emptyList(),
) {
    /** Shared by mileage, Canvas and map: unknown edges never become solid lines. */
    val observedEdges: List<Pair<RoutePoint, RoutePoint>> get() = points.zipWithNext().filter { (a, b) ->
        interruptions.none { a.timeMs < it.recoveredAt.timeMs && b.timeMs > it.lostAt.timeMs }
    }
    val first: RoutePoint get() = points.first()
    val last: RoutePoint get() = points.last()
    val durationMs: Long get() = (last.timeMs - first.timeMs).coerceAtLeast(0)
}

/** An unobserved or implausible interval. It is drawn dashed and never contributes to distance by itself. */
data class RouteInterruption(
    val lostAt: RoutePoint,
    val recoveredAt: RoutePoint,
    val reason: String,
) {
    val durationMs: Long get() = (recoveredAt.timeMs - lostAt.timeMs).coerceAtLeast(0)
}

/**
 * 途经微停留：行程内部一段原地记录（红灯、临时停车、接送）。
 * 只做标注，不切断行程；真实停留（≥[DailyRouteBuilder.TRIP_BOUNDARY_STILL_MS]）见 [DailyRoute.majorStops]。
 */
data class RouteStop(
    val startMs: Long,
    val endMs: Long,
    val latitude: Double,
    val longitude: Double,
    val placeName: String? = null,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0)
}

/**
 * 行程：两个真实停留（或大位移数据空洞）之间的一段连续出行。
 * 红灯、微停留、短断档折叠在行程内部（途经停留/虚线），一段行程不再被拆成碎片。
 */
data class RouteTrip(
    val sections: List<RouteSection>,
    val stops: List<RouteStop>,
    val interruptions: List<RouteInterruption>,
    val startPlaceName: String? = null,
    val endPlaceName: String? = null,
) {
    // Ongoing trips gain a new end point; their navigation identity must not change.
    val key: String get() = "2:${first.sourceId}:${first.timeMs}"
    val first: RoutePoint get() = sections.first().first
    val last: RoutePoint get() = sections.last().last
    val durationMs: Long get() = (last.timeMs - first.timeMs).coerceAtLeast(0)
    val distanceMeters: Double get() = sections.sumOf { it.distanceMeters }
}

data class DailyRoute(
    val sections: List<RouteSection> = emptyList(),
    val interruptions: List<RouteInterruption> = emptyList(),
    val trips: List<RouteTrip> = emptyList(),
    val majorStops: List<RouteStop> = emptyList(),
    val rejectedPointCount: Int = 0,
) {
    val firstRecorded: RoutePoint? get() = sections.firstOrNull()?.first
    val lastRecorded: RoutePoint? get() = sections.lastOrNull()?.last
    val observedDistanceMeters: Double get() = sections.sumOf { it.distanceMeters }
}

object DailyRouteBuilder {
    const val STABILITY_RADIUS_METERS = 200.0
    const val MAX_ACCEPTED_ACCURACY_METERS = 200f
    const val INTERRUPTION_AFTER_MS = 5 * 60_000L

    /** ≥ 此时长的原地记录构成真实停留，是行程边界；更短的折叠为途经停留。 */
    const val TRIP_BOUNDARY_STILL_MS = 5 * 60_000L

    /** ≥ 此时长的数据空洞在行程内部以虚线标注（不拆段）；更长的按断档拆分。 */
    const val MINOR_GAP_AFTER_MS = 90_000L

    /** 行程内可显示的途经停留最短时长。 */
    const val MIN_VISIBLE_STOP_MS = 60_000L

    /** 行程最小里程：低于此值视为原地记录噪声，不形成行程（原始数据不受影响）。 */
    const val MIN_TRIP_DISTANCE_METERS = 100.0

    /** 静止块总位移上限：真停留（红灯/停车）位移极小，拥堵车流位移大，借此区分。 */
    private const val STILL_MAX_DISPLACEMENT_METERS = 300.0

    /**
     * 瞬移物理上限（≈1080 km/h）。旧版 70 m/s 阈值会把亚秒级跨源时钟抖动
     * 和离开停留点时的吸附跳变误判为瞬移，把一段行程切碎；高铁 83 m/s 也被逐对拆段。
     * 真正的传送门毛刺（坏 fix）瞬时速度远超此值，仍会被拦截为断档。
     */
    const val IMPLAUSIBLE_SPEED_MPS = 300.0

    private const val MOVING_SPEED_MPS = 1.4f

    /**
     * 同瞬多源观测合并窗口：GPS 与网络定位/主动取位可能携带相同或极近的 measuredMs
     * 而坐标相差数十米。旧逻辑 elapsed=0 会被判成无穷速度直接拆段（碎段主因之一）。
     * |Δt| ≤ 2s 且相距 < 150m 的相邻点合并为一条（保留精度更高者）。
     */
    private const val SAME_INSTANT_MERGE_MS = 2_000L
    private const val SAME_INSTANT_MERGE_METERS = 150.0

    fun build(
        rawPoints: List<LocationPointEntity>,
        visits: List<PlaceVisitView> = emptyList(),
        gaps: List<CollectionGapEntity> = emptyList(),
    ): DailyRoute {
        val sortedVisits = visits.sortedBy { it.startMs }
        val usable = rawPoints.asSequence()
            .filter(::isUsable)
            .sortedWith(compareBy<LocationPointEntity>({ effectiveTime(it) }, { it.recordedMs }, { it.id }))
            .toList()
        // 双指针匹配停留：避免旧实现逐点线性扫全部停留（O(P×V)，千点级日程可感知）。
        val accepted = ArrayList<RoutePoint>(usable.size)
        var visitIdx = 0
        for (entity in usable) {
            val t = effectiveTime(entity)
            while (visitIdx < sortedVisits.size && sortedVisits[visitIdx].endMs < t) visitIdx++
            val covering = sortedVisits.getOrNull(visitIdx)?.takeIf { t >= it.startMs && t <= it.endMs }
            accepted += entity.toRoutePoint(covering)
        }
        val deduped = accepted.distinctBy { Triple(it.timeMs, it.latitude, it.longitude) }
        if (deduped.isEmpty()) return DailyRoute(rejectedPointCount = rawPoints.size)

        // Drop a single large excursion that immediately returns near the trusted path. The raw
        // row remains in Room and in exports, so the decision is reversible and diagnosable.
        val qualityFiltered = deduped.filterIndexed { index, point ->
            if (index == 0 || index == deduped.lastIndex) return@filterIndexed true
            val before = deduped[index - 1]
            val after = deduped[index + 1]
            val inDistance = distance(before, point)
            val outDistance = distance(point, after)
            val bypassDistance = distance(before, after)
            !(inDistance > 1_000.0 && outDistance > 1_000.0 && bypassDistance < 500.0)
        }

        val merged = mergeSameInstant(qualityFiltered)

        val locationGaps = gaps.filter { it.source == SourceId.LOCATION.name }

        // ── 硬切点：真实断档 / 物理上不可能的瞬移。只有这两类才拆开连续记录。──
        val hardSplitIndices = mutableListOf<Int>()
        val hardInterruptions = mutableListOf<RouteInterruption>()
        val minorInterruptions = mutableListOf<RouteInterruption>()
        for (i in 1 until merged.size) {
            val previous = merged[i - 1]
            val current = merged[i]
            val elapsed = current.timeMs - previous.timeMs
            val gapDistance = distance(previous, current)
            val impliedSpeedMps = if (elapsed > 0) gapDistance / (elapsed / 1_000.0) else 0.0
            val explicitGap = locationGaps.firstOrNull {
                it.startMs < current.timeMs && it.endMs > previous.timeMs
            }
            val isHardSplit = (explicitGap != null && gapDistance > STABILITY_RADIUS_METERS) ||
                elapsed > INTERRUPTION_AFTER_MS ||
                // 距离下限防止跨源时钟抖动（数百米/亚秒）被误判为瞬移拆段；
                // 真正的坏定位跳变通常达公里级，仍会被拦截。
                (impliedSpeedMps > IMPLAUSIBLE_SPEED_MPS && gapDistance > 1_000.0)
            if (isHardSplit) {
                hardSplitIndices += i
                hardInterruptions += RouteInterruption(
                    lostAt = previous,
                    recoveredAt = current,
                    reason = explicitGap?.reason ?: if (impliedSpeedMps > IMPLAUSIBLE_SPEED_MPS) {
                        "IMPLAUSIBLE_JUMP_${impliedSpeedMps.toInt()}MPS"
                    } else {
                        "NO_LOCATION_FIX_${elapsed}MS"
                    },
                )
            } else if (explicitGap != null || elapsed >= MINOR_GAP_AFTER_MS) {
                // 短空洞或原地静默（位移小）：行程内部虚线，不拆段。
                minorInterruptions += RouteInterruption(
                    lostAt = previous,
                    recoveredAt = current,
                    reason = explicitGap?.reason ?: "NO_LOCATION_FIX_${elapsed}MS",
                )
            }
        }

        // ── 原地记录聚类：必须在平滑之前做，平滑会把静止点折叠，5 分钟判据届时无从谈起。──
        // 判定用「逐步 ≤200m 且无 provider 速度确认」延伸块；分类时再加总位移闸门——
        // 慢速拥堵（30s 步进 <200m）会形成假静止块，但其总位移远超真红灯，借此排除。
        val stillRuns = mutableListOf<Pair<Int, Int>>() // from..to inclusive
        var runStart = 0
        for (i in 1 until merged.size) {
            val previous = merged[i - 1]
            val current = merged[i]
            val providerMoving = current.speedMps?.let { it.isFinite() && it >= MOVING_SPEED_MPS } == true
            if (providerMoving || distance(previous, current) > STABILITY_RADIUS_METERS) {
                if (i - 1 > runStart) stillRuns += runStart to (i - 1)
                runStart = i
            }
        }
        if (merged.lastIndex > runStart) stillRuns += runStart to merged.lastIndex

        fun stillDisplacement(from: Int, to: Int): Double = distance(merged[from], merged[to])
        fun isMajorStop(from: Int, to: Int): Boolean =
            merged[to].timeMs - merged[from].timeMs >= TRIP_BOUNDARY_STILL_MS &&
                stillDisplacement(from, to) <= STILL_MAX_DISPLACEMENT_METERS

        val majorStops = stillRuns.mapNotNull { (from, to) ->
            if (!isMajorStop(from, to)) return@mapNotNull null
            RouteStop(
                startMs = merged[from].timeMs,
                endMs = merged[to].timeMs,
                latitude = merged[from].latitude,
                longitude = merged[from].longitude,
                placeName = placeNameAt(sortedVisits, merged[from].timeMs, forward = false),
            )
        }

        // ── 行程组装：硬切点与真实停留之间的点属于同一段行程。──
        data class TripCut(val endAt: Int, val resumeAt: Int)
        val cuts = buildList {
            hardSplitIndices.forEach { add(TripCut(endAt = it - 1, resumeAt = it)) }
            stillRuns.forEach { (from, to) ->
                if (isMajorStop(from, to)) {
                    add(TripCut(endAt = from, resumeAt = to))
                }
            }
            // 按 endAt 排序：边界（行程结束位置）决定处理顺序。若按 resumeAt 排序，
            // 「静止块内部夹 >5min gap」时 hardSplit 的 resumeAt 先于 majorStop 的
            // resumeAt，majorStop 会被 endAt<tripStart 误吞，行程从静止块中间开始。
            // 跳过条件用 resumeAt<=tripStart（恢复点已在已处理区间内），不丢后续点。
        }.sortedBy { it.endAt }

        val trips = mutableListOf<RouteTrip>()
        fun emitTrip(fromInclusive: Int, toInclusive: Int) {
            if (toInclusive < fromInclusive) return
            val points = merged.subList(fromInclusive, toInclusive + 1)
            val smoothed = smoothSection(points, minorInterruptions) ?: return
            if (smoothed.distanceMeters < MIN_TRIP_DISTANCE_METERS) return
            val stops = stillRuns.mapNotNull { (from, to) ->
                if (from < fromInclusive || to > toInclusive) return@mapNotNull null
                val duration = merged[to].timeMs - merged[from].timeMs
                if (duration < MIN_VISIBLE_STOP_MS) return@mapNotNull null
                if (stillDisplacement(from, to) > STILL_MAX_DISPLACEMENT_METERS) return@mapNotNull null
                RouteStop(
                    startMs = merged[from].timeMs,
                    endMs = merged[to].timeMs,
                    latitude = merged[from].latitude,
                    longitude = merged[from].longitude,
                )
            }
            val internal = minorInterruptions.filter {
                it.lostAt.timeMs >= points.first().timeMs && it.recoveredAt.timeMs <= points.last().timeMs
            }
            trips += RouteTrip(
                sections = listOf(smoothed),
                stops = stops,
                interruptions = internal,
                startPlaceName = placeNameAt(sortedVisits, points.first().timeMs, forward = false),
                endPlaceName = placeNameAt(sortedVisits, points.last().timeMs, forward = true),
            )
        }
        var tripStart = 0
        for (cut in cuts) {
            if (cut.resumeAt <= tripStart) continue
            emitTrip(tripStart, cut.endAt)
            tripStart = cut.resumeAt
        }
        emitTrip(tripStart, merged.lastIndex)

        val sections = splitAt(hardSplitIndices, merged).mapNotNull { smoothSection(it, minorInterruptions) }
        return DailyRoute(
            sections = sections,
            // 硬中断（断档/瞬移）在前，行程内微空洞随后；UI 与导出按时间序消费。
            interruptions = (hardInterruptions + minorInterruptions).sortedBy { it.lostAt.timeMs },
            trips = trips,
            majorStops = majorStops,
            rejectedPointCount = rawPoints.size - qualityFiltered.size,
        )
    }

    /** 同瞬多源观测合并：见 [SAME_INSTANT_MERGE_MS]。原始行仍在数据库与导出中完整保留。 */
    private fun mergeSameInstant(points: List<RoutePoint>): List<RoutePoint> {
        if (points.size < 2) return points
        val result = mutableListOf(points.first())
        for (point in points.drop(1)) {
            val previous = result.last()
            val elapsed = point.timeMs - previous.timeMs
            // 合并窗口随精度放大：低精度网络定位（±150m）下，相距 300m 的两点
            // 仍可能是同一瞬观测；高精度 GPS 对则用紧凑窗口，避免吞掉真实微移动。
            val mergeWindow = SAME_INSTANT_MERGE_METERS +
                (previous.accuracyM + point.accuracyM).toDouble().coerceAtMost(300.0)
            if (elapsed >= 0 && elapsed <= SAME_INSTANT_MERGE_MS && distance(previous, point) < mergeWindow
            ) {
                if (point.accuracyM < previous.accuracyM) result[result.lastIndex] = point
            } else {
                result += point
            }
        }
        return result
    }

    private fun splitAt(indices: List<Int>, points: List<RoutePoint>): List<List<RoutePoint>> {
        if (indices.isEmpty()) return listOf(points)
        val parts = mutableListOf<List<RoutePoint>>()
        var start = 0
        for (index in indices) {
            if (index > points.lastIndex) break
            parts += points.subList(start, index)
            start = index
        }
        if (start <= points.lastIndex) parts += points.subList(start, points.size)
        return parts
    }

    private fun placeNameAt(visits: List<PlaceVisitView>, timeMs: Long, forward: Boolean): String? {
        visits.firstOrNull { timeMs in it.startMs..it.endMs }?.let { return it.name }
        val tolerance = 10 * 60_000L
        return if (forward) {
            visits.firstOrNull { it.startMs in timeMs..(timeMs + tolerance) }?.name
        } else {
            visits.firstOrNull { it.endMs in (timeMs - tolerance)..timeMs }?.name
        }
    }

    private fun smoothSection(points: List<RoutePoint>, gaps: List<RouteInterruption> = emptyList()): RouteSection? {
        if (points.isEmpty()) return null
        val result = mutableListOf(points.first())
        var stabilityAnchor = points.first()
        var moving = false
        points.drop(1).forEach { point ->
            // Preserve both gap boundaries even below the noise floor. Smoothing must not
            // bridge an unknown interval or swallow a valid edge immediately after it.
            if (gaps.any { point == it.lostAt || point == it.recoveredAt }) {
                result += point
                stabilityAnchor = point
                return@forEach
            }
            val fromAnchor = distance(stabilityAnchor, point)
            val providerConfirmsMovement = point.speedMps?.let { it.isFinite() && it >= MOVING_SPEED_MPS } == true
            if (!moving && fromAnchor <= STABILITY_RADIUS_METERS && !providerConfirmsMovement) {
                return@forEach
            }
            if (fromAnchor > STABILITY_RADIUS_METERS || providerConfirmsMovement) {
                moving = true
            }
            val previous = result.last()
            val movement = distance(previous, point)
            // Accuracy-aware floor removes meter-scale GPS chatter without imposing a 200 m
            // minimum on genuine walking/driving increments once movement has been established.
            val noiseFloor = ((previous.accuracyM + point.accuracyM) / 4.0).coerceIn(5.0, 35.0)
            if (movement >= noiseFloor) result += point
            if (!moving) stabilityAnchor = point
        }
        if (result.size == 1 && points.size > 1) {
            // Preserve the last observation time while keeping the stationary coordinate stable.
            result += points.last().copy(latitude = result.first().latitude, longitude = result.first().longitude)
        }
        val section = RouteSection(result, 0.0, gaps)
        return section.copy(distanceMeters = section.observedEdges.sumOf { (a, b) -> distance(a, b) })
    }

    private fun LocationPointEntity.toRoutePoint(visit: PlaceVisitView?): RoutePoint = RoutePoint(
        sourceId = id,
        timeMs = effectiveTime(this),
        latitude = latitude,
        longitude = longitude,
        accuracyM = accuracyM,
        speedMps = speedMps,
    )

    private fun isUsable(point: LocationPointEntity): Boolean =
        !point.isMock && point.latitude in -90.0..90.0 && point.longitude in -180.0..180.0 &&
            point.accuracyM.isFinite() && point.accuracyM in 0f..MAX_ACCEPTED_ACCURACY_METERS

    private fun effectiveTime(point: LocationPointEntity): Long =
        point.measuredMs.takeIf { it > 0 } ?: point.recordedMs

    private fun distance(a: RoutePoint, b: RoutePoint): Double =
        distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
}
