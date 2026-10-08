package com.twentyfourpi.lifelog.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.twentyfourpi.lifelog.data.SourceId
import com.twentyfourpi.lifelog.data.SourceState
import com.twentyfourpi.lifelog.data.SourceStatusEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SourcesScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onUsage: () -> Unit,
    onNotifications: () -> Unit,
    onRuntimePermissions: () -> Unit,
    onBackgroundLocation: () -> Unit,
    onAppDetails: () -> Unit,
) {
    val statuses by viewModel.statuses.collectAsStateWithLifecycle()
    var expandedSource by remember { mutableStateOf<SourceId?>(null) }
    val restrictedHelpNeeded = listOf(SourceId.USAGE, SourceId.NOTIFICATIONS).any { source ->
        statuses.firstOrNull { it.source == source.name }?.state == SourceState.PERMISSION_REQUIRED.name
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
                    Column { Text("采集状态", style = MaterialTheme.typography.headlineSmall); Text(if (viewModel.settings.collectionEnabled) "总开关已开启" else "所有采集已暂停") }
                }
                Switch(checked = viewModel.settings.collectionEnabled, onCheckedChange = viewModel::setCollection)
            }
        }
        if (restrictedHelpNeeded) item {
            InstrumentPanel {
                InstrumentRow(
                    title = "系统限制了关键权限",
                    detail = "打开应用详情，点右上角“更多/⋮ → 允许受限设置”，再返回授权。",
                    onClick = onAppDetails,
                )
            }
        }
        item {
            InstrumentSection("当前来源", subtitle = "点按查看原因和操作") {
                SourceId.entries.forEachIndexed { index, source ->
                    val status = statuses.find { it.source == source.name } ?: SourceStatusEntity(source.name, SourceState.ERROR.name, detail = "正在检查")
                    SourceRow(
                        source = source,
                        status = status,
                        enabled = viewModel.settings.sourceEnabled(source),
                        expanded = expandedSource == source,
                        onExpand = { expandedSource = if (expandedSource == source) null else source },
                        onEnabled = { viewModel.setSourceEnabled(source, it) },
                        onPermission = when (source) {
                            SourceId.USAGE -> onUsage
                            SourceId.NOTIFICATIONS -> onNotifications
                            SourceId.LOCATION -> onBackgroundLocation
                            SourceId.STEPS -> onRuntimePermissions
                            SourceId.SLEEP -> null
                        },
                        onAppDetails = if (source == SourceId.USAGE || source == SourceId.NOTIFICATIONS) onAppDetails else null,
                    )
                    if (index < SourceId.entries.lastIndex) InstrumentDivider()
                }
            }
        }
    }
}

@Composable
private fun SourceRow(
    source: SourceId,
    status: SourceStatusEntity,
    enabled: Boolean,
    expanded: Boolean,
    onExpand: () -> Unit,
    onEnabled: (Boolean) -> Unit,
    onPermission: (() -> Unit)?,
    onAppDetails: (() -> Unit)?,
) {
    val state = runCatching { SourceState.valueOf(status.state) }.getOrDefault(SourceState.ERROR)
    val color = sourceStateColor(state.name)
    Column(Modifier.fillMaxWidth().clickable(onClick = onExpand).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusDot(
                color,
                Modifier.semantics { contentDescription = "${source.label()}：${state.label()}" },
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(source.label(), style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(state.label(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                    status.lastUpdatedMs?.let {
                        Text("· ${it.sourceUpdatedLabel()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
            Switch(checked = enabled, onCheckedChange = onEnabled, enabled = source != SourceId.SLEEP)
        }
        if (expanded) {
            Text(status.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 17.dp, top = 8.dp))
            Row(Modifier.padding(start = 5.dp)) {
                if (onAppDetails != null && state == SourceState.PERMISSION_REQUIRED) TextButton(onClick = onAppDetails) { Text("解除限制") }
                if (onPermission != null && state == SourceState.PERMISSION_REQUIRED) TextButton(onClick = onPermission) { Text("去授权") }
            }
        }
    }
}

@Composable
private fun sourceStateColor(stateName: String?): Color = when (runCatching { SourceState.valueOf(stateName.orEmpty()) }.getOrNull()) {
    SourceState.ACTIVE -> MaterialTheme.colorScheme.primary
    SourceState.PAUSED -> MaterialTheme.colorScheme.outline
    SourceState.UNSUPPORTED -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.error
}

fun SourceId.label() = when (this) { SourceId.USAGE -> "手机行为"; SourceId.NOTIFICATIONS -> "通知记录"; SourceId.LOCATION -> "地点停留"; SourceId.STEPS -> "步数"; SourceId.SLEEP -> "睡眠" }
private fun SourceState.label() = when (this) {
    SourceState.ACTIVE -> "正在记录"
    SourceState.PAUSED -> "已暂停"
    SourceState.PERMISSION_REQUIRED -> "需要授权"
    SourceState.SYSTEM_BLOCKED -> "更新延迟，正在恢复"
    SourceState.UNSUPPORTED -> "设备不支持"
    SourceState.ERROR -> "需要处理"
}

private fun Long.sourceUpdatedLabel(): String {
    val at = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault())
    val today = java.time.LocalDate.now()
    return if (at.toLocalDate() == today) at.format(DateTimeFormatter.ofPattern("HH:mm"))
    else at.format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))
}
