package com.twentyfourpi.lifelog.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic scenarios with invented times, IDs and locations; no device exports. */
class GapProjectionTest {

    private val base = 1_700_000_000_000L
    private val dayStart = base
    private val dayEnd = base + 24L * 3_600_000L
    private val asOf = dayEnd
    private val generated = dayEnd

    private fun t(hour: Int, minute: Int, second: Int = 0): Long =
        base + hour * 3_600_000L + minute * 60_000L + second * 1_000L

    private fun visit(id: Long, placeId: Long, start: Long, end: Long) = PlaceVisitView(
        id = id, placeId = placeId, startMs = start, endMs = end,
        confidence = .9f, name = "地点$placeId", address = "",
        latitude = 31.0 + placeId * .01, longitude = 121.0,
    )

    private fun gap(
        id: Long,
        source: SourceId,
        start: Long,
        end: Long,
        reason: String = "LOCATION_TRUSTED_DATA_RECOVERED",
    ) = CollectionGapEntity(id = id, source = source.name, startMs = start, endMs = end, reason = reason)

    private fun point(
        id: Long,
        at: Long,
        accuracy: Float = 30f,
        mock: Boolean = false,
    ) = LocationPointEntity(
        id = id, recordedMs = at, latitude = 31.0, longitude = 121.0,
        accuracyM = accuracy, provider = "gps", measuredMs = at, isMock = mock,
    )

    // ── 合成场景场景 ────────────────────────────────────────────

    /** Invented visits to one synthetic place, separated by long unknown intervals. */
    private fun syntheticVisits(): List<PlaceVisitView> = listOf(
        visit(1, 1, t(0, 0), t(2, 0)),
        visit(2, 1, t(3, 0), t(5, 0)),
        visit(3, 1, t(7, 0), t(8, 30, 0)),
    )

    private fun syntheticPoints(): List<LocationPointEntity> = listOf(
        point(1, t(2, 0), 25f),
        point(2, t(3, 0), 25f),
        point(3, t(7, 0), 25f),
    )

    private fun syntheticProjection(mode: ProjectionMode = ProjectionMode.EFFECTIVE): GapProjection =
        projectCollectionGaps(
            gaps = listOf(gap(1, SourceId.LOCATION, t(1, 30), t(7, 30))),
            points = syntheticPoints(),
            visits = syntheticVisits(),
            windowStartMs = dayStart,
            windowEndMs = dayEnd,
            asOfMs = asOf,
            generatedAtMs = generated,
            mode = mode,
        )

    @Test
    fun `合成场景缺口被重建为实测段加两段真正未知`() {
        val projection = syntheticProjection()
        val effective = projection.gaps.single()

        // 原始区间一字未改：投影只新增解释，不删除记录
        assertEquals(t(1, 30), effective.rawStartMs)
        assertEquals(t(7, 30), effective.rawEndMs)
        assertEquals(
            t(7, 30) - t(1, 30),
            effective.rawDurationMs,
        )

        // Synthetic unknown intervals are 02:00–03:00 and 05:00–07:00.
        val unknown = effective.unknownIntervals
        assertEquals(2, unknown.size)
        assertEquals(t(2, 0), unknown[0].startMs)
        assertEquals(t(3, 0), unknown[0].endMs)
        assertEquals(t(5, 0), unknown[1].startMs)
        assertEquals(t(7, 0), unknown[1].endMs)

        // Measured intervals cover 30 minutes + 2 hours + 30 minutes.
        val expectedMeasured = (t(2, 0) - t(1, 30)) +
            (t(5, 0) - t(3, 0)) +
            (t(7, 30) - t(7, 0))
        assertEquals(expectedMeasured, effective.measuredMs)
        assertTrue(effective.supersededByEvidence)
        assertEquals(effective.rawDurationMs, effective.measuredMs + effective.inferredMs + effective.unknownMs)
    }

    @Test
    fun `撤销投影后回到原始解释整段未知`() {
        val raw = syntheticProjection(mode = ProjectionMode.RAW).gaps.single()
        assertEquals(1, raw.intervals.size)
        assertEquals(GapSupport.UNKNOWN, raw.intervals.single().support)
        assertEquals(raw.rawDurationMs, raw.unknownMs)
        assertFalse(raw.supersededByEvidence)
        // 原始解释里也不丢证据引用：点仍在
        assertTrue(raw.intervals.single().evidence.isNotEmpty())
    }

    @Test
    fun `长空档即使前后同地点也不推断为在家`() {
        val projection = syntheticProjection()
        val inferred = projection.gaps.single().intervals.filter { it.support == GapSupport.INFERRED }
        assertTrue("两个合成空档都超过推断上限，不应出现推断段", inferred.isEmpty())
    }

    // ── 推断三态 ──────────────────────────────────────────────

    @Test
    fun `前后同地点的短空档推断为有限连续`() {
        val projection = projectCollectionGaps(
            gaps = listOf(gap(1, SourceId.LOCATION, t(10, 0), t(10, 40))),
            points = emptyList(),
            visits = listOf(
                visit(1, 7, t(9, 0), t(10, 10)),
                visit(2, 7, t(10, 25), t(11, 0)),
            ),
            windowStartMs = dayStart,
            windowEndMs = dayEnd,
            asOfMs = asOf,
            generatedAtMs = generated,
        )
        val effective = projection.gaps.single()
        assertEquals(listOf(GapSupport.MEASURED, GapSupport.INFERRED, GapSupport.MEASURED), effective.intervals.map { it.support })
        assertEquals(15 * 60_000L, effective.inferredMs)
        assertEquals(0L, effective.unknownMs)
    }

    @Test
    fun `不同地点之间的空档不算推断`() {
        val projection = projectCollectionGaps(
            gaps = listOf(gap(1, SourceId.LOCATION, t(10, 0), t(10, 40))),
            points = emptyList(),
            visits = listOf(
                visit(1, 7, t(9, 0), t(10, 10)),
                visit(2, 9, t(10, 25), t(11, 0)),
            ),
            windowStartMs = dayStart,
            windowEndMs = dayEnd,
            asOfMs = asOf,
            generatedAtMs = generated,
        )
        val effective = projection.gaps.single()
        assertEquals(1, effective.unknownIntervals.size)
        assertEquals(15 * 60_000L, effective.unknownMs)
        assertEquals(0L, effective.inferredMs)
    }

    @Test
    fun `超过推断上限的短空档仍是未知`() {
        val projection = projectCollectionGaps(
            gaps = listOf(gap(1, SourceId.LOCATION, t(10, 0), t(10, 40))),
            points = emptyList(),
            visits = listOf(
                visit(1, 7, t(9, 0), t(10, 10)),
                visit(2, 7, t(10, 31), t(11, 0)),
            ),
            windowStartMs = dayStart,
            windowEndMs = dayEnd,
            asOfMs = asOf,
            generatedAtMs = generated,
        )
        val effective = projection.gaps.single()
        assertEquals(21 * 60_000L, effective.unknownMs)
        assertEquals(0L, effective.inferredMs)
    }

    // ── 证据可信性 ────────────────────────────────────────────

    @Test
    fun `低精度与模拟点不算可信证据`() {
        val projection = projectCollectionGaps(
            gaps = listOf(gap(1, SourceId.LOCATION, t(3, 0), t(3, 30))),
            points = listOf(
                point(1, t(3, 10), accuracy = 900f),
                point(2, t(3, 20), accuracy = 20f, mock = true),
            ),
            visits = emptyList(),
            windowStartMs = dayStart,
            windowEndMs = dayEnd,
            asOfMs = asOf,
            generatedAtMs = generated,
        )
        val effective = projection.gaps.single()
        assertEquals(0L, effective.measuredMs)
        assertEquals(30 * 60_000L, effective.unknownMs)
        assertFalse(effective.supersededByEvidence)
    }

    @Test
    fun `没有任何证据的缺口保持整段未知且判为需要处理`() {
        val projection = projectCollectionGaps(
            gaps = listOf(gap(1, SourceId.LOCATION, t(3, 0), t(3, 30), reason = "LOCATION_PERMISSION_DENIED")),
            points = emptyList(),
            visits = emptyList(),
            windowStartMs = dayStart,
            windowEndMs = dayEnd,
            asOfMs = asOf,
            generatedAtMs = generated,
        )
        val effective = projection.gaps.single()
        assertEquals(30 * 60_000L, effective.unknownMs)
        assertTrue(effective.actionableReason)
        assertTrue(projection.hasActionableUnknown)
    }

    @Test
    fun `其它来源的缺口不被位置证据重建`() {
        val projection = projectCollectionGaps(
            gaps = listOf(gap(1, SourceId.NOTIFICATIONS, t(3, 0), t(3, 30), reason = "NOTIFICATION_RECOVERED")),
            points = syntheticPoints(),
            visits = syntheticVisits(),
            windowStartMs = dayStart,
            windowEndMs = dayEnd,
            asOfMs = asOf,
            generatedAtMs = generated,
        )
        val effective = projection.gaps.single()
        assertEquals(30 * 60_000L, effective.unknownMs)
        assertEquals(0L, effective.measuredMs)
        // 按来源分开统计：通知的缺口不计入定位口径
        assertEquals(30 * 60_000L, projection.unknownMsFor(SourceId.NOTIFICATIONS.name))
        assertEquals(0L, projection.unknownMsFor(SourceId.LOCATION.name))
    }

    // ── 交集与并集 ────────────────────────────────────────────

    @Test
    fun `窗口未知时长按交集计算而不是整段`() {
        val projection = syntheticProjection()
        // A fully measured synthetic card must have no unknown overlap.
        assertEquals(0L, projection.unknownOverlapMs(t(0, 0), t(2, 0), SourceId.LOCATION.name))
        // A synthetic card entirely inside an unknown interval remains unknown.
        val overlap = projection.unknownOverlapMs(t(5, 0), t(7, 0), SourceId.LOCATION.name)
        assertEquals(t(7, 0) - t(5, 0), overlap)
    }

    @Test
    fun `地点时长按区间并集计不重复`() {
        val visits = listOf(
            visit(1, 1, t(9, 0), t(10, 0)),
            visit(2, 1, t(9, 30), t(10, 30)),
            visit(3, 2, t(10, 0), t(10, 20)),
        )
        val union = adjudicatedPlaceUnionMs(visits, dayStart, dayEnd)
        assertEquals(90 * 60_000L, union.getValue(1))
        assertEquals(20 * 60_000L, union.getValue(2))
        // 跨地点并集：place1 的 9:00–10:30 已覆盖 place2 的 10:00–10:20，
        // 所以总时长是 90 分钟而不是 110 分钟——110 正是旧"直接加总"的重复计时值。
        assertEquals(90 * 60_000L, stayUnionMs(visits, dayStart, dayEnd))
    }

    @Test
    fun `输入摘要在窗口为空时不炸`() {
        val projection = projectCollectionGaps(
            gaps = emptyList(),
            points = emptyList(),
            visits = emptyList(),
            windowStartMs = dayStart,
            windowEndMs = dayStart,
            asOfMs = asOf,
            generatedAtMs = generated,
        )
        assertEquals(0L, projection.unknownMs)
        assertEquals(GapProjectionRules.RULE_VERSION, projection.ruleVersion)
    }

    @Test
    fun `投影带上规则版本与截止时刻`() {
        val projection = syntheticProjection()
        assertEquals(GapProjectionRules.RULE_VERSION, projection.ruleVersion)
        assertEquals(asOf, projection.asOfMs)
        assertEquals(generated, projection.generatedAtMs)
        assertTrue(projection.inputDigest.contains("points=3"))
        assertTrue(projection.inputDigest.contains("visits=3"))
        assertNotNull(projection.gaps.single().intervals.firstOrNull())
    }
}
