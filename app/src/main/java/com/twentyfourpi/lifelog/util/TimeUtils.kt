package com.twentyfourpi.lifelog.util

import com.twentyfourpi.lifelog.data.StepSampleEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max

fun formatClock(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("HH:mm"))

fun formatDuration(ms: Long): String {
    val safeMs = ms.coerceAtLeast(0)
    val seconds = (safeMs / 1_000).toInt()
    if (safeMs == 0L) return "0秒"
    if (seconds == 0) return "不足1秒"
    if (seconds < 60) return "${seconds}秒"
    val minutes = seconds / 60
    return if (minutes >= 60) "${minutes / 60}小时${minutes % 60}分" else "${minutes}分钟"
}

fun dayBounds(date: LocalDate): Pair<Long, Long> {
    val zone = ZoneId.systemDefault()
    return date.atStartOfDay(zone).toInstant().toEpochMilli() to
        date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
}

fun calculateSteps(samples: List<StepSampleEntity>): Long {
    if (samples.size < 2) return 0
    var result = 0L
    samples.zipWithNext().forEach { (a, b) ->
        if (a.bootCount == b.bootCount) result += max(0L, b.cumulative - a.cumulative)
    }
    return result
}
