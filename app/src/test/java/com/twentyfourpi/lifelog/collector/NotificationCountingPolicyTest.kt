package com.twentyfourpi.lifelog.collector

import android.app.Notification
import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationCountingPolicyTest {
    @Test fun `filters foreground and ongoing service updates`() {
        assertEquals("ongoing", NotificationCountingPolicy.exclusionReason(Notification.FLAG_ONGOING_EVENT, true, NotificationManager.IMPORTANCE_DEFAULT))
        assertEquals("foreground_service", NotificationCountingPolicy.exclusionReason(Notification.FLAG_FOREGROUND_SERVICE, true, NotificationManager.IMPORTANCE_DEFAULT))
    }

    @Test fun `filters summaries and invisible metadata`() {
        assertEquals("group_summary", NotificationCountingPolicy.exclusionReason(Notification.FLAG_GROUP_SUMMARY, true, NotificationManager.IMPORTANCE_DEFAULT))
        assertEquals("not_clearable", NotificationCountingPolicy.exclusionReason(0, false, NotificationManager.IMPORTANCE_DEFAULT))
        assertEquals("minimum_importance", NotificationCountingPolicy.exclusionReason(0, true, NotificationManager.IMPORTANCE_MIN))
    }

    @Test fun `counts a normal clearable notification`() {
        assertNull(NotificationCountingPolicy.exclusionReason(0, true, NotificationManager.IMPORTANCE_DEFAULT))
    }
}
