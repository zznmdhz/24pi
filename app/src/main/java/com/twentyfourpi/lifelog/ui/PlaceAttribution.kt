package com.twentyfourpi.lifelog.ui

import com.twentyfourpi.lifelog.data.PlaceEntity
import com.twentyfourpi.lifelog.data.PlaceVisitView
import com.twentyfourpi.lifelog.util.distanceMeters

/**
 * F 批（方案 16.4–16.6）：地点“稳定归属”展示层纯函数。
 *
 * 目标：家附近 100–200 米漂移不再变成邻近设施，也不因访问次数无限强化误归属。
 * 本层只做**归属投影**——不覆盖坐标、不重算里程、不修改原行程边界（T44/T45/T46）。
 *
 * 与采集层 [com.twentyfourpi.lifelog.collector.selectWeightedPlaceForCandidate] 的区别：
 * 采集层解决“这个点匹配哪个地点”；本层解决“这段停留显示成哪个个人地点”，
 * 并支持用户确认/修订优先于历史权重。
 */

/** 个人地点的稳定边界：进入圈（小）与离开圈（大），避免边界抖动来回切换。 */
data class PlaceZone(
    val placeId: Long,
    val centerLat: Double,
    val centerLon: Double,
    /** 进入范围（试验 150–200m）：从外进入此圈并稳定后才能确认到达。 */
    val enterRadiusMeters: Double = 200.0,
    /** 离开范围（试验 250–300m）：超出此圈并稳定后才确认离开。 */
    val leaveRadiusMeters: Double = 280.0,
    /** 用户是否手动确认过此地点（最高优先级）。 */
    val userConfirmed: Boolean = false,
    /** 历史独立访问次数（只作有限加分，可设上限/对数衰减）。 */
    val visitCount: Int = 0,
)

/** 一次位置观测（用于归属判断；精度差会增加不确定性，不无限扩大半径）。 */
data class AttributionSample(
    val timeMs: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Float,
    /** 独立停留证据：同批缓存心跳不算新确认票。 */
    val isDistinct: Boolean = true,
)

/** 归属判断结果。 */
enum class AttributionDecision { ENTER, LEAVE, STAY }

/** 用户修订记录（F-4 前置数据结构；不修改原始 visit，只影响展示投影）。 */
data class AttributionRevision(
    /** 原始 visit / 时段标识；null 表示整条历史适用。 */
    val targetKey: String?,
    val fromPlaceId: Long?,
    val toPlaceId: Long?,
    val appliedToMs: Long,
    val reason: String,
    val revertedBy: String? = null,
    /** 创建时间（修订解析按此取最新，不依赖调用方列表顺序）。 */
    val createdMs: Long = 0L,
) {
    val isActive: Boolean get() = revertedBy == null
}

/**
 * 稳定归属状态机：进入/离开采用不同半径，并要求连续新鲜独立点确认，
 * 防止单一漂移点立即切换归属（方案 16.4）。
 *
 * 状态转换：
 * - 当前不在 [zone] 内：连续 [CONFIRM_POINTS] 个新鲜独立点落在 enter 圈内，
 *   且跨时 ≥ [CONFIRM_SPAN_MS]，才 ENTER。
 * - 当前在 [zone] 内：连续 [CONFIRM_POINTS] 个新鲜独立点落在 leave 圈外，
 *   且跨时 ≥ [CONFIRM_SPAN_MS]，才 LEAVE。坏精度点不增加确认票。
 * - 其余情况 STAY（维持现状）。用户确认的地点只在持续反证足够强时重新评估。
 */
data class PlaceAttributionState(
    val currentPlaceId: Long? = null,
    val pendingEnterCount: Int = 0,
    /** 累计进入票所对应的候选地点；候选切换时清零，避免跨地点共享票（审核建议）。 */
    val pendingEnterPlaceId: Long? = null,
    /** R10：进入确认累计时间从第一票算，不再用相邻两点的间隔。 */
    val pendingEnterStartedAtMs: Long = 0L,
    val pendingLeaveCount: Int = 0,
    /** R10：离开确认累计时间从第一票算。 */
    val pendingLeaveStartedAtMs: Long = 0L,
    val lastFreshSampleMs: Long = 0L,
) {
    fun next(
        sample: AttributionSample,
        zones: List<PlaceZone>,
    ): Pair<PlaceAttributionState, AttributionDecision> {
        if (currentPlaceId != null && zones.none { it.placeId == currentPlaceId }) {
            // 已归属地点被删除（或不再属于候选 zone）：清空，避免归属永久悬挂（审核建议）。
            return copy(
                currentPlaceId = null,
                pendingEnterCount = 0,
                pendingEnterPlaceId = null,
                pendingLeaveCount = 0,
            ) to AttributionDecision.STAY
        }
        // 坏精度/缓存心跳：不增票、不清票、不推进新鲜时间戳（中性语义，与注释/T44 一致）。
        // 好-坏-好交替（GPS 常态）不应无限推迟确认。
        if (!(sample.accuracyM <= MAX_USABLE_ACCURACY_M && sample.isDistinct)) {
            return this to AttributionDecision.STAY
        }
        val candidate = zones
            .filter { it.userConfirmed || it.visitCount > 0 }
            .minByOrNull { zone ->
                distanceMeters(zone.centerLat, zone.centerLon, sample.latitude, sample.longitude)
            }
        if (candidate == null) {
            return copy(currentPlaceId = null, pendingEnterCount = 0, pendingEnterPlaceId = null, pendingLeaveCount = 0) to AttributionDecision.STAY
        }
        val dist = distanceMeters(candidate.centerLat, candidate.centerLon, sample.latitude, sample.longitude)
        val isInside = dist <= candidate.enterRadiusMeters
        val isOutside = dist > candidate.leaveRadiusMeters
        val usable = true // 已在入口过滤；后续分支无需重复判定。

        return when {
            currentPlaceId == candidate.placeId -> {
                // 已在归属中：足够强的持续反证才进入重新评估（防误锁定）。
                if (usable && isOutside) {
                    // R10：断档超限时重新累计（隔数小时的票不拼）。
                    val gapOk = pendingLeaveCount == 0 || sample.timeMs - lastFreshSampleMs <= MAX_VOTE_INTERVAL_MS
                    val count = if (gapOk) pendingLeaveCount + 1 else 1
                    // R10：累计时间从第一票算（第一票记 startedAt；后续用与第一票的跨度）。
                    val startedAt = if (gapOk && pendingLeaveCount > 0) pendingLeaveStartedAtMs else sample.timeMs
                    val spanOk = sample.timeMs - startedAt >= CONFIRM_SPAN_MS
                    val leave = count >= CONFIRM_POINTS && spanOk
                    val nextState = copy(
                        currentPlaceId = if (leave) null else currentPlaceId,
                        pendingLeaveCount = if (leave) 0 else count,
                        pendingLeaveStartedAtMs = if (leave) 0L else startedAt,
                        pendingEnterCount = 0,
                        pendingEnterPlaceId = null,
                        pendingEnterStartedAtMs = 0L,
                        lastFreshSampleMs = sample.timeMs,
                    )
                    nextState to if (leave) AttributionDecision.LEAVE else AttributionDecision.STAY
                } else if (isInside) {
                    copy(pendingLeaveCount = 0, pendingLeaveStartedAtMs = 0L, pendingEnterCount = 0, pendingEnterPlaceId = null, pendingEnterStartedAtMs = 0L, lastFreshSampleMs = sample.timeMs) to
                        AttributionDecision.STAY
                } else {
                    // 处于中间带（enter..leave）：维持，但只积累新鲜证据。
                    copy(pendingLeaveCount = 0, pendingLeaveStartedAtMs = 0L, pendingEnterCount = 0, pendingEnterPlaceId = null, pendingEnterStartedAtMs = 0L) to AttributionDecision.STAY
                }
            }
            else -> {
                // 未归属/归属别处：进入候选地点需要连续新鲜证据。
                if (usable && isInside) {
                    // 候选切换时清零，杜绝跨圈共享票与重叠区交替翻转（审核建议）。
                    val sameCandidate = pendingEnterPlaceId == candidate.placeId
                    // R10：断档超限时重新累计（隔数小时的票不拼）。
                    val gapOk = !sameCandidate || pendingEnterCount == 0 || sample.timeMs - lastFreshSampleMs <= MAX_VOTE_INTERVAL_MS
                    val count = if (sameCandidate && gapOk) pendingEnterCount + 1 else 1
                    // R10：累计时间从第一票算；候选切换/断档后用新起点。
                    val startedAt = if (sameCandidate && pendingEnterCount > 0 && gapOk) pendingEnterStartedAtMs else sample.timeMs
                    val spanOk = sample.timeMs - startedAt >= CONFIRM_SPAN_MS
                    val enter = count >= CONFIRM_POINTS && spanOk
                    val nextState = copy(
                        currentPlaceId = if (enter) candidate.placeId else currentPlaceId,
                        pendingEnterCount = if (enter) 0 else count,
                        pendingEnterPlaceId = if (enter) null else candidate.placeId,
                        pendingEnterStartedAtMs = if (enter) 0L else startedAt,
                        pendingLeaveCount = 0,
                        pendingLeaveStartedAtMs = 0L,
                        lastFreshSampleMs = sample.timeMs,
                    )
                    nextState to if (enter) AttributionDecision.ENTER else AttributionDecision.STAY
                } else if (usable && isOutside) {
                    copy(pendingEnterCount = 0, pendingEnterPlaceId = null, pendingEnterStartedAtMs = 0L, pendingLeaveCount = 0, pendingLeaveStartedAtMs = 0L) to AttributionDecision.STAY
                } else {
                    copy(pendingEnterCount = 0, pendingEnterPlaceId = null, pendingEnterStartedAtMs = 0L, pendingLeaveCount = 0, pendingLeaveStartedAtMs = 0L) to AttributionDecision.STAY
                }
            }
        }
    }

    companion object {
        /** 连续新鲜独立点数量（试验值）。 */
        const val CONFIRM_POINTS = 2
        /** 跨时窗口（试验 90–120s）。 */
        const val CONFIRM_SPAN_MS = 90_000L
        /** R10：相邻有效票间隔超过此值视为断档，重新累计（隔数小时不能拼票）。 */
        const val MAX_VOTE_INTERVAL_MS = 10 * 60_000L
        /** 精度差于此的样本不增加确认票。 */
        const val MAX_USABLE_ACCURACY_M = 60f
    }
}

/**
 * 为一段停留选择展示归属地点。
 *
 * 优先级（方案 16.4）：用户对该时段的确认最高 → 可信地点边界（进入圈内最近）→
 * 前后连续性 → 位置精度 → 独立到访历史（有限加分，使用对数衰减，不单独压过距离与反证）。
 * 证据不足时返回 null，由上层显示“家附近”等中性标签。
 */
fun chooseDisplayPlace(
    visit: PlaceVisitView,
    zones: List<PlaceZone>,
    revisions: List<AttributionRevision> = emptyList(),
): DisplayPlaceResult {
    // 1) 用户修订优先：同 key 多条活动修订按创建时间取最新，不依赖调用方列表顺序（审核建议）。
    val activeRevisions = revisions.filter { it.isActive }
    val visitRevision = activeRevisions
        .filter { it.targetKey == "visit:${visit.id}" && it.toPlaceId != null }
        .maxByOrNull { it.createdMs }
    if (visitRevision != null) {
        val zone = zones.firstOrNull { it.placeId == visitRevision.toPlaceId }
        // 修订目标地点已被删除时降级为中性，不抛异常（审核建议：孤儿修订降级 unknown）。
        if (zone != null) {
            return DisplayPlaceResult(placeId = visitRevision.toPlaceId, confirmed = true, source = "revision")
        }
        return DisplayPlaceResult(placeId = null, confirmed = false, source = "unknown")
    }
    val placeRevision = activeRevisions
        .filter { it.targetKey == "place:${visit.placeId}" && it.toPlaceId != null }
        .maxByOrNull { it.createdMs }
    if (placeRevision != null) {
        val zone = zones.firstOrNull { it.placeId == placeRevision.toPlaceId }
        if (zone != null) {
            return DisplayPlaceResult(placeId = placeRevision.toPlaceId, confirmed = true, source = "revision")
        }
        return DisplayPlaceResult(placeId = null, confirmed = false, source = "unknown")
    }

    // 2) 进入圈内, 按 (分数, 距离) 排序——用户确认权重最高，历史次数只作有限加分。
    val center = (visit.latitude to visit.longitude)
    data class ZoneCandidate(val zone: PlaceZone, val distanceMeters: Double, val score: Double)

    val best = zones
        .filter { it.visitCount > 0 || it.userConfirmed }
        .map { zone ->
            val dist = distanceMeters(zone.centerLat, zone.centerLon, center.first, center.second)
            ZoneCandidate(zone, dist, attributionScore(zone, dist))
        }
        .filter { it.distanceMeters <= it.zone.enterRadiusMeters }
        .minWithOrNull(
            compareBy<ZoneCandidate> { -it.score }.thenBy { it.distanceMeters },
        )
    return if (best != null) {
        DisplayPlaceResult(placeId = best.zone.placeId, confirmed = true, source = "zone")
    } else {
        // 3) 证据不足：中性返回（上层可显示“家附近”）。
        displayPlaceUnknown(visit, zones, revisions)
    }
}

/** 有限加分：访问次数越多信任越高，但对数衰减 + 硬顶防止“次数最多永远赢”。 */
private fun attributionScore(zone: PlaceZone, distanceMeters: Double): Double {
    val distancePenalty = distanceMeters.coerceAtLeast(0.0)
    // 硬顶：visitCount 超过 50 不再加分，确保历史加分峰值（ln1p(50)*50≈196）
    // 显著小于进入圈半径（200m）：在同一候选圈内对比时，历史次数不能单独
    // 压过明显更近的距离（家/工厂同圈极端 199m vs 5m 时仍可能因圈内夹逼反转，
    // 但候选过滤要求 visitCount>0，且 userConfirmed(1000) 恒压非确认）。
    val cappedCount = minOf(zone.visitCount, HISTORY_VISIT_CAP)
    val historyBoost = if (cappedCount > 0) kotlin.math.ln1p(cappedCount.toDouble()) * HISTORY_BOOST_SCALE else 0.0
    val userBoost = if (zone.userConfirmed) USER_CONFIRM_BOOST else 0.0
    return userBoost + historyBoost - distancePenalty
}

private const val HISTORY_VISIT_CAP = 50
private const val HISTORY_BOOST_SCALE = 50.0
private const val USER_CONFIRM_BOOST = 1_000.0

private fun displayPlaceUnknown(
    visit: PlaceVisitView,
    zones: List<PlaceZone>,
    revisions: List<AttributionRevision>,
): DisplayPlaceResult {
    // 距离家 200–280m 的“中间带”：采用中性“家附近”而非漂移成设施。
    val zonesNear = zones.filter { zone ->
        val dist = distanceMeters(zone.centerLat, zone.centerLon, visit.latitude, visit.longitude)
        dist in zone.enterRadiusMeters..zone.leaveRadiusMeters
    }
    return if (zonesNear.isNotEmpty()) {
        DisplayPlaceResult(placeId = null, confirmed = false, source = "nearby", nearbyOfPlaceId = zonesNear.minByOrNull {
            distanceMeters(it.centerLat, it.centerLon, visit.latitude, visit.longitude)
        }?.placeId)
    } else {
        DisplayPlaceResult(placeId = null, confirmed = false, source = "unknown")
    }
}

data class DisplayPlaceResult(
    val placeId: Long?,
    val confirmed: Boolean,
    val source: String,
    val nearbyOfPlaceId: Long? = null,
)