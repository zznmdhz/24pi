package com.twentyfourpi.lifelog.util

import com.twentyfourpi.lifelog.collector.DwellAction
import com.twentyfourpi.lifelog.collector.DwellTracker
import org.junit.Assert.*
import org.junit.Test

class GeoAndDwellTest {
    @Test fun `new place becomes a visit after five minutes`() {
        val start = 1_000L
        val first = DwellTracker.update(null, start, 31.2304, 121.4737) as DwellAction.Reset
        val halfway = DwellTracker.update(first.next, start + 3 * 60_000, 31.2305, 121.4738) as DwellAction.Continue
        assertFalse(halfway.becameVisit)
        val complete = DwellTracker.update(halfway.candidate, start + 5 * 60_000, 31.2303, 121.4736) as DwellAction.Continue
        assertTrue(complete.becameVisit)
    }

    @Test fun `known place can use one minute threshold`() {
        val first = (DwellTracker.update(null, 0, 31.2304, 121.4737, DwellTracker.KNOWN_PLACE_DWELL_MS) as DwellAction.Reset).next
        val complete = DwellTracker.update(first, 60_000, 31.2304, 121.4737, DwellTracker.KNOWN_PLACE_DWELL_MS) as DwellAction.Continue
        assertTrue(complete.becameVisit)
    }

    @Test fun `movement beyond radius starts a new candidate`() {
        val first = (DwellTracker.update(null, 0, 31.2304, 121.4737) as DwellAction.Reset).next
        val moved = DwellTracker.update(first, 60_000, 31.2404, 121.4737)
        assertTrue(moved is DwellAction.Reset)
    }

    @Test fun `confirmed visit can tolerate one coarse location sample`() {
        val confirmed = (DwellTracker.update(null, 0, 31.2304, 121.4737) as DwellAction.Reset).next.copy(visitId = 9)
        val coarseButNearby = DwellTracker.update(
            confirmed,
            60_000,
            31.2331,
            121.4737,
            radiusMeters = 450.0,
        )
        assertTrue(coarseButNearby is DwellAction.Continue)
    }

    @Test fun `long callback gap starts a new candidate even at the same coordinate`() {
        val confirmed = (DwellTracker.update(null, 0, 31.2304, 121.4737) as DwellAction.Reset)
            .next.copy(lastMs = 60_000, visitId = 9, placeId = 2)
        val recovered = DwellTracker.update(
            confirmed,
            60_000 + DwellTracker.MAX_SAMPLE_GAP_MS + 1,
            31.2304,
            121.4737,
        )
        assertTrue(recovered is DwellAction.Reset)
        assertEquals(confirmed, (recovered as DwellAction.Reset).previous)
    }

    @Test fun `distance is symmetric and approximately correct`() {
        val a = distanceMeters(31.2304, 121.4737, 31.2314, 121.4737)
        val b = distanceMeters(31.2314, 121.4737, 31.2304, 121.4737)
        assertEquals(a, b, .001)
        assertTrue(a in 110.0..112.5)
    }
}
