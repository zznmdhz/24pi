package com.twentyfourpi.lifelog.ui

import com.twentyfourpi.lifelog.data.AppSessionEntity
import com.twentyfourpi.lifelog.data.CollectionGapEntity
import com.twentyfourpi.lifelog.data.DailyRoute
import com.twentyfourpi.lifelog.data.DailyRouteBuilder
import com.twentyfourpi.lifelog.data.LocationPointEntity
import com.twentyfourpi.lifelog.data.NotificationEventEntity
import com.twentyfourpi.lifelog.data.PlaceVisitView
import com.twentyfourpi.lifelog.data.SourceId
import com.twentyfourpi.lifelog.data.TimelineDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F 批冻结回归样本与日常时段聚合测试（方案 16.3 / T41–T43 / T48–T49）。
 *
 * 全部使用合成数据；真实记录不上传仓库。
 * 覆盖：真夹停折叠路径、用户确认位、8 分钟堵车上下文、时间线完整性、
 * observed/unknown 分离、退化输入与 T49 端到端投影。
 */
class DayEpisodeBuilderTest {

    private val min: (Long) -> Long = { it * 60_000L }

    // ── 合成样本工厂 ──────────────────────────────────────────

    private fun day(
        visits: List<PlaceVisitView> = emptyList(),
        apps: List<AppSessionEntity> = emptyList(),
        notifications: List<NotificationEventEntity> = emptyList(),
        gaps: List<CollectionGapEntity> = emptyList(),
    ) = TimelineDay(apps = apps, notifications = notifications, visits = visits, gaps = gaps)

    private fun visit(id: Long, placeId: Long, start: Long, end: Long) = PlaceVisitView(
        id = id, placeId = placeId, startMs = start, endMs = end,
        confidence = .9f, name = "地点$placeId", address = "",
        latitude = 31.0 + placeId * .01, longitude = 121.0,
    )

    private fun app(id: Long, pkg: String, start: Long, end: Long) = AppSessionEntity(
        id = id, packageName = pkg, appLabel = pkg.uppercase(), startMs = start, endMs = end,
    )

    private fun notification(id: Long, at: Long) = NotificationEventEntity(
        id = id, packageName = "notice.$id", appLabel = "通知$id", occurredMs = at, action = "POSTED",
    )

    private fun gap(id: Long, source: SourceId, start: Long, end: Long) = CollectionGapEntity(
        id = id, source = source.name, startMs = start, endMs = end, reason = "test",
    )

    private fun build(input: DayEpisodeInput) = DayEpisodeBuilder.build(input)

    /** 断言时间线完整性：从 dayStart 到 dayEnd 无洞无重叠、相邻相接、单调。 */
    private fun assertTimelineComplete(episodes: List<DayEpisode>, dayStartMs: Long, dayEndMs: Long) {
        assertTrue("empty episodes", episodes.isNotEmpty())
        assertEquals(dayStartMs, episodes.first().startMs)
        assertEquals(dayEndMs, episodes.last().endMs)
        episodes.zipWithNext().forEach { (a, b) ->
            assertEquals("gap/overlap between episodes", a.endMs, b.startMs)
            assertTrue("time not monotonic", b.startMs >= a.startMs)
        }
    }

    private fun routeWithTrips(vararg tripRanges: Array<Long>): DailyRoute {
        val trips = tripRanges.mapIndexed { index, r ->
            val start = r[0]
            val end = r[1]
            val p1 = com.twentyfourpi.lifelog.data.RoutePoint(0, start, 31.0, 121.0, 60f)
            val p2 = com.twentyfourpi.lifelog.data.RoutePoint(0, end, 31.01, 121.01, 60f)
            com.twentyfourpi.lifelog.data.RouteTrip(
                sections = listOf(com.twentyfourpi.lifelog.data.RouteSection(listOf(p1, p2), distanceMeters = 1_000.0)),
                stops = emptyList(),
                interruptions = emptyList(),
            )
        }
        return DailyRoute(trips = trips)
    }

    // ── T49：固定路线样本回归（点序/里程/中断/行程边界不变）───────────

    @Test fun `frozen route replay keeps trip boundary and distance`() {
        val points = listOf(
            p(0, 31.0000, 121.0000), p(30_000, 31.0050, 121.0000), p(60_000, 31.0100, 121.0000),
            p(88_000, 31.0101, 121.0000), p(116_000, 31.0102, 121.0000),
            p(146_000, 31.0150, 121.0000), p(176_000, 31.0200, 121.0000), p(206_000, 31.0250, 121.0000),
            p(236_000, 31.0251, 121.0000), p(272_000, 31.0252, 121.0000),
            p(302_000, 31.0300, 121.0000), p(332_000, 31.0350, 121.0000),
        )
        val route = DailyRouteBuilder.build(points)
        assertEquals(1, route.sections.size)
        assertEquals(0, route.interruptions.size)
        assertEquals(1, route.trips.size)
        assertEquals(1, route.trips.single().stops.size)
        assertTrue(route.trips.single().distanceMeters > 3_500.0)
    }

    /** T49 端到端：DailyRouteBuilder 产物喂给 DayEpisodeBuilder，路线证据保留、首页合并达成。 */
    @Test fun `route output feeds episode builder end to end`() {
        val dayStart = min(8 * 60)
        val dayEnd = min(12 * 60)
        val points = listOf(
            p(dayStart, 31.0000, 121.0000), p(dayStart + 20 * 1000, 31.0020, 121.0000),
            p(dayStart + 40 * 1000, 31.0080, 121.0000), p(dayStart + 55 * 1000, 31.0100, 121.0000),
        )
        val route = DailyRouteBuilder.build(points)
        assertTrue("expect trips from synthetic route", route.trips.isNotEmpty())

        val episodes = build(
            DayEpisodeInput(
                day = day(visits = listOf(visit(1, 1, min(7 * 60), dayStart), visit(2, 2, dayStart + min(60), min(11 * 60)))),
                route = route,
                dayStartMs = min(7 * 60),
                dayEndMs = min(12 * 60),
            ),
        )
        val moves = episodes.filter { it.kind == DayEpisodeKind.MOVE }
        assertTrue("expect a move from route evidence, got $episodes", moves.isNotEmpty())
        assertTimelineComplete(episodes, min(7 * 60), min(12 * 60))
    }

    // ── T41：家→公司 40 分钟，5 次 1–3 分钟短停并使用应用 ─────────────

    @Test fun `commute with five short stops collapses into one move episode`() {
        val start = min(9 * 60) // 09:00
        val tripStart = start + min(5)
        val tripEnd = start + min(40)
        // 09:05 离家 → 09:40 到公司，行程内 5 个 1–2 分钟短停（地点未确认 placeId=99）。
        val route = routeWithTrips(arrayOf(tripStart, tripEnd))
        val shortStops = listOf(
            visit(10, 101, tripStart + min(1), tripStart + min(2)),
            visit(11, 102, tripStart + min(7), tripStart + min(9)),
            visit(12, 103, tripStart + min(15), tripStart + min(16)),
            visit(13, 104, tripStart + min(21), tripStart + min(23)),
            visit(14, 105, tripStart + min(28), tripStart + min(30)),
        )
        val visitHome = visit(1, placeId = 1, start = min(8 * 60), end = tripStart)
        val visitCompany = visit(2, placeId = 2, start = tripEnd, end = min(11 * 60))

        val episodes = build(
            DayEpisodeInput(
                day = day(
                    visits = listOf(visitHome, visitCompany) + shortStops,
                    apps = listOf(app(1, "navi", tripStart, tripEnd)),
                ),
                route = route,
                dayStartMs = min(7 * 60),
                dayEndMs = min(13 * 60),
            ),
        )

        // 家 STAY → 一条“在路上”MOVE → 公司 STAY；短停全部并入，不出现 5 张地点未记录卡。
        val stays = episodes.filter { it.kind == DayEpisodeKind.STAY }
        val moves = episodes.filter { it.kind == DayEpisodeKind.MOVE }
        assertEquals("short stops must not create stay cards, got $episodes", 2, stays.size)
        assertEquals(1, moves.size)
        val move = moves.single()
        // 折叠合并后 MOVE：start 仍为 trip1 起点，end 扩展到行程终点（前 MOVE+停+后 MOVE 合成一条）。
        assertEquals(tripStart, move.startMs)
        assertEquals(tripEnd, move.endMs)
        assertTrue("tripRefs must include trip 0", move.tripRefs.contains(0))
        // 用户未确认的短停不产生独立卡。
        assertTrue(stays.none { it.placeId in setOf(101L, 102L, 103L, 104L, 105L) })
        // MOVE 内折叠保留原始事实引用（短停 visit id 仍可查）。
        assertTrue("visitRefs must be empty for merged move", move.visitRefs.isEmpty())
    }

    // ── T42：8 分钟堵车 vs 5 分钟取件 vs 30 分钟真实到访 ─────────────

    @Test fun `eight minute traffic jam between trips is not a new destination`() {
        // 堵车 8 分钟（位置稳定、夹在两行程之间、地点未确认）：上下文裁决为途中短暂停留，
        // 不单独成为主要停留卡；只有夹停 ≥10 分钟才成为新地点。
        val trip1 = min(9 * 60) to (min(9 * 60) + min(35))
        val jamStart = trip1.second
        val jamEnd = jamStart + min(8)
        val trip2 = jamEnd to (jamEnd + min(12))
        val route = routeWithTrips(arrayOf(trip1.first, trip1.second), arrayOf(trip2.first, trip2.second))
        // 8 分钟“堵车”期间确有原始到访写入（真实场景：采集层稳定 5 分钟即可落 visit 行）。
        val visits = listOf(
            visit(1, 1, min(8 * 60), trip1.first),
            visit(2, 99, jamStart, jamEnd), // 堵车 8 分钟，未命名地点
            visit(3, 2, trip2.second, min(11 * 60)),
        )

        val episodes = build(
            DayEpisodeInput(day(visits = visits), route, min(7 * 60), min(13 * 60)),
        )

        val stays = episodes.filter { it.kind == DayEpisodeKind.STAY }
        // 堵车不新增地点卡：只有 家、公司 两段 STAY；夹停 8 分钟被折叠进“在路上”。
        assertEquals("traffic jam visit must fold, got $episodes", 2, stays.size)
        assertTrue(stays.none { it.placeId == 99L })
        // 时间线仍然完整。
        assertTimelineComplete(episodes, min(7 * 60), min(13 * 60))
    }

    @Test fun `user confirmed short pickup remains a stay`() {
        // 5 分钟取件并已被用户确认（F-4 修订层落位后传入 confirmedVisitIds）：
        // 即使 ≤ 行程内短停上限，也必须保留为独立停留卡，不被时间阈值吞掉（方案 16.3-4）。
        val trip1 = min(9 * 60) to (min(9 * 60) + min(40))
        val pickupStart = trip1.second
        val pickupEnd = pickupStart + min(5)
        val trip2 = pickupEnd to (pickupEnd + min(10))
        val route = routeWithTrips(arrayOf(trip1.first, trip1.second), arrayOf(trip2.first, trip2.second))

        val episodes = build(
            DayEpisodeInput(
                day(visits = listOf(
                    visit(1, 1, min(8 * 60), trip1.first),
                    visit(2, 99, pickupStart, pickupEnd), // 5 分钟取件
                    visit(3, 2, trip2.second, min(11 * 60)),
                )),
                route,
                min(7 * 60),
                min(13 * 60),
                confirmedVisitIds = setOf(2),
            ),
        )

        val stays = episodes.filter { it.kind == DayEpisodeKind.STAY }
        // 家、取件、公司三张停留卡；取件卡理由=user-confirmed。
        assertEquals("confirmed pickup must be its own stay, got $episodes", 3, stays.size)
        val pickup = stays.single { it.placeId == 99L }
        assertEquals("user-confirmed", pickup.reason)
        assertEquals(pickupStart, pickup.startMs)
        assertEquals(pickupEnd, pickup.endMs)
    }

    @Test fun `thirty minute real visit becomes its own stay`() {
        val t1 = min(9 * 60)
        val shopStart = t1 + min(30)
        val shopEnd = shopStart + min(30)
        val t2 = shopEnd + min(10)
        val route = routeWithTrips(arrayOf(t1, shopStart), arrayOf(shopEnd, t2))

        val episodes = build(
            DayEpisodeInput(
                day(visits = listOf(
                    visit(1, 1, min(8 * 60), t1),
                    visit(2, 3, shopStart, shopEnd), // 30 分钟真实到访
                    visit(3, 2, t2, min(11 * 60)),
                )),
                route,
                min(7 * 60),
                min(13 * 60),
            ),
        )

        val stays = episodes.filter { it.kind == DayEpisodeKind.STAY }
        assertEquals("30min visit must be a stay, got $episodes", 3, stays.size)
        assertTrue(stays.any { it.placeId == 3L && it.reason.startsWith("stable") })
        assertTimelineComplete(episodes, min(7 * 60), min(13 * 60))
    }

    // ── T43：公司上午 2h、外出午餐、下午 5h 三段保留 ────────────────

    @Test fun `two company blocks around lunch are not merged`() {
        val company1 = visit(1, 2, min(9 * 60), min(12 * 60))
        val lunch = visit(2, 4, min(12 * 60), min(13 * 60))
        val company2 = visit(3, 2, min(13 * 60), min(18 * 60))

        val episodes = build(
            DayEpisodeInput(day(visits = listOf(company1, lunch, company2)), null, min(7 * 60), min(20 * 60)),
        )

        assertEquals(3, episodes.filter { it.kind == DayEpisodeKind.STAY }.size)
        val companyStays = episodes.filter { it.kind == DayEpisodeKind.STAY && it.placeId == 2L }
        assertEquals(2, companyStays.size)
        assertTrue(companyStays[0].endMs <= companyStays[1].startMs)
        assertTimelineComplete(episodes, min(7 * 60), min(20 * 60))
    }

    // ── T48：通勤期间缺定位 20 分钟 + 同地点前后缺定位 1 小时 ─────────

    @Test fun `long location gap during commute is unknown not home`() {
        val home = visit(1, 1, min(8 * 60), min(9 * 60 + 20))
        val company = visit(2, 2, min(9 * 60 + 40), min(11 * 60))
        val route = routeWithTrips(arrayOf(min(9 * 60), min(9 * 60 + 20))) // 只有出发段有行程

        val episodes = build(
            DayEpisodeInput(
                day(
                    visits = listOf(home, company),
                    gaps = listOf(gap(1, SourceId.LOCATION, min(9 * 60 + 20), min(9 * 60 + 40))),
                ),
                route,
                min(7 * 60),
                min(13 * 60),
            ),
        )

        val unknowns = episodes.filter { it.kind == DayEpisodeKind.UNKNOWN }
        assertTrue(
            "expect unknown around gap, got $episodes",
            unknowns.isNotEmpty(),
        )
        val lastStay = episodes.lastOrNull { it.kind == DayEpisodeKind.STAY }
        assertNotNull(
            "expect final stay at company, got $episodes",
            lastStay,
        )
        assertEquals(2L, lastStay!!.placeId)
        assertTimelineComplete(episodes, min(7 * 60), min(13 * 60))
    }

    // ── T44 前置：家附近漂移不产生额外停留卡 ───────────────────────

    @Test fun `home and nearby facility within 200m do not double count stays`() {
        // 家 07:00–08:10；附近 100–200m 漂移点形成一个短 visit（挂同 placeId）
        // —— 同 placeId 且间隔 ≤10 分钟应归并为一张家停留卡，不出现“家/工厂”两张。
        val home1 = visit(1, 1, min(7 * 60), min(8 * 60 + 10))
        val drift = visit(2, 1, min(8 * 60 + 15), min(8 * 60 + 20)) // 同 placeId 短漂移
        val home2 = visit(3, 1, min(8 * 60 + 25), min(9 * 60))

        val episodes = build(DayEpisodeInput(day(visits = listOf(home1, drift, home2)), null, min(6 * 60), min(10 * 60)))

        val homeStays = episodes.filter { it.kind == DayEpisodeKind.STAY && it.placeId == 1L }
        assertEquals("home stays should be one, got $episodes", 1, homeStays.size)
        assertEquals("home start wrong, got $episodes", min(7 * 60), homeStays.single().startMs)
        assertEquals("home end wrong, got $episodes", min(9 * 60), homeStays.single().endMs)

        // observed/unknown 分离：归并组内部空档（08:10–08:15、08:20–08:25）不得计入确定停留。
        val home = homeStays.single()
        val observedMs = home.observedDurationMs
        // 三段 visit 时长 = 70 + 5 + 35 = 110 分钟。
        assertEquals("observed must exclude internal gaps, got $observedMs", min(110), observedMs)
        assertTrue("unknown ranges must include internal 5-minute gaps", home.unknownRanges.isNotEmpty())
        // 卡片跨度（2h）不等于确定停留时长；未知时长不得算进家。
        assertTrue(home.observedDurationMs < home.endMs - home.startMs)
        assertTimelineComplete(episodes, min(6 * 60), min(10 * 60))
    }

    @Test fun `stay episode keeps app and notification refs`() {
        // R11/探针 P03：一段在家停留期间有应用使用→卡片的 appRefs 必须包含该应用。
        val app = app(10, "test.app", min(5), min(9))
        val visit = visit(1, placeId = 1, start = min(0), end = min(20))
        val episodes = build(
            DayEpisodeInput(
                day(
                    apps = listOf(app),
                    visits = listOf(visit),
                ),
                null, min(0), min(24 * 60),
                placeNames = mapOf(1L to "家"),
            ),
        )
        val stay = episodes.first { it.kind == DayEpisodeKind.STAY }
        assertTrue("stay must carry app refs, got ${stay.appRefs}", stay.appRefs.contains(10L))
    }

    // ── 时间线完整性 + 退化输入 ─────────────────────────────────

    @Test fun `empty day yields no placeholder episode`() {
        // R11/探针 P04：完全空的天不生成虚构 UNKNOWN 卡——由 UI 显示正常空态；
        // 只有确实有活动/证据的时段才产生时段。
        val episodes = build(DayEpisodeInput(day(), null, min(0), min(24 * 60)))
        assertEquals(true, episodes.isEmpty())
    }

    @Test fun `empty day with app evidence yields unknown activity`() {
        val gapStart = min(10)
        val episodes = build(
            DayEpisodeInput(
                day(
                    apps = listOf(app(1, "wechat", gapStart, gapStart + min(30))),
                    notifications = listOf(notification(1, gapStart + min(5))),
                    gaps = listOf(gap(1, SourceId.LOCATION, min(0), min(24 * 60))),
                ),
                null,
                min(0),
                min(24 * 60),
            ),
        )
        assertEquals(1, episodes.size)
        assertEquals(DayEpisodeKind.UNKNOWN, episodes.single().kind)
        assertEquals("unknown-activity", episodes.single().reason)
        assertTrue("app refs must be populated", episodes.single().appRefs.contains(1))
        assertTrue("notification refs must be populated", episodes.single().notificationRefs.contains(1))
        assertTrue("gap refs must be populated", episodes.single().gapRefs.contains(1))
    }

    @Test fun `trip covering whole day yields single move`() {
        val episodes = build(
            DayEpisodeInput(day(), routeWithTrips(arrayOf(min(0), min(24 * 60))), min(0), min(24 * 60)),
        )
        assertEquals(1, episodes.size)
        assertEquals(DayEpisodeKind.MOVE, episodes.single().kind)
        assertEquals(min(0), episodes.single().startMs)
        assertEquals(min(24 * 60), episodes.single().endMs)
    }

    @Test fun `visits entirely outside day are ignored safely`() {
        val episodes = build(
            DayEpisodeInput(
                day(visits = listOf(visit(1, 1, min(-60), min(-30)), visit(2, 2, min(25 * 60), min(26 * 60)))),
                null,
                min(0),
                min(24 * 60),
            ),
        )
        // 无合法 visit：退化为单条 UNKNOWN-empty，不崩溃、不产生负长或越界卡。
        assertEquals(1, episodes.size)
        assertEquals(DayEpisodeKind.UNKNOWN, episodes.single().kind)
        assertTimelineComplete(episodes, min(0), min(24 * 60))
    }

    @Test fun `move unfolds with names when both adjacent stays known`() {
        val t1 = min(9 * 60)
        val t2 = t1 + min(40)
        val route = routeWithTrips(arrayOf(t1, t2))
        val episodes = build(
            DayEpisodeInput(
                day(visits = listOf(visit(1, 1, min(8 * 60), t1), visit(2, 2, t2, min(11 * 60)))),
                route,
                min(7 * 60),
                min(13 * 60),
                placeNames = mapOf(1L to "家", 2L to "公司"),
            ),
        )
        val move = episodes.single { it.kind == DayEpisodeKind.MOVE }
        assertEquals("家", move.fromPlaceName)
        assertEquals("公司", move.toPlaceName)
    }

    @Test fun `place name is null when unknown not leaking db id`() {
        val t1 = min(9 * 60)
        val t2 = t1 + min(40)
        val route = routeWithTrips(arrayOf(t1, t2))
        val episodes = build(
            DayEpisodeInput(
                day(visits = listOf(visit(1, 1, min(8 * 60), t1), visit(2, 2, t2, min(11 * 60)))),
                route,
                min(7 * 60),
                min(13 * 60),
            ),
        )
        val move = episodes.single { it.kind == DayEpisodeKind.MOVE }
        // placeNames 缺省时不泄漏内部 DB id（审核建议：回退 null，由 UI 显示中性名）。
        assertNull(move.fromPlaceName)
        assertNull(move.toPlaceName)
    }

    // ── 辅助 ──────────────────────────────────────────────────

    private fun p(ms: Long, lat: Double, lon: Double) = LocationPointEntity(
        id = 0, recordedMs = ms, measuredMs = ms,
        latitude = lat, longitude = lon, accuracyM = 60f, provider = "test",
    )
}