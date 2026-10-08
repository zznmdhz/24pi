package com.twentyfourpi.lifelog.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** A focused privacy decision, shared by the archive deep link and My settings. */
@Composable
fun NotificationContentSettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    var enabled by remember { mutableStateOf(viewModel.settings.notificationContentEnabled) }
    var pendingValue by remember { mutableStateOf<Boolean?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回档案")
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("通知内容", style = MaterialTheme.typography.headlineSmall)
                    Text("选择通知档案保存到什么程度", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            NotificationContentExample()
        }
        item {
            InstrumentPanel {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("保存标题和正文", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (enabled) "只保存开启以后收到的普通通知内容" else "现在只保存应用、时间和数量",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = enabled, onCheckedChange = { pendingValue = it })
                    }
                    Text(
                        "标题和正文可能包含聊天、验证码或消费信息。数据只保存在本机和你的加密备份中，不保存图片、联系人或点击动作。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("已经保存的通知内容会作为历史档案永久保留；关闭开关只停止保存此后收到的正文。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    pendingValue?.let { target ->
        InstrumentDialog(
            title = if (target) "开始保存通知内容？" else "停止保存通知内容？",
            onDismiss = { pendingValue = null },
            content = {
                Text(
                    if (target) {
                        "开启后只保存此后收到的标题和正文，旧通知无法补录。"
                    } else {
                        "此后仍会记录通知的应用、时间和数量；已保存的历史内容不会自动删除。"
                    },
                )
            },
            actions = {
                TextButton(onClick = { pendingValue = null }) { Text("取消") }
                Button(onClick = {
                    enabled = target
                    viewModel.setNotificationContentEnabled(target)
                    pendingValue = null
                }) { Text(if (target) "确认开启" else "停止保存") }
            },
        )
    }
}

@Composable
fun NotificationContentExample(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("开启前后，你会看到什么", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NotificationExampleCard(
                title = "关闭时",
                lines = listOf("微信 · 14:32", "1 条通知"),
                modifier = Modifier.weight(1f),
            )
            NotificationExampleCard(
                title = "开启后",
                lines = listOf("微信 · 14:32", "晚点见，我到楼下了"),
                modifier = Modifier.weight(1f),
            )
        }
        Text("示例文字仅用于说明，不是从你的通知中读取。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NotificationExampleCard(title: String, lines: List<String>, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            lines.forEachIndexed { index, line ->
                Text(
                    line,
                    style = if (index == 0) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                    color = if (index == 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
    }
}
