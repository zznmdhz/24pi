package com.twentyfourpi.lifelog.ui

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/** P1-D：范围解析口径（界面与查询同源、永不反向）。 */
class RangeResolutionTest {

    private val today = LocalDate.of(2026, 9, 11)

    @Test
    fun `range is clamped into enabled day and today`() {
        val (from, to) = resolveRangeBounds(
            from = LocalDate.of(2020, 1, 1),
            to = LocalDate.of(2026, 9, 11),
            startedFloor = LocalDate.of(2026, 8, 1),
            today = today,
        )
        assertEquals(LocalDate.of(2026, 8, 1), from)
        assertEquals(today, to)
    }

    @Test
    fun `future end date is clamped to today`() {
        val (from, to) = resolveRangeBounds(
            from = LocalDate.of(2026, 9, 5),
            to = LocalDate.of(2026, 12, 31),
            startedFloor = LocalDate.of(2026, 8, 1),
            today = today,
        )
        assertEquals(LocalDate.of(2026, 9, 5), from)
        assertEquals(today, to)
    }

    @Test
    fun `end before the enabled day never yields a reversed range`() {
        // 子代理复核 #1：启用日未知/较晚时选一段更早的日期，不能算出 from > to。
        val (from, to) = resolveRangeBounds(
            from = LocalDate.of(2026, 6, 1),
            to = LocalDate.of(2026, 6, 30),
            startedFloor = LocalDate.of(2026, 8, 1),
            today = today,
        )
        assertEquals(LocalDate.of(2026, 8, 1), from)
        assertEquals(LocalDate.of(2026, 8, 1), to)
    }

    @Test
    fun `single day range stays as selected`() {
        val day = LocalDate.of(2026, 9, 3)
        val (from, to) = resolveRangeBounds(day, day, LocalDate.of(2026, 8, 1), today)
        assertEquals(day, from)
        assertEquals(day, to)
    }
}
