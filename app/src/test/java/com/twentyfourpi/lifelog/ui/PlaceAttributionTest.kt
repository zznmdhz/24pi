package com.twentyfourpi.lifelog.ui

import com.twentyfourpi.lifelog.data.PlaceVisitView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F 批 3：地点稳定归属纯函数测试（方案 16.4–16.5 / T44–T46）。
 * 全部合成数据；真实记录不上传。
 */
class PlaceAttributionTest {

    // 家中心点
    private val homeLat = 31.0000
    private val homeLon = 121.0000

    private fun zone(
        placeId: Long,
        lat: Double = homeLat,
        lon: Double = homeLon,
        enter: Double = 200.0,
        leave: Double = 280.0,
        confirmed: Boolean = false,
        visits: Int = 0,
    ) = PlaceZone(placeId, lat, lon, enter, leave, confirmed, visits)

    private fun sample(ms: Long, dLat: Double, dLon: Double = 0.0, acc: Float = 30f) =
        AttributionSample(ms, homeLat + dLat, homeLon + dLon, acc, isDistinct = true)

    // ── T44：家附近 100–200 米漂移不增加确认票，归属稳定 ─────────────

    @Test fun `single drift point inside 200m does not flip attribution`() {
        val zones = listOf(zone(1, visits = 10))
        var state = PlaceAttributionState()

        // 连续两个新鲜点进入家圈（在 100m 内漂移）→ 确认到家。
        val (s1, d1) = state.next(sample(1_000, 0.001), zones)
        assertTrue(d1 == AttributionDecision.STAY || d1 == AttributionDecision.ENTER)
        val (s2, d2) = s1.next(sample(100_000, 0.001), zones)
        assertEquals(AttributionDecision.ENTER, d2)
        assertEquals(1L, s2.currentPlaceId)
        state = s2

        // 单一坏精度 / 缓存心跳不增加离开确认票。
        val (s3, d3) = state.next(sample(200_000, 0.002, acc = 500f), zones)
        assertEquals(AttributionDecision.STAY, d3)
        state = s3

        // 连续两个新鲜点远离（>leave）→ 确认离开。
        val (s4, d4) = state.next(sample(300_000, 0.005), zones)
        assertTrue(d4 == AttributionDecision.STAY || d4 == AttributionDecision.LEAVE)
        val (s5, d5) = s4.next(sample(400_000, 0.005), zones)
        // 离开需要连续 2 点且跨时；如果间隔足够则 LEAVE，否则保持。
        assertTrue(d5 == AttributionDecision.STAY || d5 == AttributionDecision.LEAVE)
    }

    @Test fun `bad accuracy samples never add confirmation votes`() {
        val zones = listOf(zone(1, visits = 5))
        var state = PlaceAttributionState()

        repeat(5) { i ->
            val (next, _) = state.next(sample(i * 100_000L, 0.001, acc = 500f), zones)
            state = next
        }
        // 全是坏精度 → 不应确认到家。
        assertNull(state.currentPlaceId)
        assertEquals(0, state.pendingEnterCount)
    }

    // ── T45：真去家旁球场、范围重叠、工厂历史次数极高 ────────────────

    @Test fun `high visit factory does not override nearer confirmed home`() {
        // 家（用户确认）与工厂（访问次数极高但距离更远且未确认）范围都覆盖到该点。
        val zones = listOf(
            zone(1, confirmed = true, visits = 0),                    // 家：确认，0 次
            zone(2, lat = homeLat + 0.010, lon = homeLon, visits = 999), // 工厂：极高次数
        )
        // 观测点在家的 enter 圈内（50m），工厂距离 ~1.1km（不在工厂 enter 圈）。
        val result = chooseDisplayPlace(
            visitAt(homeLat + 0.0003, homeLon),
            zones,
        )
        assertEquals(1L, result.placeId)
        assertEquals("zone", result.source)
    }

    @Test fun `overlapping zones with ambiguous point return nearby not facility`() {
        // 家与球场范围重叠，观测点在两者 enter 圈之间 220m（家中性带）。
        val zones = listOf(
            zone(1, confirmed = true, enter = 200.0, leave = 280.0),
            zone(5, lat = homeLat + 0.004, lon = homeLon, visits = 3, enter = 200.0, leave = 280.0),
        )
        val result = chooseDisplayPlace(
            visitAt(homeLat + 0.002, homeLon), // ~222m 离家，距球场 ~220m
            zones,
        )
        // 不在任何 enter 圈内 → 中性“家附近”（不硬归家/球场）。
        assertNull(result.placeId)
        assertEquals("nearby", result.source)
        assertEquals(1L, result.nearbyOfPlaceId)
    }

    // ── T46：初次错误绑定可重新评估；真实离开不被大圈长期锁住 ─────────

    @Test fun `initial wrong binding re-evaluates when sustained evidence points home`() {
        val home = zone(1, visits = 1)
        val factory = zone(2, lat = homeLat + 0.002, lon = homeLon, visits = 1)
        val zones = listOf(home, factory)

        // 首次漂移点靠近工厂 → 错误绑定到工厂（测试初始状态直接置 factory）。
        var state = PlaceAttributionState(currentPlaceId = 2L)

        // 后续新鲜证据持续指向家（100m 内）：应解除工厂归属并进入家。
        val (s1, d1) = state.next(sample(1_000, 0.0005), zones)
        assertTrue(d1 == AttributionDecision.STAY || d1 == AttributionDecision.ENTER)
        val (s2, d2) = s1.next(sample(100_000, 0.0005), zones)
        // 已在工厂归属中、样本落在家 enter 圈 → 应切换到家（重新评估）。
        assertTrue(
            "expect re-assess to home, got d2=$d2 current=${s2.currentPlaceId}",
            d2 == AttributionDecision.ENTER && s2.currentPlaceId == 1L,
        )
    }

    @Test fun `real departure is not locked by large footprint`() {
        val zones = listOf(zone(1, visits = 8))
        var state = PlaceAttributionState(currentPlaceId = 1L)

        // 距离 400m（>leave 280m）的连续两个新鲜独立点 → LEAVE。
        val (s1, d1) = state.next(sample(1_000, 0.004), zones)
        assertTrue(d1 == AttributionDecision.STAY || d1 == AttributionDecision.LEAVE)
        val (s2, d2) = s1.next(sample(100_000, 0.004), zones)
        assertTrue(
            "expect leave after sustained outside evidence, got d2=$d2",
            d2 == AttributionDecision.LEAVE,
        )
        assertNull(s2.currentPlaceId)
    }

    // ── 修订优先（F-4 前置）─────────────────────────────────────

    @Test fun `user revision overrides zone attribution`() {
        val zones = listOf(zone(1, confirmed = true, visits = 0))
        val revision = AttributionRevision(
            targetKey = "visit:99",
            fromPlaceId = 1L,
            toPlaceId = 7L,
            appliedToMs = 1_000,
            reason = "这段其实在家修正",
        )
        // revision 指向不存在的地点时应抛错（调用方必须先建地点）。
        val zonesWithTarget = zones + zone(7)
        val result = chooseDisplayPlace(
            visitAt(homeLat, homeLon, id = 99),
            zonesWithTarget,
            revisions = listOf(revision),
        )
        assertEquals(7L, result.placeId)
        assertEquals("revision", result.source)
    }

    @Test fun `thirty second samples confirm enter after accumulated span`() {
        // R10/探针 P01：正常 30 秒采样持续 300 秒——累计超过 90s 后必须确认进入，
        // 而不是要求“相邻两点的间隔 ≥90s”（后者 30s 采样永不可能满足）。
        val zones = listOf(zone(1, visits = 3))
        var state = PlaceAttributionState()
        var decided: AttributionDecision? = null
        for (t in 1_000L..301_000L step 30_000L) {
            val (next, decision) = state.next(sample(t, 0.0), zones)
            state = next
            if (decision == AttributionDecision.ENTER) decided = decision
        }
        assertEquals(1L, state.currentPlaceId)
        assertEquals(AttributionDecision.ENTER, decided)
    }

    @Test fun `thirty second samples confirm leave after accumulated span`() {
        // R10/探针 P02：已在家的地点，移动到圈外后 30 秒采样持续 300 秒——累计超过 90s 后离开。
        val zones = listOf(zone(1, visits = 3))
        var state = PlaceAttributionState(currentPlaceId = 1L)
        var decided: AttributionDecision? = null
        for (t in 1_000L..301_000L step 30_000L) {
            val (next, decision) = state.next(sample(t, 0.1), zones) // 距中心 ~11km，明显圈外
            state = next
            if (decision == AttributionDecision.LEAVE) decided = decision
        }
        assertNull(state.currentPlaceId)
        assertEquals(AttributionDecision.LEAVE, decided)
    }

    @Test fun `bad accuracy samples do not accumulate fake votes`() {
        // R10：坏精度点不投票；好-坏-好交错不能靠隔了数小时的票拼成连续确认。
        val zones = listOf(zone(1, visits = 3))
        var state = PlaceAttributionState()
        // 第一个好点（30s）→ 第二点坏精度 → 第三点好（数小时后）：跨度超但票不满足。
        val r1 = state.next(sample(0L, 0.0), zones); state = r1.first
        val r2 = state.next(sample(30_000L, 0.0, acc = 500f), zones); state = r2.first
        assertTrue("pending vote should not progress with bad accuracy", state.pendingEnterCount <= 1)
        val r3 = state.next(sample(3_600_000L, 0.0), zones); state = r3.first
        assertNull(state.currentPlaceId)
        assertEquals(1, state.pendingEnterCount) // 只有第一个好点算一票
    }

    @Test fun `adjacent zones do not share enter votes`() {
        // R10：A 圈 1 票后跨到 B 圈，B 重新计数（不共享票）。
        val zoneA = zone(1, lat = homeLat, lon = homeLon, visits = 3)
        val zoneB = zone(2, lat = homeLat + 0.01, lon = homeLon, visits = 3)
        var state = PlaceAttributionState()
        state = state.next(sample(0L, 0.00001), listOf(zoneA, zoneB)).first // A 圈内 1 票
        assertEquals(1L, state.pendingEnterPlaceId)
        state = state.next(sample(30_000L, 0.0105), listOf(zoneA, zoneB)).first // B 圈中心附近
        assertEquals(2L, state.pendingEnterPlaceId)
        assertEquals(1, state.pendingEnterCount) // B 重新计数
    }

    @Test fun `reverted revision falls back to zone`() {
        val zones = listOf(zone(1, confirmed = true))
        val revision = AttributionRevision(
            targetKey = "visit:5",
            fromPlaceId = 1L,
            toPlaceId = 7L,
            appliedToMs = 1_000,
            reason = "修正",
            revertedBy = "rev-2",
        )
        val result = chooseDisplayPlace(
            visitAt(homeLat, homeLon, id = 5),
            zones + zone(7),
            revisions = listOf(revision),
        )
        assertEquals(1L, result.placeId)
        assertEquals("zone", result.source)
    }

    // ── 审核建议新增：跨圈共享票、修订排序/孤儿、unknown、好-坏-好、高访问对抗 ──

    @Test fun `pending enter votes do not transfer across candidate zones`() {
        // 家 A 圈内 1 票后，下一点落在另一地点 B 圈内：必须重新从 1 计，不能共享 A 的票。
        val zoneA = zone(1, enter = 200.0, leave = 280.0, visits = 2)
        val zoneB = zone(2, lat = homeLat + 0.01, lon = homeLon, enter = 200.0, leave = 280.0, visits = 2)
        val zones = listOf(zoneA, zoneB)

        // 第一点：落在 A 圈（~50m）→ pendingEnter=1（候选 A）
        val (s1, _) = PlaceAttributionState().next(sample(1_000, 0.0003), zones)
        assertEquals(1, s1.pendingEnterCount)
        assertEquals(1L, s1.pendingEnterPlaceId)

        // 第二点：落在 B 圈（~10m 距 B，之前 A 的票不得带入）
        val (s2, d2) = s1.next(
            AttributionSample(100_000L, homeLat + 0.01, homeLon, 30f, isDistinct = true),
            zones,
        )
        assertEquals(
            "B 应重新从 1 计，不能因 A 已有 1 票就立即 ENTER",
            AttributionDecision.STAY,
            d2,
        )
        assertEquals(1, s2.pendingEnterCount)
        assertEquals(2L, s2.pendingEnterPlaceId)
        assertNull(s2.currentPlaceId)
    }

    @Test fun `orphan revision target degrades to unknown not crash`() {
        // 修订目标地点已被删除（zone 列表里没有）：不抛异常，降级为中性 unknown。
        val zones = listOf(zone(1, confirmed = true))
        val orphan = AttributionRevision(
            targetKey = "visit:9",
            fromPlaceId = 1L,
            toPlaceId = 999L, // 不存在的目标
            appliedToMs = 1_000,
            reason = "孤儿修订",
        )
        val result = chooseDisplayPlace(visitAt(homeLat, homeLon, id = 9), zones, listOf(orphan))
        assertNull(result.placeId)
        assertEquals("unknown", result.source)
    }

    @Test fun `latest revision wins by created time not list order`() {
        val zones = listOf(zone(1, confirmed = true)) + zone(7) + zone(8)
        // 列表故意乱序（旧在前新在后/新在前旧在后都要取最新 createdMs）。
        val old = AttributionRevision(
            targetKey = "visit:3", fromPlaceId = 1L, toPlaceId = 7L,
            appliedToMs = 1_000, reason = "旧", createdMs = 100,
        )
        val latest = AttributionRevision(
            targetKey = "visit:3", fromPlaceId = 1L, toPlaceId = 8L,
            appliedToMs = 1_000, reason = "新", createdMs = 999,
        )
        // 打了乱序：latest 在列表中在前。
        val result = chooseDisplayPlace(visitAt(homeLat, homeLon, id = 3), zones, listOf(latest, old))
        assertEquals(8L, result.placeId)
        assertEquals("revision", result.source)

        // 顺序颠倒也不影响（createdMs 排序保证取最新）。
        val result2 = chooseDisplayPlace(visitAt(homeLat, homeLon, id = 3), zones, listOf(old, latest))
        assertEquals(8L, result2.placeId)
    }

    @Test fun `visit revision takes priority over place revision`() {
        val zones = listOf(zone(1, confirmed = true)) + zone(7) + zone(8)
        val placeRev = AttributionRevision(
            targetKey = "place:1", fromPlaceId = 1L, toPlaceId = 7L,
            appliedToMs = 0, reason = "地点级", createdMs = 900,
        )
        val visitRev = AttributionRevision(
            targetKey = "visit:3", fromPlaceId = 1L, toPlaceId = 8L,
            appliedToMs = 1_000, reason = "时段级", createdMs = 100,
        )
        val result = chooseDisplayPlace(visitAt(homeLat, homeLon, id = 3), zones, listOf(placeRev, visitRev))
        // visit 精确优先（即使 createdMs 更小）。
        assertEquals(8L, result.placeId)
    }

    @Test fun `beyond leave radius returns unknown neutral`() {
        val zones = listOf(zone(1, confirmed = true, enter = 200.0, leave = 280.0))
        val result = chooseDisplayPlace(visitAt(homeLat + 0.005, homeLon), zones) // ~555m > 280m
        assertNull(result.placeId)
        assertEquals("unknown", result.source)
    }

    @Test fun `bad-good-bad interleaving does not stall confirmation`() {
        val zones = listOf(zone(1, visits = 5))
        var state = PlaceAttributionState()

        // 好点(家 50m) → 坏点 → 好点（跨 100s）：坏点不得清票，好点继续累计。
        val (s0, _) = state.next(sample(1_000, 0.0003, acc = 30f), zones) // 票1
        val (s1, _) = s0.next(sample(50_000, 0.0003, acc = 500f), zones) // 坏：不清票
        assertEquals(1, s1.pendingEnterCount)
        val (s2, d2) = s1.next(sample(100_000, 0.0003, acc = 30f), zones) // 好：票2 → ENTER
        assertEquals(AttributionDecision.ENTER, d2)
        assertEquals(1L, s2.currentPlaceId)
    }

    @Test fun `high visit unconfirmed factory cannot beat nearer unconfirmed home inside overlap`() {
        // 两圈重叠、都未确认：高访问工厂(999 次, 195m) vs 更近家(1 次, 5m)。
        // 历史加分有硬顶（cap 50 → boost≈196 < 200），距离差应主导，家胜。
        val zones = listOf(
            zone(1, enter = 200.0, leave = 280.0, visits = 1),          // 家：极近，1 次
            zone(2, lat = homeLat + 0.0018, lon = homeLon, enter = 200.0, leave = 280.0, visits = 999), // 工厂：195m
        )
        // 观测点在家中心 5m（field 内）。
        val result = chooseDisplayPlace(visitAt(homeLat + 0.00001, homeLon), zones)
        assertEquals(1L, result.placeId)
    }

    private fun visitAt(lat: Double, lon: Double, id: Long = 1) = PlaceVisitView(
        id = id, placeId = 1, startMs = 0, endMs = 60_000,
        confidence = .9f, name = "测试", address = "",
        latitude = lat, longitude = lon,
    )
}