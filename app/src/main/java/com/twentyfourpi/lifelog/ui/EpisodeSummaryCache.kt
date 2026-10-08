package com.twentyfourpi.lifelog.ui

import com.twentyfourpi.lifelog.debug.DiagnosticLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * F 批（方案 16.6）：首页轻量移动摘要缓存。
 *
 * 目标：首页需要“哪段时间在路上”时，不重新订阅全天原始点 Flow、不依赖用户先打开地图，
 * 也不每秒全量重算历史（v0.16.0 发热事故根因之一）。
 *
 * 规则：
 * - 进入目标日期或手动刷新时只触发一次**缺失/过期**摘要计算。
 * - 有界缓存按日期保存；今天每次都强制刷新（反映最新记录），历史日保持快照。
 * - 地点纠正（appliedRevisionVersion 变更）只使受影响日期投影失效，不扫全库。
 * - 计算在 [scope] 后台执行，新请求取消进行中的旧请求（generation 隔离）。
 * - 数据截止时间 dataCutoffMs：卡片/图表展示“数据截止 XX:XX”。
 */
class EpisodeSummaryCache(
    private val scope: CoroutineScope,
) {
    class Entry(
        val date: LocalDate,
        val episodes: List<DayEpisode>,
        val computedAtMs: Long,
        val dataCutoffMs: Long,
        val projectionVersion: Int,
    )

    private data class CacheValue(
        val entry: Entry,
        val revisionVersionAtCompute: Int,
    )

    private val cache = linkedMapOf<LocalDate, CacheValue>()
    // P3：按日期分槽的单飞——此前全局只有一个槽，快速翻页会取消**别的日期**的计算，
    // 被取消的那天整场会话都落不到缓存、只能一直看旧算法兜底（子代理复核 #7）。
    //
    // 0.17.4：下面三张表（cache/jobs/dateGenerations）过去只在 @Synchronized 的方法里被改，
    // 而**协程体**（Dispatchers.Default）也在改它们——多日期并发计算时是典型的非同步 Map
    // 竞争，轻则丢条目、重则协程内部抛异常被 catch(Throwable) 静默吞掉，那天的投影永远
    // 不会就绪（共享缓存更新必须同步）。
    // 现在所有读写都必须持有同一把锁（= this，与 @Synchronized 方法一致）。
    private val jobs = mutableMapOf<LocalDate, Job>()
    private val dateGenerations = mutableMapOf<LocalDate, Long>()

    // R12：可观察加载结果（日期绑定）——计算完成自动推送给 UI，不用轮询。
    private val _results = MutableStateFlow<Map<LocalDate, Entry>>(emptyMap())
    val results: StateFlow<Map<LocalDate, Entry>> = _results.asStateFlow()

    /** R12：缓存容量上限（按日期数淘汰；超出时淘汰最旧条目）。 */
    val maxCachedDates: Int = 30

    /** 当前修订投影版本（地点纠正后由外部通知递增）。 */
    var revisionVersion: Int = 0
        private set

    @Synchronized
    fun bumpRevisionVersion() {
        revisionVersion++
        clear()
        DiagnosticLog.event("episode", "summary_invalidated", mapOf("revision_version" to revisionVersion))
    }

    /**
     * 获取某日摘要；缺失或过期时触发后台计算。
     * [compute] 由调用方提供（Repository 一次性读取有界范围后调用 [DayEpisodeBuilder]）。
     * 返回 null 表示计算尚未完成（调用方应显示“已有到访/活动”的安全降级）。
     */
    @Synchronized
    fun getOrCompute(
        date: LocalDate,
        isToday: Boolean,
        forceRefresh: Boolean,
        compute: suspend (LocalDate) -> List<DayEpisode>,
    ): Entry? {
        val existing = cache[date]
        val cacheHit = existing != null &&
            existing.revisionVersionAtCompute == revisionVersion &&
            !forceRefresh && !(isToday && existing.entry.dataCutoffMs < System.currentTimeMillis())

        if (cacheHit) {
            DiagnosticLog.event("episode", "summary_cache_hit", mapOf(
                "date" to date.toString(),
                "cutoff" to existing!!.entry.dataCutoffMs,
            ))
            return existing!!.entry
        }

        // 缓存缺失/过期：启动（或重启）计算；只取消**同一天**进行中的旧请求。
        val myGeneration = (dateGenerations[date] ?: 0L) + 1
        dateGenerations[date] = myGeneration
        jobs[date]?.cancel()
        val startedAtMs = System.currentTimeMillis()
        val revisionAtStart = revisionVersion
        DiagnosticLog.event("episode", "summary_compute_start", mapOf(
            "date" to date.toString(),
            "generation" to myGeneration,
            "reason" to when {
                existing == null -> "missing"
                forceRefresh -> "force"
                else -> "stale-or-today"
            },
        ))
        jobs[date] = scope.launch {
            try {
                val episodes = compute(date)
                // 0.17.4：写缓存这段必须持锁——getOrCompute/clear/entryOrNull 都在同一把锁上，
                // 否则多日期并发计算会同时改同一张 linkedMapOf。
                synchronized(this@EpisodeSummaryCache) {
                    // 过期请求（期间又发起新计算）不写缓存。
                    if (dateGenerations[date] != myGeneration || revisionVersion != revisionAtStart) {
                        DiagnosticLog.event("episode", "summary_cancelled", mapOf(
                            "date" to date.toString(),
                            "generation" to myGeneration,
                            "superseded_by" to (dateGenerations[date] ?: 0L),
                        ))
                        return@launch
                    }
                    val cutoff = cutoffFor(date, episodes)
                    val epochNow = System.currentTimeMillis()
                    cache[date] = CacheValue(
                        Entry(date, episodes, epochNow, cutoff, revisionVersion),
                        revisionVersionAtCompute = revisionVersion,
                    )
                    // R12：容量淘汰——超出上限时移除最旧条目（linkedMapOf 插入序）。
                    while (cache.size > maxCachedDates) {
                        cache.remove(cache.keys.first())
                    }
                    // R12：可观察结果推送（计算完成自动展示；快速切日不会串数据）。
                    _results.value = cache.mapValues { it.value.entry }
                    DiagnosticLog.event("episode", "summary_computed", mapOf(
                        "date" to date.toString(),
                        "segments" to episodes.size,
                        "elapsed_ms" to (System.currentTimeMillis() - startedAtMs),
                        "cutoff" to cutoff,
                        "cache_size" to cache.size,
                    ))
                }
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t // R12：取消不是失败，不记录
            } catch (t: Throwable) {
                DiagnosticLog.error("episode", "summary_compute_failed", t)
            } finally {
                // 只有仍是当前代次的那个任务才把自己从槽位摘掉（避免摘掉后来者的槽）。
                synchronized(this@EpisodeSummaryCache) {
                    if (dateGenerations[date] == myGeneration && revisionVersion == revisionAtStart) jobs.remove(date)
                }
            }
        }
        return null
    }

    /** 数据截止时刻：今天取最新 episode 的 endMs（否则 System.currentTimeMillis 亦可用），历史取次日 0 点。 */
    private fun cutoffFor(date: LocalDate, episodes: List<DayEpisode>): Long {
        val today = LocalDate.now()
        if (date == today) {
            val lastEnd = episodes.maxOfOrNull { it.endMs }
            return lastEnd ?: System.currentTimeMillis()
        }
        return date.plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    /** 使缓存全部失效（地点批量修订/设置变化时）——不删除任何历史档案，只影响投影。 */
    @Synchronized
    fun clear() {
        val sizeBefore = cache.size
        cache.clear()
        _results.value = emptyMap()
        // 取消所有在途计算；日期代次清掉后，它们的写入校验必然失败（不会覆盖空缓存）。
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        dateGenerations.clear()
        DiagnosticLog.event("episode", "summary_cache_cleared", mapOf("size_before" to sizeBefore))
    }

    @Synchronized
    fun size(): Int = cache.size

    /** 是否还有在途计算（按日期分槽，任一在跑即为真）。 */
    @Synchronized
    fun jobActive(): Boolean = jobs.values.any { it.isActive }

    /**
     * P3：已就绪的缓存条目（不触发计算；失效的旧条目返回 null）。
     * 首页取投影、诊断导出复算都从这里拿，避免同一份数据出现两套口径。
     */
    @Synchronized
    fun entryOrNull(date: LocalDate): Entry? =
        cache[date]?.takeIf { it.revisionVersionAtCompute == revisionVersion }?.entry

    /** 仅测试用（同模块）：读取缓存条目（失效的旧条目返回 null）。生产代码请走 [getOrCompute] / [entryOrNull]。 */
    internal fun entryForTest(date: LocalDate): Entry? = entryOrNull(date)

    /**
     * 测试等待的默认上限：**这是安全网，不是速度断言**。
     *
     * 曾经的 2s/5s 在门禁里会假红——单测与 lint/R8 并发抢 CPU 时，Dispatchers.Default 上
     * 一个平凡的 compute 也可能排队超过 2 秒（0.17.3 门禁连续两次不同用例失败，
     * 独立跑 5 轮全绿）。正常路径命中即返回，不会因此变慢。
     */
    private companion object {
        const val DEFAULT_AWAIT_TIMEOUT_MS = 30_000L
    }

    /**
     * 仅测试用（同模块）：等待异步计算完成（条目首次就绪即可）。
     * 需要「重算后的新值」时用 [awaitUntil] 断言终态——曾经的 `expectRefresh` 靠
     * computedAtMs 变化判断，基线只能在发起重算之后读取，高负载下会误报（已删除该参数）。
     */
    internal suspend fun awaitEntryForTest(
        date: LocalDate,
        timeoutMs: Long = DEFAULT_AWAIT_TIMEOUT_MS,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (entryForTest(date) != null) return
            kotlinx.coroutines.delay(10)
        }
        throw AssertionError("episode summary for $date never became available")
    }

    /**
     * 仅测试用（同模块）：轮询直到 [condition] 成立，超时抛 [AssertionError]。
     *
     * 优先用它，而不是等「computedAtMs 变化」：时间戳基线必须在发起重算**之前**捕获，
     * 否则重算抢在等待函数读基线前写完缓存就会取到新值，「必须变化」永远不成立 →
     * 5 秒后误报（门禁里 lint/R8 并发抢 CPU 时偶发；单跑 5 轮全绿）。断言终态数据既没有
     * 这个窗口，也比「时间戳变了」更贴近真正要保证的事。
     */
    internal suspend fun awaitUntil(
        timeoutMs: Long = DEFAULT_AWAIT_TIMEOUT_MS,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            kotlinx.coroutines.delay(10)
        }
        throw AssertionError("condition not met within ${timeoutMs}ms")
    }
}