package com.twentyfourpi.lifelog.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.twentyfourpi.lifelog.data.PlaceVisitView
import com.twentyfourpi.lifelog.util.formatDuration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 所有记录详情共用的底部面板，避免不同页面出现互不相干的系统弹窗样式。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LifeLogDetailSheet(
    title: String,
    subtitle: String? = null,
    onDismiss: () -> Unit,
    onBack: (() -> Unit)? = null,
    contentKey: Any? = title,
    content: LazyListScope.() -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val listState = remember(contentKey) { LazyListState() }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        dragHandle = { BottomSheetDefaults.DragHandle(width = 34.dp, height = 3.dp, color = MaterialTheme.colorScheme.outline) },
    ) {
        // R14：详情不再固定高度——短内容自适应，长内容可拖到近全屏阅读
        // （ModalBottomSheet wrap-content 默认行为）；不再为短通知留半屏空白。
        Column(Modifier.fillMaxWidth().fillMaxHeight(.8f).navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = if (onBack == null) 22.dp else 8.dp, end = 8.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回上一级") }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    subtitle?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                state = listState,
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                content = content,
            )
        }
    }
}

@Composable
fun PlaceVisitCard(
    visit: PlaceVisitView,
    modifier: Modifier = Modifier,
    isOngoing: Boolean = false,
    signalGapMs: Long = 0L,
    onClick: (() -> Unit)? = null,
) {
    val interactive = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)
    val timeRange = remember(visit.startMs, visit.endMs, isOngoing) {
        val zone = ZoneId.systemDefault()
        val start = Instant.ofEpochMilli(visit.startMs).atZone(zone)
        val end = Instant.ofEpochMilli(visit.endMs).atZone(zone)
        when {
            isOngoing -> "${start.format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))}–现在"
            start.toLocalDate() == end.toLocalDate() ->
                "${start.format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))}–${end.format(DateTimeFormatter.ofPattern("HH:mm"))}"
            else ->
                "${start.format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))} – ${end.format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))}"
        }
    }
    Surface(
        modifier = modifier.then(interactive),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(15.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.width(4.dp).height(if (signalGapMs > 0) 70.dp else 54.dp),
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(20.dp),
            ) {}
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(visit.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    timeRange,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    visit.address.ifBlank { "地点详情" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (signalGapMs > 0) Text(
                    "含 ${formatDuration(signalGapMs)} 定位信号中断",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    formatDuration(visit.endMs - visit.startMs),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
                if (onClick != null) Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = "查看地点详情",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
