package com.twentyfourpi.lifelog.ui

import com.twentyfourpi.lifelog.data.AppSessionEntity
import com.twentyfourpi.lifelog.data.PlaceVisitView

data class AppUseBlock(
    val startMs: Long,
    val endMs: Long,
    val sessions: List<AppSessionEntity>,
) {
    val usedMs: Long get() = sessions.sumOf { (it.endMs - it.startMs).coerceAtLeast(0) }
    val appCount: Int get() = sessions.map { it.packageName }.distinct().size
}

/** 两段应用记录之间空白不超过 2 分钟时，视为同一次拿起手机后的连续使用。 */
fun groupAppUseBlocks(
    sessions: List<AppSessionEntity>,
    maxGapMs: Long = 2 * 60_000L,
): List<AppUseBlock> {
    val sorted = sessions.filter { it.endMs > it.startMs }.sortedBy { it.startMs }
    if (sorted.isEmpty()) return emptyList()
    val result = mutableListOf<AppUseBlock>()
    var current = mutableListOf(sorted.first())
    var blockStart = sorted.first().startMs
    var blockEnd = sorted.first().endMs
    sorted.drop(1).forEach { session ->
        if (session.startMs - blockEnd <= maxGapMs) {
            current += session
            blockEnd = maxOf(blockEnd, session.endMs)
        } else {
            result += AppUseBlock(blockStart, blockEnd, current.toList())
            current = mutableListOf(session)
            blockStart = session.startMs
            blockEnd = session.endMs
        }
    }
    result += AppUseBlock(blockStart, blockEnd, current.toList())
    return result
}

/**
 * 同一地点的重叠记录或短时采集断点视为一次连续到访。
 * 只合并时间线上相邻的同地点记录，因此“公司 → 午饭 → 公司”仍然保留三段。
 */
fun coalescePlaceVisits(
    visits: List<PlaceVisitView>,
    maxGapMs: Long = 10 * 60_000L,
): List<PlaceVisitView> {
    val sorted = visits.filter { it.endMs >= it.startMs }.sortedBy { it.startMs }
    if (sorted.isEmpty()) return emptyList()
    val result = mutableListOf<PlaceVisitView>()
    sorted.forEach { visit ->
        val previous = result.lastOrNull()
        val representsSamePlace = previous?.placeId == visit.placeId
        if (previous != null && representsSamePlace && visit.startMs <= previous.endMs + maxGapMs) {
            result[result.lastIndex] = previous.copy(
                startMs = minOf(previous.startMs, visit.startMs),
                endMs = maxOf(previous.endMs, visit.endMs),
                confidence = maxOf(previous.confidence, visit.confidence),
            )
        } else {
            result += visit
        }
    }
    return result
}

/** 返回一次连续到访内部原始采集段之间的信号空档，不把它误写成离开地点。 */
fun placeSignalGapMs(merged: PlaceVisitView, rawVisits: List<PlaceVisitView>): Long {
    val segments = rawVisits.filter { visit ->
        visit.startMs >= merged.startMs && visit.endMs <= merged.endMs && visit.placeId == merged.placeId
    }.sortedBy { it.startMs }
    if (segments.size < 2) return 0L
    var end = segments.first().endMs
    var gap = 0L
    segments.drop(1).forEach { segment ->
        gap += (segment.startMs - end).coerceAtLeast(0L)
        end = maxOf(end, segment.endMs)
    }
    return gap
}
