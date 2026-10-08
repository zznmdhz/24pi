package com.twentyfourpi.lifelog.collector

import android.content.Context
import android.provider.Settings
import com.twentyfourpi.lifelog.data.*
import com.twentyfourpi.lifelog.debug.DiagnosticLog

class StepRecorder(
    private val context: Context,
    private val provider: DatabaseProvider,
    private val repository: LifeLogRepository,
    private val settings: SettingsStore,
) {
    suspend fun record(cumulative: Long, nowMs: Long = System.currentTimeMillis()) {
        val lastAt = settings.getLong("step_last_at")
        val lastValue = settings.getLong("step_last_value", -1)
        if (lastAt > 0 && nowMs - lastAt < 5 * 60_000 && lastValue >= 0 && cumulative - lastValue < 100) return
        val boot = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(0)
        provider.get().dao().insertStep(StepSampleEntity(recordedMs = nowMs, cumulative = cumulative, bootCount = boot))
        DiagnosticLog.event("steps", "sample_recorded", mapOf("cumulative" to cumulative, "bootCount" to boot))
        settings.putLong("step_last_at", nowMs); settings.putLong("step_last_value", cumulative)
        repository.setStatus(SourceId.STEPS, SourceState.ACTIVE, "计步传感器正常", touched = true)
    }
}
