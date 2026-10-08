@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.twentyfourpi.lifelog.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.LocationOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.twentyfourpi.lifelog.data.DailyRoute
import com.twentyfourpi.lifelog.data.IMPORTANT_DAILY_TOTAL_MS
import com.twentyfourpi.lifelog.data.IMPORTANT_SINGLE_GAP_MS
import com.twentyfourpi.lifelog.data.RouteInterruption
import com.twentyfourpi.lifelog.data.RoutePoint
import com.twentyfourpi.lifelog.data.RouteStop
import com.twentyfourpi.lifelog.data.RouteTrip
import com.twentyfourpi.lifelog.util.formatClock
import com.twentyfourpi.lifelog.util.formatDuration
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * 时间页「全天轨迹」区块（v0.16.2）。
 *
 * v0.16.1 反馈：一段完整驾车行程被拆成十几个 0米/0秒 碎段（同毫秒多源点、跨源时钟
 * 抖动、红灯微停留被各自成段）。v0.16.2 重建语义：**行程（trip）** 是一等公民——
 * 真实停留（≥5 分钟原地）或大位移数据空洞才是行程边界；红灯/微停留折叠为「途经停留」，
 * 短断档是行程内部虚线。原始 sections 层保留用于诊断导出与 Canvas 绘制。
 */
@Composable
internal fun DailyRouteSection(
    viewModel: MainViewModel,
    isToday: Boolean,
    route: DailyRoute?,
    loading: Boolean,
    /** R07：明确刷新入口；刷新保留旧图，成功才整体替换。 */
    onRefresh: (() -> Unit)? = null,
    /** U10/T10：数据截止时间（今天=加载完成时刻；历史=当天最后记录）。 */
    dataCutoffMs: Long? = null,
    onOpenTrips: (() -> Unit)? = null,
    onOpenLocation: (() -> Unit)? = null,
    onOpenTrip: ((String) -> Unit)? = null,
) {
    var selectedTripKey by remember(route) { mutableStateOf<String?>(null) }
    val selectedTrip = route?.trips?.firstOrNull { it.key == selectedTripKey }
    InstrumentPanel {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(if (isToday) "今日行程" else "当天行程", style = MaterialTheme.typography.titleLarge)
                    val subtitle = when {
                        route != null -> "${route.trips.size} 段行程 · ${formatDistance(route.trips.sumOf { it.distanceMeters })}"
                        loading -> "正在读取当天轨迹…"
                        else -> "当天还没有可显示的行程"
                    }
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isToday && onOpenLocation != null) {
                    IconButton(onClick = onOpenLocation) {
                        Icon(Icons.Outlined.LocationOn, "当前位置", tint = MaterialTheme.colorScheme.primary)
                    }
                }
                if (onRefresh != null) {
                    IconButton(onClick = onRefresh, enabled = !loading) {
                        Icon(Icons.Outlined.Refresh, "刷新行程")
                    }
                }
                if (onOpenTrips != null) {
                    IconButton(onClick = onOpenTrips) {
                        Icon(Icons.Outlined.ChevronRight, "行程地图与历史")
                    }
                }
            }
            when {
                route == null && loading -> Text("正在读取当天原始定位…", style = MaterialTheme.typography.bodyMedium)
                route == null -> Text("暂时没有轨迹；可以稍后刷新。", style = MaterialTheme.typography.bodyMedium)
                route.sections.isEmpty() -> Text(
                    "这一天还没有足够的定位点形成轨迹。原始定位记录仍会永久保存。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                else -> Row(
                    Modifier.fillMaxWidth().height(248.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(
                        Modifier.weight(.92f).fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        if (route.trips.isEmpty()) {
                            Text(
                                "没有形成行程",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            route.trips.take(5).forEachIndexed { index, trip ->
                                CompactTripRow(
                                    index = index + 1,
                                    trip = trip,
                                    selected = selectedTripKey == trip.key,
                                    onClick = { selectedTripKey = if (selectedTripKey == trip.key) null else trip.key },
                                )
                            }
                            if (route.trips.size > 5 && onOpenTrips != null) {
                                TextButton(onClick = onOpenTrips) { Text("其余 ${route.trips.size - 5} 段") }
                            }
                        }
                    }
                    Surface(
                        Modifier.weight(1.08f).fillMaxHeight(),
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        AmapRouteSurface(
                            viewModel = viewModel,
                            sections = selectedTrip?.sections ?: route.sections,
                            gaps = selectedTrip?.interruptions ?: route.interruptions,
                            modifier = Modifier.fillMaxWidth().fillMaxHeight().clip(RoundedCornerShape(18.dp)),
                            livePoint = null,
                            compact = true,
                            onOpen = selectedTrip?.let { trip -> onOpenTrip?.let { open -> { open(trip.key) } } } ?: onOpenTrips,
                        )
                    }
                }
            }
            if (route != null && dataCutoffMs != null) Text(
                "${if (selectedTrip == null) "显示全天轨迹" else "已聚焦第 ${route.trips.indexOf(selectedTrip) + 1} 段"} · 数据截至 ${formatClock(dataCutoffMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CompactTripRow(index: Int, trip: RouteTrip, selected: Boolean, onClick: () -> Unit) {
    val motion = LocalMotionPreference.current
    val background by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        animationSpec = motion.spec(150),
        label = "trip-selection",
    )
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick),
        color = background,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 5.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Surface(Modifier.size(19.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primary) {}
                Text(index.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "${formatClock(trip.first.timeMs)}–${formatClock(trip.last.timeMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
                Text(
                    "${trip.startPlaceName ?: "起点"} → ${trip.endPlaceName ?: "终点"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Text(compactDistance(trip.distanceMeters), style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

private fun compactDistance(meters: Double): String = if (meters < 1_000) {
    "${meters.toInt()}m"
} else {
    String.format(Locale.CHINA, "%.1fkm", meters / 1_000.0)
}

@Composable
private fun DailyRouteContent(route: DailyRoute, isToday: Boolean, dataCutoffMs: Long? = null, onOpenTrip: ((String) -> Unit)? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LocalRoutePreview(route)
        // U10/T10：显示数据截止时间，用户知道看到的轨迹最晚覆盖到几点。
        if (dataCutoffMs != null) {
            Text(
                "数据截至 ${formatClock(dataCutoffMs)} · 本机生成，不使用在线地图",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                "当前显示完全本机的轨迹预览，不使用任何在线地图服务。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        RouteEndpointRow("当天首次记录", route.firstRecorded!!)
        // R16：历史日末点没有可靠“确认停留”依据时叫“最后记录位置”，不冒充最终停留。
        RouteEndpointRow(if (isToday) "当前记录位置" else "最后记录位置", route.lastRecorded!!)
        InterruptionSummary(route.interruptions)
        HorizontalDivider()
        Text("行程明细", style = MaterialTheme.typography.titleSmall)
        if (route.trips.isEmpty()) {
            Text(
                "这一天没有形成出行行程（原地记录或数据空洞为主）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        route.trips.forEachIndexed { index, trip ->
            TripRow(index = index + 1, trip = trip, onClick = onOpenTrip?.let { open -> { open(trip.key) } })
        }
        if (route.majorStops.isNotEmpty()) {
            HorizontalDivider()
            Text("真实停留", style = MaterialTheme.typography.titleSmall)
            route.majorStops.forEach { stop ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text(
                            "${formatClock(stop.startMs)} – ${formatClock(stop.endMs)} · ${formatDuration(stop.durationMs)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        stop.placeName?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TripRow(index: Int, trip: RouteTrip, onClick: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick).padding(vertical = 8.dp) else Modifier)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("第 $index 段行程", style = MaterialTheme.typography.labelLarge)
                Text(
                    "开始 ${formatClock(trip.first.timeMs)} · 结束 ${formatClock(trip.last.timeMs)} · ${formatDuration(trip.durationMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val startName = trip.startPlaceName
                val endName = trip.endPlaceName
                // U12/R16：两端同名也显示完整“家 → 家”（从家出发回到家）；
                // 一端未知标出是哪端。
                val endpoints = when {
                    startName != null && endName != null -> "$startName → $endName"
                    startName != null -> "起点 $startName"
                    endName != null -> "终点 $endName"
                    else -> null
                }
                if (endpoints != null) {
                    Text(
                        endpoints,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (trip.stops.isNotEmpty()) {
                    Text(
                        "途经停留 ${trip.stops.size} 次",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // R16：默认显示前 3 次，超出可展开查看全部（不再隐藏第 4 次以后）。
                var stopsExpanded by remember { mutableStateOf(false) }
                val visibleStops = if (stopsExpanded) trip.stops else trip.stops.take(3)
                visibleStops.forEach { stop ->
                    Text(
                        "  · ${formatClock(stop.startMs)} 停留 ${formatDuration(stop.durationMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (trip.stops.size > 3) {
                    TextButton(onClick = { stopsExpanded = !stopsExpanded }) {
                        Text(if (stopsExpanded) "收起途经明细" else "查看全部 ${trip.stops.size} 次停留")
                    }
                }
            }
            Text(formatDistance(trip.distanceMeters) + if (onClick != null) "　›" else "", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** 折叠态摘要：只显示一行统计；整行可点击展开逐条中断明细。 */
@Composable
private fun InterruptionSummary(interruptions: List<RouteInterruption>) {
    if (interruptions.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val totalMs = interruptions.sumOf { it.durationMs }
    // U11/T08：接入时间页同样的影响规则——单条≥10 分钟或累计≥15 分钟才算
    // IMPORTANT（红卡），普通短中断降为中性提示，整页不再像故障报告。
    val important = interruptions.any { it.durationMs >= IMPORTANT_SINGLE_GAP_MS } ||
        totalMs >= IMPORTANT_DAILY_TOTAL_MS
    val container = if (important) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHighest
    val content = if (important) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(color = container, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth()) {
            val toggle = { expanded = !expanded }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = toggle)
                    .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Outlined.LocationOff, null, tint = content)
                Text(
                    "当天 ${interruptions.size} 次定位中断，共 ${formatDuration(totalMs)}（虚线不计入里程）",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    if (expanded) "收起中断明细" else "展开中断明细",
                    tint = content,
                )
            }
            if (expanded) {
                Column(Modifier.padding(start = 40.dp, end = 12.dp, bottom = 12.dp)) {
                    interruptions.forEach { interruption ->
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text(
                                "定位在 ${formatClock(interruption.lostAt.timeMs)} 中断",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                "${formatClock(interruption.recoveredAt.timeMs)} 恢复 · ${formatDuration(interruption.durationMs)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RouteEndpointRow(label: String, point: RoutePoint) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(formatClock(point.timeMs), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun LocalRoutePreview(route: DailyRoute) {
    val primary = MaterialTheme.colorScheme.primary
    val error = MaterialTheme.colorScheme.error
    val tertiary = MaterialTheme.colorScheme.tertiary
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Canvas(Modifier.fillMaxWidth().height(230.dp).padding(18.dp)) {
            val points = route.sections.flatMap { it.points }
            if (points.isEmpty()) return@Canvas
            val minLat = points.minOf { it.latitude }
            val maxLat = points.maxOf { it.latitude }
            val minLon = points.minOf { it.longitude }
            val maxLon = points.maxOf { it.longitude }
            // U12/T12：横纵使用同一缩放系数（避免 latSpan/lonSpan 独立拉伸导致形状变形）。
            // 经纬度 1°≈111km，比例换算后保证等距测试直线不变形；用统一 scale 控制适配画布。
            val equatorMetersPerDeg = 111_320.0
            val latSpanMeters = (maxLat - minLat).coerceAtLeast(0.0001) * equatorMetersPerDeg
            val lonSpanMeters = (maxLon - minLon).coerceAtLeast(0.0001) * equatorMetersPerDeg * cos(minLat * PI / 180.0)
            val scale = min(
                size.width / lonSpanMeters.coerceAtLeast(1.0),
                size.height / latSpanMeters.coerceAtLeast(1.0),
            )
            // 居中后绘制：保持比例，上下左右居中裁切。
            val drawW = (lonSpanMeters * scale).toFloat()
            val drawH = (latSpanMeters * scale).toFloat()
            val offsetX = (size.width - drawW) / 2f
            val offsetY = (size.height - drawH) / 2f
            fun xy(point: RoutePoint) = Offset(
                x = offsetX + (((point.longitude - minLon) * equatorMetersPerDeg * cos(minLat * PI / 180.0)) * scale).toFloat(),
                y = offsetY + (drawH - ((point.latitude - minLat) * equatorMetersPerDeg) * scale).toFloat(),
            )
            route.sections.forEach { section ->
                section.observedEdges.forEach { (a, b) ->
                    drawLine(primary, xy(a), xy(b), strokeWidth = 8f, cap = StrokeCap.Round)
                }
            }
            route.interruptions.forEach { gap ->
                drawLine(
                    error, xy(gap.lostAt), xy(gap.recoveredAt), strokeWidth = 5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 12f)),
                )
                drawCircle(error, 11f, xy(gap.lostAt), style = Stroke(width = 5f))
            }
            // 途经停留：行程内部原地记录（红灯/接送），空心圆标注。
            route.trips.flatMap { it.stops }.forEach { stop ->
                drawCircle(tertiary, 9f, xy(RoutePoint(0L, stop.startMs, stop.latitude, stop.longitude, 0f)), style = Stroke(width = 4f))
            }
            route.firstRecorded?.let { drawCircle(primary, 12f, xy(it)) }
            route.lastRecorded?.let { drawCircle(primary, 15f, xy(it), style = Stroke(width = 6f)) }
        }
    }
}

internal fun formatDistance(meters: Double): String = when {
    meters < 1_000 -> "${meters.toInt()} 米"
    else -> String.format(Locale.CHINA, "%.1f 公里", meters / 1_000.0)
}
