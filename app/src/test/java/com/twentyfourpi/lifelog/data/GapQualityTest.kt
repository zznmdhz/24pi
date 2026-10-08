package com.twentyfourpi.lifelog.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GapQualityTest {
    @Test fun `sub two minute jitter stays diagnostic only`() {
        val result = assessCollectionGaps(listOf(gap(0, 45_000)), 0, DAY)

        assertEquals(1, result.hiddenGaps.size)
        assertTrue(result.visibleGaps.isEmpty())
    }

    @Test fun `brief gap is visible without becoming important`() {
        val result = assessCollectionGaps(listOf(gap(0, 5 * MIN)), 0, DAY)

        assertEquals(1, result.briefGaps.size)
        assertTrue(result.importantGaps.isEmpty())
    }

    @Test fun `ten minute gap is important`() {
        val result = assessCollectionGaps(listOf(gap(0, 10 * MIN)), 0, DAY)

        assertEquals(1, result.importantGaps.size)
    }

    @Test fun `nearby gaps from same source merge before classification`() {
        val result = assessCollectionGaps(
            listOf(gap(0, MIN), gap(MIN + 20_000, 2 * MIN)),
            0,
            DAY,
        )

        assertEquals(1, result.mergedGaps.size)
        assertEquals(2 * MIN, result.mergedGaps.single().endMs)
        assertEquals(1, result.briefGaps.size)
    }

    @Test fun `many small gaps become important when daily total matters`() {
        val values = (0 until 16).map { index ->
            val start = index * 5 * MIN
            gap(start, start + MIN)
        }
        val result = assessCollectionGaps(values, 0, DAY)

        assertEquals(16, result.importantGaps.size)
        assertTrue(result.hiddenGaps.isEmpty())
    }

    @Test fun `permission failure is important even when short`() {
        val result = assessCollectionGaps(
            listOf(gap(0, 10_000, reason = "LOCATION_PERMISSION_REVOKED")),
            0,
            DAY,
        )

        assertEquals(1, result.importantGaps.size)
    }

    private fun gap(start: Long, end: Long, source: String = SourceId.LOCATION.name, reason: String = "CALLBACK_RECOVERED") =
        CollectionGapEntity(id = start + 1, source = source, startMs = start, endMs = end, reason = reason)

    companion object {
        private const val MIN = 60_000L
        private const val DAY = 24 * 60 * MIN
    }
}
