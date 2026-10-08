package com.twentyfourpi.lifelog.ai

import com.twentyfourpi.lifelog.data.AppSessionEntity
import com.twentyfourpi.lifelog.data.NotificationEventEntity
import com.twentyfourpi.lifelog.data.PlaceEntity
import com.twentyfourpi.lifelog.data.PlaceVisitView
import com.twentyfourpi.lifelog.data.TimelineDay
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class AiEvidenceTest {
    private val zone = ZoneId.of("UTC")
    private val date = LocalDate.of(2026, 9, 25)
    private val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
    private val place = PlaceEntity(id = 1, name = "公司", latitude = 1.0, longitude = 2.0, kind = "COMPANY")
    private val other = PlaceEntity(id = 2, name = "公司旁", latitude = 1.0, longitude = 2.0, mergedIntoPlaceId = 1)
    private fun visit(id: Long, placeId: Long, a: Long, b: Long) = PlaceVisitView(id, placeId, a, b, 1f, "旧名", "", 1.0, 2.0)

    @Test fun clippedMergedOverlapAndCompanyUnion() {
        val acc = EvidenceAccumulator(ReviewRange(date, date.plusDays(1), "测试"), zone, start + 70 * 60_000, listOf(place, other))
        acc.add(date, TimelineDay(visits = listOf(
            visit(10, 1, start - 10 * 60_000, start + 50 * 60_000),
            visit(11, 2, start + 30 * 60_000, start + 80 * 60_000),
        )))
        val result = acc.finish()
        assertEquals(70 * 60_000L, result.placeRows.single().durationMs)
        assertEquals(70 * 60_000L, result.companyTotalMs)
        assertEquals(1, result.placeRows.single().count)
        assertEquals("公司", result.placeRows.single().name)
    }

    @Test fun separateStaysAtCanonicalPlaceCountTwice() {
        val acc = EvidenceAccumulator(ReviewRange(date, date.plusDays(1), "测试"), zone, start + 70 * 60_000, listOf(place, other))
        acc.add(date, TimelineDay(visits = listOf(
            visit(10, 1, start, start + 10 * 60_000),
            visit(11, 2, start + 11 * 60_000, start + 20 * 60_000),
        )))
        assertEquals(2, acc.finish().placeRows.single().count)
    }

    @Test fun emptyMeansUnknownAndPayloadExcludesRawPrivateFields() {
        val acc = EvidenceAccumulator(ReviewRange(date, date.plusDays(1), "测试"), zone, start + 86_400_000, listOf(place))
        acc.add(date, TimelineDay(notifications = listOf(NotificationEventEntity(packageName = "test", appLabel = "消息", occurredMs = start + 1, action = "POSTED", notificationTitle = "secret-title", notificationBody = "secret-body")),
            apps = listOf(AppSessionEntity(packageName = "app", appLabel = "应用", startMs = start, endMs = start + 60_000))))
        val e = acc.finish()
        val preview = e.preview("昨天的总结")
        assertEquals(1, e.notificationCount)
        assertFalse(preview.contains("secret-title"))
        assertFalse(preview.contains("secret-body"))
        assertFalse(preview.contains("latitude"))
        assertTrue(preview.contains("应用"))
        assertFalse(EvidenceAccumulator(ReviewRange(date, date.plusDays(1), "测试"), zone, start + 86_400_000, listOf(place)).finish().hasRecords)
    }

    @Test fun explicitAmbiguousOrOversizeRangeNeedsSelection() {
        assertTrue(ReviewRangeParser.parse("2026年8月在公司", date) is RangeParse.NeedsSelection)
        assertTrue(ReviewRangeParser.parse("上月和本月", date) is RangeParse.NeedsSelection)
        assertTrue(ReviewRangeParser.parse("过去120天", date) is RangeParse.NeedsSelection)
        assertTrue(ReviewRangeParser.parse("本月及最近7天", date) is RangeParse.NeedsSelection)
        assertTrue(ReviewRangeParser.parse("最近7天和最近30天", date) is RangeParse.NeedsSelection)
        val recent = (ReviewRangeParser.parse("最近30天去了哪里", date) as RangeParse.Valid).range
        assertEquals(30L, recent.days)
        assertEquals(30L, (ReviewRangeParser.parse("最近三十天", date) as RangeParse.Valid).range.days)
    }

    @Test fun explicitVisitCountRankingChangesOrderAndPreview() {
        val range = ReviewRange(date, date.plusDays(1), "测试")
        val evidence = ReviewEvidence(range, start + 86_400_000, zone, 1,
            listOf(EvidenceRow(1, "长停留", 9_000_000, 1), EvidenceRow(2, "多次到访", 1_000_000, 3)),
            emptyList(), 0, emptyList(), 0, emptyMap())
        assertEquals("多次到访", evidence.rankedPlaces("按到访次数排序").first().name)
        val preview = evidence.preview("地点按到访次数排序")
        assertTrue(preview.contains("按到访次数排序"))
        assertTrue(preview.indexOf("多次到访") < preview.indexOf("长停留"))
    }
}
