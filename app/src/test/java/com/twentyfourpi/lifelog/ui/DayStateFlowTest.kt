package com.twentyfourpi.lifelog.ui

import com.twentyfourpi.lifelog.data.TimelineDay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * U03：日期与数据绑定竞态测试。
 *
 * 旧实现 TimelineDay() 初值没有日期；切日期瞬间旧数据/空初值会短暂冒充新日期。修复后
 * dayStateFlow 在切换时先发该日期的 loading 快照；flatMapLatest 取消旧订阅，慢查询的
 * 旧结果不会在切到新日期后再发出。测试不依赖 TimelineDay 内容，只校验状态序列的
 * date 与 loading 时序。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DayStateFlowTest {

    private val today = LocalDate.of(2026, 9, 9)
    private val yesterday = today.minusDays(1)

    private fun instantDay(): (LocalDate) -> Flow<TimelineDay> = { date ->
        flow { emit(TimelineDay()) }
    }

    /** 慢查询：延迟 [delayMs] 后才返回当天数据，用于制造“切换时旧查询仍在飞”的场景。 */
    private fun slowDay(delayMs: Long): (LocalDate) -> Flow<TimelineDay> = { _ ->
        flow {
            delay(delayMs)
            emit(TimelineDay())
        }
    }

    @Test
    fun `first emission is a loading snapshot bound to the selected date`() = runTest {
        val selectedDate = MutableStateFlow(today)
        val states = dayStateFlow(selectedDate, instantDay()).take(2).toList()

        assertEquals(2, states.size)
        assertEquals(today, states[0].date)
        assertTrue("切换后必须先出现 loading 快照", states[0].loading)
        assertEquals(today, states[1].date)
        assertFalse("数据到达后 loading 必须复位", states[1].loading)
    }

    @Test
    fun `date switch emits new-date loading before old slow query can finish`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val selectedDate = MutableStateFlow(yesterday)
        val collected = mutableListOf<DayUiState>()
        val job = launch(dispatcher) {
            dayStateFlow(selectedDate, slowDay(delayMs = 5_000)).toList(collected)
        }
        // 旧日期查询发出 loading 后正挂在 delay(5000) 上。
        runCurrent()
        assertEquals(yesterday, collected.last().date)
        assertTrue(collected.last().loading)

        // 数据还没回来就切到今天：flatMapLatest 应取消旧查询。
        selectedDate.value = today
        runCurrent()
        assertTrue(
            "切换后应立即出现今天的 loading 快照，而不是继续等昨天的慢查询",
            collected.last().date == today && collected.last().loading,
        )

        // 把虚拟时间推过旧查询的剩余延迟：旧 flow 已被取消，不应再发出昨天数据。
        advanceTimeBy(6_000)
        runCurrent()
        job.cancel()

        val afterSwitch = collected.dropWhile { it.date == yesterday }
        assertTrue("慢查询返回不得混入已切换后的新日期", afterSwitch.all { it.date == today })
        // 序列里必须同时包含“今天 loading”与“今天数据完成”两个阶段。
        assertTrue(afterSwitch.any { it.loading })
        assertTrue(afterSwitch.any { !it.loading })
    }

    @Test
    fun `loading snapshot is empty but not final empty-data state`() = runTest {
        val selectedDate = MutableStateFlow(today)
        val first = dayStateFlow(selectedDate, slowDay(delayMs = 1_000)).first()

        // loading=true 表示读取中，UI 不能把它当成“无记录”空态。
        assertTrue(first.loading)
        assertEquals(today, first.date)
    }

    @Test
    fun `observe emits only states bound to the requested date`() = runTest {
        val selectedDate = MutableStateFlow(today)
        val states = dayStateFlow(selectedDate, instantDay()).take(2).toList()
        states.forEach { assertEquals(today, it.date) }

        selectedDate.value = yesterday
        val next = dayStateFlow(selectedDate, instantDay()).take(2).toList()
        next.forEach { assertEquals(yesterday, it.date) }
    }
}
