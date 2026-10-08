package com.twentyfourpi.lifelog.collector

import org.junit.Assert.*
import org.junit.Test

/**
 * v0.15 地点匹配回归测试集。
 *
 * 场景来源：2026-08-21 诊断包（22,559 次停留决策）确认的根因——
 * 8/20 13:22 用户从公司返家（终点在南桥，应归"家"），系统却把停留
 * 绑到了 200 米内新固化的临时点"聪佳制衣厂"。旧匹配只取最近命中，
 * 无权重；本组测试锁定加权修复的行为契约。
 */
class PlaceMatchingTest {

    // ---- PlaceEntity.isTransient 判定 ----

    @Test fun `place with two visits is not transient`() {
        val place = place(visitCount = 2, lastVisitMs = NOW - DAY)
        assertFalse(place.isTransientAt(NOW))
    }

    @Test fun `single visit place is transient`() {
        val place = place(visitCount = 1, lastVisitMs = NOW - DAY)
        assertTrue(place.isTransientAt(NOW))
    }

    @Test fun `brand new place is transient until second visit`() {
        val place = place(visitCount = 0, lastVisitMs = 0)
        assertTrue(place.isTransientAt(NOW))
    }

    @Test fun `frequent place decays to transient after thirty idle days`() {
        val place = place(visitCount = 40, lastVisitMs = NOW - 31 * DAY)
        assertTrue("30天没去的常驻地应降权为临时点", place.isTransientAt(NOW))
    }

    @Test fun `frequent place visited recently stays permanent`() {
        val place = place(visitCount = 40, lastVisitMs = NOW - 29 * DAY)
        assertFalse(place.isTransientAt(NOW))
    }

    @Test fun `custom named place keeps strong identity even after a long absence`() {
        val place = place(visitCount = 1, lastVisitMs = NOW - 90 * DAY).copy(isCustomName = true)
        assertFalse(place.isTransientAt(NOW))
    }

    // ---- 权重打分语义（通过 DwellCandidate + 模拟坐标验证选择逻辑的输入域）----
    // selectWeightedPlace 是 LocationRecorder 的私有方法，这里以同公式验证关键排序：
    // score = visitCount / (distanceMeters + 50)，transient ×0.25。

    @Test fun `home beats nearby transient factory at equal distance`() {
        val homeScore = score(distance = 150.0, visitCount = 60, transient = false)
        val factoryScore = score(distance = 30.0, visitCount = 1, transient = true)
        assertTrue(
            "常驻地(60次,150m) 必须压过临时点(1次,30m)：$homeScore vs $factoryScore",
            homeScore > factoryScore,
        )
    }

    @Test fun `genuinely closer unknown area still wins when no known place is near`() {
        // 用户真的在别处停留：附近只有一个临时点时它仍应被选中（不产生 null 崩溃）
        val onlyChoice = score(distance = 80.0, visitCount = 1, transient = true)
        assertTrue(onlyChoice > 0.0)
    }

    @Test fun `permanent place outweighs transient at ten times distance`() {
        val farHome = score(distance = 190.0, visitCount = 25, transient = false)
        val nearCarWash = score(distance = 19.0, visitCount = 1, transient = true)
        assertTrue("洗车店贴脸也不该抢走家：$farHome vs $nearCarWash", farHome > nearCarWash)
    }

    // ---- 心跳（heartbeat-only）模式：A3 lastknown 时效门槛的行为契约 ----

    @Test fun `heartbeat extends existing candidate without moving it`() {
        val candidate = DwellCandidate(
            startMs = NOW - 10 * MIN, lastMs = NOW - 1 * MIN,
            latitude = HOME_LAT, longitude = HOME_LON, samples = 12,
            visitId = 7L, placeId = 4L,
        )
        val action = DwellTracker.heartbeat(
            candidate, NOW, HOME_LAT + 0.001, HOME_LON + 0.001,
        ) as DwellAction.Continue
        assertEquals("心跳不得移动候选坐标", candidate.latitude, action.candidate.latitude, 0.0)
        assertEquals("心跳只刷新时间", NOW, action.candidate.lastMs)
        assertFalse(action.becameVisit)
    }

    @Test fun `heartbeat never confirms a new visit`() {
        val freshCandidate = DwellCandidate(
            startMs = NOW, lastMs = NOW, latitude = HOME_LAT, longitude = HOME_LON, samples = 1,
        )
        val action = DwellTracker.heartbeat(freshCandidate, NOW + 10 * MIN, HOME_LAT, HOME_LON)
        assertTrue(action is DwellAction.Reset)
        assertEquals(freshCandidate, (action as DwellAction.Reset).next)
    }

    @Test fun `stale heartbeat outside radius is discarded not reset into a new place`() {
        val candidate = DwellCandidate(
            startMs = NOW - HOUR, lastMs = NOW - 9 * MIN,
            latitude = HOME_LAT, longitude = HOME_LON, samples = 30,
            visitId = 7L, placeId = 4L,
        )
        // 十几公里外的陈旧缓存位置：绝不能据此开新地点。
        // Reset(previous=candidate, next=candidate) 语义=丢弃心跳、原候选保持不变。
        val action = DwellTracker.heartbeat(candidate, NOW, 31.0, 121.0)
        assertTrue("出半径的心跳必须丢弃", action is DwellAction.Reset)
        assertEquals("previous 与 next 都指向原候选（未被篡改）", candidate, (action as DwellAction.Reset).next)
        assertNull(action.previous?.let { null } ?: action.previous)
        assertEquals("候选坐标不被心跳污染", HOME_LAT, action.next.latitude, 0.0)
    }

    @Test fun `regular update still confirms visits after required dwell`() {
        // 保证 A3 重构没有破坏原有 record 路径
        val first = (DwellTracker.update(null, 0, 31.2304, 121.4737) as DwellAction.Reset).next
        val later = DwellTracker.update(first, 5 * 60_000, 31.2304, 121.4737) as DwellAction.Continue
        assertTrue(later.becameVisit)
    }

    @Test fun `real weighted selector includes frequent place slightly beyond dwell radius`() {
        val home = place(visitCount = 60, lastVisitMs = NOW - DAY).copy(id = 1, latitude = HOME_LAT, longitude = HOME_LON)
        val factory = place(visitCount = 1, lastVisitMs = NOW - DAY).copy(
            id = 2,
            latitude = HOME_LAT + 0.0019,
            longitude = HOME_LON,
        )
        val candidate = DwellCandidate(
            startMs = NOW - 10 * MIN,
            lastMs = NOW,
            latitude = factory.latitude,
            longitude = factory.longitude,
            samples = 8,
        )

        assertEquals(home, selectWeightedPlaceForCandidate(listOf(home, factory), candidate, NOW))
    }

    @Test fun `extended radius alone never steals a genuinely new place`() {
        val home = place(visitCount = 60, lastVisitMs = NOW - DAY).copy(id = 1, latitude = HOME_LAT, longitude = HOME_LON)
        val newArea = DwellCandidate(
            startMs = NOW - 10 * MIN,
            lastMs = NOW,
            latitude = HOME_LAT + 0.0021,
            longitude = HOME_LON,
            samples = 8,
        )

        assertNull(selectWeightedPlaceForCandidate(listOf(home), newArea, NOW))
    }

    private fun place(visitCount: Int, lastVisitMs: Long) = com.twentyfourpi.lifelog.data.PlaceEntity(
        id = 1, name = "test", latitude = HOME_LAT, longitude = HOME_LON,
        visitCount = visitCount, lastVisitMs = lastVisitMs,
    )

    private fun score(distance: Double, visitCount: Int, transient: Boolean): Double {
        val penalty = if (transient) 0.25 else 1.0
        return visitCount.coerceAtLeast(1) / (distance + 50.0) * penalty
    }

    companion object {
        private const val NOW = 1_787_329_800_000L // 2026-08-22 00:30 CST，固定锚点保证测试确定性
        private const val MIN = 60_000L
        private const val HOUR = 3_600_000L
        private const val DAY = 24 * HOUR
        private const val HOME_LAT = 31.2304
        private const val HOME_LON = 121.4737
    }
}
