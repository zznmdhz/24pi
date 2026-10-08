package com.twentyfourpi.lifelog.ui

internal fun isAngleOnDialSweep(
    angleDegrees: Float,
    startDegrees: Float = 140f,
    sweepDegrees: Float = 260f,
): Boolean {
    val normalized = ((angleDegrees - startDegrees) % 360f + 360f) % 360f
    return normalized in 0f..sweepDegrees
}

internal fun isDialGestureHit(
    angleDegrees: Float,
    distanceFromCenter: Float,
    radius: Float,
    radialTolerance: Float,
): Boolean =
    kotlin.math.abs(distanceFromCenter - radius) <= radialTolerance &&
        isAngleOnDialSweep(angleDegrees)
