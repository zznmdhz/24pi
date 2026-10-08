package com.twentyfourpi.lifelog.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyRouteBuilderTest {
    @Test fun `stationary jitter inside 200 meters does not create distance`() {
        val route = DailyRouteBuilder.build(listOf(
            point(0, 31.0000, 121.0000, accuracy = 80f),
            point(60_000, 31.0005, 121.0005, accuracy = 80f),
            point(120_000, 30.9995, 120.9995, accuracy = 80f),
        ))
        assertEquals(0.0, route.observedDistanceMeters, 0.1)
        assertEquals(2, route.sections.single().points.size)
    }

    @Test fun `small increments count after movement leaves stability radius`() {
        val route = DailyRouteBuilder.build(listOf(
            point(1_000, 31.0000, 121.0000),
            point(61_000, 31.0010, 121.0000),
            point(121_000, 31.0020, 121.0000),
            point(181_000, 31.0025, 121.0000),
        ))
        assertTrue(route.observedDistanceMeters > 250.0)
    }

    @Test fun `provider speed confirms movement inside stability radius`() {
        val route = DailyRouteBuilder.build(listOf(
            point(1_000, 31.0000, 121.0000),
            point(31_000, 31.0003, 121.0000, speed = 1.8f),
            point(61_000, 31.0006, 121.0000, speed = 1.8f),
        ))
        assertTrue(route.observedDistanceMeters > 50.0)
    }

    @Test fun `interruption is dashed metadata and excluded from distance`() {
        val route = DailyRouteBuilder.build(listOf(
            point(1_000, 31.0, 121.0),
            point(61_000, 31.001, 121.0),
            point(601_000, 32.0, 122.0),
            point(661_000, 32.001, 122.0),
        ))
        assertEquals(2, route.sections.size)
        assertEquals(1, route.interruptions.size)
        assertTrue(route.observedDistanceMeters < 1_000.0)
    }

    @Test fun `mock and inaccurate points are rejected without touching raw data`() {
        val route = DailyRouteBuilder.build(listOf(
            point(1_000, 31.0, 121.0),
            point(2_000, 31.1, 121.1, accuracy = 500f),
            point(3_000, 31.2, 121.2, mock = true),
        ))
        assertEquals(2, route.rejectedPointCount)
        assertEquals(1, route.sections.size)
    }

    @Test fun `isolated kilometer scale spike is removed from projection`() {
        val route = DailyRouteBuilder.build(listOf(
            point(1_000, 31.0, 121.0),
            point(31_000, 35.0, 125.0),
            point(61_000, 31.0001, 121.0001),
        ))
        assertEquals(1, route.rejectedPointCount)
        assertTrue(route.observedDistanceMeters < 100.0)
    }

    @Test fun `implausible relocation becomes interruption and not mileage`() {
        val route = DailyRouteBuilder.build(listOf(
            point(1_000, 31.0, 121.0),
            point(61_000, 32.0, 122.0),
        ))
        assertEquals(1, route.interruptions.size)
        assertEquals(0.0, route.observedDistanceMeters, 0.1)
    }

    // ── v0.16.2：行程语义重建（对应真机反馈的碎段问题）──────────────────────────

    @Test fun `same instant dual source points do not split the trip`() {
        // GPS 回调与网络定位携带相同 measuredMs、坐标相差 ~22m：旧逻辑 elapsed=0
        // 被判无穷速度拆段（真机「第19段 0米·0秒」类碎段的来源）。
        val route = DailyRouteBuilder.build(listOf(
            point(1_000, 31.0000, 121.0000, measured = 1_000),
            point(31_000, 31.0100, 121.0000, measured = 31_000),
            point(31_500, 31.0102, 121.0000, measured = 31_000),
            point(61_000, 31.0200, 121.0000, measured = 61_000),
        ))
        assertEquals(1, route.sections.size)
        assertEquals(0, route.interruptions.size)
        assertEquals(1, route.trips.size)
    }

    @Test fun `red light micro stops fold into one driving trip`() {
        // 09:39–09:53 驾车，途中两次红灯（56 秒 / 66 秒）：应为一段行程，
        // 第二次红灯（≥60 秒）列为途经停留；不得出现 0米微段。
        val route = DailyRouteBuilder.build(listOf(
            point(0, 31.0000, 121.0000),
            point(30_000, 31.0050, 121.0000),
            point(60_000, 31.0100, 121.0000),
            point(88_000, 31.0101, 121.0000),   // 红灯 1 开始
            point(116_000, 31.0102, 121.0000),  // 红灯 1 结束（56 秒）
            point(146_000, 31.0150, 121.0000),
            point(176_000, 31.0200, 121.0000),
            point(206_000, 31.0250, 121.0000),
            point(236_000, 31.0251, 121.0000),  // 红灯 2 开始
            point(272_000, 31.0252, 121.0000),  // 红灯 2 结束（66 秒）
            point(302_000, 31.0300, 121.0000),
            point(332_000, 31.0350, 121.0000),
        ))
        assertEquals(1, route.sections.size)
        assertEquals(0, route.interruptions.size)
        assertEquals(1, route.trips.size)
        assertEquals(1, route.trips.single().stops.size)
        assertTrue(route.trips.single().distanceMeters > 3_500.0)
    }

    @Test fun `screenshot morning commute replays as two trips and two major stops`() {
        // 真机 2026-08-26 反馈回放：09:39–10:04 驾车（含同毫秒双源点与时钟抖动点、
        // 红灯）→ 10:04–11:42 停车 → 11:42 起午后出行（驾车+慢行，中间仅 2 分钟
        // 零位移停车间隙）→ 12:38–12:50 停车 11 分钟 → 12:51 尾点。
        // 旧行为产出 30+ 碎段；新行为：2 段行程 + 2 个真实停留边界。
        // 语义说明：午后驾车与慢行之间 <5 分钟且零位移的停车不构成行程边界
        // （反方审核 R3：短停车/送人不应切碎行程），折叠为途经停留。
        val points = mutableListOf<LocationPointEntity>()
        var t = 0L
        var lat = 31.0000
        fun drive(steps: Int, stepDegrees: Double = 0.005) {
            repeat(steps) {
                points.add(point(t, lat, 121.0, measured = t))
                t += 30_000
                lat += stepDegrees
            }
        }
        fun artifactPair() {
            // 同毫秒双源点（H1）+ 亚秒时钟抖动点（H2：100m/0.5s，旧逻辑 200m/s 拆段）。
            points.add(point(t, lat, 121.0, measured = t))
            points.add(point(t + 500, lat + 0.0002, 121.0, measured = t))
            points.add(point(t + 800, lat + 0.0009, 121.0, measured = t + 300))
        }
        // 行程 1：09:39–10:04（含红灯与退化点对）
        drive(6)
        points.add(point(t, lat + 0.0001, 121.0, measured = t)); t += 28_000 // 红灯 28 秒
        points.add(point(t, lat + 0.0002, 121.0, measured = t))
        artifactPair()
        t += 30_000; lat += 0.005
        drive(10)
        // 真实停留 1：10:04–11:42（98 分钟停车）
        val stop1Start = t
        points.add(point(t, lat, 121.0, measured = t))
        t += 98 * 60_000
        points.add(point(t, lat + 0.0001, 121.0, measured = t))
        // 行程 2：11:42–12:38（驾车 2.8 公里 + 2 分钟零位移停车间隙 + 慢行/碎点簇）
        drive(8, 0.0035)
        t += 120_000
        repeat(6) {
            points.add(point(t, lat, 121.0, measured = t))
            t += 5 * 60_000
            lat += 0.002
        }
        artifactPair()
        t += 60_000; lat += 0.002
        drive(4)
        // 真实停留 2：12:38–12:50（11 分钟）
        val stop2Start = t
        points.add(point(t, lat, 121.0, measured = t))
        t += 11 * 60_000
        points.add(point(t, lat + 0.0001, 121.0, measured = t))
        // 尾点：12:51 原地（不成行程）
        t += 60_000
        points.add(point(t, lat + 0.0002, 121.0, measured = t))

        val route = DailyRouteBuilder.build(points)

        assertEquals(2, route.trips.size)
        assertEquals(2, route.majorStops.size)
        assertEquals(stop1Start, route.majorStops[0].startMs)
        assertEquals(stop2Start, route.majorStops[1].startMs)
        // 行程 1 必须覆盖 09:39–10:04 全程，不被红灯/退化点对切开。
        assertEquals(0L, route.trips[0].first.timeMs)
        assertTrue(route.trips[0].last.timeMs >= stop1Start)
        assertTrue(route.trips[0].distanceMeters > 5_000.0)
        // 行程 2 从停车 1 恢复开始，覆盖午后全程。
        assertTrue(route.trips[1].first.timeMs >= stop1Start)
        assertTrue(route.trips[1].distanceMeters > 5_000.0)
        // 硬拆段只应出现在两个真实停留处（停车静默 > 5 分钟）。
        assertEquals(3, route.sections.size)
    }

    @Test fun `high speed rail at 83 mps stays one trip`() {
        // 高铁 83 m/s：旧逻辑 70 m/s 阈值把全程逐对拆段；新逻辑物理上限 300 m/s 不拆。
        val points = (0..9).map { i ->
            point(i * 30_000L, 31.0 + i * 0.0225, 121.0)
        }
        val route = DailyRouteBuilder.build(points)
        assertEquals(0, route.interruptions.size)
        assertEquals(1, route.trips.size)
        assertTrue(route.trips.single().distanceMeters > 20_000.0)
    }

    @Test fun `true kilometer scale teleport still splits`() {
        val route = DailyRouteBuilder.build(listOf(
            point(0, 31.0, 121.0),
            point(30_000, 31.01, 121.0),
            point(60_000, 35.0, 125.0),
            point(90_000, 35.01, 125.0),
        ))
        assertEquals(1, route.interruptions.size)
        assertEquals(2, route.trips.size)
    }

    @Test fun `minor gap inside a trip becomes dashed line not a split`() {
        // 2 分钟静默且位移小（隧道/电梯/静默停留）：行程内虚线，不拆段。
        val route = DailyRouteBuilder.build(listOf(
            point(0, 31.0000, 121.0000),
            point(30_000, 31.0050, 121.0000),
            point(60_000, 31.0100, 121.0000),
            point(180_000, 31.0101, 121.0000), // 静默 120 秒后恢复，位移 11m
            point(210_000, 31.0150, 121.0000),
        ))
        assertEquals(1, route.sections.size)
        assertEquals(1, route.interruptions.size)
        assertEquals(1, route.trips.size)
        assertEquals(1, route.trips.single().interruptions.size)
    }

    @Test fun `explicit gap with small displacement is minor and not a split`() {
        // 采集端 3 分钟静默恢复 gap（LOCATION_TRUSTED_DATA_RECOVERED）+ 原地：
        // 旧逻辑无条件拆段；新逻辑位移小归为行程内虚线。
        val gap = CollectionGapEntity(source = "LOCATION", startMs = 60_000, endMs = 240_000, reason = "LOCATION_TRUSTED_DATA_RECOVERED")
        val route = DailyRouteBuilder.build(
            rawPoints = listOf(
                point(0, 31.0000, 121.0000),
                point(60_000, 31.0010, 121.0000),
                point(240_000, 31.0012, 121.0000), // 恢复点，位移 22m
                point(270_000, 31.0060, 121.0000),
            ),
            gaps = listOf(gap),
        )
        assertEquals(1, route.sections.size)
        assertEquals(1, route.interruptions.size)
    }

    @Test fun `hard gap inside a long still period does not split into a mid-stop trip`() {
        // 反方辩证第二轮补抓边界：静止块（≥5min）中间夹着 >5min 的数据空洞时，
        // cuts 若按 resumeAt 排序会先处理 hardSplit、把 majorStop 的边界误吞，
        // 导致行程从静止块中间开始。按 endAt 排序后：完整静止块 = 真实停留边界，
        // 行程 1 到静止开始为止，行程 2 从静止结束才开始。
        val route = DailyRouteBuilder.build(listOf(
            // 行程 1：移动 1 公里
            point(0, 31.0000, 121.0000),
            point(30_000, 31.0050, 121.0000),
            point(60_000, 31.0100, 121.0000),
            // 静止块：09:00–09:08，中间 09:02–09:07 有 5 分钟空洞（hard split）
            point(90_000, 31.0101, 121.0000),  // 静止开始（90s）
            point(150_000, 31.0102, 121.0000), // 空洞前最后一可信点
            point(600_000, 31.0103, 121.0000), // 空洞后第一可信点（位移约 30m，总位移 <300m）
            point(690_000, 31.0104, 121.0000), // 静止结束（11min 静止）
            // 行程 2：继续移动
            point(720_000, 31.0150, 121.0000),
            point(750_000, 31.0200, 121.0000),
        ))
        // 静止期间 elapsed=450s > 5 分钟 → 1 个 hard split（两段 sections）；
        // 静止块 90s→690s 共 600s ≥ 5 分钟且位移 <300m → 真实停留。
        assertEquals(1, route.majorStops.size)
        assertEquals(2, route.trips.size)
        assertEquals(2, route.sections.size)
        // 行程 1 到静止开始为止（60s 到达点）；行程 2 从静止块最后一点（690s，
        // 离开前最后记录位置，720s 才实质性移动）开始——不得从空洞恢复点（600s）起步。
        assertEquals(60_000L, route.trips[0].last.timeMs)
        assertEquals(690_000L, route.trips[1].first.timeMs)
    }

    private fun point(
        at: Long,
        lat: Double,
        lon: Double,
        accuracy: Float = 10f,
        mock: Boolean = false,
        speed: Float? = null,
        measured: Long = at,
    ) = LocationPointEntity(
        recordedMs = at,
        measuredMs = measured,
        latitude = lat,
        longitude = lon,
        accuracyM = accuracy,
        provider = "test",
        isMock = mock,
        speedMps = speed,
    )
}
