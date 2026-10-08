package com.twentyfourpi.lifelog.ui

import java.time.LocalDate

/**
 * P1-D：日期范围解析（纯函数，便于单测）。
 *
 * 界面（可选的起止日期、索引/汇总的统计区间）与查询（RecordExplorer、搜索）必须共用同一套口径：
 * 起止都收敛到 启用日至今天，且**永不产生 from > to 的反向区间**——
 * 子代理复核 #1 正是在「启用日未知」时发现界面兜 2020-01-01、查询兜「今天」，
 * 自选一段历史日期会被裁成反向区间。
 */
internal fun resolveRangeBounds(
    from: LocalDate,
    to: LocalDate,
    startedFloor: LocalDate,
    today: LocalDate,
): Pair<LocalDate, LocalDate> {
    val fromResolved = maxOf(from, startedFloor).coerceAtMost(today)
    val toResolved = to.coerceAtMost(today).coerceAtLeast(fromResolved)
    return fromResolved to toResolved
}
