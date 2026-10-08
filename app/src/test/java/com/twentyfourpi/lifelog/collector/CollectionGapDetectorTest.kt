package com.twentyfourpi.lifelog.collector

import com.twentyfourpi.lifelog.data.LocationPointEntity
import com.twentyfourpi.lifelog.data.PlaceVisitView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionGapDetectorTest {
    @Test fun `reports a long raw point gap with no visit coverage`() {
        val result = uncoveredLocationGaps(
            points = listOf(point(1_000L), point(4_001_000L)),
            visits = emptyList(),
            minimumGapMs = 30 * 60_000L,
        )
        assertEquals(listOf(LocationGapWindow(1_000L, 4_001_000L)), result)
    }

    @Test fun `does not report a stationary gap covered by a confirmed visit`() {
        val result = uncoveredLocationGaps(
            points = listOf(point(1_000L), point(4_001_000L)),
            visits = listOf(visit(1_000L, 4_001_000L)),
            minimumGapMs = 30 * 60_000L,
        )
        assertTrue(result.isEmpty())
    }

    @Test fun `ignores ordinary short location intervals`() {
        val result = uncoveredLocationGaps(
            points = listOf(point(1_000L), point(901_000L)),
            visits = emptyList(),
            minimumGapMs = 30 * 60_000L,
        )
        assertTrue(result.isEmpty())
    }

    private fun point(at: Long) = LocationPointEntity(
        recordedMs = at,
        latitude = 31.0,
        longitude = 121.0,
        accuracyM = 30f,
        provider = "network",
    )

    private fun visit(start: Long, end: Long) = PlaceVisitView(
        id = 1,
        placeId = 1,
        startMs = start,
        endMs = end,
        confidence = .9f,
        name = "家",
        address = "",
        latitude = 31.0,
        longitude = 121.0,
    )
}
