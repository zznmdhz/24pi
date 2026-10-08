package com.twentyfourpi.lifelog.collector

internal data class NotificationReconciliationPlan(
    val staleOpenHashes: Set<String>,
    val missingEligibleHashes: Set<String>,
)

/** Pure reconciliation decision used by the listener and covered without Android service state. */
internal fun notificationReconciliationPlan(
    openBeforeSnapshot: Set<String>,
    activeHashes: Set<String>,
    eligibleHashes: Set<String>,
    indexComplete: Boolean,
): NotificationReconciliationPlan = NotificationReconciliationPlan(
    // A partial/corrupt snapshot may recover known rows, but must never close an unknown active row.
    staleOpenHashes = if (indexComplete) openBeforeSnapshot - activeHashes else emptySet(),
    // Filtering is deliberate: foreground/ongoing/group/min-importance snapshots are never backfilled.
    missingEligibleHashes = eligibleHashes - openBeforeSnapshot,
)
