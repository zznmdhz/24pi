package com.twentyfourpi.lifelog.collector

import android.Manifest
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.twentyfourpi.lifelog.data.SettingsStore
import com.twentyfourpi.lifelog.data.SourceId

fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

fun Context.hasUsageAccess(): Boolean {
    val ops = getSystemService(AppOpsManager::class.java)
    return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName) ==
        AppOpsManager.MODE_ALLOWED
}

fun Context.hasNotificationListenerAccess(): Boolean {
    val expected = ComponentName(this, MetadataNotificationListener::class.java)
    return Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        ?.split(':')?.mapNotNull(ComponentName::unflattenFromString)?.contains(expected) == true
}

/** 只有定位或计步需要常驻前台服务；应用与通知由系统接口和定时同步负责。 */
fun Context.shouldRunCollectorService(settings: SettingsStore): Boolean =
    (settings.sourceEnabled(SourceId.LOCATION) &&
        hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) &&
        hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) ||
        (settings.sourceEnabled(SourceId.STEPS) && hasPermission(Manifest.permission.ACTIVITY_RECOGNITION))
