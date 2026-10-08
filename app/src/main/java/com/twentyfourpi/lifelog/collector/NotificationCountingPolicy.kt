package com.twentyfourpi.lifelog.collector

import android.app.Notification
import android.app.NotificationManager

object NotificationCountingPolicy {
    fun exclusionReason(flags: Int, clearable: Boolean, importance: Int): String? = when {
        flags and Notification.FLAG_ONGOING_EVENT != 0 -> "ongoing"
        flags and Notification.FLAG_FOREGROUND_SERVICE != 0 -> "foreground_service"
        flags and Notification.FLAG_GROUP_SUMMARY != 0 -> "group_summary"
        !clearable -> "not_clearable"
        importance <= NotificationManager.IMPORTANCE_MIN -> "minimum_importance"
        else -> null
    }
}
