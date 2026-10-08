package com.twentyfourpi.lifelog.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.twentyfourpi.lifelog.data.SourceId

/**
 * v0.15 状态呈现（UX 审查 P0-3：采集正常时零反馈、色弱不可读）。
 * 状态点旁必须带可读文字与最近成功时间，不再依赖颜色区分。
 */
@Composable
fun StatusDotWithText(
    source: SourceId,
    stateLabel: String,
    detail: String?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.semantics {
            contentDescription = "${source.label()}：$stateLabel${detail?.let { "，$it" } ?: ""}"
        },
    ) {
        StatusDot(
            if (stateLabel == SourceStateLabel.ACTIVE) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error,
        )
        Column {
            Text(source.label(), style = MaterialTheme.typography.labelSmall)
            Text(stateLabel, style = MaterialTheme.typography.labelSmall)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** 状态行整行可点击，进入数据源详情页。 */
@Composable
fun ClickableStatusRow(onOpenSources: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpenSources).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("查看各数据源状态", style = MaterialTheme.typography.bodyMedium)
        Icon(Icons.Outlined.ChevronRight, contentDescription = "查看数据源")
    }
}

/** 统一的状态文案出口：新增状态时必须在此补文案，否则编译失败。 */
object SourceStateLabel {
    const val ACTIVE = "正在记录"

    fun of(state: String): String = when (state) {
        "ACTIVE" -> ACTIVE
        "PAUSED" -> "已暂停"
        "PERMISSION_REQUIRED" -> "需要授权"
        "SYSTEM_BLOCKED" -> "更新延迟"
        "UNSUPPORTED" -> "设备不支持"
        "ERROR" -> "需要处理"
        else -> "状态未知"
    }
}
