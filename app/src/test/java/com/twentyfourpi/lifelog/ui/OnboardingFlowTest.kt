package com.twentyfourpi.lifelog.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class OnboardingFlowTest {
    @Test
    fun `location permissions are requested in a strict sequence`() {
        assertEquals(
            LocationSetupAction.REQUEST_FINE_LOCATION,
            locationSetupAction(false, false, false),
        )
        assertEquals(
            LocationSetupAction.REQUEST_BACKGROUND_LOCATION,
            locationSetupAction(true, false, false),
        )
        assertEquals(
            LocationSetupAction.REQUEST_NOTIFICATION_DISPLAY,
            locationSetupAction(true, true, false),
        )
        assertEquals(
            LocationSetupAction.CONTINUE,
            locationSetupAction(true, true, true),
        )
    }

    @Test
    fun `step setup requests collection notification before activity recognition`() {
        assertEquals(
            StepSetupAction.REQUEST_NOTIFICATION_DISPLAY,
            stepSetupAction(notificationDisplay = false, activityRecognition = false),
        )
        assertEquals(
            StepSetupAction.REQUEST_ACTIVITY_RECOGNITION,
            stepSetupAction(notificationDisplay = true, activityRecognition = false),
        )
        assertEquals(
            StepSetupAction.COMPLETE,
            stepSetupAction(notificationDisplay = true, activityRecognition = true),
        )
    }
}
