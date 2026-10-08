package com.twentyfourpi.lifelog.collector

import com.twentyfourpi.lifelog.util.distanceMeters

data class DwellCandidate(
    val startMs: Long,
    val lastMs: Long,
    val latitude: Double,
    val longitude: Double,
    val samples: Int,
    val visitId: Long = 0,
    val placeId: Long = 0,
)

sealed interface DwellAction {
    data class Continue(val candidate: DwellCandidate, val becameVisit: Boolean) : DwellAction
    data class Reset(val previous: DwellCandidate?, val next: DwellCandidate) : DwellAction
}

object DwellTracker {
    const val RADIUS_METERS = 200.0
    const val KNOWN_PLACE_DWELL_MS = 60_000L
    const val NEW_PLACE_DWELL_MS = 5 * 60_000L
    const val PROVISIONAL_MS = 3 * 60_000L
    const val MAX_SAMPLE_GAP_MS = 10 * 60_000L

    fun update(
        current: DwellCandidate?,
        timestamp: Long,
        latitude: Double,
        longitude: Double,
        requiredDwellMs: Long = NEW_PLACE_DWELL_MS,
        radiusMeters: Double = RADIUS_METERS,
        maxSampleGapMs: Long = MAX_SAMPLE_GAP_MS,
    ): DwellAction = updateInternal(
        current, timestamp, latitude, longitude, requiredDwellMs, radiusMeters, maxSampleGapMs,
        heartbeatOnly = false,
    )

    /**
     * v0.15 心跳模式（A3 lastknown 时效门槛的落地机制）：
     * probe:lastknown / 缓存合成的心跳样本只允许"延续"已有候选的时间窗，
     * 不更新候选坐标、不触发新停留确认。这样十几分钟前的旧缓存位置
     * 永远不可能把停留归属拉到别处（根因 3：probe:lastknown 串位）。
     */
    fun heartbeat(
        current: DwellCandidate?,
        timestamp: Long,
        latitude: Double,
        longitude: Double,
        maxSampleGapMs: Long = MAX_SAMPLE_GAP_MS,
    ): DwellAction = updateInternal(
        current, timestamp, latitude, longitude,
        requiredDwellMs = Long.MAX_VALUE, // 心跳永不触发 becameVisit
        radiusMeters = RADIUS_METERS,
        maxSampleGapMs = maxSampleGapMs,
        heartbeatOnly = true,
    )

    private fun updateInternal(
        current: DwellCandidate?,
        timestamp: Long,
        latitude: Double,
        longitude: Double,
        requiredDwellMs: Long,
        radiusMeters: Double,
        maxSampleGapMs: Long,
        heartbeatOnly: Boolean,
    ): DwellAction {
        if (heartbeatOnly) {
            // 心跳只有落在现有候选半径内才有意义：维持候选活跃。
            // 半径外/无候选时直接返回 Reset(自身) 让调用方丢弃，不产生新候选。
            if (current == null || current.visitId == 0L || timestamp - current.lastMs > maxSampleGapMs ||
                distanceMeters(current.latitude, current.longitude, latitude, longitude) > radiusMeters
            ) {
                return DwellAction.Reset(null, current ?: DwellCandidate(timestamp, timestamp, latitude, longitude, 0))
            }
            return DwellAction.Continue(current.copy(lastMs = timestamp), becameVisit = false)
        }
        val continuityLost = current != null && timestamp - current.lastMs > maxSampleGapMs
        if (current == null || continuityLost || distanceMeters(current.latitude, current.longitude, latitude, longitude) > radiusMeters) {
            return DwellAction.Reset(current, DwellCandidate(timestamp, timestamp, latitude, longitude, 1))
        }
        val n = current.samples + 1
        val updated = current.copy(
            lastMs = timestamp,
            latitude = (current.latitude * current.samples + latitude) / n,
            longitude = (current.longitude * current.samples + longitude) / n,
            samples = n,
        )
        return DwellAction.Continue(updated, current.visitId == 0L && timestamp - current.startMs >= requiredDwellMs)
    }
}
