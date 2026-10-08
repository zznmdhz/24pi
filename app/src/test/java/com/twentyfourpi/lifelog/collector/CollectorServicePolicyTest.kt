package com.twentyfourpi.lifelog.collector

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectorServicePolicyTest {
    @Test fun `location and health foreground types are combined`() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH,
            collectorForegroundServiceType(locationEnabled = true, healthEnabled = true),
        )
    }

    @Test fun `data sync is used only when no persistent collector is enabled`() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            collectorForegroundServiceType(locationEnabled = false, healthEnabled = false),
        )
    }

    @Test fun `trusted callback requires both useful accuracy and fresh timestamp`() {
        val now = 1_000_000L

        assertTrue(isTrustedLocationCallback(30f, now - 30_000L, now))
        assertFalse(isTrustedLocationCallback(250f, now - 30_000L, now))
        assertFalse(isTrustedLocationCallback(30f, now - 3 * 60_000L, now))
    }
}
