package com.twentyfourpi.lifelog.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * F 批 5：首页轻量移动摘要缓存测试（方案 16.6 / T54 前置）。
 *
 * 覆盖：缓存命中不重算、forceRefresh 重算、修订版本失效、取消过期请求、
 * 数据截止时间语义、安全降级（计算中返回 null）。
 *
 * 注意：使用真实调度器 + runBlocking（runTest 虚拟时间与异步缓存 job 的
 * 真实执行竞态会导致偶发超时）。
 */
class EpisodeSummaryCacheTest {

    private val today = LocalDate.now()
    private val yesterday = today.minusDays(1)

    private fun episode(kind: DayEpisodeKind, startMs: Long, endMs: Long) = DayEpisode(
        id = "ep:$startMs:$endMs",
        startMs = startMs,
        endMs = endMs,
        kind = kind,
        placeId = null,
        reason = "test",
    )

    private fun cache(scope: CoroutineScope) = EpisodeSummaryCache(scope)

    @Test fun `clear publishes empty results so the home screen can let go of stale projections`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val c = cache(scope)
        c.getOrCompute(yesterday, isToday = false, forceRefresh = false) {
            listOf(episode(DayEpisodeKind.STAY, 1000, 2000))
        }
        c.awaitEntryForTest(yesterday)
        assertTrue("results must carry the computed entry", c.results.value.containsKey(yesterday))
        c.clear()
        // P3：清空必须能被订阅方看见（首页状态以这份快照为准，不能再留着旧投影）。
        assertTrue("clear must publish an empty snapshot", c.results.value.isEmpty())
        assertNull(c.entryForTest(yesterday))
    }

    @Test fun `revision invalidation rejects an old computation even after generation reuse`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val c = cache(scope)
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val finished = kotlinx.coroutines.CompletableDeferred<Unit>()
        c.getOrCompute(yesterday, false, false) {
            // Model a CPU calculation that completes after cancellation.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                started.complete(Unit)
                release.await()
                finished.complete(Unit)
                listOf(episode(DayEpisodeKind.STAY, 1000, 2000))
            }
        }
        started.await()
        c.bumpRevisionVersion()
        assertTrue(c.results.value.isEmpty())
        c.getOrCompute(yesterday, false, false) {
            listOf(episode(DayEpisodeKind.STAY, 1000, 9000))
        }
        c.awaitEntryForTest(yesterday)
        release.complete(Unit)
        finished.await()
        c.awaitUntil { !c.jobActive() }
        assertEquals(9000L, c.entryForTest(yesterday)!!.episodes.single().endMs)
        c.clear()
    }

    @Test fun `different dates do not cancel each other`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val c = cache(scope)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        c.getOrCompute(yesterday, isToday = false, forceRefresh = false) {
            started.complete(Unit)
            gate.await()
            listOf(episode(DayEpisodeKind.STAY, 1000, 2000))
        }
        started.await()
        // 另一个日期发起计算：按日期分槽，不得取消昨天那份在途计算（子代理复核 #7）。
        c.getOrCompute(yesterday.minusDays(1), isToday = false, forceRefresh = false) {
            listOf(episode(DayEpisodeKind.STAY, 3000, 4000))
        }
        gate.complete(Unit)
        // 两份计算都在 Dispatchers.Default 上异步跑：两个都要等就绪，
        // 不能在发起后立刻断言（高负载下会假红，0.17.3 门禁实测）。
        c.awaitEntryForTest(yesterday)
        c.awaitEntryForTest(yesterday.minusDays(1))
        assertNotNull("other dates must not cancel this one", c.entryForTest(yesterday))
        assertNotNull(c.entryForTest(yesterday.minusDays(1)))
    }

    @Test fun `cache hit does not recompute`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val c = cache(scope)
        var computeCount = 0

        c.getOrCompute(yesterday, isToday = false, forceRefresh = false) {
            computeCount++
            listOf(episode(DayEpisodeKind.STAY, 1000, 2000))
        }
        c.awaitEntryForTest(yesterday)
        assertEquals(1, computeCount)

        // 第二次：缓存命中，同步返回 Entry，不再触发计算。
        val second = c.getOrCompute(yesterday, isToday = false, forceRefresh = false) {
            computeCount++
            listOf(episode(DayEpisodeKind.STAY, 1000, 2000))
        }
        assertNotNull("cache hit should return entry synchronously", second)
        assertEquals("cache hit must not recompute", 1, computeCount)
    }

    @Test fun `force refresh recomputes`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val c = cache(scope)
        var value = 1

        c.getOrCompute(yesterday, isToday = false, forceRefresh = false) {
            listOf(episode(DayEpisodeKind.STAY, 1000, 2000L * value))
        }
        c.awaitEntryForTest(yesterday)

        value = 2
        // force 时立即返回 null（计算异步重启）。
        val immediate = c.getOrCompute(yesterday, isToday = false, forceRefresh = true) {
            listOf(episode(DayEpisodeKind.STAY, 1000, 2000L * value))
        }
        assertNull("force refresh is async", immediate)
        // 等待「新值已落缓存」这一终态，而不是等 computedAtMs 变化（高负载下会误报）。
        c.awaitUntil { c.entryForTest(yesterday)?.episodes?.single()?.endMs == 2000L * 2 }
        assertEquals(2000L * 2, c.entryForTest(yesterday)!!.episodes.single().endMs)
    }

    @Test fun `revision bump invalidates cache`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val c = cache(scope)
        var computeCount = 0

        c.getOrCompute(yesterday, isToday = false, forceRefresh = false) {
            computeCount++; listOf(episode(DayEpisodeKind.STAY, 1000, 2000))
        }
        c.awaitEntryForTest(yesterday)
        assertEquals(1, computeCount)

        // 地点纠正 → 修订版本递增 → 缓存失效重算。
        c.bumpRevisionVersion()
        val after = c.getOrCompute(yesterday, isToday = false, forceRefresh = false) {
            computeCount++; listOf(episode(DayEpisodeKind.STAY, 1000, 2000))
        }
        assertNull("after revision bump, compute must restart", after)
        c.awaitUntil { computeCount == 2 && c.entryForTest(yesterday) != null }
        assertEquals(2, computeCount)
    }

    @Test fun `today cached entry carries data cutoff`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val c = cache(scope)
        var computeCount = 0

        val now = System.currentTimeMillis()
        c.getOrCompute(today, isToday = true, forceRefresh = false) {
            computeCount++; listOf(episode(DayEpisodeKind.STAY, now - 60_000, now))
        }
        c.awaitEntryForTest(today)
        assertEquals(1, computeCount)
        assertEquals(now, c.entryForTest(today)!!.dataCutoffMs)
    }

    @Test fun `cache evicts oldest beyond capacity`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val c = cache(scope)
        var n = 0
        // 快速触发超过容量上限（maxCachedDates=30）的日期计算。
        val startDay = LocalDate.now().minusDays(40)
        for (i in 0 until 35) {
            val d = startDay.plusDays(i.toLong())
            c.getOrCompute(d, isToday = false, forceRefresh = false) {
                n++
                listOf(episode(DayEpisodeKind.STAY, 1000, 1000L * i))
            }
            c.awaitEntryForTest(d)
        }
        // R12：容量上限 30——最旧 5 个应被淘汰。
        assertTrue("cache should evict oldest ($startDay..), size=${c.size()}", c.size() <= 30)
        assertNull(c.entryForTest(startDay))
        assertNotNull(c.entryForTest(startDay.plusDays(34)))
    }

    @Test fun `results flow publishes computed entries`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val c = cache(scope)
        val yesterday = LocalDate.now().minusDays(1)
        c.getOrCompute(yesterday, isToday = false, forceRefresh = false) {
            listOf(episode(DayEpisodeKind.STAY, 1000, 2000))
        }
        c.awaitEntryForTest(yesterday)
        // R12：StateFlow 应包含该日期的结果（计算完成自动展示）。
        val published = c.results.value[yesterday]
        assertNotNull(published)
        assertEquals(1, published!!.episodes.size)
    }

    @Test fun `cancelled stale generation does not write cache`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val c = cache(scope)
        var computeCount = 0

        // 第一次 compute 用闸门挂起；第二次 force 会取消第一次（CancellationException），
        // 用新值写入。最终缓存内容必须来自第二次。
        val firstGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val firstStart = kotlinx.coroutines.CompletableDeferred<Unit>()

        // 第一次：开始计算但卡在闸门。
        val firstJob = scope.launch {
            c.getOrCompute(yesterday, isToday = false, forceRefresh = false) {
                computeCount++
                firstStart.complete(Unit)
                firstGate.await()
                listOf(episode(DayEpisodeKind.STAY, 1000, 2000))
            }
        }
        firstStart.await()
        // 等 new job 未被第二次替换前，缓存还没有条目。
        assertNull(c.entryForTest(yesterday))

        // 第二次：force，直接快速完成并写缓存。
        c.getOrCompute(yesterday, isToday = false, forceRefresh = true) {
            computeCount++
            listOf(episode(DayEpisodeKind.STAY, 5000, 6000))
        }
        // 断言终态数据（不是「时间戳变了」），彻底避开基线捕获窗口。
        c.awaitUntil { c.entryForTest(yesterday)?.episodes?.single()?.startMs == 5000L }

        // 放行第一次；它醒来后因 generation 已过期（1 != 2）不得覆盖缓存。
        firstGate.complete(Unit)
        firstJob.join()
        val entry = c.entryForTest(yesterday)!!
        assertEquals("cancelled job must not write", 5000L, entry.episodes.single().startMs)
        assertEquals("cancelled job must not write, computeCount=$computeCount", 2, computeCount)
    }

    @Test fun `historical date cutoff is next day midnight`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val c = cache(scope)
        c.getOrCompute(yesterday, isToday = false, forceRefresh = false) {
            listOf(episode(DayEpisodeKind.STAY, 1000, 2000))
        }
        c.awaitEntryForTest(yesterday)
        val expectedCutoff = yesterday.plusDays(1)
            .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expectedCutoff, c.entryForTest(yesterday)!!.dataCutoffMs)
    }

    @Test fun `clear drops cache`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val c = cache(scope)
        c.getOrCompute(yesterday, isToday = false, forceRefresh = false) { listOf(episode(DayEpisodeKind.STAY, 1000, 2000)) }
        c.awaitEntryForTest(yesterday)
        assertEquals(1, c.size())
        c.clear()
        assertEquals(0, c.size())
        assertNull(c.entryForTest(yesterday))
    }
}