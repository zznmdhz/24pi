package com.twentyfourpi.lifelog.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.twentyfourpi.lifelog.data.CollectionGapEntity
import com.twentyfourpi.lifelog.data.userFacingGapReason
import org.junit.Test

class CollectorRecoveryPolicyTest {
    @Test fun `does not restart before any data or attempt`() {
        assertFalse(CollectorRecoveryPolicy.locationNeedsRestart(600_000L, referenceMs = 0L))
    }

    @Test fun `restarts three minutes after the last real measurement`() {
        val measured = 70_000L
        assertFalse(CollectorRecoveryPolicy.locationNeedsRestart(measured + 180_000L, referenceMs = measured))
        assertTrue(CollectorRecoveryPolicy.locationNeedsRestart(measured + 180_001L, measured))
    }

    /**
     * 审核 #3 的回归锁：发出请求、重建监听都不等于拿到位置。
     * 旧实现用 max(数据时间, 请求时间) 判定，导致每次重建都把真断档判定往后推最多 3 分钟。
     */
    @Test fun `issuing a request must not push the stale window forward`() {
        val lastRealData = 10_000L
        val attemptSince = 400_000L
        val now = 500_000L

        assertEquals(
            lastRealData,
            CollectorRecoveryPolicy.staleReference(lastRealData, attemptSince),
        )
        assertTrue(CollectorRecoveryPolicy.locationNeedsRestart(now, CollectorRecoveryPolicy.staleReference(lastRealData, attemptSince)))
    }

    @Test fun `a cold session with no data still declares a gap after the attempt window`() {
        val attemptSince = 1_000_000L
        assertFalse(
            CollectorRecoveryPolicy.locationNeedsRestart(
                attemptSince + 180_000L,
                CollectorRecoveryPolicy.staleReference(0L, attemptSince),
            ),
        )
        assertTrue(
            CollectorRecoveryPolicy.locationNeedsRestart(
                attemptSince + 180_001L,
                CollectorRecoveryPolicy.staleReference(0L, attemptSince),
            ),
        )
    }

    @Test fun `rebuild listeners only after repeated probe failures`() {
        assertFalse(CollectorRecoveryPolicy.shouldRebuildListeners(0))
        assertFalse(CollectorRecoveryPolicy.shouldRebuildListeners(1))
        assertTrue(CollectorRecoveryPolicy.shouldRebuildListeners(2))
        assertTrue(CollectorRecoveryPolicy.shouldRebuildListeners(5))
    }

    @Test fun `rebuild backoff doubles and caps at fifteen minutes`() {
        assertEquals(60_000L, CollectorRecoveryPolicy.nextRebuildDelayMs(0))
        assertEquals(120_000L, CollectorRecoveryPolicy.nextRebuildDelayMs(1))
        assertEquals(240_000L, CollectorRecoveryPolicy.nextRebuildDelayMs(2))
        assertEquals(480_000L, CollectorRecoveryPolicy.nextRebuildDelayMs(3))
        assertEquals(900_000L, CollectorRecoveryPolicy.nextRebuildDelayMs(4))
        assertEquals(900_000L, CollectorRecoveryPolicy.nextRebuildDelayMs(10))
    }

    @Test fun `heartbeat with cached location only while source is fresh`() {
        assertTrue(CollectorRecoveryPolicy.shouldHeartbeatWithCached(0L))
        assertTrue(CollectorRecoveryPolicy.shouldHeartbeatWithCached(90_000L))
        assertFalse(CollectorRecoveryPolicy.shouldHeartbeatWithCached(90_001L))
        assertFalse(CollectorRecoveryPolicy.shouldHeartbeatWithCached(-1L))
    }

    // ── P2：定位缺口生命周期（可信恢复来源 / 起点口径 / 只关一次）─────────────────

    @Test fun `probe live fix closes the gap without any system callback`() {
        assertTrue(CollectorRecoveryPolicy.closesLocationGap(CollectorRecoveryPolicy.TrustedRecoverySource.PROBE_LIVE))
        assertEquals(0, CollectorRecoveryPolicy.nextProbeFailures(3, CollectorRecoveryPolicy.TrustedRecoverySource.PROBE_LIVE))
    }

    @Test fun `cached last known location is a heartbeat not evidence`() {
        assertFalse(CollectorRecoveryPolicy.closesLocationGap(CollectorRecoveryPolicy.TrustedRecoverySource.PROBE_CACHE))
        // 审核 #2：缓存命中不累计失败计数（既不是证据、也不是失败）。
        assertEquals(0, CollectorRecoveryPolicy.nextProbeFailures(0, CollectorRecoveryPolicy.TrustedRecoverySource.PROBE_CACHE))
        assertEquals(1, CollectorRecoveryPolicy.nextProbeFailures(1, CollectorRecoveryPolicy.TrustedRecoverySource.PROBE_CACHE))
    }

    @Test fun `failed probe keeps pushing listener rebuild`() {
        assertEquals(1, CollectorRecoveryPolicy.nextProbeFailures(0, null))
        assertEquals(2, CollectorRecoveryPolicy.nextProbeFailures(1, null))
        assertTrue(CollectorRecoveryPolicy.shouldRebuildListeners(CollectorRecoveryPolicy.nextProbeFailures(1, null)))
    }

    @Test fun `throttled recording does not block recovery`() {
        // 接收端节流只影响原始点落库；可信回调本身就足以关闭缺口（服务里两者分开判定）。
        assertTrue(CollectorRecoveryPolicy.closesLocationGap(CollectorRecoveryPolicy.TrustedRecoverySource.SYSTEM_CALLBACK))
        assertTrue(isTrustedLocationCallback(accuracyM = 18f, locationTimeMs = 1_700_000_000_000L, receivedAtMs = 1_700_000_030_000L))
        assertFalse(isTrustedLocationCallback(accuracyM = 420f, locationTimeMs = 1_700_000_000_000L, receivedAtMs = 1_700_000_030_000L))
    }

    @Test fun `callback and probe recovering together close the gap once`() {
        // 串行闸门 + 删除起点语义：第一次读到起点才关，第二次读到的已经是 0。
        assertTrue(CollectorRecoveryPolicy.shouldCloseGap(1_000L))
        assertFalse(CollectorRecoveryPolicy.shouldCloseGap(0L))
    }

    @Test fun `gap start prefers real data over the last request`() {
        val request = 900_000L
        val data = 500_000L
        assertEquals(
            data,
            CollectorRecoveryPolicy.gapStartAt(lastDataMs = data, lastAcceptedCallbackMs = data, lastRequestMs = request, nowMs = 1_000_000L),
        )
        // 本会话从未拿到数据：退回监听起点，而不是「现在」——否则整段静默被吞掉。
        assertEquals(
            request,
            CollectorRecoveryPolicy.gapStartAt(lastDataMs = 0L, lastAcceptedCallbackMs = 0L, lastRequestMs = request, nowMs = 1_000_000L),
        )
        // 连监听起点都没有：只能用当前时刻。
        assertEquals(
            1_000_000L,
            CollectorRecoveryPolicy.gapStartAt(lastDataMs = 0L, lastAcceptedCallbackMs = 0L, lastRequestMs = 0L, nowMs = 1_000_000L),
        )
    }

    @Test fun `cache hit must not short circuit when trusted evidence is required`() {
        // 审核阻断 #1：要求可信证据时，缓存命中不能提前结束取位。
        assertFalse(CollectorRecoveryPolicy.canShortCircuitOnCacheHit(needTrustedEvidence = true))
        assertTrue(CollectorRecoveryPolicy.canShortCircuitOnCacheHit(needTrustedEvidence = false))
    }

    @Test fun `only live fixes and trusted callbacks count as trusted evidence`() {
        assertTrue(
            CollectorRecoveryPolicy.providesTrustedEvidence(CollectorRecoveryPolicy.TrustedRecoverySource.PROBE_LIVE),
        )
        assertTrue(
            CollectorRecoveryPolicy.providesTrustedEvidence(CollectorRecoveryPolicy.TrustedRecoverySource.SYSTEM_CALLBACK),
        )
        assertFalse(
            CollectorRecoveryPolicy.providesTrustedEvidence(CollectorRecoveryPolicy.TrustedRecoverySource.PROBE_CACHE),
        )
        assertFalse(CollectorRecoveryPolicy.providesTrustedEvidence(null))
    }

    @Test fun `restart settled gap keeps a user readable reason`() {
        val reason = CollectorRecoveryPolicy.RESTART_SETTLED_GAP_REASON
        assertTrue(reason.contains("PROCESS_RESTART"))
        val gap = CollectionGapEntity(source = "LOCATION", startMs = 0L, endMs = 60_000L, reason = reason)
        assertEquals("采集进程停止后重新启动，这段时间没有留下连续记录。", gap.userFacingGapReason())
    }

/** 审核 #2 的回归锁：缓存命中既不是失败也不是证据，不能推动监听重建。 */
    @Test fun `cache hits do not accumulate probe failures`() {
        val cached = CollectorRecoveryPolicy.TrustedRecoverySource.PROBE_CACHE
        assertEquals(0, CollectorRecoveryPolicy.nextProbeFailures(0, cached))
        assertEquals(5, CollectorRecoveryPolicy.nextProbeFailures(5, cached))
        assertFalse(CollectorRecoveryPolicy.shouldRebuildListeners(CollectorRecoveryPolicy.nextProbeFailures(1, cached)))
        // 只有真正取位失败（null）才累计并推动重建。
        assertEquals(2, CollectorRecoveryPolicy.nextProbeFailures(1, null))
        assertTrue(CollectorRecoveryPolicy.shouldRebuildListeners(2))
    }

    /** 审核 #5 的回归锁：OEM 把 getCurrentLocation 实现成回缓存时，不能关闭缺口。 */
    @Test fun `stale live fix is a heartbeat and cannot close the gap`() {
        val received = 1_000_000L
        val staleMeasure = received - 10 * 60_000L
        assertFalse(CollectorRecoveryPolicy.isTrustedLiveFix(received, staleMeasure, accuracyM = 25f))
        assertFalse(CollectorRecoveryPolicy.closesLocationGap(CollectorRecoveryPolicy.TrustedRecoverySource.PROBE_LIVE_STALE))
        assertFalse(CollectorRecoveryPolicy.providesTrustedEvidence(CollectorRecoveryPolicy.TrustedRecoverySource.PROBE_LIVE_STALE))
        assertEquals(3, CollectorRecoveryPolicy.nextProbeFailures(3, CollectorRecoveryPolicy.TrustedRecoverySource.PROBE_LIVE_STALE))
    }

    @Test fun `a fresh accurate live fix is trusted even when GPS time runs ahead of the clock`() {
        val received = 1_000_000L
        assertTrue(CollectorRecoveryPolicy.isTrustedLiveFix(received, received - 4_000L, accuracyM = 12f))
        // GPS 提供的测量时刻常比墙钟快几秒（实测 −4s），不能当成陈旧而误拒。
        assertTrue(CollectorRecoveryPolicy.isTrustedLiveFix(received, received + 4_000L, accuracyM = 12f))
        assertFalse(CollectorRecoveryPolicy.isTrustedLiveFix(received, received, accuracyM = 550f))
    }

    /** 审核 #4 的回归锁：进程重启收口是唯一例外的「非恢复」关闭来源，且必须能关缺口。 */
    @Test fun `process restart settle closes the gap but is not trusted evidence`() {
        val settle = CollectorRecoveryPolicy.TrustedRecoverySource.PROCESS_RESTART_SETTLE
        assertTrue(CollectorRecoveryPolicy.closesLocationGap(settle))
        assertFalse(CollectorRecoveryPolicy.providesTrustedEvidence(settle))
        assertFalse(CollectorRecoveryPolicy.closesLocationGap(CollectorRecoveryPolicy.TrustedRecoverySource.PROBE_CACHE))
    }

    @Test fun `trusted recovery reason maps to the location recovery copy`() {
        val gap = CollectionGapEntity(
            source = "LOCATION",
            startMs = 0L,
            endMs = 60_000L,
            reason = CollectorRecoveryPolicy.TRUSTED_RECOVERED_GAP_REASON,
        )
        assertEquals("可信位置数据恢复前存在一段空白。", gap.userFacingGapReason())
    }
}
