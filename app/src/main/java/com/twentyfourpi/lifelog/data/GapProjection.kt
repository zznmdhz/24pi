package com.twentyfourpi.lifelog.data

/**
 * 有效缺口投影层（第四批）。
 *
 * 背景：原始 `collection_gaps` 记录的是"某个来源在这段时间没有落库数据"，但它会把两种情况
 * 混为一谈——真实没有位置，和"系统回调静默、主动取位拿到了点却没关缺口"。
 * 缺口内仍可能存在有效定位点和到访，展示时需要逐段核对证据。
 *
 * 本层只做一件事：**用原始证据（测量时间 + 可信性）把原始缺口重建成有依据的区间**，
 * 并给每个区间标注它到底有多可信：
 * - [GapSupport.MEASURED]：到访区间覆盖的部分——有真实停留证据；
 * - [GapSupport.INFERRED]：夹在同一地点两段实测停留之间的短空档（有限时间、无离开证据）；
 * - [GapSupport.UNKNOWN]：既没有实测覆盖，也不满足推断条件的部分——**真正未知**。
 *
 * 三条铁律（对应复核稿第 6、7 节）：
 * 1. 不删除任何原始记录：本层是派生的只读投影，原始点/到访/缺口永远可查；
 * 2. 不能只凭点数把整段删掉，也不能把原始缺口全数扣出在家时长——重建按测量时间逐段解释；
 * 3. 长空档不默认补齐：即便前后是同一地点，也不能超过推断上限（见 [GapProjectionRules.MAX_INFERENCE_MS]）。
 */

/** 投影后每段的可信度。 */
enum class GapSupport {
    /** 有实测到达访证据支撑。 */
    MEASURED,

    /** 有限时间的连续推断（前后同地点、无离开证据、时长受限）。 */
    INFERRED,

    /** 真正未知：既无实测覆盖也不满足推断条件。 */
    UNKNOWN,
}

/** 投影模式——「撤销投影」回到原始解释的开关。 */
enum class ProjectionMode {
    /** 有效投影（默认）。 */
    EFFECTIVE,

    /** 原始解释：缺口原样整段视为未知，不做重建。 */
    RAW,
}

/** 证据引用：只存引用 id 与时刻，不复制记录内容。 */
data class GapEvidenceRef(
    /** "visit" / "point" */
    val kind: String,
    val refId: Long,
    /** 原始测量时刻（点的 measuredMs / 到访的 startMs）。 */
    val atMs: Long,
    val accuracyM: Double? = null,
)

/** 投影后的一段区间。 */
data class GapProjectionInterval(
    val startMs: Long,
    val endMs: Long,
    val support: GapSupport,
    val evidence: List<GapEvidenceRef> = emptyList(),
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

/**
 * 一条原始缺口的重建结果。
 *
 * [intervals] 是按时间排列、互不重叠的分段；[unknownIntervals] 才是展示层该讲的"缺失"。
 */
data class EffectiveGap(
    val rawGapId: Long,
    val source: String,
    val rawStartMs: Long,
    val rawEndMs: Long,
    val rawReason: String,
    val intervals: List<GapProjectionInterval>,
    /** 原始缺口的前提（"这段时间没有数据"）是否被证据推翻。 */
    val supersededByEvidence: Boolean,
    /** 原因是否属于"必须让用户知道"的硬故障（权限/关闭/写库失败等）。 */
    val actionableReason: Boolean,
) {
    val rawDurationMs: Long get() = (rawEndMs - rawStartMs).coerceAtLeast(0L)

    val measuredMs: Long get() = intervals.filter { it.support == GapSupport.MEASURED }.sumOf { it.durationMs }
    val inferredMs: Long get() = intervals.filter { it.support == GapSupport.INFERRED }.sumOf { it.durationMs }
    val unknownMs: Long get() = intervals.filter { it.support == GapSupport.UNKNOWN }.sumOf { it.durationMs }

    val unknownIntervals: List<GapProjectionInterval>
        get() = intervals.filter { it.support == GapSupport.UNKNOWN }

    /** 原始时长被证据解释了多少（用于回归对账：原始时长不因投影而改变）。 */
    val explainedMs: Long get() = measuredMs + inferredMs
}

/**
 * 一天/一段窗口的完整投影。
 *
 * 四个出口（首页、详情、汇总统计、诊断导出）必须消费**同一份** [GapProjection] 与同一个
 * [asOfMs] 截止时刻，否则同一天会出现两套说法。
 */
data class GapProjection(
    val ruleVersion: Int,
    val mode: ProjectionMode,
    val generatedAtMs: Long,
    val asOfMs: Long,
    val windowStartMs: Long,
    val windowEndMs: Long,
    /** 输入证据摘要（计数 + 最大 id），用于对账，不含任何内容。 */
    val inputDigest: String,
    val gaps: List<EffectiveGap>,
) {
    /** 合并后的"真正未知"区间（全部来源，供导出与完整性核对使用）。 */
    val unknownIntervals: List<GapProjectionInterval> by lazy {
        mergeAdjacent(
            gaps.flatMap { it.unknownIntervals }.sortedBy { it.startMs },
            GapProjectionRules.UNKNOWN_MERGE_DISTANCE_MS,
        )
    }

    val unknownMs: Long get() = unknownIntervals.sumOf { it.durationMs }

    /** 单一来源的未知区间——展示层各说各的，不互相宣判。 */
    fun unknownIntervalsFor(source: String): List<GapProjectionInterval> = mergeAdjacent(
        gaps.filter { it.source.equals(source, ignoreCase = true) }
            .flatMap { it.unknownIntervals }
            .sortedBy { it.startMs },
        GapProjectionRules.UNKNOWN_MERGE_DISTANCE_MS,
    )

    fun unknownMsFor(source: String): Long = unknownIntervalsFor(source).sumOf { it.durationMs }

    /** 与某个窗口（例如一张卡）真正相交的未知分钟数——按交集解释，不是整卡连坐。 */
    fun unknownOverlapMs(startMs: Long, endMs: Long, source: String? = null): Long =
        if (source == null) {
            unknownIntervals.sumOf { overlapMs(it.startMs, it.endMs, startMs, endMs) }
        } else {
            unknownIntervalsFor(source).sumOf { overlapMs(it.startMs, it.endMs, startMs, endMs) }
        }

    /** 与窗口相交的未知片段（含位置，供界面写"开头/中段/末尾"）。 */
    fun unknownOverlapIntervals(
        startMs: Long,
        endMs: Long,
        source: String? = null,
    ): List<GapProjectionInterval> {
        val source_ = source?.let { unknownIntervalsFor(it) } ?: unknownIntervals
        return source_.mapNotNull { interval ->
            val start = maxOf(interval.startMs, startMs)
            val end = minOf(interval.endMs, endMs)
            if (end > start) interval.copy(startMs = start, endMs = end) else null
        }
    }

    /** 仍需用户处理的缺口（按来源筛选可选）。 */
    fun actionableGaps(source: String? = null): List<EffectiveGap> = gaps.filter { gap ->
        gap.actionableReason && gap.unknownMs > 0 &&
            (source == null || gap.source.equals(source, ignoreCase = true))
    }

    /**
     * 这张卡片是否达到"实质缺失"（红卡）门槛。
     *
     * 规则：未知占卡片 ≥ [GapProjectionRules.CARD_SEVERE_RATIO]，或未知时长 ≥
     * [GapProjectionRules.CARD_SEVERE_MIN_MS]。判定放在数据层，界面与导出共用同一口径。
     */
    fun isSevereCard(startMs: Long, endMs: Long, source: String? = null): Boolean {
        if (endMs <= startMs) return false
        val unknown = unknownOverlapMs(startMs, endMs, source)
        if (unknown <= 0L) return false
        val cardMs = endMs - startMs
        return unknown.toDouble() / cardMs >= GapProjectionRules.CARD_SEVERE_RATIO ||
            unknown >= GapProjectionRules.CARD_SEVERE_MIN_MS
    }

    /** 是否还有必须让用户处理的硬故障缺口。 */
    val hasActionableUnknown: Boolean get() = actionableGaps().isNotEmpty()

    val unknownMinutes: Long get() = unknownMs / 60_000L

    companion object {
        fun empty(windowStartMs: Long, windowEndMs: Long, asOfMs: Long, generatedAtMs: Long): GapProjection =
            GapProjection(
                ruleVersion = GapProjectionRules.RULE_VERSION,
                mode = ProjectionMode.EFFECTIVE,
                generatedAtMs = generatedAtMs,
                asOfMs = asOfMs,
                windowStartMs = windowStartMs,
                windowEndMs = windowEndMs,
                inputDigest = "empty",
                gaps = emptyList(),
            )
    }
}

/** 投影规则的常量——改动即代表规则版本变更，必须同步升 [GapProjectionRules.RULE_VERSION]。 */
/**
 * 「撤销投影」开关的持久化键。
 *
 * 定义在数据层：界面（设置页开关）、仓库/ViewModel、诊断导出三方必须读同一个键，
 * 否则导出包会跟界面讲两套话。
 */
const val GAP_PROJECTION_RAW_SETTING_KEY = "gap_projection_raw_mode"

object GapProjectionRules {
    /** 规则版本：写入日志与导出，便于回放对账。 */
    const val RULE_VERSION = 1

    /** 可信定位点的精度上限（米）。超过则只当作"有过尝试"，不作为可信证据。 */
    const val TRUSTED_ACCURACY_M = 200.0

    /**
     * 允许推断为连续停留的最长空档。
     *
     * 推断上限采用保守值；超过上限的空档保留为未知，不因前后地点相同而补齐。
     */
    const val MAX_INFERENCE_MS = 20 * 60_000L

    /** 相邻未知区间合并距离：小于它的间隙不值得单独提示。 */
    const val UNKNOWN_MERGE_DISTANCE_MS = 30_000L

    /** 卡片被判定为"实质缺失"的下限：未知时长 ≥ 30 分钟。 */
    const val CARD_SEVERE_MIN_MS = 30 * 60_000L

    /** 卡片被判为"实质缺失"的另一条件：未知占比 ≥ 50%。 */
    const val CARD_SEVERE_RATIO = 0.5

    /** 未知段取证容差：到访边界与定位点时间戳通常差几秒，取 ±2 分钟内的最近点作界定证据。 */
    const val BOUNDING_POINT_TOLERANCE_MS = 120_000L
}

/** 一个实测停留片段（到访裁剪到窗口），带地点身份用于"前后同地点"判定。 */
private data class MeasuredSpan(
    val startMs: Long,
    val endMs: Long,
    val placeId: Long,
    val ref: GapEvidenceRef,
) {
    val durationMs: Long get() = endMs - startMs
}

/**
 * 用原始证据重建缺口区间。
 *
 * 只有 LOCATION 来源的缺口会被重建，其它来源原样保留——通知/应用/步数各有各的采集链路，
 * 不能互相宣判（复核稿第四批第 2 条）。
 *
 * @param mode [ProjectionMode.RAW] 时不做任何重建，等价于"撤销投影"。
 */
fun projectCollectionGaps(
    gaps: List<CollectionGapEntity>,
    points: List<LocationPointEntity>,
    visits: List<PlaceVisitView>,
    windowStartMs: Long,
    windowEndMs: Long,
    asOfMs: Long,
    generatedAtMs: Long,
    mode: ProjectionMode = ProjectionMode.EFFECTIVE,
    locationSourceName: String = SourceId.LOCATION.name,
): GapProjection {
    if (windowEndMs <= windowStartMs) {
        return GapProjection.empty(windowStartMs, windowEndMs, asOfMs, generatedAtMs).copy(mode = mode)
    }
    val clipped = gaps.mapNotNull { gap ->
        val start = maxOf(gap.startMs, windowStartMs)
        val end = minOf(gap.endMs, windowEndMs)
        if (end > start) gap.copy(startMs = start, endMs = end) else null
    }.sortedBy { it.startMs }

    val trustedPoints = points.filter {
        !it.isMock &&
            it.accuracyM <= GapProjectionRules.TRUSTED_ACCURACY_M.toFloat() &&
            it.measuredMs in windowStartMs until windowEndMs
    }
    val digest = buildString {
        append("points=").append(points.size).append("/trusted=").append(trustedPoints.size)
        append(",visits=").append(visits.size).append(",gaps=").append(gaps.size)
        append(",maxPointId=").append(points.maxOfOrNull { it.id } ?: 0L)
        append(",maxVisitId=").append(visits.maxOfOrNull { it.id } ?: 0L)
        append(",maxGapId=").append(gaps.maxOfOrNull { it.id } ?: 0L)
    }
    return GapProjection(
        ruleVersion = GapProjectionRules.RULE_VERSION,
        mode = mode,
        generatedAtMs = generatedAtMs,
        asOfMs = asOfMs,
        windowStartMs = windowStartMs,
        windowEndMs = windowEndMs,
        inputDigest = digest,
        gaps = clipped.map { gap ->
            rebuildGap(
                gap = gap,
                trustedPoints = trustedPoints,
                visits = visits,
                locationSourceName = locationSourceName,
                mode = mode,
            )
        },
    )
}

private fun rebuildGap(
    gap: CollectionGapEntity,
    trustedPoints: List<LocationPointEntity>,
    visits: List<PlaceVisitView>,
    locationSourceName: String,
    mode: ProjectionMode,
): EffectiveGap {
    val start = gap.startMs
    val end = gap.endMs
    val actionable = gap.reason.isAlwaysVisibleGapReason()
    val insidePoints = trustedPoints.filter { it.measuredMs in start until end }
    val insideVisits = visits.filter { it.startMs < end && it.endMs > start }
    val isLocation = gap.source.equals(locationSourceName, ignoreCase = true)

    fun rawInterpretation(): EffectiveGap = EffectiveGap(
        rawGapId = gap.id,
        source = gap.source,
        rawStartMs = start,
        rawEndMs = end,
        rawReason = gap.reason,
        intervals = listOf(
            GapProjectionInterval(
                startMs = start,
                endMs = end,
                support = GapSupport.UNKNOWN,
                evidence = insidePoints.map(::pointRef),
            ),
        ),
        supersededByEvidence = false,
        actionableReason = actionable,
    )

    if (mode == ProjectionMode.RAW || !isLocation) return rawInterpretation()
    if (insideVisits.isEmpty() && insidePoints.isEmpty()) return rawInterpretation()

    val measured = insideVisits
        .map { visit ->
            val vs = maxOf(visit.startMs, start)
            val ve = minOf(visit.endMs, end)
            MeasuredSpan(vs, ve, visit.placeId, visitRef(visit))
        }
        .filter { it.durationMs > 0 }
        .sortedBy { it.startMs }

    val mergedMeasured = mergeMeasuredSpans(measured)
    val intervals = mutableListOf<GapProjectionInterval>()
    var cursor = start
    for (span in mergedMeasured) {
        if (span.startMs > cursor) {
            intervals += classifyHole(
                holeStart = cursor,
                holeEnd = span.startMs,
                before = mergedMeasured.lastOrNull { it.endMs <= cursor },
                after = span,
                allSpans = mergedMeasured,
                trustedPoints = trustedPoints,
            )
        }
        intervals += GapProjectionInterval(span.startMs, span.endMs, GapSupport.MEASURED, listOf(span.ref))
        cursor = maxOf(cursor, span.endMs)
    }
    if (cursor < end) {
        intervals += classifyHole(
            holeStart = cursor,
            holeEnd = end,
            before = mergedMeasured.lastOrNull { it.endMs <= cursor },
            after = null,
            allSpans = mergedMeasured,
        )
    }
    val normalized = mergeSameSupport(intervals.filter { it.durationMs > 0 })
    return EffectiveGap(
        rawGapId = gap.id,
        source = gap.source,
        rawStartMs = start,
        rawEndMs = end,
        rawReason = gap.reason,
        intervals = normalized,
        supersededByEvidence = normalized.any { it.support == GapSupport.MEASURED },
        actionableReason = actionable,
    )
}

/**
 * 判定一个空档是"有限推断"还是"真正未知"。
 *
 * 推断必须同时满足：前后都有实测停留、前后地点相同、空档不超过
 * [GapProjectionRules.MAX_INFERENCE_MS]，且空档内没有别的地点到访（无离开证据）。
 */
private fun classifyHole(
    holeStart: Long,
    holeEnd: Long,
    before: MeasuredSpan?,
    after: MeasuredSpan?,
    allSpans: List<MeasuredSpan>,
    trustedPoints: List<LocationPointEntity> = emptyList(),
): GapProjectionInterval {
    val duration = holeEnd - holeStart
    val samePlace = before != null && after != null && before.placeId == after.placeId
    val noLeavingEvidence = samePlace && allSpans.none { span ->
        span.placeId != before?.placeId && span.startMs < holeEnd && span.endMs > holeStart
    }
    val short = duration <= GapProjectionRules.MAX_INFERENCE_MS
    val inferable = samePlace && noLeavingEvidence && short
    return GapProjectionInterval(
        startMs = holeStart,
        endMs = holeEnd,
        support = if (inferable) GapSupport.INFERRED else GapSupport.UNKNOWN,
        // 证据引用：推断段给出两侧停留；未知段给出界定它的最近实测点（±2 分钟内），
        // 外部复核据此重放"这段为什么算未知、由什么证据界定"（复核稿第 9 节）。
        evidence = if (inferable) {
            listOfNotNull(before?.ref, after?.ref)
        } else {
            boundingPointRefs(holeStart, holeEnd, trustedPoints)
        },
    )
}

/**
 * 取界定这个空档的最近实测点引用（空档前最后一个、空档后第一个，各允许
 * [BOUNDING_POINT_TOLERANCE_MS] 的容差——到访边界与点的时间戳通常差几秒）。
 */
private fun boundingPointRefs(
    holeStart: Long,
    holeEnd: Long,
    trustedPoints: List<LocationPointEntity>,
): List<GapEvidenceRef> {
    val tolerance = GapProjectionRules.BOUNDING_POINT_TOLERANCE_MS
    val before = trustedPoints.filter { it.measuredMs in (holeStart - tolerance)..holeStart }.maxByOrNull { it.measuredMs }
    val after = trustedPoints.filter { it.measuredMs in holeEnd..(holeEnd + tolerance) }.minByOrNull { it.measuredMs }
    return listOfNotNull(before?.let(::pointRef), after?.let(::pointRef))
}

private fun mergeMeasuredSpans(spans: List<MeasuredSpan>): List<MeasuredSpan> {
    if (spans.isEmpty()) return emptyList()
    val result = mutableListOf<MeasuredSpan>()
    var current = spans.first()
    for (next in spans.drop(1)) {
        if (next.startMs <= current.endMs) {
            current = current.copy(endMs = maxOf(current.endMs, next.endMs))
        } else {
            result += current
            current = next
        }
    }
    result += current
    return result
}

private fun mergeSameSupport(intervals: List<GapProjectionInterval>): List<GapProjectionInterval> {
    if (intervals.isEmpty()) return emptyList()
    val sorted = intervals.sortedBy { it.startMs }
    val result = mutableListOf<GapProjectionInterval>()
    var current = sorted.first()
    for (next in sorted.drop(1)) {
        if (next.support == current.support && next.startMs <= current.endMs) {
            current = GapProjectionInterval(
                startMs = current.startMs,
                endMs = maxOf(current.endMs, next.endMs),
                support = current.support,
                evidence = (current.evidence + next.evidence).distinctBy { it.kind to it.refId },
            )
        } else {
            result += current
            current = next
        }
    }
    result += current
    return result
}

private fun pointRef(point: LocationPointEntity): GapEvidenceRef =
    GapEvidenceRef("point", point.id, point.measuredMs, point.accuracyM.toDouble())

private fun visitRef(visit: PlaceVisitView): GapEvidenceRef =
    GapEvidenceRef("visit", visit.id, visit.startMs, null)

/** 合并相邻区间（同支持度且间隙 ≤ distance）。 */
private fun mergeAdjacent(
    intervals: List<GapProjectionInterval>,
    distance: Long,
): List<GapProjectionInterval> {
    if (intervals.isEmpty()) return emptyList()
    val result = mutableListOf<GapProjectionInterval>()
    var current = intervals.first()
    for (next in intervals.drop(1)) {
        if (next.startMs <= current.endMs + distance) {
            current = current.copy(endMs = maxOf(current.endMs, next.endMs))
        } else {
            result += current
            current = next
        }
    }
    result += current
    return result
}

private fun overlapMs(aStart: Long, aEnd: Long, bStart: Long, bEnd: Long): Long =
    (minOf(aEnd, bEnd) - maxOf(aStart, bStart)).coerceAtLeast(0L)

/**
 * 每个地点的**裁决后停留时长**：到访区间按并集计算，重叠不重复计时。
 *
 * 复核稿第 6.3 节：档案"常去地点"直接加总到访时长，会和定位缺口给出两套说法；
 * 这里统一成区间并集，四个出口共用。
 */
fun adjudicatedPlaceUnionMs(
    visits: List<PlaceVisitView>,
    windowStartMs: Long,
    windowEndMs: Long,
): Map<Long, Long> = visits
    .groupBy { it.placeId }
    .mapValues { (_, placeVisits) -> unionDurationMs(placeVisits, windowStartMs, windowEndMs) }

/** 单一地点的裁决后停留时长（区间并集）。 */
fun placeUnionMs(
    visits: List<PlaceVisitView>,
    placeId: Long,
    windowStartMs: Long,
    windowEndMs: Long,
): Long = unionDurationMs(visits.filter { it.placeId == placeId }, windowStartMs, windowEndMs)

/** 全部停留的并集时长（不同地点重叠也只计一次）。 */
fun stayUnionMs(
    visits: List<PlaceVisitView>,
    windowStartMs: Long,
    windowEndMs: Long,
): Long = unionDurationMs(visits, windowStartMs, windowEndMs)

private fun unionDurationMs(
    visits: List<PlaceVisitView>,
    windowStartMs: Long,
    windowEndMs: Long,
): Long {
    val clipped = visits
        .mapNotNull { visit ->
            val start = maxOf(visit.startMs, windowStartMs)
            val end = minOf(visit.endMs, windowEndMs)
            if (end > start) start to end else null
        }
        .sortedBy { it.first }
    var total = 0L
    var cursorStart = -1L
    var cursorEnd = -1L
    for ((start, end) in clipped) {
        if (cursorStart < 0) {
            cursorStart = start
            cursorEnd = end
        } else if (start <= cursorEnd) {
            cursorEnd = maxOf(cursorEnd, end)
        } else {
            total += cursorEnd - cursorStart
            cursorStart = start
            cursorEnd = end
        }
    }
    if (cursorStart >= 0) total += cursorEnd - cursorStart
    return total
}
