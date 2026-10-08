package com.twentyfourpi.lifelog.util

import com.twentyfourpi.lifelog.data.StepSampleEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class StepCalculationTest {
    @Test fun `counts positive deltas within a boot and ignores reset`() {
        val samples = listOf(
            sample(1, 100), sample(1, 180), sample(1, 175), sample(1, 220),
            sample(2, 10), sample(2, 50),
        )
        assertEquals(165, calculateSteps(samples))
    }

    private fun sample(boot: Int, value: Long) = StepSampleEntity(recordedMs = value, cumulative = value, bootCount = boot)
}
