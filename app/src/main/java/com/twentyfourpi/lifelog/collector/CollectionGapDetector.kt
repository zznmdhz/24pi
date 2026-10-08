package com.twentyfourpi.lifelog.collector

import com.twentyfourpi.lifelog.data.CollectionGapEntity
import com.twentyfourpi.lifelog.data.DatabaseProvider
import com.twentyfourpi.lifelog.data.LocationPointEntity
import com.twentyfourpi.lifelog.data.PlaceVisitView
import com.twentyfourpi.lifelog.data.SettingsStore
import com.twentyfourpi.lifelog.data.SourceId
import com.twentyfourpi.lifelog.data.classifyGapImpact
import com.twentyfourpi.lifelog.debug.DiagnosticLog

internal class CollectionGapDetector(
    private val provider: DatabaseProvider,
    private val settings: SettingsStore,
) {
    fun captureProcessRestart(nowMs: Long = System.currentTimeMillis()): LocationGapWindow? {
        val previousHeartbeat = settings.getLong(HEARTBEAT_KEY)
        settings.putLong(HEARTBEAT_KEY, nowMs)
        return LocationGapWindow(previousHeartbeat, nowMs)
            .takeIf { previousHeartbeat > 0 && nowMs - previousHeartbeat >= PROCESS_GAP_MIN_MS }
    }

    suspend fun recordProcessRestart(
        gap: LocationGapWindow,
        sources: Set<SourceId> = setOf(SourceId.LOCATION),
    ) {
        sources.forEach { source -> recordGap(source, gap.startMs, gap.endMs, "PROCESS_RESTART") }
    }

    fun heartbeat(nowMs: Long = System.currentTimeMillis()) {
        settings.putLong(HEARTBEAT_KEY, nowMs)
    }

    fun clearHeartbeat() {
        settings.remove(HEARTBEAT_KEY)
    }

    suspend fun backfillRecentPointGaps(
        nowMs: Long = System.currentTimeMillis(),
        lookbackMs: Long = 30L * 24 * 60 * 60_000,
    ) {
        if (settings.getBoolean(BACKFILL_KEY)) return
        val since = nowMs - lookbackMs
        val dao = provider.get().dao()
        val points = dao.recentLocationPoints(since)
        val visits = dao.debugVisits(since)
        uncoveredLocationGaps(points, visits).forEach { gap ->
            recordGap(SourceId.LOCATION, gap.startMs, gap.endMs, "NO_LOCATION_SAMPLES")
        }
        settings.putBoolean(BACKFILL_KEY, true)
    }

    suspend fun recordGap(source: SourceId, startMs: Long, endMs: Long, reason: String) {
        if (endMs <= startMs) return
        val gap = CollectionGapEntity(
            source = source.name,
            startMs = startMs,
            endMs = endMs,
            reason = reason,
        )
        val inserted = provider.get().dao().insertCollectionGap(gap)
        DiagnosticLog.event(
            source.name.lowercase(),
            "collection_gap_recorded",
            mapOf(
                "source" to source.name,
                "startMs" to startMs,
                "endMs" to endMs,
                "durationMs" to endMs - startMs,
                "reason" to reason,
                "inserted" to (inserted != -1L),
                "provisionalUserImpact" to classifyGapImpact(gap).name,
                "policyNote" to "raw_gap_retained_ui_classified_separately",
            ),
        )
    }

    companion object {
        const val HEARTBEAT_KEY = "collector_heartbeat_at"
        private const val BACKFILL_KEY = "location_gap_backfill_v1"
        private const val PROCESS_GAP_MIN_MS = 5 * 60_000L
    }
}

internal data class LocationGapWindow(val startMs: Long, val endMs: Long)

internal fun uncoveredLocationGaps(
    points: List<LocationPointEntity>,
    visits: List<PlaceVisitView>,
    minimumGapMs: Long = 30 * 60_000L,
    minimumVisitCoverage: Double = .8,
): List<LocationGapWindow> = points.sortedBy { it.recordedMs }.zipWithNext().mapNotNull { (before, after) ->
    val gapMs = after.recordedMs - before.recordedMs
    if (gapMs < minimumGapMs) return@mapNotNull null
    val coveredMs = visits
        .mapNotNull { visit ->
            val start = maxOf(before.recordedMs, visit.startMs)
            val end = minOf(after.recordedMs, visit.endMs)
            (end - start).takeIf { it > 0 }
        }
        .sum()
        .coerceAtMost(gapMs)
    LocationGapWindow(before.recordedMs, after.recordedMs)
        .takeIf { coveredMs.toDouble() / gapMs < minimumVisitCoverage }
}
