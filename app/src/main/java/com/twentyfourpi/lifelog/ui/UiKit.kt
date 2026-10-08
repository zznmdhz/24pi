package com.twentyfourpi.lifelog.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * S 批统一组件库（v0.16 之后建立，供 B/C/D 各批接入，不在本批替换现有页面）。
 *
 * 视觉规范见 docs/UI_UX_REFACTOR_PLAN_2026-09-09.md 第 10 节：
 * - 动作协议：右箭头=下一页，下箭头=原位展开，开关=变更状态；同一行只能有一种动作。
 * - 触控目标 ≥48dp；正文 14–16sp；圆角 8/12/16dp；普通列表不逐行套卡片。
 * - 红色只表示当前需要处理的故障；历史缺口用中性色。
 *
 * 组件均为纯展示/交互件，不依赖业务数据模型，便于 Preview 与测试。
 */

// ── 间距与几何令牌（10.1）───────────────────────────────────────────────

/** 页面与列表统一几何常量。避免各页面自取近似值。 */
object UiDimens {
    /** 默认水平页边距；窄屏（<360dp 可用宽）由页面自行降为 16dp。 */
    val PageHorizontal = 20.dp
    val PageHorizontalNarrow = 16.dp

    /** 触控目标最小尺寸（视觉图标可更小）。 */
    val TouchTarget = 48.dp

    /** 圆角角色：小控件 / 分组 / 图与面板。 */
    val RadiusControl = 8.dp
    val RadiusGroup = 16.dp
    val RadiusPanel = 20.dp

    /** 右侧数值列参考宽，保证多行数值右对齐。 */
    val ValueColumnMin = 76.dp
    val ValueColumnMax = 112.dp
}

/**
 * 页面顶栏：同一返回热区、同一标题层级。
 * 纯标题场景最小 48dp 触控高；带返回按钮时由 48dp 按钮热区 + 上下 padding 达到 56dp
 * 顶栏参考高度。长标题允许换行，不挤掉返回按钮。承载 20sp 标题；P-A 页超大标题另行约定。
 */
@Composable
fun UiPageHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = UiDimens.TouchTarget).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.size(UiDimens.TouchTarget)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        actions?.invoke(this)
    }
}

/**
 * 普通记录行：整行可点（右箭头=进入下一页），或纯展示。
 * 主副字段从同一纵线开始，右侧数值同一对齐列，长内容不会挤坏数值。
 */
@Composable
fun UiRecordRow(
    title: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    value: String? = null,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val action = if (onClick == null) {
        Modifier
    } else {
        Modifier.clickable(onClick = onClick)
    }
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = UiDimens.TouchTarget).then(action).padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.let { Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { it() } }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                // 10.1：主记录名称 16sp 适度加粗（titleMedium），不使用更小层级固化降档。
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            supportingText?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        value?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                modifier = Modifier.widthIn(min = UiDimens.ValueColumnMin, max = UiDimens.ValueColumnMax),
                style = MaterialTheme.typography.labelLarge,
                color = valueColor,
                textAlign = TextAlign.End,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
            )
        }
        if (onClick != null) {
            Icon(
                Icons.Outlined.ChevronRight,
                // 10.3：装饰箭头由整行合并语义承担，不再为可点行单独重复朗读。
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/**
 * 原位展开组：下箭头表示展开/收起，默认最多展示首层摘要；
 * 子记录超过 [visibleLimit] 时提供“查看全部”动作，不静默截断。
 * 展开状态与内容由调用方持有（页面状态，不写进 rememberSaveable 大数据）。
 */
@Composable
fun UiExpandableGroup(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    trailingCount: String? = null,
    visibleLimit: Int? = null,
    onViewAll: (() -> Unit)? = null,
    viewAllLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val motion = LocalMotionPreference.current
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = motion.spec(180),
        label = "group-arrow",
    )
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(UiDimens.RadiusGroup),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth().animateContentSize(animationSpec = motion.spec(200))) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .semantics {
                        stateDescription = if (expanded) "已展开" else "已收起"
                        role = Role.Button
                    }
                    .heightIn(min = UiDimens.TouchTarget)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    // 10.1：分组主名称 16sp（titleMedium）。
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    summary?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                trailingCount?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(
                    Icons.Outlined.ExpandMore,
                    // 10.3：展开方向已由 stateDescription 表达，图标为装饰、不重复朗读。
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.graphicsLayer { rotationZ = arrowRotation },
                )
            }
            if (expanded) {
                // 10.7：分隔线从文本起点内缩，不铺满整屏网格。
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(start = 16.dp),
                )
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    content = content,
                )
                if (visibleLimit != null && onViewAll != null) {
                    TextButton(onClick = onViewAll, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)) {
                        // 10.6：动作按内容命名，例如“查看全部12条通知”。
                        Text(viewAllLabel ?: "查看全部")
                    }
                }
            }
        }
    }
}

/**
 * 设置行共四种（10.8）：开关行 / 选择行 / 导航行 / 动作行。
 * 所有行统一 ≥48dp 触控高、同一边距、同一文字层级。
 */

/** 开关行：整行与开关同为 toggle 动作，单一 toggleable 语义（10.8），点击文字与开关行为一致。 */
@Composable
fun UiSettingToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .heightIn(min = UiDimens.TouchTarget)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            supportingText?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = null, // 行 toggleable 已提供唯一动作源，避免双绑定歧义
            enabled = enabled,
        )
    }
}

/** 选择行：名称 + 当前值 + 右箭头；进入选择后即时保存，返回保留位置。 */
@Composable
fun UiSettingChoiceRow(
    title: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
) {
    UiRecordRow(
        title = title,
        supportingText = supportingText,
        value = value,
        onClick = onClick,
        modifier = modifier,
    )
}

/** 导航行：名称 + 摘要 + 右箭头；只负责进入下一页，不执行数据操作。 */
@Composable
fun UiSettingNavigationRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    icon: ImageVector? = null,
) {
    UiRecordRow(
        title = title,
        supportingText = supportingText,
        onClick = onClick,
        modifier = modifier,
        leading = icon?.let { vec -> { Icon(vec, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) } },
    )
}

/** 动作行：动词 + 对象，如“创建加密备份”；显示操作过程与结果，不伪装成开关。 */
@Composable
fun UiSettingActionRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    enabled: Boolean = true,
) {
    val action = if (enabled) Modifier.clickable(onClick = onClick) else Modifier
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = UiDimens.TouchTarget).then(action).padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            supportingText?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * 行内提示：普通字段状态、历史记录情况、当前需要行动、操作反馈统一由它呈现。
 * [tone] 控制颜色：NEUTRAL 普通状态；WARNING 历史/次要缺口（10.2：历史缺口用中性灰，
 * 琥珀留给通知语义）；ERROR 当前需要处理（红）。
 * 可点击横幅按 10.1 保证 ≥48dp 触控热区（视觉高度可更小）。
 */
enum class UiNoticeTone { NEUTRAL, WARNING, ERROR }

@Composable
fun UiInlineNotice(
    text: String,
    modifier: Modifier = Modifier,
    tone: UiNoticeTone = UiNoticeTone.NEUTRAL,
    onClick: (() -> Unit)? = null,
) {
    val color = when (tone) {
        UiNoticeTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
        UiNoticeTone.WARNING -> MaterialTheme.colorScheme.outline
        UiNoticeTone.ERROR -> MaterialTheme.colorScheme.error
    }
    val action = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick == null) Modifier else Modifier.minimumInteractiveComponentSize())
            .heightIn(min = 36.dp)
            .then(action)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (tone == UiNoticeTone.ERROR) {
            Icon(Icons.Outlined.ErrorOutline, null, tint = color, modifier = Modifier.size(18.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            modifier = Modifier.weight(1f),
        )
        if (onClick != null) {
            Icon(Icons.Outlined.ChevronRight, null, tint = color, modifier = Modifier.size(18.dp))
        }
    }
}

/** 空数据：只在成功读取且确实为空后显示（U03：读取中不是无数据）。 */
@Composable
fun UiEmptyState(
    title: String,
    detail: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        detail?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 加载中：不先展示无数据结论。 */
@Composable
fun UiLoadingState(
    text: String = "正在加载…",
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 错误：说明“什么操作没完成 + 可以做什么”；就近重试，不贴异常码。 */
@Composable
fun UiErrorState(
    message: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    retryLabel: String = "重试",
) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        if (onRetry != null) {
            TextButton(onClick = onRetry) {
                Icon(Icons.Outlined.Refresh, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                Text(retryLabel)
            }
        }
    }
}

/**
 * 任务操作反馈：待操作 → 准备 → 正在处理 → 校验 → 完成/失败/取消。
 * 无法计算总进度时显示阶段文本，不伪造百分比。
 */
@Composable
fun UiOperationStatus(
    modifier: Modifier = Modifier,
    stage: String? = null,
    message: String? = null,
    isError: Boolean = false,
    inProgress: Boolean = false,
) {
    val tone = if (isError) UiNoticeTone.ERROR else UiNoticeTone.NEUTRAL
    if (stage == null && message == null) return
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        stage?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = if (inProgress && !isError) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        message?.takeIf { it.isNotBlank() }?.let {
            UiInlineNotice(text = it, tone = tone)
        }
    }
}
