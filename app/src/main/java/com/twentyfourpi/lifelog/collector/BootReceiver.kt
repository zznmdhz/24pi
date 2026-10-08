package com.twentyfourpi.lifelog.collector

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.twentyfourpi.lifelog.LifeLogApp

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext as LifeLogApp
        if (app.settings.collectionEnabled) {
            UsageSyncWorker.schedule(context)
            CollectorWatchdogScheduler.schedule(context, soon = true)
            if (context.shouldRunCollectorService(app.settings)) {
                runCatching { LifeLogCollectorService.start(context) }
            }
        }
    }
}
