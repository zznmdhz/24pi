package com.twentyfourpi.lifelog.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.twentyfourpi.lifelog.ai.*
import java.time.LocalDate

@Composable
fun AiReviewScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPlace: (Long, String, LocalDate, LocalDate) -> Unit = { _, _, _, _ -> },
) {
    val vm: AiViewModel = viewModel()
    DisposableEffect(vm) { onDispose { vm.cancel() } }
    val state by vm.state.collectAsStateWithLifecycle()
    AiReviewContent(state, vm.config(), onBack, onOpenSettings, onOpenPlace, vm::setQuestion, vm::setRange, vm::prepare, vm::send, vm::cancel)
}

@Composable
internal fun AiReviewContent(
    state: AiReviewState,
    config: AiConfig,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPlace: (Long, String, LocalDate, LocalDate) -> Unit,
    onQuestion: (String) -> Unit,
    onRange: (ReviewRange) -> Unit,
    onPrepare: () -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
) {
    var customStart by remember { mutableStateOf("") }
    var customEnd by remember { mutableStateOf("") }
    var rangeMessage by remember { mutableStateOf("") }
    var showAllPlaces by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    val quick = listOf(
        "今天" to ReviewRange(today, today.plusDays(1), "今天"),
        "昨天" to ReviewRange(today.minusDays(1), today, "昨天"),
        "最近7天" to ReviewRange(today.minusDays(6), today.plusDays(1), "最近7天"),
        "最近30天" to ReviewRange(today.minusDays(29), today.plusDays(1), "最近30天"),
        "本周" to ReviewRange(today.minusDays((today.dayOfWeek.value - 1).toLong()), today.plusDays(1), "本周"),
        "本月" to ReviewRange(today.withDayOfMonth(1), today.plusDays(1), "本月"),
    )
    LazyColumn(Modifier.fillMaxSize().testTag("aiReviewList"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onCancel(); onBack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回") }
                Text("AI 回顾", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onOpenSettings) { Text("配置") }
            }
        }
        item { Text("先在本机整理事实，再决定是否发送给你的服务。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item {
            OutlinedTextField(state.question, onQuestion, modifier = Modifier.fillMaxWidth(), label = { Text("想回顾什么？") }, minLines = 2, maxLines = 4)
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("最近30天去了哪里，按停留时间排序", "这个月在公司待了多长时间", "最近7天哪些应用使用最多", "昨天的通知概览").chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { q -> SuggestionChip(onClick = { onQuestion(q) }, label = { Text(q, maxLines = 2) }, modifier = Modifier.weight(1f)) }
                    }
                }
            }
        }
        item {
            Text("日期范围", style = MaterialTheme.typography.titleMedium)
            Text(state.range?.let { "${it.start} 至 ${it.endExclusive.minusDays(1)} · ${it.label}" } ?: "可从问题识别，也可直接选择", style = MaterialTheme.typography.bodySmall)
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                quick.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { (label, r) -> FilterChip(selected = state.range == r, onClick = { onRange(r) }, label = { Text(label) }, modifier = Modifier.weight(1f)) }
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(customStart, { customStart = it }, label = { Text("开始 YYYY-MM-DD") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(customEnd, { customEnd = it }, label = { Text("结束 YYYY-MM-DD") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            }
            TextButton(onClick = {
                val start = runCatching { LocalDate.parse(customStart) }.getOrNull()
                val end = runCatching { LocalDate.parse(customEnd) }.getOrNull()
                if (start == null || end == null || end < start || end.isAfter(today) || java.time.temporal.ChronoUnit.DAYS.between(start, end) >= 90) rangeMessage = "请选择截至今天、最多90天的有效日期"
                else { onRange(ReviewRange(start, end.plusDays(1), "自选日期")); rangeMessage = "" }
            }) { Text("使用自选日期") }
            if (rangeMessage.isNotBlank()) Text(rangeMessage, color = MaterialTheme.colorScheme.error)
        }
        item {
            Button(onClick = onPrepare, enabled = !state.working && !state.sending, modifier = Modifier.fillMaxWidth()) { Text("整理本地依据") }
            if (state.working || state.sending) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(state.status)
                    TextButton(onClick = onCancel) { Text("取消") }
                }
            } else if (state.status.isNotBlank()) Text(state.status, color = if (state.stale) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.evidence?.let { evidence ->
            item {
                Text("本地依据", style = MaterialTheme.typography.titleLarge)
                Text("${evidence.range.start} 至 ${evidence.range.endExclusive.minusDays(1)} · 已记录 ${evidence.recordedDays}/${evidence.range.days} 天", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (evidence.companyRows.isNotEmpty()) Text("公司地点合计：${evidence.companyTotalMs / 60_000} 分钟（重叠已去除）")
                if (!evidence.hasRecords) Text("没有可用记录，不能推断这段时间没有活动。")
                if (evidence.companyRows.isEmpty()) Text("没有标记为公司的地点；可在地点管理中标记。")
            }
            items(evidence.rankedPlaces(state.question).take(if (showAllPlaces) evidence.placeRows.size else 5), key = { "place_${it.id}" }) { row ->
                ListItem(headlineContent = { Text(row.name) }, supportingContent = { Text("已记录 ${row.durationMs / 60_000} 分钟 · ${row.count} 次") },
                    modifier = Modifier.clickable { row.id?.let { onOpenPlace(it, row.name, evidence.range.start, evidence.range.endExclusive) } })
            }
            if (evidence.placeRows.size > 5) item { TextButton(onClick = { showAllPlaces = !showAllPlaces }) { Text(if (showAllPlaces) "收起地点" else "查看全部 ${evidence.placeRows.size} 个地点") } }
            if (evidence.appRows.isNotEmpty()) item { Text("应用使用", style = MaterialTheme.typography.titleMedium) }
            items(evidence.appRows.take(10)) { row -> Text("${row.name} · ${row.durationMs / 60_000} 分钟") }
            item { Text("收到通知：${evidence.notificationCount} · 各来源缺口合计（可重叠）：${evidence.gapMs.values.sum() / 60_000} 分钟", style = MaterialTheme.typography.bodySmall) }
            item {
                HorizontalDivider()
                Text("将发送给", style = MaterialTheme.typography.titleMedium)
                Text(config.baseUrl.ifBlank { "尚未配置服务地址" })
                if (!config.hasKey || config.baseUrl.isBlank() || config.model.isBlank()) TextButton(onClick = onOpenSettings) { Text("前往 AI 配置") }
                Text("发送预览", style = MaterialTheme.typography.titleMedium)
                Text(state.preview, style = MaterialTheme.typography.bodySmall)
                Button(onClick = onSend, enabled = !state.working && !state.sending && !state.stale && evidence.hasRecords && config.hasKey, modifier = Modifier.fillMaxWidth()) { Text("确认发送并生成总结") }
            }
        }
        state.answer?.let { answer -> item { Text("AI 生成总结", style = MaterialTheme.typography.titleLarge); Text(answer); Text("数字请以上方本地依据为准。", style = MaterialTheme.typography.bodySmall) } }
    }
}
