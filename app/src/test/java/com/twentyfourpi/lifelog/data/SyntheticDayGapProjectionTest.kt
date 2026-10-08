package com.twentyfourpi.lifelog.data

import com.twentyfourpi.lifelog.ui.cardGapNotice
import com.twentyfourpi.lifelog.ui.dayGapNotice
import org.junit.Assert.*
import org.junit.Test

/** Invented whole-hour intervals and sequential IDs, independent of any device data. */
class SyntheticDayGapProjectionTest {
    private val hour = 3_600_000L
    private val end = 24 * hour
    private val visits = listOf(visit(1, 0, 2), visit(2, 3, 4), visit(3, 6, 7))
    private val gaps = listOf(CollectionGapEntity(
        id = 1, source = SourceId.LOCATION.name, startMs = hour, endMs = 7 * hour,
        reason = "LOCATION_TRUSTED_DATA_RECOVERED",
    ))
    private val points = listOf(2, 3, 4, 6).mapIndexed { index, h ->
        LocationPointEntity(
            id = index.toLong() + 1, recordedMs = h * hour, measuredMs = h * hour,
            latitude = 0.0, longitude = 0.0, accuracyM = 25f, provider = "gps",
        )
    }

    private fun visit(id: Long, start: Int, finish: Int) = PlaceVisitView(
        id = id, placeId = 1, startMs = start * hour, endMs = finish * hour,
        confidence = .9f, name = "Synthetic place A", address = "",
        latitude = 0.0, longitude = 0.0,
    )

    private fun project(mode: ProjectionMode = ProjectionMode.EFFECTIVE) = projectCollectionGaps(
        gaps = gaps, points = points, visits = visits, windowStartMs = 0,
        windowEndMs = end, asOfMs = end, generatedAtMs = end, mode = mode,
    )

    @Test fun `place duration uses the union of synthetic visits`() {
        assertEquals(4 * hour, adjudicatedPlaceUnionMs(visits, 0, end).getValue(1))
    }

    @Test fun `projection conserves duration while retaining two unknown intervals`() {
        val gap = project().gaps.single()
        assertEquals(6 * hour, gap.rawDurationMs)
        assertEquals(listOf(hour, 2 * hour), gap.unknownIntervals.map { it.durationMs })
        assertEquals(3 * hour, gap.measuredMs)
        assertEquals(gap.rawDurationMs, gap.measuredMs + gap.inferredMs + gap.unknownMs)
        assertTrue(gap.supersededByEvidence)
    }

    @Test fun `unknown intervals retain their bounding evidence`() {
        val gap = project().gaps.single()
        gap.unknownIntervals.forEach { assertTrue(it.evidence.isNotEmpty()) }
        assertTrue(gap.intervals.any { it.support == GapSupport.MEASURED && it.evidence.any { ref -> ref.kind == "visit" } })
    }

    @Test fun `raw mode restores the original interpretation without changing input`() {
        val before = visits.toList()
        val raw = project(ProjectionMode.RAW).gaps.single()
        assertEquals(1, raw.intervals.size)
        assertEquals(6 * hour, raw.unknownMs)
        assertEquals(before, visits)
        assertEquals(4 * hour, adjudicatedPlaceUnionMs(visits, 0, end).getValue(1))
    }

    @Test fun `measured cards stay neutral and fully unknown cards remain severe`() {
        val projection = project()
        assertNull(cardGapNotice(0, 2 * hour, projection))
        assertEquals(0L, projection.unknownOverlapMs(0, 2 * hour, SourceId.LOCATION.name))
        val unknown = projection.unknownIntervalsFor(SourceId.LOCATION.name)[1]
        assertTrue(requireNotNull(cardGapNotice(unknown.startMs, unknown.endMs, projection)).severe)
        assertFalse(requireNotNull(dayGapNotice(projection)).actionable)
    }
}
