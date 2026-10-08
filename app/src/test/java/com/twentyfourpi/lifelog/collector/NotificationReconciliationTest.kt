package com.twentyfourpi.lifelog.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationReconciliationTest {
    @Test
    fun closesMissingOpenAndBackfillsOnlyEligibleActiveNotifications() {
        val plan = notificationReconciliationPlan(
            openBeforeSnapshot = setOf("still-active", "now-missing"),
            activeHashes = setOf("still-active", "eligible-new", "filtered-new"),
            eligibleHashes = setOf("still-active", "eligible-new"),
            indexComplete = true,
        )

        assertEquals(setOf("now-missing"), plan.staleOpenHashes)
        assertEquals(setOf("eligible-new"), plan.missingEligibleHashes)
    }

    @Test
    fun partialSnapshotNeverClosesExistingLifecycle() {
        val plan = notificationReconciliationPlan(
            openBeforeSnapshot = setOf("unknown-active"),
            activeHashes = emptySet(),
            eligibleHashes = setOf("recoverable"),
            indexComplete = false,
        )

        assertTrue(plan.staleOpenHashes.isEmpty())
        assertEquals(setOf("recoverable"), plan.missingEligibleHashes)
    }
}
