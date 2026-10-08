package com.twentyfourpi.lifelog.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DialMathTest {
    @Test fun `visible dial arc accepts midpoint and both endpoints`() {
        assertTrue(isAngleOnDialSweep(270f))
        assertTrue(isAngleOnDialSweep(140f))
        assertTrue(isAngleOnDialSweep(40f))
    }

    @Test fun `bottom sun gap does not capture gestures`() {
        assertFalse(isAngleOnDialSweep(90f))
    }

    @Test fun `dial hit rejects center and points outside ring`() {
        assertFalse(isDialGestureHit(270f, distanceFromCenter = 0f, radius = 100f, radialTolerance = 20f))
        assertFalse(isDialGestureHit(270f, distanceFromCenter = 140f, radius = 100f, radialTolerance = 20f))
        assertTrue(isDialGestureHit(270f, distanceFromCenter = 100f, radius = 100f, radialTolerance = 20f))
    }
}
