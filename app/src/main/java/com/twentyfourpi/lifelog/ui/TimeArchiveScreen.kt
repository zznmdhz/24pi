@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.twentyfourpi.lifelog.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.twentyfourpi.lifelog.data.AppSessionEntity
import com.twentyfourpi.lifelog.data.CollectionGapEntity
import com.twentyfourpi.lifelog.data.DailyReviewSummary
import com.twentyfourpi.lifelog.data.DailyRoute
import com.twentyfourpi.lifelog.data.DailyRouteBuilder
import com.twentyfourpi.lifelog.data.GapImpact
import com.twentyfourpi.lifelog.data.NotificationEventEntity
import com.twentyfourpi.lifelog.data.PlaceVisitView
import com.twentyfourpi.lifelog.data.ReviewComparisonState
import com.twentyfourpi.lifelog.data.ReviewMetric
import com.twentyfourpi.lifelog.data.SevenDayReviewSummary
import com.twentyfourpi.lifelog.data.SourceId
import com.twentyfourpi.lifelog.data.SourceState
import com.twentyfourpi.lifelog.data.TimelineDay
import com.twentyfourpi.lifelog.data.projectCollectionGaps
import com.twentyfourpi.lifelog.data.assessCollectionGaps
import com.twentyfourpi.lifelog.data.classifyGapImpact
import com.twentyfourpi.lifelog.data.MillisInterval
import com.twentyfourpi.lifelog.data.buildDailyReviewSummary
import com.twentyfourpi.lifelog.data.buildSevenDayReviewSummary
import com.twentyfourpi.lifelog.data.intervalUnionDurationMs
import com.twentyfourpi.lifelog.data.userFacingGapReason
import com.twentyfourpi.lifelog.debug.DiagnosticLog
import com.twentyfourpi.lifelog.util.calculateSteps
import com.twentyfourpi.lifelog.util.dayBounds
import com.twentyfourpi.lifelog.util.formatClock
import com.twentyfourpi.lifelog.util.formatDuration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The primary archive surface. It answers one question only: what happened on this day?
 * Every summary on this page can be opened as a chapter and then traced to raw evidence.
 */
@Composable
fun TimeArchiveDayScreen(
    viewModel: MainViewModel,
    onSelectDate: (LocalDate) -> Unit,
    onOpenChapter: (startMs: Long, endMs: Long) -> Unit,
    onOpenCollectionHealth: () -> Unit,
    onOpenTrips: () -> Unit = {},
    onOpenLocation: () -> Unit = {},
    onOpenTrip: (String) -> Unit = {},
    onOpenAi: () -> Unit = {},
    onBack: (() -> Unit)? = null,
) {
    val date by viewModel.selectedDate.collectAsStateWithLifecycle()
    val dayState by viewModel.day.collectAsStateWithLifecycle()
    // U03：日期与数据绑定。状态携带 date；只有与当前选中日期匹配的数据才可绘制，
    // 否则给空快照（切换瞬间旧日期数据不会冒充新日期，也避免初值 TimelineDay() 被
    // 误当成“无记录”）。dayLoading 表示正在读取，空态只在成功读取且为空后出现。
    val dayLoading = dayState.date != date || dayState.loading
    val day = if (dayState.date == date) dayState.data else TimelineDay()
    val statuses by viewModel.statuses.collectAsStateWithLifecycle()
    val (dayStart, dayEnd) = remember(date) { dayBounds(date) }
    val gapAssessment = remember(day.gaps, dayStart, dayEnd) {
        assessCollectionGaps(day.gaps, dayStart, dayEnd)
    }
    val displayDay = remember(day, gapAssessment) { day.copy(gaps = gapAssessment.visibleGaps) }
    // v0.16.1：不再订阅当天全量原始点（事故版每个新点都触发整天重算，是发热根因之一）。
    // 轨迹改为默认折叠，展开时才一次性加载并缓存；切换日期后重置。
    // 审核#1：今天的轨迹允许在重新展开时刷新（缓存过期），历史日期保持快照语义。
    // U02：取消的任务也必须复位 loading（finally），并用 generation 防止旧取消任务
    // 清掉新请求的 loading；加载中收起再展开不再卡在“读取中”。
    var routeExpanded by remember(date) { mutableStateOf(true) }
    var dailyRoute by remember(date) { mutableStateOf<DailyRoute?>(null) }
    var routeLoading by remember(date) { mutableStateOf(false) }
    var routeLoadGeneration by remember(date) { mutableIntStateOf(0) }
    var routeRefreshToken by remember(date) { mutableIntStateOf(0) }
    // R07：轨迹数据截止时间 = 最后一次加载完成的时刻快照（不随 nowMs 走动）。
    var dailyRouteComputedAtMs by remember(date) { mutableStateOf<Long?>(null) }
    LaunchedEffect(date, routeExpanded, routeRefreshToken) {
        if (routeExpanded) {
            val stale = dailyRoute == null || date == java.time.LocalDate.now()
            if (stale) {
                val requestGeneration = ++routeLoadGeneration
                routeLoading = true
                try {
                    dailyRoute = try {
                        viewModel.loadDailyRoute(date, forceRefresh = date == java.time.LocalDate.now())
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                    // R07：数据截止时间 = 本次加载完成时刻快照（今天显示“已加载到…”）。
                    if (requestGeneration == routeLoadGeneration) {
                        dailyRouteComputedAtMs = if (date == java.time.LocalDate.now()) {
                            System.currentTimeMillis()
                        } else {
                            dailyRoute?.lastRecorded?.timeMs
                        }
                    }
                } finally {
                    // 只有当前代请求才复位 loading；被取消的旧任务不得清掉新请求的 loading。
                    if (requestGeneration == routeLoadGeneration) routeLoading = false
                }
            }
        }
    }
    // R06：优先消费新语义聚合（DayEpisodeBuilder）；首次进入触发后台计算，
    // 未就绪时回退旧 TimeChapterComposer（安全降级，不空白等待）。
    // P3：订阅投影状态流——算完立即重组，不再靠 readState 的当前值「碰运气」刷新。
    val dailyEpisodes by viewModel.dailyEpisodes.collectAsStateWithLifecycle()
    val episodeChapters = remember(dailyEpisodes, date, displayDay) {
        dailyEpisodes[date]?.let { episodesToChapters(it, displayDay) }
    }
    LaunchedEffect(date, displayDay) {
        viewModel.refreshDailyEpisodes(date)
    }
    val usingEpisodeProjection = episodeChapters != null
    val chapters = episodeChapters ?: remember(displayDay, dayStart, dayEnd) {
        TimeChapterComposer.compose(displayDay, dayStart, dayEnd)
    }
    val newestFirstChapters = remember(chapters) { newestFirstTimeChapters(chapters) }
    LaunchedEffect(date, gapAssessment, chapters) {
        DiagnosticLog.event("ui", "day_timeline_projected", mapOf(
            "date" to date.toString(),
            "rawGapCount" to day.gaps.size,
            "mergedGapCount" to gapAssessment.mergedGaps.size,
            "hiddenGapCount" to gapAssessment.hiddenGaps.size,
            "briefGapCount" to gapAssessment.briefGaps.size,
            "importantGapCount" to gapAssessment.importantGaps.size,
            "hiddenGapMs" to gapAssessment.hiddenDurationMs,
            "briefGapMs" to gapAssessment.briefDurationMs,
            "importantGapMs" to gapAssessment.importantDurationMs,
            "chapterCount" to chapters.size,
            // Record the active projection so recomputation and fallback can be distinguished.
            "projection" to if (usingEpisodeProjection) "day-episode" else "legacy-composer",
            "displayOrder" to "NEWEST_FIRST",
            "newestChapterStartMs" to chapters.lastOrNull()?.startMs,
            "oldestChapterStartMs" to chapters.firstOrNull()?.startMs,
        ))
    }
    LaunchedEffect(date, dailyRoute, routeExpanded) {
        DiagnosticLog.event("route", "daily_route_projected", mapOf(
            "date" to date.toString(),
            "panelExpanded" to routeExpanded,
            "routeLoaded" to (dailyRoute != null),
            "rejectedPointCount" to (dailyRoute?.rejectedPointCount ?: 0),
            "sectionCount" to (dailyRoute?.sections?.size ?: 0),
            "interruptionCount" to (dailyRoute?.interruptions?.size ?: 0),
            "observedDistanceMeters" to (dailyRoute?.observedDistanceMeters?.toLong() ?: 0L),
            "stabilityRadiusMeters" to DailyRouteBuilder.STABILITY_RADIUS_METERS,
        ))
    }
    val startedDate = viewModel.settings.startedAt.takeIf { it > 0L }?.let {
        Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
    }
    val today = LocalDate.now()
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(date, today) {
        while (date == today) {
            delay(30_000)
            nowMs = System.currentTimeMillis()
        }
    }
    val initialProbe = when {
        date == today -> nowMs.coerceIn(dayStart, dayEnd)
        chapters.isNotEmpty() -> chapters.last().startMs
        else -> dayStart + (dayEnd - dayStart) / 2
    }
    var probeMs by rememberSaveable(date.toEpochDay()) { mutableLongStateOf(initialProbe) }
    var probeTouched by rememberSaveable(date.toEpochDay()) { mutableStateOf(false) }
    var calendarOpen by rememberSaveable { mutableStateOf(false) }
    var gapSummaryOpen by rememberSaveable(date.toEpochDay()) { mutableStateOf(false) }
    var timeBandExpanded by rememberSaveable(date.toEpochDay()) { mutableStateOf(false) }
    val selectedChapter = remember(chapters, probeMs) {
        chapters.firstOrNull { probeMs in it.startMs until it.endMs }
    }
    val postedNotifications = remember(day.notifications) { day.notifications.filter { it.action == "POSTED" } }
    val probeSnapshot = remember(displayDay, probeMs) { displayDay.probeSnapshot(probeMs) }
    val currentChapter = remember(chapters, nowMs, date, today) {
        if (date == today) chapters.firstOrNull { nowMs in it.startMs until it.endMs } else null
    }
    val currentSnapshot = remember(displayDay, nowMs, date, today) {
        if (date == today) displayDay.probeSnapshot(nowMs) else ProbeSnapshot(null, emptyList(), emptyList(), emptyList())
    }
    val appUsageMs = remember(day.apps, dayStart, dayEnd) {
        intervalUnionDurationMs(
            day.apps.map { MillisInterval(it.startMs, it.endMs) },
            dayStart,
            dayEnd,
        )
    }
    // 第四批：缺口解释的唯一来源是有效投影——同一规则版本、同一截止时刻，与档案汇总、
    // 诊断导出共用（复核稿第 9 节第 4 条：不允许同一天两套说法）。
    val dayProjections by viewModel.dayProjections.collectAsStateWithLifecycle()
    // 投影还没算出来时用同一个纯函数就地算一版（少定位点证据引用），避免首帧闪一次红字。
    val fallbackProjection = remember(day.gaps, day.visits, dayStart, dayEnd) {
        projectCollectionGaps(
            gaps = day.gaps,
            points = emptyList(),
            visits = day.visits,
            windowStartMs = dayStart,
            windowEndMs = dayEnd,
            asOfMs = dayEnd,
            generatedAtMs = dayEnd,
        )
    }
    val projection = dayProjections[date] ?: fallbackProjection
    val dayNotice = remember(projection) { dayGapNotice(projection) }
    val coverage: ArchiveCoverageStatus? = remember(dayNotice, startedDate, date) {
        when {
            startedDate != null && date < startedDate -> ArchiveCoverageStatus("尚未开始记录", ArchiveCoverageKind.NOT_STARTED)
            // 只有"现在仍需要你处理"的采集/权限故障才升级成红状态；已经恢复的来源只给中性提示，
            // 不再让历史缺口天天挂红（复核稿第 6.4 节）。
            dayNotice == null -> null
            dayNotice.actionable -> ArchiveCoverageStatus(dayNotice.summary, ArchiveCoverageKind.INCOMPLETE)
            else -> ArchiveCoverageStatus(dayNotice.summary, ArchiveCoverageKind.BASIC)
        }
    }
    val unhealthy = remember(statuses, date, viewModel.settings.collectionEnabled) {
        if (date != today || !viewModel.settings.collectionEnabled) emptyList() else statuses.filter { status ->
            val source = runCatching { SourceId.valueOf(status.source) }.getOrNull()
            val state = runCatching { SourceState.valueOf(status.state) }.getOrNull()
            source != null && source != SourceId.SLEEP && viewModel.settings.sourceEnabled(source) &&
                state in setOf(SourceState.PERMISSION_REQUIRED, SourceState.SYSTEM_BLOCKED, SourceState.ERROR)
        }
    }
    LaunchedEffect(date, chapters) {
        if (date != today && !probeTouched && chapters.isNotEmpty()) probeMs = chapters.last().startMs
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val motion = LocalMotionPreference.current
    val showBackToNow by remember(date, today, listState) {
        derivedStateOf { date == today && listState.firstVisibleItemIndex > 2 }
    }
    val currentMomentIndex = (if (onBack != null) 1 else 0) + 2
    Box(Modifier.fillMaxSize()) {
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
      ) {
        if (onBack != null) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回档案") }
                    Text("返回档案", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        item {
            DateRangeHeader(
                title = date.archiveDateTitle(),
                subtitle = when {
                    date != today -> date.dayOfWeek.chineseLabel()
                    !viewModel.settings.collectionEnabled -> "今天 · 记录已暂停"
                    dayLoading -> "今天 · 正在读取记录"
                    else -> "今天 · 正在形成档案"
                },
                coverage = coverage,
                onCoverageClick = if (gapAssessment.visibleGaps.isEmpty()) null else ({ gapSummaryOpen = true }),
                onPrevious = {
                    val lower = startedDate
                    onSelectDate(if (lower != null) date.minusDays(1).coerceAtLeast(lower) else date.minusDays(1))
                },
                onNext = if (date < today) ({ onSelectDate(date.plusDays(1)) }) else null,
                onCalendar = { calendarOpen = true },
                onToday = if (date != today) ({ onSelectDate(today) }) else null,
            )
        }
        item(key = "ask-ai") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onOpenAi) {
                    Icon(Icons.Outlined.Apps, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("问 AI 回顾")
                }
            }
        }

        if (date == today && startedDate?.let { date >= it } != false) {
            item(key = "current-moment") {
                // U04：暂停状态必须与“现在”区同一来源——暂停时不再显示“正在记录”，
                // 而是显示“记录已暂停 · 已有记录仍可查看”。dayLoading 时先显示读取中。
                val paused = !viewModel.settings.collectionEnabled
                SelectedTimeSummary(
                    probeMs = nowMs,
                    chapter = currentChapter,
                    snapshot = currentSnapshot,
                    onOpen = currentChapter?.let { chapter ->
                        { onOpenChapter(chapter.startMs, chapter.endMs) }
                    },
                    label = "现在 · ${formatClock(nowMs)}",
                    paused = paused,
                    loading = dayLoading,
                )
            }
        }

        if (unhealthy.isNotEmpty()) {
            item {
                CurrentCollectionNotice(
                    labels = unhealthy.map { it.source.archiveSourceLabel() },
                    onClick = onOpenCollectionHealth,
                )
            }
        }
        if (date == today && !viewModel.settings.collectionEnabled) {
            item {
                CurrentCollectionPausedNotice(onClick = onOpenCollectionHealth)
            }
        }

        item(key = "day-overview-panel") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DayFactSummary(
                    appUsageMs = appUsageMs,
                    notifications = postedNotifications.size,
                    steps = calculateSteps(day.steps),
                    tripDistanceMeters = dailyRoute?.trips?.sumOf { it.distanceMeters },
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (date == today) "今日时间线" else "当天时间线", style = MaterialTheme.typography.titleLarge)
                    ArchiveTimeBand(
                        startMs = dayStart,
                        endMs = dayEnd,
                        placeSpans = day.visits.map { ArchiveSpan(it.startMs, it.endMs, it.name) },
                        appSpans = day.apps.map { ArchiveSpan(it.startMs, it.endMs, it.appLabel) },
                        notificationTicks = postedNotifications.toArchiveTicks(),
                        gaps = gapAssessment.visibleGaps.map { it.toArchiveGap() },
                        probeMs = probeMs.takeIf { probeTouched },
                        onTimeSelected = { probeMs = it; probeTouched = true },
                        compact = false,
                    )
                }
                dayNotice?.let { notice -> DayGapDetail(notice) }
            }
        }
        item(key = "day-overview-route") {
            DailyRouteSection(
                viewModel = viewModel,
                onOpenTrips = onOpenTrips,
                onOpenLocation = onOpenLocation,
                onOpenTrip = onOpenTrip,
                isToday = date == today,
                route = dailyRoute,
                loading = routeLoading,
                onRefresh = if (date == today) ({ routeRefreshToken++ }) else null,
                dataCutoffMs = dailyRouteComputedAtMs,
            )
        }

        if (startedDate != null && date < startedDate) {
            item {
                ArchiveEmptyState(
                    title = "这一天还没有档案",
                    detail = "24π 从 ${startedDate.format(DateTimeFormatter.ofPattern("M月d日"))} 起开始忠实记录。",
                )
            }
        } else {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(if (date == today) "最近记录" else "当天记录", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (date == today) "从现在向下回看今天更早的记录" else "从当天最后一段向下回看更早记录",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (chapters.isEmpty()) {
                // U03：读取中不是无数据。切换日期后首个 loading 状态 data 为空快照，
                // chapters 为空只允许在成功读取且确实为空后显示“还没有记录”。
                if (dayLoading) {
                    item {
                        Text(
                            "正在读取当天记录…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 28.dp),
                        )
                    }
                } else {
                    item {
                        ArchiveEmptyState(
                            title = "这一天还没有可浏览的记录",
                            detail = if (gapAssessment.importantGaps.isEmpty()) {
                                "继续使用手机后，记录会按时间出现在这里。"
                            } else {
                                "部分时间没有记录到数据，暂时无法形成时间段。"
                            },
                        )
                    }
                }
            } else {
                items(newestFirstChapters, key = { it.key }) { chapter ->
                    ChapterSummaryCard(
                        chapter = chapter,
                        // 按交集解释：卡片只对自己真正缺失的那段负责（复核稿第 6.1 节）。
                        notice = cardGapNotice(chapter.startMs, chapter.endMs, projection),
                        onClick = { onOpenChapter(chapter.startMs, chapter.endMs) },
                    )
                }
            }
            // U05：统计/周回顾/轨迹/时间图已移入顶部入口面板，列表尾部不再重复放置
            // 同一套大卡片（方案 5.1-6 消除双份展示）。
            if (probeTouched) item {
                SelectedTimeSummary(
                    probeMs = probeMs,
                    chapter = selectedChapter,
                    snapshot = probeSnapshot,
                    onOpen = selectedChapter?.let { chapter ->
                        { onOpenChapter(chapter.startMs, chapter.endMs) }
                    },
                )
            }
        }
      }
      if (showBackToNow) {
          Button(
              onClick = { scope.launch {
                  if (motion.enabled) listState.animateScrollToItem(currentMomentIndex)
                  else listState.scrollToItem(currentMomentIndex)
              } },
              modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
          ) { Text("回到现在") }
      }
    }

    if (calendarOpen) {
        ArchiveDatePicker(
            initialDate = date,
            earliestDate = startedDate ?: date.minusYears(10),
            latestDate = today,
            onDismiss = { calendarOpen = false },
            onSelected = {
                onSelectDate(it)
                calendarOpen = false
            },
        )
    }
    if (gapSummaryOpen) {
        LifeLogDetailSheet(
            title = "${date.format(DateTimeFormatter.ofPattern("M月d日"))}未记录的时间",
            subtitle = "这些是过去没有留下数据的时间，不代表当前仍有故障",
            onDismiss = { gapSummaryOpen = false },
            contentKey = "day-gaps-${date.toEpochDay()}",
        ) {
            items(gapAssessment.visibleGaps, key = { "day-gap-${it.id}-${it.source}" }) { gap ->
                GapRow(
                    startMs = maxOf(gap.startMs, dayStart),
                    endMs = minOf(gap.endMs, dayEnd),
                    sourceLabel = gap.source.archiveSourceLabel(),
                    detail = gap.userFacingGapReason(),
                )
            }
            if (gapAssessment.hiddenGaps.isNotEmpty()) {
                item {
                    Text(
                        "另有 ${gapAssessment.hiddenGaps.size} 次不足 2 分钟的短暂波动，仅保留在诊断记录中，不影响当天完整度。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                TextButton(onClick = { gapSummaryOpen = false; onOpenCollectionHealth() }) {
                    Text("查看当前采集状态")
                }
            }
        }
    }
}

@Composable
private fun SevenDayReviewCard(review: SevenDayReviewSummary) {
    val app = review[ReviewMetric.APP_USAGE]
    val notices = review[ReviewMetric.NOTIFICATIONS]
    val places = review[ReviewMetric.PLACES]
    // R13：近 7 天回顾默认收起（次要信息不抢首屏），与“当天统计”主次分明。
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("最近 7 天", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                if (expanded) "收起近 7 天回顾" else "展开近 7 天回顾",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            Text(
                "应用 ${formatDuration(app.currentObservedValue)} · ${notices.currentObservedValue} 条通知 · ${places.currentObservedValue} 个地点记录",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                review.sevenDayComparisonText(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Full-screen chapter: context first, then raw facts in one chronological coordinate. */
@Composable
fun TimeChapterDetailScreen(
    viewModel: MainViewModel,
    chapterStartMs: Long,
    chapterEndMs: Long,
    onBack: () -> Unit,
    initialEvidenceKind: EvidenceKind? = null,
    initialEvidenceId: Long? = null,
    onInitialEvidenceDismiss: (() -> Unit)? = null,
) {
    val date by viewModel.selectedDate.collectAsStateWithLifecycle()
    val dayState by viewModel.day.collectAsStateWithLifecycle()
    // U03：时段详情只在状态与当前日期匹配时绘制；读取中显示加载而不是“找不到时段”。
    val dayLoading = dayState.date != date || dayState.loading
    val day = if (dayState.date == date) dayState.data else TimelineDay()
    val (dayStart, dayEnd) = remember(date) { dayBounds(date) }
    val displayDay = remember(day, dayStart, dayEnd) {
        day.copy(gaps = assessCollectionGaps(day.gaps, dayStart, dayEnd).visibleGaps)
    }
    // P3：详情与首页同源——消费同一版 DayEpisode 投影，否则章卡边界不同会让
    // 「点进去的那张卡」变成相邻的另一张（子代理复核 #2）。未就绪时同样降级旧 composer。
    val detailEpisodes by viewModel.dailyEpisodes.collectAsStateWithLifecycle()
    LaunchedEffect(date) { viewModel.refreshDailyEpisodes(date) }
    val detailEpisodeChapters = remember(detailEpisodes, date, displayDay) {
        detailEpisodes[date]?.let { episodesToChapters(it, displayDay) }
    }
    val chapters = detailEpisodeChapters ?: remember(displayDay, dayStart, dayEnd) {
        TimeChapterComposer.compose(displayDay, dayStart, dayEnd)
    }
    val chapter = remember(chapters, chapterStartMs, chapterEndMs) {
        chapters.firstOrNull { it.startMs == chapterStartMs && it.endMs == chapterEndMs }
            ?: chapters.firstOrNull {
                chapterEndMs <= chapterStartMs && chapterStartMs in it.startMs until it.endMs
            }
            ?: chapters.maxByOrNull { overlapMs(it.startMs, it.endMs, chapterStartMs, chapterEndMs) }
                ?.takeIf { overlapMs(it.startMs, it.endMs, chapterStartMs, chapterEndMs) > 0L }
    }
    var selectedEvidence by remember(chapterStartMs, chapterEndMs, initialEvidenceKind, initialEvidenceId) {
        mutableStateOf<ArchiveEvidence?>(null)
    }
    // R14：分组独立展开——每个分组自己的展开状态（展开通知 A 再展开应用 B，A 不收起）。
    var expandedFlowKeys by rememberSaveable(chapterStartMs, chapterEndMs) { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(displayDay, initialEvidenceKind, initialEvidenceId) {
        if (initialEvidenceKind != null && initialEvidenceId != null && selectedEvidence == null) {
            selectedEvidence = displayDay.findEvidence(initialEvidenceKind, initialEvidenceId)
        }
    }

    if (dayLoading) {
        Column(
            Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            ChapterPageHeader("正在读取当天记录", null, onBack)
            ArchiveEmptyState("记录加载中", "正在读取这一天的内容，请稍候。")
        }
        return
    }

    if (chapter == null) {
        Column(
            Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            ChapterPageHeader("时间段已更新", null, onBack)
            ArchiveEmptyState("找不到原来的时间段", "记录可能刚刚完成整理。返回当天后可以看到最新内容。")
        }
        return
    }

    val postedNotifications = remember(chapter.notifications) { chapter.notifications.filter { it.action == "POSTED" } }
    val evidenceFlow = remember(chapter, postedNotifications) {
        chapter.toEvidenceFlow(postedNotifications)
    }
    val displayedEvidenceFlow = remember(evidenceFlow, expandedFlowKeys) {
        evidenceFlow.toDisplayItems(expandedFlowKeys)
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            ChapterPageHeader(
                title = "${formatClock(chapter.startMs)}–${formatClock(chapter.endMs)}",
                // U21：地点未记录时不重复占位；正文用中性“这段时间”，详情内才解释。
                subtitle = "${chapter.place?.name ?: "这段时间"} · ${formatDuration(chapter.endMs - chapter.startMs)}",
                onBack = onBack,
            )
        }
        chapter.place?.address?.takeIf { it.isNotBlank() }?.let { address ->
            item {
                Text(
                    address,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        item {
            RecordGapExplanation(viewModel, chapter.startMs, chapter.endMs)
            ChapterSectionHeader(
                "详细记录",
                "按发生时间排列：${chapter.apps.size} 段应用 · ${postedNotifications.size} 条通知 · ${chapter.placeVisits.size} 段停留",
            )
        }
        if (evidenceFlow.isEmpty()) {
            item { SectionEmptyLine("这个时间段没有可展开的详细记录") }
        } else {
            items(displayedEvidenceFlow, key = { it.key }) { row ->
                when (row) {
                    is ChapterDisplayItem.Parent -> ChapterFlowRow(
                        item = row.item,
                        expanded = row.item.key in expandedFlowKeys,
                        onToggle = {
                            expandedFlowKeys = if (row.item.key in expandedFlowKeys) {
                                expandedFlowKeys - row.item.key
                            } else {
                                expandedFlowKeys + row.item.key
                            }
                        },
                        onEvidence = { selectedEvidence = it },
                    )
                    is ChapterDisplayItem.AppChild -> AppFlowChildRow(
                        session = row.session,
                        onClick = { selectedEvidence = ArchiveEvidence.App(row.session) },
                    )
                    is ChapterDisplayItem.NotificationChild -> NotificationFlowChildRow(
                        event = row.event,
                        onClick = { selectedEvidence = ArchiveEvidence.Notification(row.event) },
                    )
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    selectedEvidence?.let { evidence ->
        ArchiveEvidenceSheet(
            evidence = if (evidence is ArchiveEvidence.Place) day.visits.firstOrNull { it.id == evidence.value.id }?.let(ArchiveEvidence::Place) ?: evidence else evidence,
            viewModel = viewModel,
            onDismiss = {
                if (initialEvidenceKind != null) onInitialEvidenceDismiss?.invoke()
                else selectedEvidence = null
            },
        )
    }
}

private sealed interface ChapterFlowItem {
    val key: String
    val startMs: Long
    val endMs: Long

    data class Place(val visit: PlaceVisitView) : ChapterFlowItem {
        override val key = "place-${visit.id}-${visit.startMs}"
        override val startMs = visit.startMs
        override val endMs = visit.endMs
    }

    data class App(val session: AppSessionEntity) : ChapterFlowItem {
        override val key = "app-${session.id}-${session.startMs}"
        override val startMs = session.startMs
        override val endMs = session.endMs
    }

    data class AppBurst(val sessions: List<AppSessionEntity>) : ChapterFlowItem {
        override val key = "app-burst-${sessions.first().startMs}-${sessions.joinToString("-") { it.id.toString() }}"
        override val startMs = sessions.first().startMs
        override val endMs = sessions.maxOf { it.endMs }
    }

    data class Notifications(val events: List<NotificationEventEntity>) : ChapterFlowItem {
        override val key = "notifications-${events.first().packageName}-${events.first().occurredMs / NOTIFICATION_FLOW_BUCKET_MS}"
        override val startMs = events.first().occurredMs
        override val endMs = events.last().occurredMs + 1L
    }

    data class Gap(val value: CollectionGapEntity) : ChapterFlowItem {
        override val key = "gap-${value.id}-${value.source}-${value.startMs}"
        override val startMs = value.startMs
        override val endMs = value.endMs
    }

    data class PlaceContinuityGap(val range: TimeChapterRange) : ChapterFlowItem {
        override val key = "place-gap-${range.startMs}-${range.endMs}"
        override val startMs = range.startMs
        override val endMs = range.endMs
    }
}

private sealed interface ChapterDisplayItem {
    val key: String

    data class Parent(val item: ChapterFlowItem) : ChapterDisplayItem {
        override val key = "parent-${item.key}"
    }

    data class AppChild(val parentKey: String, val session: AppSessionEntity) : ChapterDisplayItem {
        override val key = "$parentKey-app-child-${session.id}-${session.startMs}"
    }

    data class NotificationChild(
        val parentKey: String,
        val event: NotificationEventEntity,
    ) : ChapterDisplayItem {
        override val key = "$parentKey-notification-child-${event.id}-${event.occurredMs}"
    }
}

private data class ProbeSnapshot(
    val place: PlaceVisitView?,
    val activeApps: List<AppSessionEntity>,
    val nearbyNotifications: List<NotificationEventEntity>,
    val activeGaps: List<CollectionGapEntity>,
)

private sealed interface ArchiveEvidence {
    data class App(val value: AppSessionEntity) : ArchiveEvidence
    data class Notification(val value: NotificationEventEntity) : ArchiveEvidence
    data class Place(val value: PlaceVisitView) : ArchiveEvidence
    data class Gap(val value: CollectionGapEntity) : ArchiveEvidence
}

private fun TimelineDay.findEvidence(kind: EvidenceKind, id: Long): ArchiveEvidence? = when (kind) {
    EvidenceKind.APP_SESSION -> apps.firstOrNull { it.id == id }?.let(ArchiveEvidence::App)
    EvidenceKind.NOTIFICATION -> notifications.firstOrNull { it.id == id }?.let(ArchiveEvidence::Notification)
    EvidenceKind.PLACE_VISIT -> visits.firstOrNull { it.id == id }?.let(ArchiveEvidence::Place)
    EvidenceKind.COLLECTION_GAP -> gaps.firstOrNull { it.id == id }?.let(ArchiveEvidence::Gap)
    EvidenceKind.LOCATION_POINT -> null
}

@Composable
private fun DayFactSummary(
    appUsageMs: Long,
    notifications: Int,
    steps: Long,
    tripDistanceMeters: Double?,
) {
    val facts = listOf(
        "屏幕使用" to compactHomeDuration(appUsageMs),
        "通知" to "$notifications 条",
        "步数" to if (steps > 0L) String.format(Locale.CHINA, "%,d", steps) else "—",
        "行程" to (tripDistanceMeters?.let(::compactHomeDistance) ?: "—"),
    )
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
            facts.chunked(2).forEachIndexed { rowIndex, pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    pair.forEach { (label, value) ->
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                        }
                    }
                }
                if (rowIndex == 0) HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

private fun compactHomeDuration(durationMs: Long): String {
    val minutes = (durationMs / 60_000L).coerceAtLeast(0L)
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours > 0 && rest > 0 -> "${hours}时${rest}分"
        hours > 0 -> "${hours}小时"
        else -> "${rest}分钟"
    }
}

private fun compactHomeDistance(meters: Double): String = if (meters < 1_000) {
    "${meters.toInt()}m"
} else {
    String.format(Locale.CHINA, "%.1fkm", meters / 1_000.0)
}

@Composable
private fun SelectedTimeSummary(
    probeMs: Long,
    chapter: TimeChapter?,
    snapshot: ProbeSnapshot,
    onOpen: (() -> Unit)?,
    label: String? = null,
    paused: Boolean = false,
    loading: Boolean = false,
) {
    val motion = LocalMotionPreference.current
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
    Column(
        Modifier.fillMaxWidth().padding(18.dp).animateContentSize(animationSpec = motion.spec(200)),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label ?: formatClock(probeMs), style = if (label != null) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge)
        if (chapter == null && snapshot.place == null && snapshot.activeApps.isEmpty() && snapshot.nearbyNotifications.isEmpty() && snapshot.activeGaps.isEmpty()) {
            // U04：空态文案必须来自采集状态，不能只凭“现在”前缀猜成“正在记录”。
            // 暂停时主文案表达“可继续查看”，读取中不先展示无数据结论。
            val emptyText = when {
                paused -> "记录已暂停 · 已有记录仍可查看"
                loading -> "正在读取今天的记录"
                label?.startsWith("现在") == true -> "记录已开启，暂时没有新活动"
                else -> "这个时刻没有已记录事件；空白不等于采集中断。"
            }
            Text(
                emptyText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        if (snapshot.activeGaps.isNotEmpty()) {
                            snapshot.activeGaps.joinToString("、") { it.source.archiveSourceLabel() } + "未记录"
                        } else snapshot.place?.name ?: chapter?.place?.name ?: "地点未确认",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        snapshot.description(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (onOpen != null) TextButton(onClick = onOpen) { Text("查看时间段"); Icon(Icons.Outlined.ChevronRight, null) }
            }
        }
    }
    }
}

@Composable
private fun CurrentCollectionPausedNotice(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(Modifier.size(8.dp), shape = CircleShape, color = MaterialTheme.colorScheme.outline) {}
        Column(Modifier.weight(1f)) {
            Text("当前记录已暂停", style = MaterialTheme.typography.titleSmall)
            Text("暂停期间不会采集；恢复后只从新的系统记录继续。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Outlined.ChevronRight, "打开采集状态", tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ChapterSummaryCard(chapter: TimeChapter, notice: CardGapNotice?, onClick: () -> Unit) {
    val place = chapter.place?.name
    // 第四批：缺口提示按"与这张卡真正相交"的时长判定，并只在卡片上出现一次。
    // Use only the unknown overlap within this card.
    // U21/T33-34：只有地点时把地点作为时段身份；无地点但有活动时不再逐条
    // 显示"地点未记录"占位，改用中性"这段时间"，详情内才给出地点解释。
    val hasActivity = chapter.topApps.isNotEmpty() || chapter.notificationCount > 0
    val identity = place ?: if (hasActivity) "这段时间" else "无记录"
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.width(66.dp).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(formatClock(chapter.startMs), style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace)
            Text(formatClock(chapter.endMs), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(Modifier.size(10.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primary) {}
            Surface(Modifier.width(2.dp).height(76.dp), color = MaterialTheme.colorScheme.outlineVariant) {}
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(identity, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
            }
            Text(formatDuration(chapter.endMs - chapter.startMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(chapter.cardDescription(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            notice?.let {
                Text(if (it.severe) it.detail else it.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
            }
            HorizontalDivider(Modifier.padding(top = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/**
 * 第四批：日级缺口构成明细（入口卡原位展开）。
 *
 * 首页那行"定位信息不足 N 分钟"必须能点开看到它由哪些时段构成，否则用户只能看到结论、
 * 无法核对（复核稿第 4 节第 5 条）。
 */
@Composable
private fun DayGapDetail(notice: DayGapNotice, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    UiExpandableGroup(
        title = if (notice.actionable) "有采集故障需要处理" else "定位信息不足 ${formatGapSpan(notice.unknownMs)}",
        expanded = expanded,
        onToggle = { expanded = !expanded },
        modifier = modifier,
        summary = notice.summary,
        content = {
            notice.spans.forEach { span ->
                Text(span, style = MaterialTheme.typography.bodySmall)
            }
            if (notice.detail.isNotBlank()) {
                Text(
                    notice.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

@Composable
private fun CurrentCollectionNotice(labels: List<String>, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text("当前采集需要处理", style = MaterialTheme.typography.titleSmall)
            Text(labels.distinct().joinToString("、") + " 尚未正常更新", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Outlined.ChevronRight, "查看采集状态", tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ChapterPageHeader(title: String, subtitle: String?, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace) }
        }
    }
}

@Composable
private fun ChapterSectionHeader(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ChapterFlowRow(
    item: ChapterFlowItem,
    expanded: Boolean,
    onToggle: () -> Unit,
    onEvidence: (ArchiveEvidence) -> Unit,
) {
    when (item) {
        is ChapterFlowItem.Place -> EvidenceRow(
            timeLabel = formatClock(item.startMs),
            title = item.visit.name,
            supportingText = item.visit.address.takeIf { it.isNotBlank() } ?: "由位置点整理出的停留",
            trailing = formatDuration(item.endMs - item.startMs),
            accentColor = MaterialTheme.colorScheme.primary,
            leading = { Icon(Icons.Outlined.LocationOn, null, tint = MaterialTheme.colorScheme.primary) },
            onClick = { onEvidence(ArchiveEvidence.Place(item.visit)) },
        )

        is ChapterFlowItem.App -> EvidenceRow(
            timeLabel = formatClock(item.startMs),
            title = item.session.appLabel,
            supportingText = "${formatClock(item.startMs)}–${formatClock(item.endMs)}",
            trailing = formatDuration(item.endMs - item.startMs),
            accentColor = MaterialTheme.colorScheme.tertiary,
            leading = { ArchiveAppIcon(item.session.packageName, item.session.appLabel) },
            onClick = { onEvidence(ArchiveEvidence.App(item.session)) },
        )

        is ChapterFlowItem.AppBurst -> {
            val durationMs = item.sessions.sumOf { (it.endMs - it.startMs).coerceAtLeast(0L) }
            val labels = item.sessions.map { it.appLabel }.distinct().take(3).joinToString("、")
            EvidenceRow(
                timeLabel = formatClock(item.startMs),
                title = "连续切换 · ${item.sessions.size} 次",
                modifier = Modifier.semantics { stateDescription = if (expanded) "已展开" else "已折叠" },
                supportingText = "$labels · ${if (expanded) "点击收起" else "点击查看每次使用"}",
                trailing = formatDuration(durationMs),
                accentColor = MaterialTheme.colorScheme.tertiary,
                leading = {
                    Icon(
                        if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        if (expanded) "收起" else "展开",
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                },
                onClick = onToggle,
            )
        }

        is ChapterFlowItem.Notifications -> {
            val first = item.events.first()
            if (item.events.size == 1) {
                EvidenceRow(
                    timeLabel = formatClock(first.occurredMs),
                    title = first.notificationTitle?.takeIf { it.isNotBlank() } ?: first.appLabel,
                    supportingText = first.notificationBody?.takeIf { it.isNotBlank() }
                        ?: first.contentState.archiveContentFallback(),
                    trailing = "1 条",
                    accentColor = MaterialTheme.colorScheme.secondary,
                    leading = { Icon(Icons.Outlined.NotificationsNone, null, tint = MaterialTheme.colorScheme.secondary) },
                    onClick = { onEvidence(ArchiveEvidence.Notification(first)) },
                )
            } else {
                EvidenceRow(
                    timeLabel = formatClock(item.startMs),
                    title = first.appLabel,
                    modifier = Modifier.semantics { stateDescription = if (expanded) "已展开" else "已折叠" },
                    supportingText = "5 分钟内 ${item.events.size} 条 · ${if (expanded) "点击收起" else "点击展开通知"}",
                    trailing = "${item.events.size} 条",
                    accentColor = MaterialTheme.colorScheme.secondary,
                    leading = {
                        Icon(
                            if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            if (expanded) "收起" else "展开",
                            tint = MaterialTheme.colorScheme.secondary,
                        )
                    },
                    onClick = onToggle,
                )
            }
        }

        is ChapterFlowItem.Gap -> GapRow(
            startMs = item.startMs,
            endMs = item.endMs,
            sourceLabel = item.value.source.archiveSourceLabel(),
            detail = item.value.userFacingGapReason(),
            onClick = { onEvidence(ArchiveEvidence.Gap(item.value)) },
        )

        is ChapterFlowItem.PlaceContinuityGap -> GapRow(
            startMs = item.startMs,
            endMs = item.endMs,
            sourceLabel = "地点",
            detail = "这段时间没有记录到位置；前后的地点记录不会被用来补写轨迹。",
        )
    }
}

@Composable
private fun AppFlowChildRow(session: AppSessionEntity, onClick: () -> Unit) {
    EvidenceRow(
        timeLabel = formatClock(session.startMs),
        title = session.appLabel,
        supportingText = "${formatClock(session.startMs)}–${formatClock(session.endMs)}",
        trailing = formatDuration(session.endMs - session.startMs),
        accentColor = MaterialTheme.colorScheme.tertiary,
        leading = { ArchiveAppIcon(session.packageName, session.appLabel) },
        onClick = onClick,
    )
}

@Composable
private fun NotificationFlowChildRow(event: NotificationEventEntity, onClick: () -> Unit) {
    EvidenceRow(
        timeLabel = formatClock(event.occurredMs),
        title = event.notificationTitle?.takeIf { it.isNotBlank() } ?: event.appLabel,
        supportingText = event.notificationBody?.takeIf { it.isNotBlank() }
            ?: event.contentState.archiveContentFallback(),
        accentColor = MaterialTheme.colorScheme.secondary,
        onClick = onClick,
    )
}

// v0.15 性能：应用图标内存缓存。LazyColumn 快速滚动时同一包名只解码一次，
// 消除组合期主线程 IPC+位图解码造成的掉帧（工程师审查 P0-1）。
private val appIconCache = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>): Boolean = size > 256
}

@Composable
private fun ArchiveAppIcon(packageName: String, label: String) {
    val context = LocalContext.current
    var bitmap by remember(packageName) { mutableStateOf(appIconCache[packageName]) }
    LaunchedEffect(packageName) {
        if (bitmap == null) {
            val loaded = withContext(Dispatchers.IO) {
                runCatching { context.packageManager.getApplicationIcon(packageName).toBitmap(48, 48).asImageBitmap() }.getOrNull()
            }
            if (loaded != null) {
                appIconCache[packageName] = loaded
                bitmap = loaded
            }
        }
    }
    if (bitmap != null) {
        Image(bitmap!!, null, Modifier.size(32.dp).clip(CircleShape))
    } else {
        Surface(Modifier.size(32.dp), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainer) {
            Box(contentAlignment = Alignment.Center) { Text(label.take(1), style = MaterialTheme.typography.labelMedium) }
        }
    }
}

@Composable
private fun SectionEmptyLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
}

@Composable
private fun ArchiveEmptyState(title: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ArchiveEvidenceSheet(evidence: ArchiveEvidence, viewModel: MainViewModel, onDismiss: () -> Unit) {
    when (evidence) {
        is ArchiveEvidence.App -> LifeLogDetailSheet(
            title = evidence.value.appLabel,
            subtitle = "应用使用记录",
            onDismiss = onDismiss,
            contentKey = "app-${evidence.value.id}",
        ) {
            item { EvidenceDetailLine("开始", evidence.value.startMs.archiveDateTime()) }
            item { EvidenceDetailLine("结束", evidence.value.endMs.archiveDateTime()) }
            item { EvidenceDetailLine("持续", formatDuration(evidence.value.endMs - evidence.value.startMs)) }
            item {
                // U16：技术字段收进可展开区，正文先让用户读到时间与时长。
                var techExpanded by remember(evidence.value.id) { mutableStateOf(false) }
                UiExpandableGroup(
                    title = "技术信息",
                    expanded = techExpanded,
                    onToggle = { techExpanded = !techExpanded },
                ) {
                    EvidenceDetailLine("应用包名", evidence.value.packageName)
                }
            }
        }
        is ArchiveEvidence.Notification -> LifeLogDetailSheet(
            title = evidence.value.appLabel,
            subtitle = evidence.value.occurredMs.archiveDateTime(),
            onDismiss = onDismiss,
            contentKey = "notice-${evidence.value.id}",
        ) {
            item { EvidenceDetailLine("标题", evidence.value.notificationTitle?.takeIf { it.isNotBlank() } ?: "未保存") }
            item { EvidenceDetailLine("正文", evidence.value.notificationBody?.takeIf { it.isNotBlank() } ?: evidence.value.contentState.archiveContentFallback()) }
            item { EvidenceDetailLine("发生时间", evidence.value.occurredMs.archiveDateTime()) }
            item { EvidenceDetailLine("移除状态", evidence.value.removedMs?.let { "${it.archiveDateTime()} 已移除" } ?: "未记录移除时间") }
            item {
                // U16：技术字段收进可展开区（U22 展开行为统一）。
                var techExpanded by remember(evidence.value.id) { mutableStateOf(false) }
                UiExpandableGroup(
                    title = "技术信息",
                    expanded = techExpanded,
                    onToggle = { techExpanded = !techExpanded },
                ) {
                    EvidenceDetailLine("记录方式", evidence.value.captureOrigin.archiveCaptureOrigin())
                }
            }
            item { EvidenceFactNote("这里显示的是收到通知时系统提供的内容，不代表它现在仍在通知栏。") }
        }
        is ArchiveEvidence.Place -> LifeLogDetailSheet(
            title = evidence.value.name,
            subtitle = "由位置点整理出的停留",
            onDismiss = onDismiss,
            contentKey = "place-${evidence.value.id}",
        ) {
            item { EvidenceDetailLine("进入", evidence.value.startMs.archiveDateTime()) }
            item { EvidenceDetailLine("离开", evidence.value.endMs.archiveDateTime()) }
            item { EvidenceDetailLine("持续", formatDuration(evidence.value.endMs - evidence.value.startMs)) }
            item { EvidenceDetailLine("完整地址", evidence.value.address.ifBlank { "尚无可读名称" }) }
            item { VisitCorrectionEditor(viewModel, evidence.value) }
            item {
                // U16：技术字段收进可展开区。
                var techExpanded by remember(evidence.value.id) { mutableStateOf(false) }
                UiExpandableGroup(
                    title = "技术信息",
                    expanded = techExpanded,
                    onToggle = { techExpanded = !techExpanded },
                ) {
                    EvidenceDetailLine("坐标", "%.6f, %.6f".format(evidence.value.latitude, evidence.value.longitude))
                    EvidenceDetailLine("识别置信度", "${(evidence.value.confidence * 100).toInt()}%")
                }
            }
            item { EvidenceFactNote("这次停留由本机位置点整理形成；位置点仍保存在本机数据库和加密备份中。") }
        }
        is ArchiveEvidence.Gap -> LifeLogDetailSheet(
            title = "${evidence.value.source.archiveSourceLabel()}未记录的时间",
            subtitle = "${formatClock(evidence.value.startMs)}–${formatClock(evidence.value.endMs)}",
            onDismiss = onDismiss,
            contentKey = "gap-${evidence.value.id}",
        ) {
            item { EvidenceDetailLine("开始", evidence.value.startMs.archiveDateTime()) }
            item { EvidenceDetailLine("结束", evidence.value.endMs.archiveDateTime()) }
            item { EvidenceDetailLine("持续", formatDuration(evidence.value.endMs - evidence.value.startMs)) }
            item { EvidenceDetailLine("说明", evidence.value.userFacingGapReason()) }
            item { RecordGapExplanation(viewModel, evidence.value.startMs, evidence.value.endMs) }
            item {
                // U16：诊断代码收进技术区。
                var techExpanded by remember(evidence.value.id) { mutableStateOf(false) }
                UiExpandableGroup(
                    title = "技术信息",
                    expanded = techExpanded,
                    onToggle = { techExpanded = !techExpanded },
                ) {
                    EvidenceDetailLine("诊断代码", evidence.value.reason.ifBlank { "UNKNOWN" })
                }
            }
            item { EvidenceFactNote("这是原始采集缺口；如有停留等证据，展开记录依据可查看实测、推断与未知部分。") }
        }
    }
}

@Composable
private fun EvidenceDetailLine(label: String, value: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun EvidenceFactNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ArchiveDatePicker(
    initialDate: LocalDate,
    earliestDate: LocalDate,
    latestDate: LocalDate,
    onDismiss: () -> Unit,
    onSelected: (LocalDate) -> Unit,
) {
    val selectableDates = remember(earliestDate, latestDate) {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                val candidate = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
                return candidate in earliestDate..latestDate
            }

            override fun isSelectableYear(year: Int): Boolean = year in earliestDate.year..latestDate.year
        }
    }
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initialDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        yearRange = earliestDate.year..latestDate.year,
        selectableDates = selectableDates,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    state.selectedDateMillis?.let { onSelected(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                },
            ) { Text("查看") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    ) { DatePicker(state = state) }
}

private fun TimeChapter.cardDescription(): String = buildList {
    if (topApps.isNotEmpty()) add(topApps.joinToString(" · ") { "${it.appLabel} ${formatDuration(it.durationMs)}" })
    if (notificationCount > 0) add("$notificationCount 条通知")
    if (hasAnyGap) add("部分时间未记录")
}.joinToString("  ·  ").ifBlank { "没有应用或通知事件" }

private const val APP_BURST_MAX_SESSION_MS = 90_000L
private const val APP_BURST_MAX_GAP_MS = 30_000L
private const val NOTIFICATION_FLOW_BUCKET_MS = 5 * 60_000L

/**
 * Produces one chronological stream. Clusters only reduce visual repetition; every
 * original entity remains reachable by expanding its cluster.
 */
private fun TimeChapter.toEvidenceFlow(
    postedNotifications: List<NotificationEventEntity>,
): List<ChapterFlowItem> = buildList {
    addAll(placeVisits.map(ChapterFlowItem::Place))
    addAll(apps.toAppFlowItems())
    addAll(
        postedNotifications
            .groupBy { it.packageName to (it.occurredMs / NOTIFICATION_FLOW_BUCKET_MS) }
            .values
            .map { ChapterFlowItem.Notifications(it.sortedBy { event -> event.occurredMs }) },
    )
    addAll(gaps.map(ChapterFlowItem::Gap))
    addAll(placeContinuityGaps.map(ChapterFlowItem::PlaceContinuityGap))
}.sortedWith(
    compareBy<ChapterFlowItem> { it.startMs }
        .thenBy {
            when (it) {
                is ChapterFlowItem.Place -> 0
                is ChapterFlowItem.Gap, is ChapterFlowItem.PlaceContinuityGap -> 1
                is ChapterFlowItem.App, is ChapterFlowItem.AppBurst -> 2
                is ChapterFlowItem.Notifications -> 3
            }
        }
        .thenBy { it.endMs }
        .thenBy { it.key },
)

private fun List<ChapterFlowItem>.toDisplayItems(expandedKeys: Set<String>): List<ChapterDisplayItem> =
    buildList {
        this@toDisplayItems.forEach { item ->
            add(ChapterDisplayItem.Parent(item))
            if (item.key !in expandedKeys) return@forEach
            when (item) {
                is ChapterFlowItem.AppBurst -> item.sessions.forEach { session ->
                    add(ChapterDisplayItem.AppChild(item.key, session))
                }
                is ChapterFlowItem.Notifications -> item.events.forEach { event ->
                    add(ChapterDisplayItem.NotificationChild(item.key, event))
                }
                else -> Unit
            }
        }
    }

private fun List<AppSessionEntity>.toAppFlowItems(): List<ChapterFlowItem> {
    val result = mutableListOf<ChapterFlowItem>()
    val pending = mutableListOf<AppSessionEntity>()

    fun flush() {
        if (pending.size >= 3) result += ChapterFlowItem.AppBurst(pending.toList())
        else result += pending.map(ChapterFlowItem::App)
        pending.clear()
    }

    sortedWith(compareBy<AppSessionEntity> { it.startMs }.thenBy { it.endMs }.thenBy { it.id })
        .forEach { session ->
            val short = session.endMs - session.startMs <= APP_BURST_MAX_SESSION_MS
            val previous = pending.lastOrNull()
            val continuesBurst = previous != null &&
                short &&
                previous.endMs - previous.startMs <= APP_BURST_MAX_SESSION_MS &&
                session.startMs - previous.endMs <= APP_BURST_MAX_GAP_MS
            if (!continuesBurst) flush()
            pending += session
        }
    flush()
    return result
}

private fun TimelineDay.probeSnapshot(probeMs: Long): ProbeSnapshot {
    val radiusMs = 2 * 60_000L + 30_000L
    return ProbeSnapshot(
        place = visits.firstOrNull { probeMs in it.startMs until it.endMs },
        activeApps = apps.filter { probeMs in it.startMs until it.endMs }.sortedByDescending { it.endMs - it.startMs },
        nearbyNotifications = notifications.filter {
            it.action == "POSTED" && kotlin.math.abs(it.occurredMs - probeMs) <= radiusMs
        }.sortedBy { it.occurredMs },
        activeGaps = gaps.filter { probeMs in it.startMs until it.endMs },
    )
}

private fun ProbeSnapshot.description(): String = buildList {
    activeApps.take(2).takeIf { it.isNotEmpty() }?.let { current ->
        add("正在使用 ${current.joinToString("、") { it.appLabel }}")
    }
    if (nearbyNotifications.isNotEmpty()) add("前后 5 分钟 ${nearbyNotifications.size} 条通知")
    if (activeGaps.isNotEmpty()) add(activeGaps.joinToString("、") { it.source.archiveSourceLabel() } + "未记录")
    if (isEmpty()) add(if (place != null) "此刻没有应用或通知事件" else "此刻只有地点信息")
}.joinToString(" · ")

private fun DailyReviewSummary.appUsageComparisonText(): String {
    val comparison = this[ReviewMetric.APP_USAGE]
    return when (comparison.state) {
        ReviewComparisonState.AVAILABLE -> {
            val delta = comparison.deltaValue ?: 0.0
            val direction = when {
                delta > 30_000 -> "多"
                delta < -30_000 -> "少"
                else -> "接近"
            }
            if (direction == "接近") {
                "与过去 ${comparison.comparableDayCount} 个完整记录日的平均值接近"
            } else {
                "比过去 ${comparison.comparableDayCount} 个完整记录日平均$direction ${formatDuration(kotlin.math.abs(delta).toLong())}"
            }
        }
        // v0.15 UX P1-6：上手期的文案改为正向事实陈述——价值最需要证明的阶段，
        // 不再反复说"暂不做比较"，而是告诉用户已经积累了什么。
        ReviewComparisonState.CURRENT_PERIOD_IN_PROGRESS -> "今天已记录 ${formatDuration(comparison.currentObservedValue)}，明天回来看完整对比"
        ReviewComparisonState.COVERAGE_GAP -> "今天已记录 ${formatDuration(comparison.currentObservedValue)}；部分时间缺数据，先看总量"
        ReviewComparisonState.INSUFFICIENT_COMPARABLE_DAYS -> "已连续记录 ${comparison.comparableDayCount} 天；满 3 天后这里会显示趋势对比"
        ReviewComparisonState.INCOMPLETE_PERIOD -> "这一天已有部分记录；数据更完整时对比会更准确"
    }
}

private fun SevenDayReviewSummary.sevenDayComparisonText(): String {
    val comparison = this[ReviewMetric.APP_USAGE]
    return when (comparison.state) {
        ReviewComparisonState.AVAILABLE -> {
            val delta = comparison.deltaValue ?: 0.0
            when {
                delta > 30_000 -> "应用使用比此前 7 个完整记录日多 ${formatDuration(delta.toLong())}"
                delta < -30_000 -> "应用使用比此前 7 个完整记录日少 ${formatDuration(kotlin.math.abs(delta).toLong())}"
                else -> "应用使用与此前 7 个完整记录日接近"
            }
        }
        // v0.15 UX P1-6：正向事实陈述替代否定句
        ReviewComparisonState.CURRENT_PERIOD_IN_PROGRESS -> "本周已累计 ${formatDuration(comparison.currentObservedValue)} 应用使用"
        ReviewComparisonState.COVERAGE_GAP -> "本周已累计 ${formatDuration(comparison.currentObservedValue)}；补全记录后可对比上周"
        ReviewComparisonState.INCOMPLETE_PERIOD -> "再积累几天就能看到第一份周趋势"
        ReviewComparisonState.INSUFFICIENT_COMPARABLE_DAYS -> "继续记录中，趋势对比即将可用"
    }
}

private fun List<NotificationEventEntity>.toArchiveTicks(bucketMs: Long = 5 * 60_000L): List<ArchiveNotificationTick> =
    groupBy { it.occurredMs / bucketMs }.map { (_, values) ->
        val ordered = values.sortedBy { it.occurredMs }
        ArchiveNotificationTick(atMs = ordered[ordered.size / 2].occurredMs, count = values.size)
    }.sortedBy { it.atMs }

private fun CollectionGapEntity.toArchiveGap(): ArchiveGap {
    val lanes = when (source) {
        SourceId.LOCATION.name -> setOf(ArchiveLane.PLACE)
        SourceId.USAGE.name -> setOf(ArchiveLane.APPLICATION)
        SourceId.NOTIFICATIONS.name -> setOf(ArchiveLane.NOTIFICATION)
        else -> emptySet()
    }
    return ArchiveGap(startMs, endMs, lanes)
}

private fun LocalDate.archiveDateTitle(): String = when (this) {
    LocalDate.now() -> format(DateTimeFormatter.ofPattern("M月d日 · 今天"))
    LocalDate.now().minusDays(1) -> format(DateTimeFormatter.ofPattern("M月d日 · 昨天"))
    else -> format(DateTimeFormatter.ofPattern("yyyy年M月d日"))
}

private fun java.time.DayOfWeek.chineseLabel(): String = when (this) {
    java.time.DayOfWeek.MONDAY -> "星期一"
    java.time.DayOfWeek.TUESDAY -> "星期二"
    java.time.DayOfWeek.WEDNESDAY -> "星期三"
    java.time.DayOfWeek.THURSDAY -> "星期四"
    java.time.DayOfWeek.FRIDAY -> "星期五"
    java.time.DayOfWeek.SATURDAY -> "星期六"
    java.time.DayOfWeek.SUNDAY -> "星期日"
}

private fun Long.archiveDateTime(): String = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm:ss"))

internal fun String.archiveSourceLabel(): String = when (this) {
    SourceId.USAGE.name -> "应用"
    SourceId.NOTIFICATIONS.name -> "通知"
    SourceId.LOCATION.name -> "地点"
    SourceId.STEPS.name -> "步数"
    SourceId.SLEEP.name -> "睡眠"
    else -> this
}

private fun String.archiveContentFallback(): String = when (this) {
    "DISABLED" -> "收到时尚未开启通知内容保存"
    "READ_FAILED" -> "系统或第三方通知数据异常，内容读取失败"
    "NOT_PROVIDED" -> "Android 没有提供可见标题或正文"
    "CAPTURED" -> "内容已保存"
    "REDACTED" -> "内容已由用户清除"
    "LEGACY_UNKNOWN" -> "旧记录的内容状态未知"
    else -> "没有保存正文"
}

private fun String.archiveCaptureOrigin(): String = when (this) {
    "REALTIME" -> "实时监听"
    "RECONNECT" -> "监听恢复时补录"
    else -> "旧版本记录"
}

private fun overlapMs(aStart: Long, aEnd: Long, bStart: Long, bEnd: Long): Long =
    (minOf(aEnd, bEnd) - maxOf(aStart, bStart)).coerceAtLeast(0L)
