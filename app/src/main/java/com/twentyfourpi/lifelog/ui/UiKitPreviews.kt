package com.twentyfourpi.lifelog.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * S 批组件预览：同一套 UiKit 在正常 / 空 / 加载 / 错误 / 展开等状态下的对照。
 * 所有数据均为合成内容，不读取真实数据库；预览页组供 B/C/D 批接入前审阅统一性。
 * 页面级 10 张对照（时间首页/时段详情/通知详情/全天概览/全天轨迹/档案结果/我的/
 * 通知设置/后台运行/数据与备份）在 B/C/D 批接入真实数据后逐页补齐并截图归档。
 */

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun UiKitOverviewPreview() {
    LifeLogTheme {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("UiKit 组件对照（合成数据）", style = MaterialTheme.typography.titleLarge)

            UiPageHeader(title = "时间 · 9月9日", subtitle = "今天 · 正在形成档案", onBack = {})

            Text("记录行：有地点 / 缺地点 / 动作", style = MaterialTheme.typography.titleSmall)
            UiRecordRow(title = "12:10–12:40", supportingText = "微信 18分钟 · 浏览器 7分钟 · 12条通知", value = "30分钟", onClick = {})
            UiRecordRow(title = "10:30–12:10", supportingText = "公司", value = "1小时40分", onClick = {})
            UiRecordRow(title = "08:00–10:30", supportingText = "家", value = "2小时30分")

            Text("展开组：行程 / 通知分组", style = MaterialTheme.typography.titleSmall)
            var expanded by remember { mutableStateOf(false) }
            UiExpandableGroup(
                title = "行程 09:39–10:04",
                summary = "家 → 公司 · 4.2公里",
                trailingCount = "3 段",
                expanded = expanded,
                onToggle = { expanded = !expanded },
            ) {
                UiRecordRow(title = "途经停留 1 次", supportingText = "09:44 停留 40秒", onClick = {})
            }

            Text("状态：空 / 加载 / 错误 / 提示", style = MaterialTheme.typography.titleSmall)
            UiInlineNotice("更新至 12:58", tone = UiNoticeTone.NEUTRAL)
            UiInlineNotice("该时段有 12 分钟定位空缺", tone = UiNoticeTone.WARNING, onClick = {})
            UiInlineNotice("地点权限需要开启 · 去设置", tone = UiNoticeTone.ERROR, onClick = {})
            UiLoadingState("正在读取当天记录…")
            UiErrorState("轨迹加载失败", onRetry = {})
            UiEmptyState("这一天还没有可浏览的记录", "继续使用手机后，记录会按时间出现在这里。")
        }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun SettingRowsPreview() {
    LifeLogTheme {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("设置行四种（合成数据）", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            var toggle1 by remember { mutableStateOf(true) }
            var toggle2 by remember { mutableStateOf(false) }
            UiSettingToggleRow(title = "记录通知", supportingText = "统计普通通知，并保存标题和正文", checked = toggle1, onCheckedChange = { toggle1 = it })
            UiSettingToggleRow(title = "保存标题和正文", supportingText = "已保存的历史内容保持不变", checked = toggle2, onCheckedChange = { toggle2 = it })
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            UiSettingChoiceRow(title = "深色模式", value = "浅色", onClick = {})
            UiSettingNavigationRow(title = "后台运行", supportingText = "已完成 2 项", onClick = {}, icon = Icons.Outlined.Schedule)
            UiSettingNavigationRow(title = "地点命名服务", supportingText = "自动获取名称", onClick = {}, icon = Icons.Outlined.Home)
            UiSettingNavigationRow(title = "问题记录与诊断", supportingText = "观察版", onClick = {}, icon = Icons.Outlined.Person)
            UiSettingActionRow(title = "创建加密备份", supportingText = "保存完整档案到本机文件", onClick = {})
            UiSettingActionRow(title = "导出可读记录", supportingText = "CSV ZIP", onClick = {})
        }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun OperationPreview() {
    LifeLogTheme {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("操作过程与结果（合成数据）", style = MaterialTheme.typography.titleLarge)

            Text("准备中", style = MaterialTheme.typography.titleSmall)
            UiOperationStatus(stage = "正在准备…", message = "正在读取本机档案", inProgress = true)

            Text("失败（保留重试）", style = MaterialTheme.typography.titleSmall)
            UiOperationStatus(stage = "备份失败", message = "无法写入所选位置，请重试", isError = true)
            UiErrorState("导出失败：存储不可写", onRetry = {})

            Text("成功（就近展示）", style = MaterialTheme.typography.titleSmall)
            UiOperationStatus(stage = "备份已保存", message = "24pi-backup-2026-09-09.24pi")
        }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun ExpandableStatesPreview() {
    LifeLogTheme {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("展开/收起/长内容（合成数据）", style = MaterialTheme.typography.titleLarge)

            var g1 by remember { mutableStateOf(false) }
            UiExpandableGroup(
                title = "通知 · 微信",
                summary = "5 分钟内 6 条",
                trailingCount = "6 条",
                expanded = g1,
                onToggle = { g1 = !g1 },
                visibleLimit = 5,
                onViewAll = {},
                viewAllLabel = "查看全部 6 条通知",
            ) {
                repeat(6) { i ->
                    UiRecordRow(
                        title = "通知标题 $i",
                        supportingText = "这是一条很长很长很长很长很长很长的通知正文，用于检查长文本是否会挤坏右侧数值。",
                        onClick = {},
                    )
                }
            }

            var g2 by remember { mutableStateOf(false) }
            UiExpandableGroup(
                title = "中断摘要",
                summary = "当天 2 次定位中断，共 18 分钟",
                expanded = g2,
                onToggle = { g2 = !g2 },
            ) {
                UiRecordRow(title = "定位在 09:40 中断", supportingText = "09:58 恢复 · 18 分钟", onClick = {})
            }
        }
    }
}
