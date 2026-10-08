package com.twentyfourpi.lifelog.data

/**
 * 用户界面的历史完整度分级。
 *
 * 原始 collection_gaps 永远保留给诊断和导出；这里仅决定哪些缺口值得打断用户。
 * 短暂系统抖动不会再把整天标成失败，权限关闭、数据库错误等明确故障仍然始终可见。
 */
enum class GapImpact {
    HIDDEN,
    BRIEF,
    IMPORTANT,
}

data class GapAssessment(
    val mergedGaps: List<CollectionGapEntity>,
    val hiddenGaps: List<CollectionGapEntity>,
    val briefGaps: List<CollectionGapEntity>,
    val importantGaps: List<CollectionGapEntity>,
) {
    val visibleGaps: List<CollectionGapEntity>
        get() = (briefGaps + importantGaps).sortedBy { it.startMs }

    val hiddenDurationMs: Long
        get() = hiddenGaps.sumOf { it.durationMs }

    val briefDurationMs: Long
        get() = briefGaps.sumOf { it.durationMs }

    val importantDurationMs: Long
        get() = importantGaps.sumOf { it.durationMs }
}

val CollectionGapEntity.durationMs: Long
    get() = (endMs - startMs).coerceAtLeast(0L)

fun CollectionGapEntity.userFacingGapReason(): String {
    val value = reason.uppercase()
    return when {
        "PERMISSION" in value -> "当时缺少必要权限，记录来源无法继续工作。"
        "DISABLED" in value -> "当时对应的系统服务或记录项目处于关闭状态。"
        "PROCESS_RESTART" in value -> "采集进程停止后重新启动，这段时间没有留下连续记录。"
        "NO_LOCATION_SAMPLES" in value -> "这段时间没有足够的位置点或已确认停留。"
        "LOW_ACCURACY" in value -> "定位仍有响应，但精度不足，无法确认可信地点。"
        "LOCATION" in value && "RECOVER" in value -> "可信位置数据恢复前存在一段空白。"
        "NOTIFICATION" in value && "RECOVER" in value -> "通知监听恢复前存在一段空白。"
        "DATABASE" in value || "WRITE_FAILED" in value -> "本机记录写入失败，可能影响这段数据。"
        reason.isBlank() -> "这段时间没有记录到数据。"
        else -> "记录来源曾短暂中断，之后已经恢复。"
    }
}

fun assessCollectionGaps(
    gaps: List<CollectionGapEntity>,
    windowStartMs: Long,
    windowEndMs: Long,
    mergeDistanceMs: Long = GAP_MERGE_DISTANCE_MS,
): GapAssessment {
    if (windowEndMs <= windowStartMs) return GapAssessment(emptyList(), emptyList(), emptyList(), emptyList())
    val merged = gaps
        .mapNotNull { gap ->
            val start = maxOf(gap.startMs, windowStartMs)
            val end = minOf(gap.endMs, windowEndMs)
            gap.copy(startMs = start, endMs = end).takeIf { end > start }
        }
        .groupBy { it.source }
        .values
        .flatMap { sameSource -> mergeSameSourceGaps(sameSource, mergeDistanceMs) }
        .sortedBy { it.startMs }

    val cumulativeBySource = merged.groupBy { it.source }.mapValues { (_, values) ->
        values.sumOf(CollectionGapEntity::durationMs)
    }
    val classified = merged.groupBy { gap ->
        classifyGapImpact(gap, cumulativeBySource.getValue(gap.source))
    }
    return GapAssessment(
        mergedGaps = merged,
        hiddenGaps = classified[GapImpact.HIDDEN].orEmpty(),
        briefGaps = classified[GapImpact.BRIEF].orEmpty(),
        importantGaps = classified[GapImpact.IMPORTANT].orEmpty(),
    )
}

fun classifyGapImpact(gap: CollectionGapEntity, cumulativeSourceGapMs: Long = gap.durationMs): GapImpact = when {
    gap.reason.isAlwaysVisibleGapReason() -> GapImpact.IMPORTANT
    gap.durationMs >= IMPORTANT_SINGLE_GAP_MS -> GapImpact.IMPORTANT
    cumulativeSourceGapMs >= IMPORTANT_DAILY_TOTAL_MS -> GapImpact.IMPORTANT
    gap.durationMs >= BRIEF_GAP_MIN_MS -> GapImpact.BRIEF
    else -> GapImpact.HIDDEN
}

private fun mergeSameSourceGaps(
    values: List<CollectionGapEntity>,
    mergeDistanceMs: Long,
): List<CollectionGapEntity> {
    val sorted = values.sortedWith(compareBy<CollectionGapEntity> { it.startMs }.thenBy { it.endMs })
    if (sorted.isEmpty()) return emptyList()
    val result = mutableListOf<CollectionGapEntity>()
    var current = sorted.first()
    for (next in sorted.drop(1)) {
        if (next.startMs <= current.endMs + mergeDistanceMs) {
            current = current.copy(
                endMs = maxOf(current.endMs, next.endMs),
                reason = mergeReasons(current.reason, next.reason),
            )
        } else {
            result += current
            current = next
        }
    }
    result += current
    return result
}

private fun mergeReasons(first: String, second: String): String =
    (first.split('|') + second.split('|')).filter(String::isNotBlank).distinct().joinToString("|")

internal fun String.isAlwaysVisibleGapReason(): Boolean {
    val normalized = uppercase()
    return ALWAYS_VISIBLE_REASON_MARKERS.any(normalized::contains)
}

const val BRIEF_GAP_MIN_MS = 2 * 60_000L
const val IMPORTANT_SINGLE_GAP_MS = 10 * 60_000L
const val IMPORTANT_DAILY_TOTAL_MS = 15 * 60_000L
const val GAP_MERGE_DISTANCE_MS = 30_000L

private val ALWAYS_VISIBLE_REASON_MARKERS = listOf(
    "PERMISSION",
    "DISABLED",
    "SYSTEM_BLOCKED",
    "DATABASE",
    "MIGRATION",
    "RESTORE",
    "CRASH",
    "WRITE_FAILED",
)
