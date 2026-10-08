package com.twentyfourpi.lifelog.ui

import com.twentyfourpi.lifelog.data.GapProjection
import com.twentyfourpi.lifelog.data.GapProjectionRules
import com.twentyfourpi.lifelog.data.GapSupport
import com.twentyfourpi.lifelog.data.SourceId
import com.twentyfourpi.lifelog.util.formatClock

/**
 * 缺口投影的展示规则（第四批）。
 *
 * 复核稿第 6.3 节的两条病根：
 * 1. 原始缺口与卡片相交时，按真正未知的交集给分钟数，只有实质缺失才降级成红卡；
 * 2. 卡片描述与详情重复追加同一句警告——这里保证一屏只出现一次，剩下的解释放详情。
 *
 * 措辞铁律：只说"定位信息不足"，不说"数据失效"；不同来源不互相宣判（通知/应用/地点分开描述）。
 */

/** 卡片级缺口提示。 */
data class CardGapNotice(
    /** 与卡片真正相交的未知时长。 */
    val unknownMs: Long,
    /** 是否达到"实质缺失"（红卡）门槛。 */
    val severe: Boolean,
    /** 卡片副标题用的一句话（弱提示）。 */
    val summary: String,
    /** 详情用解释（含构成，避免与卡片重复同一句）。 */
    val detail: String,
)

/** 一天（一段窗口）的缺口提示。 */
data class DayGapNotice(
    val unknownMs: Long,
    /** 是否还有需要用户处理的采集/权限故障。 */
    val actionable: Boolean,
    val summary: String,
    val spans: List<String>,
    val detail: String,
)

/**
 * 生成卡片提示；没有交集返回 null（绝大多数卡片都该是 null）。
 *
 * 红卡门槛：未知占比 ≥ 50% 或未知时长 ≥ 30 分钟。一段 2 小时 51 分的卡里缺 7 分 36 秒，
 * 不该整张红。
 */
fun cardGapNotice(
    cardStartMs: Long,
    cardEndMs: Long,
    projection: GapProjection,
    locationSource: String = SourceId.LOCATION.name,
): CardGapNotice? {
    if (cardEndMs <= cardStartMs) return null
    val overlap = projection.unknownOverlapMs(cardStartMs, cardEndMs, locationSource)
    if (overlap <= 0L) return null
    val cardMs = cardEndMs - cardStartMs
    // 单一口径：严重度判定在数据层（界面与诊断导出共用），这里不重复实现阈值。
    val severe = projection.isSevereCard(cardStartMs, cardEndMs, locationSource)
    val parts = projection.unknownOverlapIntervals(cardStartMs, cardEndMs, locationSource)
    val duration = formatGapSpan(overlap)
    val coversWholeCard = parts.size == 1 &&
        parts.first().startMs <= cardStartMs + 1_000L &&
        parts.first().endMs >= cardEndMs - 1_000L
    val position = when {
        coversWholeCard -> "全程"
        parts.firstOrNull()?.let { it.startMs <= cardStartMs + 1_000L } == true -> "开头"
        parts.lastOrNull()?.let { it.endMs >= cardEndMs - 1_000L } == true -> "末尾"
        else -> "中段"
    }
    val summary = when {
        coversWholeCard -> "$duration 定位信息不足"
        severe -> "本段 $duration 定位信息不足"
        else -> "$position$duration 定位信息不足"
    }
    val detail = buildString {
        append("本段 ")
        append(formatGapSpan(cardMs))
        if (coversWholeCard) {
            append(" 都没有可用于确认地点的定位信息")
        } else {
            append(" 中有 ")
            append(if (position == "全程") "" else position)
            append(duration)
            append(" 定位信息不足，其余时间有记录")
        }
        append("。原始定位点与到访记录都保留，可在诊断导出中回看。")
    }
    return CardGapNotice(unknownMs = overlap, severe = severe, summary = summary, detail = detail)
}

/**
 * 生成一天的缺口提示。
 *
 * 只有**仍需用户处理**的故障（权限、服务被关闭、写库失败等）才算"不完整"并进顶部提示；
 * 已恢复的采集空档用中性措辞，不再把整天判成失败。
 */
fun dayGapNotice(
    projection: GapProjection,
    locationSource: String = SourceId.LOCATION.name,
): DayGapNotice? {
    val locationUnknownMs = projection.unknownMsFor(locationSource)
    val actionableOther = projection.actionableGaps().filterNot { it.source.equals(locationSource, ignoreCase = true) }
    if (locationUnknownMs <= 0L && actionableOther.isEmpty()) return null
    val spans = projection.unknownIntervalsFor(locationSource).map { interval ->
        "${formatClock(interval.startMs)}–${formatClock(interval.endMs)}（${formatGapSpan(interval.durationMs)}）"
    }
    val total = formatGapSpan(locationUnknownMs)
    val locationActionable = projection.actionableGaps(locationSource).isNotEmpty()
    return when {
        locationActionable -> DayGapNotice(
            unknownMs = locationUnknownMs,
            actionable = true,
            summary = "定位出现需要处理的故障，共 $total 未记录",
            spans = spans,
            detail = "这类中断由权限或系统服务状态造成，需要你确认后才会恢复；原始记录已保留。",
        )

        locationUnknownMs > 0L -> DayGapNotice(
            unknownMs = locationUnknownMs,
            actionable = false,
            summary = "有 $total 定位信息不足（采集已恢复）",
            spans = spans,
            detail = "这些空档里没有可用于确认地点的定位信息；其余时间有实测停留或到访证据，原始记录完整保留。",
        )

        else -> DayGapNotice(
            unknownMs = 0L,
            actionable = true,
            summary = "有其它记录来源需要处理",
            spans = actionableOther.map { gap ->
                "${gap.source}：${formatClock(gap.rawStartMs)}–${formatClock(gap.rawEndMs)}（${formatGapSpan(gap.unknownMs)}）"
            },
            detail = "定位记录正常；以下来源仍有需要确认的故障。各来源分别统计，不互相判定数据失效。",
        )
    }
}

/** 缺口语境下的时长：带秒，避免"7 分 36 秒"被吞成"8 分钟"。 */
fun formatGapSpan(ms: Long): String {
    val safe = ms.coerceAtLeast(0L)
    if (safe < 60_000L) return "不足1分钟"
    val totalSeconds = safe / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return when {
        hours > 0L && minutes > 0L -> "${hours}小时${minutes}分"
        hours > 0L -> "${hours}小时"
        seconds > 0L && minutes < 10L -> "${minutes}分${seconds}秒"
        else -> "${minutes}分钟"
    }
}

/** 证据口径的解释文案（详情里用，说明三态是什么意思）。 */
fun supportLegend(support: GapSupport): String = when (support) {
    GapSupport.MEASURED -> "实测停留"
    GapSupport.INFERRED -> "有限推断"
    GapSupport.UNKNOWN -> "无定位信息"
}
