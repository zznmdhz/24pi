package com.twentyfourpi.lifelog.ui

import android.graphics.Paint as NativePaint
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.twentyfourpi.lifelog.util.formatClock
import com.twentyfourpi.lifelog.util.formatDuration
import kotlin.math.min

/** 历史完整度只描述所选时间范围，不代表采集器此刻是否健康。 */
enum class ArchiveCoverageKind {
    COMPLETE,
    BASIC,
    INCOMPLETE,
    PAUSED,
    NOT_STARTED,
    UNKNOWN,
}

@Immutable
data class ArchiveCoverageStatus(
    val label: String,
    val kind: ArchiveCoverageKind,
)

/** 时间档案的三条证据轨道。 */
enum class ArchiveLane(val label: String) {
    PLACE("地点"),
    APPLICATION("应用"),
    NOTIFICATION("通知"),
}

@Immutable
data class ArchiveSpan(
    val startMs: Long,
    val endMs: Long,
    val label: String = "",
)

@Immutable
data class ArchiveNotificationTick(
    val atMs: Long,
    val count: Int = 1,
)

@Immutable
data class ArchiveGap(
    val startMs: Long,
    val endMs: Long,
    val lanes: Set<ArchiveLane> = ArchiveLane.entries.toSet(),
)

/**
 * 日期或日期范围的统一标题。历史完整度保持紧凑，详细缺口由时间带负责表达。
 * 传入 null 的动作不会显示相应按钮，避免不可操作的装饰图标。
 */
@Composable
fun DateRangeHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    coverage: ArchiveCoverageStatus? = null,
    onCoverageClick: (() -> Unit)? = null,
    onPrevious: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
    onCalendar: (() -> Unit)? = null,
    /** v0.15 UX P0-2：非今日时显示"回到今天"，修复翻页后回不去的迷失。 */
    onToday: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Column(
                Modifier
                    .weight(1f)
                    // U06：日期文字本身可点击打开日历（触控目标 ≥48dp），不再只是右侧小日历图标。
                    .then(
                        if (onCalendar != null) {
                            Modifier
                                .heightIn(min = 48.dp)
                                .clickable(onClick = onCalendar)
                                .semantics { contentDescription = "$title，点击选择日期"; role = Role.Button }
                        } else Modifier,
                    ),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null || coverage != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        subtitle?.takeIf(String::isNotBlank)?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        coverage?.let {
                            ArchiveCoverageLabel(
                                status = it,
                                onClick = onCoverageClick,
                            )
                        }
                    }
                }
            }
            onPrevious?.let { action ->
                IconButton(onClick = action, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.ChevronLeft, "上一时间范围")
                }
            }
            onNext?.let { action ->
                IconButton(onClick = action, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.ChevronRight, "下一时间范围")
                }
            }
            onCalendar?.let { action ->
                IconButton(onClick = action, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.CalendarMonth, "选择日期")
                }
            }
            onToday?.let { action ->
                // v0.15 UX P0-2：查看历史日期时提供一步"回到今天"
                TextButton(onClick = action, modifier = Modifier.heightIn(min = 40.dp)) {
                    Text("今天", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun ArchiveCoverageLabel(
    status: ArchiveCoverageStatus,
    onClick: (() -> Unit)?,
) {
    val color = when (status.kind) {
        ArchiveCoverageKind.COMPLETE -> MaterialTheme.colorScheme.primary
        ArchiveCoverageKind.BASIC -> MaterialTheme.colorScheme.onSurfaceVariant
        ArchiveCoverageKind.INCOMPLETE -> MaterialTheme.colorScheme.error
        ArchiveCoverageKind.PAUSED,
        ArchiveCoverageKind.NOT_STARTED,
        ArchiveCoverageKind.UNKNOWN,
        -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val action = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)
    Row(
        modifier = action.heightIn(min = 28.dp).padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Surface(
            modifier = Modifier.size(7.dp),
            shape = RoundedCornerShape(50),
            color = if (status.kind == ArchiveCoverageKind.NOT_STARTED) Color.Transparent else color,
            border = if (status.kind == ArchiveCoverageKind.NOT_STARTED) BorderStroke(1.dp, color) else null,
        ) {}
        Text(
            status.label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            maxLines = 1,
        )
    }
}

/**
 * 三类证据共用一根横向时间轴。地点是背景章节，应用是持续段，通知是瞬时刻度；
 * 缺口覆盖其受影响的轨道。点按或横向拖动会返回对应的 epochMs。
 */
@Composable
fun ArchiveTimeBand(
    startMs: Long,
    endMs: Long,
    modifier: Modifier = Modifier,
    placeSpans: List<ArchiveSpan> = emptyList(),
    appSpans: List<ArchiveSpan> = emptyList(),
    notificationTicks: List<ArchiveNotificationTick> = emptyList(),
    gaps: List<ArchiveGap> = emptyList(),
    probeMs: Long? = null,
    axisLabelFormatter: (Long) -> String = { formatClock(it) },
    onTimeSelected: ((Long) -> Unit)? = null,
    compact: Boolean = false,
) {
    require(endMs > startMs) { "ArchiveTimeBand endMs must be greater than startMs" }

    val density = LocalDensity.current
    val currentOnTimeSelected by rememberUpdatedState(onTimeSelected)
    val leftInsetPx = with(density) { 50.dp.toPx() }
    val rightInsetPx = with(density) { 8.dp.toPx() }
    val totalMs = (endMs - startMs).toDouble()

    fun epochAt(x: Float, width: Float): Long {
        val plotWidth = (width - leftInsetPx - rightInsetPx).coerceAtLeast(1f)
        val fraction = ((x - leftInsetPx) / plotWidth).coerceIn(0f, 1f)
        return (startMs + totalMs * fraction).toLong().coerceIn(startMs, endMs)
    }

    val locationColor = MaterialTheme.colorScheme.primary
    val appColor = MaterialTheme.colorScheme.primary
    val notificationColor = MaterialTheme.colorScheme.primary
    // 历史缺失不是“当前故障”，用中性色留出可信边界，避免整页报警感。
    val gapColor = MaterialTheme.colorScheme.outline
    val axisColor = MaterialTheme.colorScheme.outlineVariant
    val secondaryTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    val surfaceTrack = MaterialTheme.colorScheme.surfaceVariant
    val surfaceColor = MaterialTheme.colorScheme.surface
    val probeColor = MaterialTheme.colorScheme.onSurface

    val selectedProgress = probeMs?.let { ((it - startMs).toFloat() / (endMs - startMs)).coerceIn(0f, 1f) } ?: 0f
    val archiveDescription = "全天记录：${placeSpans.size} 段地点、${appSpans.size} 段应用、" +
        "${notificationTicks.sumOf { it.count }} 条通知、${gaps.size} 段未记录时间"
    val interactionModifier = if (onTimeSelected == null) {
        Modifier.semantics {
            contentDescription = archiveDescription
            stateDescription = "静态时间范围 ${axisLabelFormatter(startMs)} 到 ${axisLabelFormatter(endMs)}"
        }
    } else Modifier
        .pointerInput(startMs, endMs) {
            detectTapGestures { position ->
                currentOnTimeSelected?.invoke(epochAt(position.x, size.width.toFloat()))
            }
        }
        .pointerInput(startMs, endMs) {
            detectHorizontalDragGestures(
                onDragStart = { position ->
                    currentOnTimeSelected?.invoke(epochAt(position.x, size.width.toFloat()))
                },
            ) { change, _ ->
                currentOnTimeSelected?.invoke(epochAt(change.position.x, size.width.toFloat()))
                change.consume()
            }
        }
        .semantics {
            contentDescription = archiveDescription
            stateDescription = probeMs?.let { "当前查看 ${axisLabelFormatter(it)}" } ?: "尚未选择时间"
            progressBarRangeInfo = ProgressBarRangeInfo(selectedProgress, 0f..1f)
            setProgress { progress ->
                currentOnTimeSelected?.invoke((startMs + (endMs - startMs) * progress.coerceIn(0f, 1f)).toLong())
                true
            }
        }
    val bandHeight = if (compact) 108.dp else 164.dp
    // U17：Paint 在 Composable 层创建并复用，避免 Canvas 每次绘制新建 Paint。
    val labelPaint = remember(density, secondaryTextColor) {
        NativePaint().apply {
            isAntiAlias = true
            textSize = with(density) { 11.sp.toPx() }
        }
    }
    Canvas(
        modifier = modifier.fillMaxWidth().height(bandHeight).then(interactionModifier),
    ) {
        val left = 50.dp.toPx()
        val right = 8.dp.toPx()
        val plotWidth = (size.width - left - right).coerceAtLeast(1f)
        val axisY = (if (compact) 24.dp else 28.dp).toPx()
        val placeTop = (if (compact) 36.dp else 44.dp).toPx()
        val placeBottom = (if (compact) 56.dp else 70.dp).toPx()
        val appTop = (if (compact) 67.dp else 88.dp).toPx()
        val appBottom = (if (compact) 77.dp else 101.dp).toPx()
        val notificationTop = (if (compact) 84.dp else 116.dp).toPx()
        val notificationBottom = (if (compact) 100.dp else 148.dp).toPx()

        fun xFor(epochMs: Long): Float {
            val fraction = ((epochMs - startMs) / totalMs).coerceIn(0.0, 1.0).toFloat()
            return left + fraction * plotWidth
        }

        fun drawLaneLabel(label: String, centerY: Float, color: Color) {
            labelPaint.color = color.toArgb()
            labelPaint.textAlign = NativePaint.Align.LEFT
            drawContext.canvas.nativeCanvas.drawText(label, 0f, centerY + 4.dp.toPx(), labelPaint)
        }

        repeat(5) { index ->
            val fraction = index / 4f
            val x = left + plotWidth * fraction
            val at = (startMs + totalMs * fraction).toLong()
            drawLine(axisColor, Offset(x, axisY), Offset(x, notificationBottom), 1.dp.toPx())
            labelPaint.color = secondaryTextColor.toArgb()
            labelPaint.textAlign = when (index) {
                0 -> NativePaint.Align.LEFT
                4 -> NativePaint.Align.RIGHT
                else -> NativePaint.Align.CENTER
            }
            drawContext.canvas.nativeCanvas.drawText(
                axisLabelFormatter(at),
                x,
                14.dp.toPx(),
                labelPaint,
            )
        }

        drawLaneLabel(ArchiveLane.PLACE.label, (placeTop + placeBottom) / 2, locationColor)
        drawLaneLabel(ArchiveLane.APPLICATION.label, (appTop + appBottom) / 2, appColor)
        drawLaneLabel(ArchiveLane.NOTIFICATION.label, (notificationTop + notificationBottom) / 2, notificationColor)

        drawRoundRect(
            surfaceTrack,
            Offset(left, placeTop),
            Size(plotWidth, placeBottom - placeTop),
            CornerRadius(5.dp.toPx()),
        )
        placeSpans.forEach { segment ->
            if (segment.endMs <= startMs || segment.startMs >= endMs || segment.endMs <= segment.startMs) return@forEach
            val startX = xFor(maxOf(segment.startMs, startMs))
            val endX = xFor(minOf(segment.endMs, endMs)).coerceAtLeast(startX + 2.dp.toPx())
            drawRoundRect(
                // v0.15 对比度：地点段填充 0.17→0.22，在 surfaceVariant 轨道上可辨识
                locationColor.copy(alpha = .22f),
                Offset(startX, placeTop),
                Size(endX - startX, placeBottom - placeTop),
                CornerRadius(5.dp.toPx()),
            )
            drawLine(
                locationColor.copy(alpha = .8f),
                Offset(startX, placeBottom),
                Offset(endX, placeBottom),
                1.5.dp.toPx(),
                cap = StrokeCap.Round,
            )
            if (segment.label.isNotBlank() && endX - startX >= 56.dp.toPx()) {
                labelPaint.color = locationColor.toArgb()
                labelPaint.textAlign = NativePaint.Align.CENTER
                drawContext.canvas.nativeCanvas.drawText(
                    segment.label.take(8),
                    (startX + endX) / 2,
                    (placeTop + placeBottom) / 2 + 4.dp.toPx(),
                    labelPaint,
                )
            }
        }

        drawRoundRect(
            surfaceTrack,
            Offset(left, appTop),
            Size(plotWidth, appBottom - appTop),
            CornerRadius(3.dp.toPx()),
        )
        appSpans.forEach { segment ->
            if (segment.endMs <= startMs || segment.startMs >= endMs || segment.endMs <= segment.startMs) return@forEach
            val startX = xFor(maxOf(segment.startMs, startMs))
            val endX = xFor(minOf(segment.endMs, endMs)).coerceAtLeast(startX + 2.dp.toPx())
            drawRoundRect(
                appColor.copy(alpha = .78f),
                Offset(startX, appTop),
                Size(endX - startX, appBottom - appTop),
                CornerRadius(3.dp.toPx()),
            )
        }

        drawLine(
            axisColor,
            Offset(left, notificationBottom),
            Offset(left + plotWidth, notificationBottom),
            1.dp.toPx(),
        )
        notificationTicks.forEach { tick ->
            if (tick.atMs !in startMs..endMs) return@forEach
            val x = xFor(tick.atMs)
            val height = (7 + min(tick.count.coerceAtLeast(1), 7) * 3).dp.toPx()
            drawLine(
                notificationColor.copy(alpha = .9f),
                Offset(x, notificationBottom),
                Offset(x, notificationBottom - height),
                strokeWidth = if (tick.count > 1) 2.dp.toPx() else 1.25.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }

        fun drawGapLane(gap: ArchiveGap, top: Float, bottom: Float) {
            if (gap.endMs <= startMs || gap.startMs >= endMs || gap.endMs <= gap.startMs) return
            val startX = xFor(maxOf(gap.startMs, startMs))
            val endX = xFor(minOf(gap.endMs, endMs)).coerceAtLeast(startX + 4.dp.toPx())
            clipRect(startX, top, endX, bottom) {
                drawRect(
                    gapColor.copy(alpha = .045f),
                    topLeft = Offset(startX, top),
                    size = Size(endX - startX, bottom - top),
                )
                val diagonal = (bottom - top).coerceAtLeast(8.dp.toPx())
                var lineX = startX - diagonal
                while (lineX < endX + diagonal) {
                    drawLine(
                        // v0.15 对比度：缺口斜纹 0.5→0.65——"数据缺失"是产品核心诚实表达，
                        // 原合成对比仅 1.59:1 在浅色下几乎不可见
                        gapColor.copy(alpha = .65f),
                        Offset(lineX, bottom),
                        Offset(lineX + diagonal, top),
                        .8.dp.toPx(),
                    )
                    lineX += 6.dp.toPx()
                }
            }
            drawRect(
                // v0.15 对比度：缺口虚线框 0.52→0.7
                gapColor.copy(alpha = .7f),
                topLeft = Offset(startX, top),
                size = Size(endX - startX, bottom - top),
                style = Stroke(
                    width = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
                ),
            )
        }

        gaps.forEach { gap ->
            if (ArchiveLane.PLACE in gap.lanes) drawGapLane(gap, placeTop, placeBottom)
            if (ArchiveLane.APPLICATION in gap.lanes) drawGapLane(gap, appTop, appBottom)
            if (ArchiveLane.NOTIFICATION in gap.lanes) drawGapLane(gap, notificationTop, notificationBottom)
            if (gap.lanes.isEmpty()) {
                // Steps and sleep do not own one of the three primary lanes. Keep their
                // historical gap visible as a thin global marker without pretending that
                // place, app, or notification evidence is missing.
                drawGapLane(gap, axisY + 5.dp.toPx(), axisY + 10.dp.toPx())
            }
        }

        probeMs?.takeIf { it in startMs..endMs }?.let { selected ->
            val x = xFor(selected)
            drawLine(
                probeColor.copy(alpha = .7f),
                Offset(x, axisY - 4.dp.toPx()),
                Offset(x, notificationBottom + 3.dp.toPx()),
                1.dp.toPx(),
            )
            drawCircle(
                color = surfaceColor,
                radius = 5.dp.toPx(),
                center = Offset(x, axisY - 4.dp.toPx()),
            )
            drawCircle(
                color = probeColor,
                radius = 5.dp.toPx(),
                center = Offset(x, axisY - 4.dp.toPx()),
                style = Stroke(1.dp.toPx()),
            )
        }
    }
}

/**
 * 统一证据行：时间列和右侧数值均不会被长标题挤压；正文承担可伸缩空间。
 */
@Composable
fun EvidenceRow(
    timeLabel: String,
    title: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    trailing: String? = null,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val action = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .then(action)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            timeLabel,
            modifier = Modifier.width(52.dp).padding(top = 2.dp),
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Surface(
            modifier = Modifier.width(2.dp).fillMaxHeight(),
            shape = RoundedCornerShape(2.dp),
            color = accentColor.copy(alpha = .72f),
        ) {}
        leading?.let {
            Box(Modifier.size(34.dp), contentAlignment = Alignment.TopCenter) { it() }
        }
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            supportingText?.takeIf(String::isNotBlank)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.takeIf(String::isNotBlank)?.let {
            Text(
                it,
                modifier = Modifier.widthIn(min = 76.dp, max = 112.dp).padding(start = 2.dp, top = 2.dp),
                style = MaterialTheme.typography.labelLarge,
                color = accentColor,
                textAlign = TextAlign.End,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

/** 缺口是“没有证据”的时间段，而不是普通事件，因此使用斜纹断带而非实心卡片。 */
@Composable
fun GapRow(
    startMs: Long,
    endMs: Long,
    sourceLabel: String,
    modifier: Modifier = Modifier,
    detail: String = "这段时间没有记录到数据，24π 不会推测并补写。",
    timeLabelFormatter: (Long) -> String = { formatClock(it) },
    onClick: (() -> Unit)? = null,
) {
    require(endMs > startMs) { "GapRow endMs must be greater than startMs" }
    val error = MaterialTheme.colorScheme.error
    EvidenceRow(
        timeLabel = timeLabelFormatter(startMs),
        title = "${sourceLabel}记录缺失",
        supportingText = "${timeLabelFormatter(startMs)}–${timeLabelFormatter(endMs)} · $detail",
        trailing = formatDuration(endMs - startMs),
        accentColor = error,
        leading = { ArchiveGapMark(error) },
        modifier = modifier,
        onClick = onClick,
    )
}

@Composable
private fun ArchiveGapMark(color: Color) {
    Canvas(Modifier.width(20.dp).height(38.dp)) {
        val step = 6.dp.toPx()
        var x = -size.height
        clipRect(0f, 0f, size.width, size.height) {
            while (x < size.width + size.height) {
                drawLine(
                    color.copy(alpha = .55f),
                    Offset(x, size.height),
                    Offset(x + size.height, 0f),
                    1.dp.toPx(),
                )
                x += step
            }
        }
        drawRect(
            color.copy(alpha = .52f),
            style = Stroke(
                width = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
            ),
        )
    }
}

/**
 * 一段时间章节的轻量容器。默认只有细边框和留白；应用、通知摘要等由 content 插槽提供。
 */
@Composable
fun TimeChapterCard(
    timeRange: String,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: String? = null,
    coverage: ArchiveCoverageStatus? = null,
    onCoverageClick: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val action = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)
    Surface(
        modifier = modifier.fillMaxWidth().then(action),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        timeRange,
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    subtitle?.takeIf(String::isNotBlank)?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    coverage?.let {
                        ArchiveCoverageLabel(it, onCoverageClick)
                    }
                }
                trailing?.takeIf(String::isNotBlank)?.let {
                    Text(
                        it,
                        modifier = Modifier.widthIn(min = 76.dp, max = 112.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                    )
                }
            }
            content?.let {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                it()
            }
        }
    }
}
