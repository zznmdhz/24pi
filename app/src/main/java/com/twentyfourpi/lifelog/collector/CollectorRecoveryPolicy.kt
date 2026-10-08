package com.twentyfourpi.lifelog.collector

import kotlin.math.min

/**
 * 定位恢复策略。所有决策都是纯函数，便于单元测试。
 *
 * 健康判定以“最近一次有效数据”为准：系统真实回调与主动取位（probe）成功
 * 都会刷新数据时间。小米 HyperOS 在设备静默期会冻结被动定位回调，但
 * getCurrentLocation 主动取位仍然可用；因此只看系统回调会把“系统静默但
 * 应用仍有数据”误报成中断。只有回调与主动取位都拿不到数据才算真正断档。
 */
internal object CollectorRecoveryPolicy {
    const val LOCATION_STALE_MS = 3 * 60_000L

    /** 真实回调停止多久后不再用旧位置合成心跳，转入主动取位。 */
    const val HEARTBEAT_MAX_SOURCE_AGE_MS = 90_000L

    /** 主动取位失败多少次后允许重建监听。 */
    const val REBUILD_AFTER_PROBE_FAILURES = 2

    /** 重建监听的最小/最大退避间隔。 */
    const val REBUILD_BACKOFF_BASE_MS = 60_000L
    const val REBUILD_BACKOFF_CAP_MS = 15 * 60_000L

    /**
     * 断档判定只认「最近一次真实测量」（[referenceMs] 见 [staleReference]）。
     * 发出请求、重建监听都不等于拿到位置——用请求时刻会把真实断档往后推，
     * 并在静默期把取位节奏错误地降速。
     */
    fun locationNeedsRestart(
        nowMs: Long,
        referenceMs: Long,
        staleAfterMs: Long = LOCATION_STALE_MS,
    ): Boolean = referenceMs > 0L && nowMs - referenceMs > staleAfterMs

    /**
     * 断档判定的参照时刻：有真实测量就用它；一次都没拿到过才用「开始尝试的时刻」，
     * 从而「试了 3 分钟仍无任何数据」也能如实判为断档，且不会被每次请求刷新。
     */
    fun staleReference(lastDataMs: Long, attemptSinceMs: Long): Long =
        if (lastDataMs > 0L) lastDataMs else attemptSinceMs

    /** 可信 fix 的最大测量年龄（与系统回调路径同源，避免两套新鲜度标准）。 */
    const val TRUSTED_FIX_MAX_AGE_MS = 2 * 60_000L

    /** 可信 fix 的最大精度（米）。 */
    const val TRUSTED_FIX_MAX_ACCURACY_M = 200f

    /**
     * 实时取位（getCurrentLocation）的结果是否算可信测量。
     * OEM 可能把它实现成「直接回系统缓存的旧点」，因此必须与回调路径同样是
     * 「精度达标 + 测量时刻新鲜」，否则会给缺口一个漂亮的假结束时刻。
     * 允许年龄为负：GPS 提供的测量时刻常比墙钟快几秒，不能当成陈旧。
     */
    fun isTrustedLiveFix(
        receivedAtMs: Long,
        measuredAtMs: Long,
        accuracyM: Float,
        maxAgeMs: Long = TRUSTED_FIX_MAX_AGE_MS,
    ): Boolean {
        if (accuracyM > TRUSTED_FIX_MAX_ACCURACY_M) return false
        if (measuredAtMs <= 0L) return true
        return receivedAtMs - measuredAtMs <= maxAgeMs
    }

    /** 主动取位失败连续 [failures] 次后，是否应该重建监听。 */
    fun shouldRebuildListeners(probeFailures: Int): Boolean =
        probeFailures >= REBUILD_AFTER_PROBE_FAILURES

    /** 第 [attempt] 次重建监听的退避间隔（1m, 2m, 4m, 8m, ... 封顶 15m）。 */
    fun nextRebuildDelayMs(attempt: Int): Long {
        val exp = REBUILD_BACKOFF_BASE_MS shl attempt.coerceIn(0, 10)
        return min(exp, REBUILD_BACKOFF_CAP_MS)
    }

    /** 回调断供时是否还能用旧位置合成心跳（只在刚断不久时允许，避免把用户钉在旧地点）。 */
    fun shouldHeartbeatWithCached(sourceAgeMs: Long): Boolean =
        sourceAgeMs in 0..HEARTBEAT_MAX_SOURCE_AGE_MS

    /**
     * P2：本次位置数据的来源。
     * 只有系统可信回调与主动取位的**实时结果**才算真实测量；系统缓存位置只是心跳。
     */
    enum class TrustedRecoverySource {
        /** 新鲜、精度达标的系统回调。 */
        SYSTEM_CALLBACK,

        /** 主动取位拿到的实时位置（getCurrentLocation）。 */
        PROBE_LIVE,

        /** 系统缓存位置（getLastKnownLocation）——可能来自别的应用，不能当作已恢复的证据。 */
        PROBE_CACHE,

        /** 主动取位拿到了位置，但测量时刻不够新鲜（多半是 OEM 回缓存）——只当心跳。 */
        PROBE_LIVE_STALE,

        /** 进程重启时就地收口上一段未关闭缺口（不是「已恢复」，但必须把那段收住）。 */
        PROCESS_RESTART_SETTLE,
    }

    /** P2：这次恢复能否关闭定位缺口——缓存心跳与陈旧测量都不能冒充新点。 */
    fun closesLocationGap(source: TrustedRecoverySource): Boolean = when (source) {
        TrustedRecoverySource.SYSTEM_CALLBACK,
        TrustedRecoverySource.PROBE_LIVE,
        TrustedRecoverySource.PROCESS_RESTART_SETTLE,
        -> true

        TrustedRecoverySource.PROBE_CACHE,
        TrustedRecoverySource.PROBE_LIVE_STALE,
        -> false
    }

    /**
     * P2：缓存心跳能否提前结束本次取位。
     * 只有「不需要可信证据」的常规取位才可以短路；缺口已开/已判静默时必须继续做实时取位，
     * 否则缓存永远新鲜、缺口一旦打开就再也等不到能关它的证据（审核阻断 #1）。
     */
    fun canShortCircuitOnCacheHit(needTrustedEvidence: Boolean): Boolean = !needTrustedEvidence

    /** P2：该结果是否算可信证据——只有系统回调与实时取位算，缓存心跳不算。 */
    fun providesTrustedEvidence(source: TrustedRecoverySource?): Boolean =
        source == TrustedRecoverySource.PROBE_LIVE || source == TrustedRecoverySource.SYSTEM_CALLBACK

    /**
     * P2：只有「真实测量」清零失败计数、「取位返回 null」累计失败。
     * 缓存心跳与陈旧测量保持计数不变——它们既不是证据、也不是失败：
     * 把缓存命中当失败会让监听在静默夜里被反复重建（每 3~18 分钟一次），
     * 既耗电又会打断正在进行的 GPS 首次定位。
     */
    fun nextProbeFailures(current: Int, source: TrustedRecoverySource?): Int = when (source) {
        TrustedRecoverySource.PROBE_LIVE -> 0
        null -> current + 1
        else -> current
    }

    /**
     * P2：本次恢复是否应该关闭缺口。
     * 起点为 0 说明缺口根本不存在、或已被另一个恢复入口先关掉——同一段静默只关一次。
     */
    fun shouldCloseGap(gapStartMs: Long): Boolean = gapStartMs > 0L

    /**
     * P2：缺口起点取「最后一次真实数据」的时刻，而不是「最后一次发出请求」的时刻——
     * 发请求不等于拿到位置，用请求时刻会把起点往后推，漏报真正的缺失时间。
     * 从未有过数据的会话才回退到请求时刻/当前时刻。
     */
    fun gapStartAt(lastDataMs: Long, lastAcceptedCallbackMs: Long, lastRequestMs: Long, nowMs: Long): Long =
        listOf(lastDataMs, lastAcceptedCallbackMs, lastRequestMs).firstOrNull { it > 0L } ?: nowMs

    /**
     * P2：上一段进程留下的未关闭缺口，收口时使用的原因串。
     * 含 PROCESS_RESTART，界面文案为「采集进程停止后重新启动，这段时间没有留下连续记录」。
     */
    const val RESTART_SETTLED_GAP_REASON = "LOCATION_PROCESS_RESTART"

    /** P2：可信位置恢复时写缺口用的原因串（界面文案：可信位置数据恢复前存在一段空白）。 */
    const val TRUSTED_RECOVERED_GAP_REASON = "LOCATION_TRUSTED_DATA_RECOVERED"
}
