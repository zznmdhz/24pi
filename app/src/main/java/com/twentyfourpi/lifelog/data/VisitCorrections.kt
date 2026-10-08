package com.twentyfourpi.lifelog.data

data class TripSummary(
    val date: java.time.LocalDate,
    val key: String,
    val startMs: Long,
    val endMs: Long,
    val distanceMeters: Double,
    val startPlace: String?,
    val endPlace: String?,
    val interruptionCount: Int,
)

fun DailyRoute.tripSummaries(date: java.time.LocalDate) = trips.map {
    TripSummary(date, it.key, it.first.timeMs, it.last.timeMs, it.distanceMeters,
        it.startPlaceName, it.endPlaceName, it.interruptions.size)
}
