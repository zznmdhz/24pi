package com.twentyfourpi.lifelog.data

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReviewSummaryTest {
    private val today: LocalDate = LocalDate.of(2026, 8, 13)

    @Test
    fun intervalUnion_countsOverlapsOnlyOnce() {
        val duration = intervalUnionDurationMs(
            intervals = listOf(
                MillisInterval(0, 100),
                MillisInterval(50, 150),
                MillisInterval(150, 180),
                MillisInterval(220, 260),
            ),
            windowStartMs = 0,
            windowEndMs = 300,
        )

        assertEquals(220L, duration)
    }

    @Test
    fun intervalUnionCount_joinsOverlapAndTouchButNotRealGap() {
        val intervals = listOf(
            MillisInterval(0, 100), MillisInterval(50, 150), MillisInterval(150, 180),
            MillisInterval(220, 260),
        )
        assertEquals(2, intervalUnionCount(intervals, 0, 300))
        assertEquals(1, intervalUnionCount(intervals, 40, 200))
        assertEquals(0, intervalUnionCount(intervals, 180, 220))
    }

    @Test
    fun intervalUnion_clipsSessionsCrossingMidnight() {
        val duration = intervalUnionDurationMs(
            intervals = listOf(
                MillisInterval(50, 120),
                MillisInterval(180, 250),
                MillisInterval(90, 110),
            ),
            windowStartMs = 100,
            windowEndMs = 200,
        )

        assertEquals(40L, duration)
    }

    @Test
    fun dailyReview_doesNotCompareInProgressTodayWithCompletedDays() {
        val current = day(today, usage = 15)
        val history = (1L..7L).map { offset -> day(today.minusDays(offset), usage = 10) }

        val review = buildDailyReviewSummary(current, history + current, today)

        val usage = review[ReviewMetric.APP_USAGE]
        assertEquals(ReviewComparisonState.CURRENT_PERIOD_IN_PROGRESS, usage.state)
        assertEquals(15L, usage.currentObservedValue)
        assertNull(usage.baselineValue)
    }

    @Test
    fun dailyReview_requiresAtLeastThreeComparableDays() {
        val selected = today.minusDays(1)
        val review = buildDailyReviewSummary(
            current = day(selected, usage = 20),
            availableDays = listOf(
                day(selected.minusDays(1), usage = 10),
                day(selected.minusDays(2), usage = 12),
            ),
            today = today,
        )

        val usage = review[ReviewMetric.APP_USAGE]
        assertEquals(ReviewComparisonState.INSUFFICIENT_COMPARABLE_DAYS, usage.state)
        assertEquals(2, usage.comparableDayCount)
        assertNull(usage.baselineValue)
    }

    @Test
    fun sevenDayReview_comparesCompleteWeekWithPreviousWeek() {
        val endDate = today.minusDays(1)
        val currentStart = endDate.minusDays(6)
        val previousStart = currentStart.minusDays(7)
        val summaries = buildList {
            repeat(7) { index -> add(day(previousStart.plusDays(index.toLong()), usage = 10, notifications = 2)) }
            repeat(7) { index -> add(day(currentStart.plusDays(index.toLong()), usage = 20, notifications = 4)) }
        }

        val review = buildSevenDayReviewSummary(summaries, endDate, today)

        val usage = review[ReviewMetric.APP_USAGE]
        assertEquals(ReviewComparisonState.AVAILABLE, usage.state)
        assertEquals(140L, usage.currentObservedValue)
        assertEquals(70.0, usage.baselineValue!!, 0.0)
        assertEquals(70.0, usage.deltaValue!!, 0.0)
        assertEquals(1.0, usage.deltaRatio!!, 0.0)
        assertEquals(7, usage.comparableDayCount)
    }

    @Test
    fun collectionGapDisablesOnlyAffectedMetricComparison() {
        val selected = today.minusDays(1)
        val history = (1L..3L).map { offset ->
            day(selected.minusDays(offset), usage = 10, notifications = 3)
        }
        val current = day(selected, usage = 20, notifications = 6, usageGap = 1)

        val review = buildDailyReviewSummary(current, history, today)

        assertEquals(ReviewComparisonState.COVERAGE_GAP, review[ReviewMetric.APP_USAGE].state)
        assertNull(review[ReviewMetric.APP_USAGE].baselineValue)
        assertEquals(ReviewComparisonState.COVERAGE_GAP, review[ReviewMetric.APP_SWITCHES].state)
        assertEquals(ReviewComparisonState.AVAILABLE, review[ReviewMetric.NOTIFICATIONS].state)
    }

    @Test
    fun weeklyGapDisablesComparisonInsteadOfTreatingPartialDayAsZero() {
        val endDate = today.minusDays(1)
        val start = endDate.minusDays(13)
        val summaries = (0L..13L).map { offset ->
            day(
                date = start.plusDays(offset),
                usage = 10,
                notifications = 2,
                notificationGap = if (offset == 10L) 60 else 0,
            )
        }

        val review = buildSevenDayReviewSummary(summaries, endDate, today)

        assertEquals(ReviewComparisonState.AVAILABLE, review[ReviewMetric.APP_USAGE].state)
        assertEquals(ReviewComparisonState.COVERAGE_GAP, review[ReviewMetric.NOTIFICATIONS].state)
        assertNull(review[ReviewMetric.NOTIFICATIONS].baselineValue)
    }

    @Test
    fun missingCalendarDayMakesWeeklyComparisonIncomplete() {
        val endDate = today.minusDays(1)
        val start = endDate.minusDays(13)
        val summaries = (0L..13L).filter { it != 4L }.map { day(start.plusDays(it), usage = 10) }

        val review = buildSevenDayReviewSummary(summaries, endDate, today)

        assertEquals(ReviewComparisonState.INCOMPLETE_PERIOD, review[ReviewMetric.APP_USAGE].state)
        assertNull(review[ReviewMetric.APP_USAGE].baselineValue)
        assertEquals(6, review.previousPresentDayCount)
    }

    @Test
    fun blankCalendarPlaceholderIsNotTreatedAsZero() {
        val endDate = today.minusDays(1)
        val start = endDate.minusDays(13)
        val summaries = (0L..13L).map { offset ->
            if (offset == 4L) blankDay(start.plusDays(offset))
            else day(start.plusDays(offset), usage = 10)
        }

        val review = buildSevenDayReviewSummary(summaries, endDate, today)

        assertEquals(ReviewComparisonState.INCOMPLETE_PERIOD, review[ReviewMetric.APP_USAGE].state)
        assertEquals(6, review.previousPresentDayCount)
    }

    private fun day(
        date: LocalDate,
        usage: Long,
        notifications: Int = 0,
        usageGap: Long = 0,
        notificationGap: Long = 0,
    ): DailyArchiveSummary = DailyArchiveSummary(
        date = date,
        appUsageMs = usage,
        switchCount = usage.toInt(),
        notificationCount = notifications,
        placeCount = 1,
        stepCount = 100,
        topApp = "测试应用",
        mainPlace = "测试地点",
        firstActivityMs = null,
        lastActivityMs = null,
        usageGapMs = usageGap,
        notificationGapMs = notificationGap,
    )

    private fun blankDay(date: LocalDate): DailyArchiveSummary = DailyArchiveSummary(
        date = date,
        appUsageMs = 0,
        switchCount = 0,
        notificationCount = 0,
        placeCount = 0,
        stepCount = 0,
        topApp = null,
        mainPlace = null,
        firstActivityMs = null,
        lastActivityMs = null,
    )
}
