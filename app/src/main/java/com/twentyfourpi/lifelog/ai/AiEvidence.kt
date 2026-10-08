package com.twentyfourpi.lifelog.ai

import com.twentyfourpi.lifelog.data.PlaceEntity
import com.twentyfourpi.lifelog.data.MillisInterval
import com.twentyfourpi.lifelog.data.TimelineDay
import com.twentyfourpi.lifelog.data.intervalUnionCount
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min

data class ReviewRange(val start: LocalDate, val endExclusive: LocalDate, val label: String) {
    val days: Long get() = java.time.temporal.ChronoUnit.DAYS.between(start, endExclusive)
    init { require(endExclusive > start && days <= 90) }
}

data class EvidenceRow(val id: Long?, val name: String, val durationMs: Long = 0, val count: Int = 0)
data class ReviewEvidence(
    val range: ReviewRange,
    val asOfMs: Long,
    val zone: ZoneId,
    val recordedDays: Int,
    val placeRows: List<EvidenceRow>,
    val companyRows: List<EvidenceRow>,
    val companyTotalMs: Long,
    val appRows: List<EvidenceRow>,
    val notificationCount: Int,
    val gapMs: Map<String, Long>,
) {
    val hasRecords: Boolean get() = recordedDays > 0
    fun countRanking(question: String): Boolean = listOf("按到访次数", "按访问次数", "按次数", "频次", "频率").any { it in question }
    fun rankedPlaces(question: String): List<EvidenceRow> = if (countRanking(question))
        placeRows.sortedWith(compareByDescending<EvidenceRow> { it.count }.thenByDescending { it.durationMs }.thenBy { it.name }) else placeRows
    fun preview(question: String): String = buildString {
        fun shortName(name: String) = if (name.length > 60) name.take(59) + "…（名称已截短）" else name
        appendLine("问题：${question.take(240)}")
        appendLine("范围：${range.start} 至 ${range.endExclusive}（结束日不含），时区：$zone")
        appendLine("截止：${Instant.ofEpochMilli(asOfMs).atZone(zone)}；有记录天数：$recordedDays/${range.days}")
        appendLine("以下均为设备中已记录的事实；未记录不等于零活动。")
        val lower = question.lowercase()
        val known = listOf("地点", "去了", "哪里", "公司", "停留", "place", "应用", "app", "通知", "notification")
        val showAll = listOf("总结", "回顾", "summary").any { it in lower } || known.none { it in lower }
        if (showAll || listOf("地点", "去了", "哪里", "公司", "停留", "place").any { it in lower }) {
            val ranked = rankedPlaces(question)
            appendLine("地点（最多10项，${if (countRanking(question)) "按到访次数" else "按已记录停留时间"}排序）：")
            ranked.take(10).forEach { appendLine("${shortName(it.name)} | ${it.durationMs / 60_000}分钟 | ${it.count}次") }
            ranked.drop(10).firstOrNull { it.name in question }?.let { appendLine("指定地点补充：${shortName(it.name)} | ${it.durationMs / 60_000}分钟 | ${it.count}次") }
            if (placeRows.size > 10) appendLine("其余${placeRows.size - 10}项未发送；可在本机查看。")
            if (companyRows.isEmpty()) appendLine("没有标记为公司的地点，无法计算公司停留。")
            else {
                appendLine("公司标记地点总停留（并集）：${companyTotalMs / 60_000}分钟")
                companyRows.take(10).forEach { appendLine("${shortName(it.name)} | ${it.durationMs / 60_000}分钟 | ${it.count}次") }
            }
        }
        if (showAll || "应用" in lower || "app" in lower) {
            appendLine("应用（最多10项）：")
            appRows.take(10).forEach { appendLine("${shortName(it.name)} | ${it.durationMs / 60_000}分钟") }
            if (appRows.size > 10) appendLine("其余${appRows.size - 10}项未发送。")
        }
        if (showAll || "通知" in lower || "notification" in lower) appendLine("通知事件数：$notificationCount")
        appendLine("已记录缺口：${gapMs.entries.sortedBy { it.key }.joinToString { "${it.key} ${it.value / 60_000}分钟" }}")
    }
}

sealed interface RangeParse {
    data class Valid(val range: ReviewRange) : RangeParse
    data class NeedsSelection(val reason: String) : RangeParse
}

object ReviewRangeParser {
    fun parse(question: String, today: LocalDate = LocalDate.now()): RangeParse {
        val q = question.lowercase()
        val markers = listOf("昨天", "yesterday", "今天", "today", "上周", "last week", "本周", "this week", "上月", "last month", "本月", "这个月", "this month").count { it in q }
        if (markers > 1) return RangeParse.NeedsSelection("问题包含多个日期范围，请只选择一个")
        val relativeMatches = Regex("(?:最近|过去|last\\s*)(\\d{1,3}|三十|七)\\s*(?:天|days?)|(?:最近|过去)一周", RegexOption.IGNORE_CASE).findAll(q).toList()
        if (markers > 0 && relativeMatches.isNotEmpty() || relativeMatches.size > 1) return RangeParse.NeedsSelection("问题包含多个日期范围，请只选择一个")
        if (Regex("\\d{4}年|\\d{4}[-/.]\\d{1,2}|\\d{1,2}月|\\d{1,2}号|哪天|某天|那天|上个星期|前天").containsMatchIn(q)) return RangeParse.NeedsSelection("问题包含日期，请明确选择范围")
        val day = when {
            "昨天" in q || "yesterday" in q -> ReviewRange(today.minusDays(1), today, "昨天")
            "今天" in q || "today" in q -> ReviewRange(today, today.plusDays(1), "今天")
            "上周" in q || "last week" in q -> {
                val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
                ReviewRange(monday.minusWeeks(1), monday, "上周")
            }
            "本周" in q || "this week" in q -> {
                val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
                ReviewRange(monday, today.plusDays(1), "本周")
            }
            "上月" in q || "last month" in q -> {
                val first = today.withDayOfMonth(1)
                ReviewRange(first.minusMonths(1), first, "上月")
            }
            "本月" in q || "这个月" in q || "this month" in q -> ReviewRange(today.withDayOfMonth(1), today.plusDays(1), "本月")
            else -> null
        }
        if (day != null) return RangeParse.Valid(day)
        val value = relativeMatches.singleOrNull()?.groupValues?.get(1)
        val n = when (value) { "三十" -> 30L; "七" -> 7L; "" -> if (relativeMatches.isNotEmpty()) 7L else null; else -> value?.toLongOrNull() }
        if (n != null) return if (n in 1..90) RangeParse.Valid(ReviewRange(today.minusDays(n - 1), today.plusDays(1), "最近${n}天")) else RangeParse.NeedsSelection("最多查看90天，请选择较短范围")
        return RangeParse.NeedsSelection("请选择日期范围")
    }
}

class EvidenceAccumulator(private val range: ReviewRange, private val zone: ZoneId, private val asOfMs: Long, places: List<PlaceEntity>) {
    private val placesById = places.associateBy { it.id }
    private val intervals = mutableMapOf<Long, MutableList<Pair<Long, Long>>>()
    private val companyIntervals = mutableListOf<Pair<Long, Long>>()
    private val apps = mutableMapOf<String, MutableList<Pair<Long, Long>>>()
    private val appLabels = mutableMapOf<String, String>()
    private val gaps = mutableMapOf<String, MutableList<Pair<Long, Long>>>()
    private var notifications = 0
    private var recordedDays = 0

    fun add(date: LocalDate, day: TimelineDay) {
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = min(date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), asOfMs)
        if (end <= start) return
        var observed = false
        day.visits.forEach { v ->
            val clipped = clip(v.startMs, v.endMs, start, end) ?: return@forEach
            val id = canonical(v.placeId)
            intervals.getOrPut(id) { mutableListOf() }.add(clipped)
            if (placesById[id]?.kind == "COMPANY") companyIntervals.add(clipped)
            observed = true
        }
        day.apps.forEach { a ->
            val clipped = clip(a.startMs, a.endMs, start, end) ?: return@forEach
            apps.getOrPut(a.packageName) { mutableListOf() }.add(clipped)
            appLabels[a.packageName] = a.appLabel
            observed = true
        }
        day.notifications.forEach { n -> if (n.action == "POSTED" && n.occurredMs in start until end) { notifications++; observed = true } }
        day.gaps.forEach { g ->
            clip(g.startMs, g.endMs, start, end)?.let { gaps.getOrPut(g.source) { mutableListOf() }.add(it) }
        }
        if (observed) recordedDays++
    }

    fun finish(): ReviewEvidence {
        val rangeStartMs = range.start.atStartOfDay(zone).toInstant().toEpochMilli()
        val rangeEndMs = min(range.endExclusive.atStartOfDay(zone).toInstant().toEpochMilli(), asOfMs)
        val placeRows = intervals.map { (id, ranges) -> EvidenceRow(
            id, placesById[id]?.name ?: "地点 #$id", unionMs(ranges),
            intervalUnionCount(ranges.map { MillisInterval(it.first, it.second) }, rangeStartMs, rangeEndMs),
        ) }
            .sortedWith(compareByDescending<EvidenceRow> { it.durationMs }.thenBy { it.name })
        return ReviewEvidence(range, asOfMs, zone, recordedDays, placeRows,
            placeRows.filter { row -> row.id?.let { placesById[it]?.kind } == "COMPANY" },
            unionMs(companyIntervals),
            apps.map { (pkg, ranges) -> EvidenceRow(null, appLabels[pkg] ?: pkg, unionMs(ranges)) }.sortedByDescending { it.durationMs },
            notifications, gaps.mapValues { unionMs(it.value) })
    }

    private fun canonical(id: Long): Long {
        var current = id
        val seen = mutableSetOf<Long>()
        while (seen.add(current)) current = placesById[current]?.mergedIntoPlaceId ?: return current
        return id
    }
}

internal fun clip(a: Long, b: Long, start: Long, end: Long): Pair<Long, Long>? {
    val lo = max(a, start); val hi = min(b, end)
    return if (hi > lo) lo to hi else null
}

internal fun unionMs(intervals: List<Pair<Long, Long>>): Long {
    var total = 0L; var left = 0L; var right = 0L; var initialized = false
    intervals.sortedBy { it.first }.forEach { (a, b) ->
        if (!initialized) { left = a; right = b; initialized = true }
        else if (a <= right) right = max(right, b)
        else { total += right - left; left = a; right = b }
    }
    return if (initialized) total + right - left else 0L
}
