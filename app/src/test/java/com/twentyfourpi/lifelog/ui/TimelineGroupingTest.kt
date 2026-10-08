package com.twentyfourpi.lifelog.ui

import com.twentyfourpi.lifelog.data.AppSessionEntity
import com.twentyfourpi.lifelog.data.PlaceVisitView
import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineGroupingTest {
    @Test fun nearbyAppsBecomeOnePhoneUseBlock() {
        val sessions = listOf(
            app("a", 0, 60_000),
            app("b", 90_000, 150_000),
            app("c", 5 * 60_000, 6 * 60_000),
        )
        val blocks = groupAppUseBlocks(sessions)
        assertEquals(2, blocks.size)
        assertEquals(2, blocks[0].appCount)
        assertEquals(120_000, blocks[0].usedMs)
        assertEquals(0, blocks[0].startMs)
        assertEquals(150_000, blocks[0].endMs)
    }

    @Test fun overlappingSnapshotsStayInOneBlock() {
        val blocks = groupAppUseBlocks(listOf(app("a", 1_000, 20_000), app("b", 15_000, 40_000)))
        assertEquals(1, blocks.size)
        assertEquals(40_000, blocks.single().endMs)
    }

    @Test fun duplicatePlaceVisitsBecomeOneContinuousVisit() {
        val visits = coalescePlaceVisits(listOf(
            visit(1, 10_000, 70_000),
            visit(1, 10_000, 130_000),
        ))

        assertEquals(1, visits.size)
        assertEquals(10_000, visits.single().startMs)
        assertEquals(130_000, visits.single().endMs)
    }

    @Test fun returningAfterAnotherPlaceRemainsASeparateVisit() {
        val visits = coalescePlaceVisits(listOf(
            visit(1, 0, 10 * 60_000),
            visit(2, 11 * 60_000, 30 * 60_000),
            visit(1, 31 * 60_000, 50 * 60_000),
        ))

        assertEquals(listOf(1L, 2L, 1L), visits.map { it.placeId })
    }

    @Test fun nearbyDistinctPlaceIdsRemainSeparateWithoutUserMerge() {
        val visits = coalescePlaceVisits(listOf(
            visit(10, 0, 60_000, latitude = 31.0),
            visit(11, 0, 120_000, latitude = 31.0),
        ))

        assertEquals(2, visits.size)
        assertEquals(listOf(10L, 11L), visits.map { it.placeId })
        assertEquals(0L, placeSignalGapMs(visits.first(), visits.drop(1)))
    }

    @Test fun shortCollectorGapAtSamePlaceRemainsOneVisit() {
        val raw = listOf(
            visit(1, 13 * 60_000, 25 * 60_000),
            visit(1, 28 * 60_000, 40 * 60_000),
        )
        val visits = coalescePlaceVisits(raw)

        assertEquals(1, visits.size)
        assertEquals(13 * 60_000, visits.single().startMs)
        assertEquals(40 * 60_000, visits.single().endMs)
        assertEquals(3 * 60_000, placeSignalGapMs(visits.single(), raw))
    }

    @Test fun longGapAtSamePlaceRemainsVisible() {
        val visits = coalescePlaceVisits(listOf(
            visit(1, 0, 10 * 60_000),
            visit(1, 21 * 60_000, 30 * 60_000),
        ))

        assertEquals(2, visits.size)
    }

    private fun app(pkg: String, start: Long, end: Long) = AppSessionEntity(
        packageName = pkg,
        appLabel = pkg.uppercase(),
        startMs = start,
        endMs = end,
    )

    private fun visit(placeId: Long, start: Long, end: Long, latitude: Double = 31.0 + placeId * .01) = PlaceVisitView(
        id = start + placeId,
        placeId = placeId,
        startMs = start,
        endMs = end,
        confidence = .9f,
        name = "地点$placeId",
        address = "",
        latitude = latitude,
        longitude = 121.0,
    )
}
