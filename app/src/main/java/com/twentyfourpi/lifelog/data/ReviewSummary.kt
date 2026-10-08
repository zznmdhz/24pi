package com.twentyfourpi.lifelog.data

import java.time.LocalDate

/** A half-open millisecond interval: [startMs, endMs). */
data class MillisInterval(val startMs: Long, val endMs: Long)

/**
 * Calculates the duration covered by the union of [intervals] inside the
 * half-open [windowStartMs, windowEndMs) window.
 *
 * Overlapping or adjacent intervals are counted once. Intervals crossing a
 * calendar boundary are clipped to the requested window, so a session cannot
 * inflate either day's total.
 */
fun intervalUnionDurationMs(
    intervals: Iterable<MillisInterval>,
    windowStartMs: Long,
    windowEndMs: Long,
): Long {
    if (windowEndMs <= windowStartMs) return 0
    val clipped = intervals.mapNotNull { interval ->
        val start = maxOf(interval.startMs, windowStartMs)
        val end = minOf(interval.endMs, windowEndMs)
        if (end > start) MillisInterval(start, end) else null
    }.sortedWith(compareBy<MillisInterval> { it.startMs }.thenBy { it.endMs })
    if (clipped.isEmpty()) return 0

    var mergedStart = clipped.first().startMs
    var mergedEnd = clipped.first().endMs
    var total = 0L
    for (next in clipped.drop(1)) {
        if (next.startMs <= mergedEnd) {
            mergedEnd = maxOf(mergedEnd, next.endMs)
        } else {
            total += mergedEnd - mergedStart
            mergedStart = next.startMs
            mergedEnd = next.endMs
        }
    }
    return total + (mergedEnd - mergedStart)
}

/** Number of observed stays after overlapping or touching intervals are joined. Gaps stay separate. */
fun intervalUnionCount(
    intervals: Iterable<MillisInterval>,
    windowStartMs: Long,
    windowEndMs: Long,
): Int {
    if (windowEndMs <= windowStartMs) return 0
    val clipped = intervals.mapNotNull { interval ->
        val start = maxOf(interval.startMs, windowStartMs)
        val end = minOf(interval.endMs, windowEndMs)
        if (end > start) MillisInterval(start, end) else null
    }.sortedWith(compareBy<MillisInterval> { it.startMs }.thenBy { it.endMs })
    if (clipped.isEmpty()) return 0
    var count = 1
    var mergedEnd = clipped.first().endMs
    for (next in clipped.drop(1)) {
        if (next.startMs <= mergedEnd) mergedEnd = maxOf(mergedEnd, next.endMs)
        else { count++; mergedEnd = next.endMs }
    }
    return count
}

/** Metrics that can be compared without interpreting the user's behaviour. */
enum class ReviewMetric(val source: SourceId) {
    APP_USAGE(SourceId.USAGE),
    APP_SWITCHES(SourceId.USAGE),
    NOTIFICATIONS(SourceId.NOTIFICATIONS),
    PLACES(SourceId.LOCATION),
    STEPS(SourceId.STEPS),
}

enum class ReviewComparisonState {
    /** The selected day or range is still in progress and is not compared with a complete period. */
    CURRENT_PERIOD_IN_PROGRESS,

    /** One or more calendar days are absent. Missing days are never converted to zero. */
    INCOMPLETE_PERIOD,

    /** A recorded collection gap makes the current value or baseline partial. */
    COVERAGE_GAP,

    /** Fewer than the minimum number of complete comparison days are available. */
    INSUFFICIENT_COMPARABLE_DAYS,

    /** Both values cover comparable, complete periods. */
    AVAILABLE,
}

data class ReviewMetricComparison(
    val metric: ReviewMetric,
    /** Observed value for the selected day/range. It may be partial when [state] is not AVAILABLE. */
    val currentObservedValue: Long,
    val baselineValue: Double? = null,
    val deltaValue: Double? = null,
    val deltaRatio: Double? = null,
    val comparableDayCount: Int = 0,
    val state: ReviewComparisonState,
)

data class DailyReviewSummary(
    val date: LocalDate,
    val comparisons: Map<ReviewMetric, ReviewMetricComparison>,
) {
    operator fun get(metric: ReviewMetric): ReviewMetricComparison = comparisons.getValue(metric)
}

data class SevenDayReviewSummary(
    val startDate: LocalDate,
    val endDate: LocalDate,
    val previousStartDate: LocalDate,
    val previousEndDate: LocalDate,
    val currentPresentDayCount: Int,
    val previousPresentDayCount: Int,
    val comparisons: Map<ReviewMetric, ReviewMetricComparison>,
) {
    operator fun get(metric: ReviewMetric): ReviewMetricComparison = comparisons.getValue(metric)
}

/**
 * Compares a completed day with the average of up to [lookbackDays] preceding
 * complete days. Today is deliberately never compared with completed days.
 */
fun buildDailyReviewSummary(
    current: DailyArchiveSummary,
    availableDays: List<DailyArchiveSummary>,
    today: LocalDate,
    minimumComparableDays: Int = 3,
    lookbackDays: Int = 7,
): DailyReviewSummary {
    require(minimumComparableDays > 0)
    require(lookbackDays >= minimumComparableDays)
    val byDate = availableDays.associateBy { it.date }
    val candidateDates = (1..lookbackDays).map { current.date.minusDays(it.toLong()) }

    return DailyReviewSummary(
        date = current.date,
        comparisons = ReviewMetric.entries.associateWith { metric ->
            val currentValue = current.valueFor(metric)
            when {
                current.date >= today -> ReviewMetricComparison(
                    metric = metric,
                    currentObservedValue = currentValue,
                    state = ReviewComparisonState.CURRENT_PERIOD_IN_PROGRESS,
                )
                // archiveSummaries deliberately emits a row for every calendar day. An entirely
                // empty row is therefore only a placeholder, not proof that every metric was zero.
                !current.hasRecords -> ReviewMetricComparison(
                    metric = metric,
                    currentObservedValue = currentValue,
                    state = ReviewComparisonState.INCOMPLETE_PERIOD,
                )
                current.gapMs(metric.source) > 0 -> ReviewMetricComparison(
                    metric = metric,
                    currentObservedValue = currentValue,
                    state = ReviewComparisonState.COVERAGE_GAP,
                )
                else -> {
                    val comparable = candidateDates.mapNotNull(byDate::get)
                        .filter { it.hasRecords }
                        .filter { it.gapMs(metric.source) == 0L }
                    if (comparable.size < minimumComparableDays) {
                        ReviewMetricComparison(
                            metric = metric,
                            currentObservedValue = currentValue,
                            comparableDayCount = comparable.size,
                            state = ReviewComparisonState.INSUFFICIENT_COMPARABLE_DAYS,
                        )
                    } else {
                        comparison(
                            metric = metric,
                            currentValue = currentValue,
                            baselineValue = comparable.map { it.valueFor(metric) }.average(),
                            comparableDayCount = comparable.size,
                        )
                    }
                }
            }
        },
    )
}

/**
 * Builds a rolling seven-day review and compares it with the immediately
 * preceding seven days. A comparison is available only when all 14 calendar
 * days exist and the metric's source has no recorded gap in either period.
 */
fun buildSevenDayReviewSummary(
    availableDays: List<DailyArchiveSummary>,
    endDate: LocalDate,
    today: LocalDate,
): SevenDayReviewSummary {
    val startDate = endDate.minusDays(6)
    val previousEndDate = startDate.minusDays(1)
    val previousStartDate = previousEndDate.minusDays(6)
    val byDate = availableDays.associateBy { it.date }
    val currentDates = datesFrom(startDate, endDate)
    val previousDates = datesFrom(previousStartDate, previousEndDate)
    // Blank placeholders are absent coverage, not a legitimate all-zero day. A real zero for one
    // metric is still comparable when the day contains other evidence and its source has no gap.
    val currentDays = currentDates.mapNotNull(byDate::get).filter { it.hasRecords }
    val previousDays = previousDates.mapNotNull(byDate::get).filter { it.hasRecords }

    val comparisons = ReviewMetric.entries.associateWith { metric ->
        val currentObserved = currentDays.sumOf { it.valueFor(metric) }
        when {
            endDate >= today -> ReviewMetricComparison(
                metric = metric,
                currentObservedValue = currentObserved,
                state = ReviewComparisonState.CURRENT_PERIOD_IN_PROGRESS,
            )
            currentDays.size != 7 || previousDays.size != 7 -> ReviewMetricComparison(
                metric = metric,
                currentObservedValue = currentObserved,
                state = ReviewComparisonState.INCOMPLETE_PERIOD,
            )
            (currentDays + previousDays).any { it.gapMs(metric.source) > 0 } -> ReviewMetricComparison(
                metric = metric,
                currentObservedValue = currentObserved,
                comparableDayCount = 7,
                state = ReviewComparisonState.COVERAGE_GAP,
            )
            else -> comparison(
                metric = metric,
                currentValue = currentObserved,
                baselineValue = previousDays.sumOf { it.valueFor(metric) }.toDouble(),
                comparableDayCount = 7,
            )
        }
    }

    return SevenDayReviewSummary(
        startDate = startDate,
        endDate = endDate,
        previousStartDate = previousStartDate,
        previousEndDate = previousEndDate,
        currentPresentDayCount = currentDays.size,
        previousPresentDayCount = previousDays.size,
        comparisons = comparisons,
    )
}

private fun comparison(
    metric: ReviewMetric,
    currentValue: Long,
    baselineValue: Double,
    comparableDayCount: Int,
): ReviewMetricComparison {
    val delta = currentValue - baselineValue
    return ReviewMetricComparison(
        metric = metric,
        currentObservedValue = currentValue,
        baselineValue = baselineValue,
        deltaValue = delta,
        // A zero baseline has no meaningful percentage change. Keep the absolute delta only.
        deltaRatio = if (baselineValue == 0.0) null else delta / baselineValue,
        comparableDayCount = comparableDayCount,
        state = ReviewComparisonState.AVAILABLE,
    )
}

private fun DailyArchiveSummary.valueFor(metric: ReviewMetric): Long = when (metric) {
    ReviewMetric.APP_USAGE -> appUsageMs
    ReviewMetric.APP_SWITCHES -> switchCount.toLong()
    ReviewMetric.NOTIFICATIONS -> notificationCount.toLong()
    ReviewMetric.PLACES -> placeCount.toLong()
    ReviewMetric.STEPS -> stepCount
}

private fun datesFrom(startDate: LocalDate, endDate: LocalDate): List<LocalDate> =
    generateSequence(startDate) { date -> date.plusDays(1).takeIf { it <= endDate } }.toList()
