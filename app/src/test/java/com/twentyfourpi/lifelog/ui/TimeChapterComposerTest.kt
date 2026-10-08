package com.twentyfourpi.lifelog.ui

import com.twentyfourpi.lifelog.data.AppSessionEntity
import com.twentyfourpi.lifelog.data.CollectionGapEntity
import com.twentyfourpi.lifelog.data.NotificationEventEntity
import com.twentyfourpi.lifelog.data.PlaceVisitView
import com.twentyfourpi.lifelog.data.SourceId
import com.twentyfourpi.lifelog.data.TimelineDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeChapterComposerTest {
    @Test fun `place is the primary boundary and app or notification events do not split it`() {
        val place = visit(id = 1, placeId = 7, start = min(10), end = min(60))
        val shortApp = app(id = 1, pkg = "chat", start = min(12), end = min(20))
        val longApp = app(id = 2, pkg = "video", start = min(30), end = min(45))
        val firstNotice = notification(id = 1, at = min(15))
        val secondNotice = notification(id = 2, at = min(40))

        val chapters = compose(
            TimelineDay(
                apps = listOf(shortApp, longApp),
                notifications = listOf(firstNotice, secondNotice),
                visits = listOf(place),
            ),
            end = min(120),
        )

        assertEquals(1, chapters.size)
        val chapter = chapters.single()
        assertSame(place, chapter.place)
        assertEquals(min(10), chapter.startMs)
        assertEquals(min(60), chapter.endMs)
        assertEquals(listOf(shortApp, longApp), chapter.apps)
        assertEquals(listOf(firstNotice, secondNotice), chapter.notifications)
        assertEquals(2, chapter.notificationCount)
        assertEquals(listOf("video", "chat"), chapter.topApps.map { it.packageName })
    }

    @Test fun `same place within ten minutes is merged while the internal observation gap remains`() {
        val first = visit(id = 1, placeId = 3, start = min(0), end = min(10))
        val second = visit(id = 2, placeId = 3, start = min(18), end = min(30))
        val appInGap = app(id = 1, pkg = "reader", start = min(12), end = min(14))
        val noticeInGap = notification(id = 1, at = min(15))

        val chapter = compose(
            TimelineDay(
                apps = listOf(appInGap),
                notifications = listOf(noticeInGap),
                visits = listOf(first, second),
            ),
            end = min(60),
        ).single()

        assertEquals(min(0), chapter.startMs)
        assertEquals(min(30), chapter.endMs)
        assertEquals(listOf(first, second), chapter.placeVisits)
        assertEquals(listOf(TimeChapterRange(min(10), min(18))), chapter.placeContinuityGaps)
        assertEquals(listOf(appInGap), chapter.apps)
        assertEquals(listOf(noticeInGap), chapter.notifications)
        assertTrue(chapter.hasLocationGap)
        assertTrue(chapter.hasAnyGap)
    }

    @Test fun `overlapping different places are projected as disjoint chronological chapters`() {
        val longVisit = visit(
            id = 1,
            placeId = 1,
            start = min(0),
            end = min(30),
            confidence = .8f,
        )
        val strongerVisit = visit(
            id = 2,
            placeId = 2,
            start = min(10),
            end = min(20),
            confidence = .95f,
        )
        val spanningApp = app(id = 1, pkg = "work", start = min(5), end = min(25))
        val notice = notification(id = 1, at = min(15))

        val chapters = compose(
            TimelineDay(
                apps = listOf(spanningApp),
                notifications = listOf(notice),
                visits = listOf(longVisit, strongerVisit),
            ),
            end = min(40),
        )

        assertEquals(3, chapters.size)
        assertEquals(listOf(min(0), min(10), min(20)), chapters.map { it.startMs })
        assertEquals(listOf(min(10), min(20), min(30)), chapters.map { it.endMs })
        assertEquals(listOf(1L, 2L, 1L), chapters.map { it.place?.placeId })
        chapters.zipWithNext().forEach { (left, right) -> assertTrue(left.endMs <= right.startMs) }
        assertEquals(1, chapters.sumOf { it.notificationCount })
        assertEquals(listOf(notice), chapters[1].notifications)
        assertEquals(chapters.size, chapters.map { it.key }.distinct().size)
    }

    @Test fun `adjacent conflict slices of the same canonical place merge without duplicate visits`() {
        val longA = visit(id = 1, placeId = 1, start = 0, end = 100)
        val b = visit(id = 2, placeId = 2, start = 20, end = 30)
        val shortA = visit(id = 3, placeId = 1, start = 40, end = 50)

        val chapters = compose(
            TimelineDay(visits = listOf(longA, b, shortA)),
            end = 120,
        )

        assertEquals(3, chapters.size)
        assertEquals(listOf(1L, 2L, 1L), chapters.map { it.place?.placeId })
        assertEquals(listOf(0L, 20L, 30L), chapters.map { it.startMs })
        assertEquals(listOf(20L, 30L, 100L), chapters.map { it.endMs })
        assertEquals(listOf(longA, shortA), chapters.last().placeVisits)
        assertEquals(2, chapters.last().placeVisits.map { it.id }.distinct().size)
        assertTrue(chapters.last().placeContinuityGaps.isEmpty())
        chapters.zipWithNext().forEach { (left, right) -> assertTrue(left.endMs <= right.startMs) }
    }

    @Test fun `same place more than ten minutes apart remains two chapters`() {
        val chapters = compose(
            TimelineDay(
                visits = listOf(
                    visit(id = 1, placeId = 1, start = min(0), end = min(10)),
                    visit(id = 2, placeId = 1, start = min(21), end = min(30)),
                ),
            ),
            end = min(60),
        )

        assertEquals(2, chapters.size)
        assertNotEquals(chapters[0].key, chapters[1].key)
        assertTrue(chapters.all { it.placeContinuityGaps.isEmpty() })
    }

    @Test fun `unplaced application activity clusters at a fifteen minute gap`() {
        val first = app(id = 1, pkg = "a", start = min(0), end = min(5))
        val second = app(id = 2, pkg = "b", start = min(20), end = min(25))
        val third = app(id = 3, pkg = "c", start = min(41), end = min(42))

        val chapters = compose(TimelineDay(apps = listOf(first, second, third)), end = min(60))

        assertEquals(2, chapters.size)
        assertNull(chapters[0].place)
        assertEquals(min(0), chapters[0].startMs)
        assertEquals(min(25), chapters[0].endMs)
        assertEquals(listOf(first, second), chapters[0].apps)
        assertEquals(min(41), chapters[1].startMs)
        assertEquals(min(42), chapters[1].endMs)
        assertEquals(listOf(third), chapters[1].apps)
    }

    @Test fun `dense notification-only activity is clustered instead of creating one chapter per event`() {
        val first = notification(id = 1, at = min(0))
        val second = notification(id = 2, at = min(14))
        val third = notification(id = 3, at = min(30))

        val chapters = compose(
            TimelineDay(notifications = listOf(first, second, third)),
            end = min(60),
        )

        assertEquals(2, chapters.size)
        assertEquals(listOf(first, second), chapters[0].notifications)
        assertEquals(2, chapters[0].notificationCount)
        assertEquals(min(15), chapters[0].endMs)
        assertEquals(listOf(third), chapters[1].notifications)
        assertEquals(1, chapters[1].notificationCount)
        assertEquals(min(31), chapters[1].endMs)
        assertTrue(chapters.all { it.place == null })
    }

    @Test fun `a lone notification gets a stable display window without changing its timestamp`() {
        val notice = notification(id = 1, at = min(12))

        val chapter = compose(TimelineDay(notifications = listOf(notice)), end = min(60)).single()

        assertEquals(min(12), chapter.startMs)
        assertEquals(min(13), chapter.endMs)
        assertEquals(TimeChapterComposer.NOTIFICATION_DISPLAY_WINDOW_MS, chapter.endMs - chapter.startMs)
        assertSame(notice, chapter.notifications.single())
        assertEquals(min(12), notice.occurredMs)
    }

    @Test fun `adjacent app and gap seeds cannot cluster across a confirmed place`() {
        val app = app(id = 1, pkg = "before", start = min(0), end = min(10))
        val place = visit(id = 1, placeId = 5, start = min(10), end = min(20))
        val spanningGap = gap(id = 1, source = SourceId.USAGE, start = min(5), end = min(25))

        val chapters = compose(
            TimelineDay(apps = listOf(app), visits = listOf(place), gaps = listOf(spanningGap)),
            end = min(40),
        )

        assertEquals(3, chapters.size)
        assertEquals(listOf(min(0), min(10), min(20)), chapters.map { it.startMs })
        assertEquals(listOf(min(10), min(20), min(25)), chapters.map { it.endMs })
        assertEquals(listOf(null, 5L, null), chapters.map { it.place?.placeId })
        chapters.zipWithNext().forEach { (left, right) -> assertTrue(left.endMs <= right.startMs) }
        assertEquals(listOf(spanningGap), chapters[0].gaps)
        assertEquals(listOf(spanningGap), chapters[1].gaps)
        assertEquals(listOf(spanningGap), chapters[2].gaps)
    }

    @Test fun `a place chapter blocks unknown activity clusters and raw sessions stay unchanged`() {
        val spanningApp = app(id = 1, pkg = "maps", start = min(0), end = min(30))
        val place = visit(id = 1, placeId = 4, start = min(10), end = min(20))

        val chapters = compose(
            TimelineDay(apps = listOf(spanningApp), visits = listOf(place)),
            end = min(40),
        )

        assertEquals(3, chapters.size)
        assertEquals(listOf(min(0), min(10), min(20)), chapters.map { it.startMs })
        assertEquals(listOf(min(10), min(20), min(30)), chapters.map { it.endMs })
        assertEquals(listOf(null, place, null), chapters.map { it.place })
        chapters.forEach { chapter ->
            assertSame(spanningApp, chapter.apps.single())
            assertEquals(min(10), chapter.topApps.single().durationMs)
        }
        assertEquals(min(0), spanningApp.startMs)
        assertEquals(min(30), spanningApp.endMs)
    }

    @Test fun `cross-midnight facts are clipped only at chapter level and source gaps are exposed`() {
        val place = visit(id = 8, placeId = 2, start = 50, end = 250)
        val spanningApp = app(id = 9, pkg = "work", start = 80, end = 220)
        val firstNotice = notification(id = 10, at = 100)
        val lastNotice = notification(id = 11, at = 199)
        val locationGap = gap(id = 1, source = SourceId.LOCATION, start = 90, end = 110)
        val notificationGap = gap(id = 2, source = SourceId.NOTIFICATIONS, start = 190, end = 210)
        val usageGap = gap(id = 3, source = SourceId.USAGE, start = 120, end = 130)
        val day = TimelineDay(
            apps = listOf(spanningApp),
            notifications = listOf(firstNotice, lastNotice),
            visits = listOf(place),
            gaps = listOf(locationGap, notificationGap, usageGap),
        )

        val firstResult = TimeChapterComposer.compose(day, dayStartMs = 100, dayEndMs = 200)
        val secondResult = TimeChapterComposer.compose(day, dayStartMs = 100, dayEndMs = 200)
        val chapter = firstResult.single()

        assertEquals(100, chapter.startMs)
        assertEquals(200, chapter.endMs)
        assertSame(place, chapter.place)
        assertSame(spanningApp, chapter.apps.single())
        assertEquals(100, chapter.topApps.single().durationMs)
        assertEquals(2, chapter.notificationCount)
        assertEquals(listOf(locationGap, usageGap, notificationGap), chapter.gaps)
        assertTrue(chapter.hasLocationGap)
        assertTrue(chapter.hasNotificationGap)
        assertTrue(chapter.hasUsageGap)
        assertFalse(chapter.hasStepGap)
        assertEquals(firstResult.map { it.key }, secondResult.map { it.key })

        assertEquals(50, place.startMs)
        assertEquals(250, place.endMs)
        assertEquals(80, spanningApp.startMs)
        assertEquals(220, spanningApp.endMs)
    }

    @Test fun `a source gap outside known places remains a visible unknown chapter`() {
        val usageGap = gap(id = 1, source = SourceId.USAGE, start = min(10), end = min(20))

        val chapter = compose(TimelineDay(gaps = listOf(usageGap)), end = min(60)).single()

        assertNull(chapter.place)
        assertEquals(min(10), chapter.startMs)
        assertEquals(min(20), chapter.endMs)
        assertEquals(listOf(usageGap), chapter.gaps)
        assertTrue(chapter.hasUsageGap)
        assertTrue(chapter.hasAnyGap)
    }

    @Test fun `day presentation puts newest chapter first without changing chapter contents`() {
        val chronological = compose(
            TimelineDay(
                apps = listOf(
                    app(1, "morning", min(8 * 60), min(9 * 60)),
                    app(2, "noon", min(12 * 60), min(13 * 60)),
                ),
            ),
            end = min(24 * 60),
        )

        val displayed = newestFirstTimeChapters(chronological)

        assertEquals(listOf(min(12 * 60), min(8 * 60)), displayed.map { it.startMs })
        assertEquals(chronological.map { it.key }.toSet(), displayed.map { it.key }.toSet())
    }

    private fun compose(day: TimelineDay, start: Long = 0, end: Long): List<TimeChapter> =
        TimeChapterComposer.compose(day, dayStartMs = start, dayEndMs = end)

    private fun min(value: Long): Long = value * 60_000L

    private fun app(id: Long, pkg: String, start: Long, end: Long) = AppSessionEntity(
        id = id,
        packageName = pkg,
        appLabel = pkg.uppercase(),
        startMs = start,
        endMs = end,
    )

    private fun notification(id: Long, at: Long) = NotificationEventEntity(
        id = id,
        packageName = "notice.$id",
        appLabel = "通知$id",
        occurredMs = at,
        action = "POSTED",
    )

    private fun visit(
        id: Long,
        placeId: Long,
        start: Long,
        end: Long,
        confidence: Float = .9f,
    ) = PlaceVisitView(
        id = id,
        placeId = placeId,
        startMs = start,
        endMs = end,
        confidence = confidence,
        name = "地点$placeId",
        address = "",
        latitude = 31.0 + placeId * .01,
        longitude = 121.0,
    )

    private fun gap(id: Long, source: SourceId, start: Long, end: Long) = CollectionGapEntity(
        id = id,
        source = source.name,
        startMs = start,
        endMs = end,
        reason = "test",
    )
}
