package com.twentyfourpi.lifelog.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Merge
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.twentyfourpi.lifelog.data.CollectionGapEntity
import com.twentyfourpi.lifelog.data.LocationPointEntity
import com.twentyfourpi.lifelog.data.MillisInterval
import com.twentyfourpi.lifelog.data.PlaceEntity
import com.twentyfourpi.lifelog.data.PlaceKind
import com.twentyfourpi.lifelog.data.PlaceVisitView
import com.twentyfourpi.lifelog.data.SettingsStore
import com.twentyfourpi.lifelog.data.SourceId
import com.twentyfourpi.lifelog.data.SourceState
import com.twentyfourpi.lifelog.data.SourceStatusEntity
import com.twentyfourpi.lifelog.data.intervalUnionCount
import com.twentyfourpi.lifelog.util.distanceMeters
import com.twentyfourpi.lifelog.util.formatClock
import com.twentyfourpi.lifelog.util.formatDuration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

private enum class FootprintView(val label: String) {
    SUMMARY("地点汇总"), TIMELINE("到访"), LIBRARY("管理")
}

private val visibleFootprintViews = listOf(FootprintView.SUMMARY, FootprintView.LIBRARY)

private enum class PlaceLibrarySort(val label: String) {
    DURATION("停留最久"), VISITS("到访最多"), RECENT("最近到访"), NAME("名称")
}

private data class FootprintPlaceSummary(
    val placeId: Long,
    val name: String,
    val totalMs: Long,
    val visits: Int,
    val days: Int,
    val lastVisitedMs: Long,
)

private sealed interface FootprintItem {
    val startMs: Long
    val endMs: Long

    data class Visit(val value: PlaceVisitView) : FootprintItem {
        override val startMs = value.startMs
        override val endMs = value.endMs
    }

    data class Gap(val value: CollectionGapEntity) : FootprintItem {
        override val startMs = value.startMs
        override val endMs = value.endMs
    }
}

/**
 * 足迹是人生时间线的“章节”：时间线说明何时在哪，汇总回答一段时间去了哪，地点库只负责管理。
 */
@Composable
fun PlacesScreen(
    viewModel: MainViewModel,
    onOpenVisit: (PlaceVisitView) -> Unit = {},
    onBack: (() -> Unit)? = null,
    initialManagement: Boolean = false,
) {
    val places by viewModel.places.collectAsStateWithLifecycle()
    val latestLocation by viewModel.latestLocation.collectAsStateWithLifecycle()
    val rawVisits by viewModel.recentVisits.collectAsStateWithLifecycle()
    val collectionGaps by viewModel.collectionGaps.collectAsStateWithLifecycle()
    var view by rememberSaveable { mutableStateOf(if (initialManagement) FootprintView.LIBRARY else FootprintView.SUMMARY) }
    var rangeDays by rememberSaveable { mutableStateOf<Int?>(7) }
    var selectedVisitId by rememberSaveable { mutableStateOf<Long?>(null) }
    var selectedSummaryPlaceId by rememberSaveable { mutableStateOf<Long?>(null) }
    var editing by remember { mutableStateOf<PlaceEntity?>(null) }
    var merging by remember { mutableStateOf<PlaceEntity?>(null) }
    var mergeSourceForPreview by remember { mutableStateOf<PlaceEntity?>(null) }
    var mergeTarget by remember { mutableStateOf<PlaceEntity?>(null) }
    var mergeAffectedVisits by remember { mutableStateOf<Int?>(null) }
    var hiding by remember { mutableStateOf<PlaceEntity?>(null) }
    var creatingAtLocation by remember { mutableStateOf(false) }
    var placeQuery by rememberSaveable { mutableStateOf("") }
    var managementRangeDays by rememberSaveable { mutableStateOf<Int?>(null) }
    var managementSort by rememberSaveable { mutableStateOf(PlaceLibrarySort.DURATION) }
    var managementRangeMenuOpen by remember { mutableStateOf(false) }
    var managementSortMenuOpen by remember { mutableStateOf(false) }
    var placeError by remember { mutableStateOf<String?>(null) }
    var placeBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun submitPlaceAction(action: suspend () -> Unit, completed: () -> Unit = {}) {
        if (placeBusy) return
        placeBusy = true
        placeError = null
        scope.launch {
            try { action(); completed() }
            catch (error: Exception) { placeError = error.message ?: "保存失败，请重试" }
            finally { placeBusy = false }
        }
    }
    LaunchedEffect(mergeSourceForPreview?.id) {
        mergeAffectedVisits = mergeSourceForPreview?.let { viewModel.affectedVisitsForMerge(it.id) }
    }
    var placeMenuId by remember { mutableStateOf<Long?>(null) }
    val visibleView = view.takeIf { it in visibleFootprintViews } ?: FootprintView.SUMMARY

    LaunchedEffect(view) {
        if (view !in visibleFootprintViews) view = FootprintView.SUMMARY
    }

    val nowMs = System.currentTimeMillis() + 1L
    val managementStartMs = remember(managementRangeDays) {
        managementRangeDays?.let { days ->
            LocalDate.now().minusDays((days - 1).toLong())
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } ?: 0L
    }
    val managementSummaries = remember(rawVisits, managementStartMs, nowMs) {
        buildFootprintSummaries(rawVisits, managementStartMs, nowMs).associateBy { it.placeId }
    }
    val displayedPlaces = remember(places, placeQuery, managementSummaries, managementSort) {
        places.filter {
            placeQuery.isBlank() || it.name.contains(placeQuery.trim(), ignoreCase = true) ||
                it.address.contains(placeQuery.trim(), ignoreCase = true)
        }.sortedWith(compareBy<PlaceEntity> { it.mergedIntoPlaceId != null }.thenBy { it.ignored }
            .thenByDescending { place -> when (managementSort) {
                PlaceLibrarySort.DURATION -> managementSummaries[place.id]?.totalMs ?: 0L
                PlaceLibrarySort.VISITS -> (managementSummaries[place.id]?.visits ?: 0).toLong()
                PlaceLibrarySort.RECENT -> managementSummaries[place.id]?.lastVisitedMs ?: 0L
                PlaceLibrarySort.NAME -> 0L
            } }.thenBy { it.name })
    }
    val rangeStartMs = remember(rangeDays, viewModel.settings.startedAt) {
        rangeDays?.let {
            LocalDate.now().minusDays((it - 1).toLong())
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } ?: viewModel.settings.startedAt.coerceAtLeast(0L)
    }
    val visitsInRange = remember(rawVisits, rangeStartMs, nowMs) {
        rawVisits.filter { it.endMs >= rangeStartMs && it.startMs < nowMs }
    }
    val continuousVisits = remember(visitsInRange) {
        coalescePlaceVisits(visitsInRange).sortedByDescending { it.startMs }
    }
    val locationGaps = remember(collectionGaps, rangeStartMs, nowMs) {
        collectionGaps.filter {
            it.source == SourceId.LOCATION.name && it.endMs >= rangeStartMs && it.startMs < nowMs
        }
    }
    val timelineDays = remember(continuousVisits, locationGaps) {
        val items = continuousVisits.map { FootprintItem.Visit(it) } +
            locationGaps.map { FootprintItem.Gap(it) }
        items.sortedByDescending { it.startMs }
            .groupBy { it.startMs.localDate() }
            .toList()
            .sortedByDescending { it.first }
    }
    val summaries = remember(visitsInRange, rangeStartMs, nowMs) {
        buildFootprintSummaries(visitsInRange, rangeStartMs, nowMs)
    }
    val selectedVisit = selectedVisitId?.let { id -> rawVisits.firstOrNull { it.id == id } }
    val selectedSummary = selectedSummaryPlaceId?.let { placeId -> summaries.firstOrNull { it.placeId == placeId } }
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("placesList"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                onBack?.let { back ->
                    IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回档案") }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("地点档案", style = MaterialTheme.typography.headlineSmall)
                    Text("地点是时间的上下文，不是另一条独立时间线。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            TabRow(
                selectedTabIndex = visibleFootprintViews.indexOf(visibleView),
                containerColor = MaterialTheme.colorScheme.surface,
                divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) },
            ) {
                visibleFootprintViews.forEach { item ->
                    Tab(
                        selected = view == item,
                        onClick = { view = item },
                        text = { Text(item.label) },
                    )
                }
            }
        }
        if (placeError != null) item { Text(placeError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

        if (visibleView != FootprintView.LIBRARY) {
            item { FootprintPeriodSelector(rangeDays) { rangeDays = it } }
        }

        when (visibleView) {
            FootprintView.TIMELINE -> {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("停留时间线", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "地点与采集断档按真实时间排列，不猜测缺失时段。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (timelineDays.isEmpty()) {
                    item {
                        EmptyCard(
                            "这个范围还没有确认的停留",
                            "实时坐标会立即记录；已知地点约1分钟、新地点约5分钟后形成完整起止时间。",
                        )
                    }
                } else {
                    timelineDays.forEach { (date, entries) ->
                        item(key = "footprint-date-$date") {
                            FootprintDateHeader(date, entries)
                        }
                        itemsIndexed(
                            items = entries,
                            key = { _, item ->
                                when (item) {
                                    is FootprintItem.Visit -> "visit-${item.value.id}-${item.startMs}"
                                    is FootprintItem.Gap -> "gap-${item.value.id}-${item.startMs}"
                                }
                            },
                        ) { index, item ->
                            when (item) {
                                is FootprintItem.Visit -> FootprintVisitRow(
                                    visit = item.value,
                                    isOngoing = System.currentTimeMillis() - item.value.endMs < 2 * 60_000L,
                                    signalGapMs = placeSignalGapMs(item.value, rawVisits),
                                    isLast = index == entries.lastIndex,
                                    onClick = { selectedVisitId = item.value.id },
                                )
                                is FootprintItem.Gap -> FootprintGapRow(
                                    gap = item.value,
                                    isLast = index == entries.lastIndex,
                                )
                            }
                        }
                    }
                }
            }

            FootprintView.SUMMARY -> {
                item {
                    FootprintSummaryPanel(
                        summaries = summaries,
                        gapMs = locationGaps.sumOf {
                            clippedDuration(it.startMs, it.endMs, rangeStartMs, nowMs)
                        },
                    )
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("地点停留", style = MaterialTheme.typography.titleLarge)
                        Text("仅统计实际观测到的停留时间，按总时长排序。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (summaries.isEmpty()) {
                    item { EmptyCard("没有可汇总的停留", "调整日期范围，或等待地点停留形成。") }
                } else {
                    itemsIndexed(summaries, key = { _, item -> "summary-${item.placeId}" }) { index, summary ->
                        FootprintSummaryRow(
                            summary = summary,
                            maxMs = summaries.first().totalMs,
                            onClick = { selectedSummaryPlaceId = summary.placeId },
                        )
                        if (index < summaries.lastIndex) {
                            HorizontalDivider(Modifier.padding(start = 18.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }

            FootprintView.LIBRARY -> {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("地点管理", style = MaterialTheme.typography.titleLarge)
                        Text("按已记录停留找到常去的地点，再命名或归并；定位与到访原始记录会保留。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item {
                    OutlinedTextField(
                        value = placeQuery, onValueChange = { placeQuery = it },
                        label = { Text("搜索地点名称或定位地址") },
                        singleLine = true, modifier = Modifier.fillMaxWidth().testTag("placeSearchInput"),
                    )
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val rangeLabel = if (managementRangeDays == null) "全部记录" else "近 30 天"
                        Box(Modifier.weight(1f)) {
                            OutlinedButton(
                                onClick = { managementRangeMenuOpen = true },
                                modifier = Modifier.fillMaxWidth().semantics {
                                    contentDescription = "统计范围：$rangeLabel，点按选择"
                                },
                            ) { Text("$rangeLabel ▾", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            DropdownMenu(expanded = managementRangeMenuOpen,
                                onDismissRequest = { managementRangeMenuOpen = false }) {
                                DropdownMenuItem(text = { Text("全部记录") }, onClick = {
                                    managementRangeDays = null; managementRangeMenuOpen = false
                                })
                                DropdownMenuItem(text = { Text("近 30 天") }, onClick = {
                                    managementRangeDays = 30; managementRangeMenuOpen = false
                                })
                            }
                        }
                        Box(Modifier.weight(1f)) {
                            OutlinedButton(
                                onClick = { managementSortMenuOpen = true },
                                modifier = Modifier.fillMaxWidth().semantics {
                                    contentDescription = "排序方式：${managementSort.label}，点按选择"
                                },
                            ) { Text("${managementSort.label} ▾", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            DropdownMenu(expanded = managementSortMenuOpen,
                                onDismissRequest = { managementSortMenuOpen = false }) {
                                PlaceLibrarySort.entries.forEach { option ->
                                    DropdownMenuItem(text = { Text(option.label) }, onClick = {
                                        managementSort = option; managementSortMenuOpen = false
                                    })
                                }
                            }
                        }
                    }
                }
                item {
                    val measured = latestLocation?.let { if (it.measuredMs > 0) it.measuredMs else it.recordedMs }
                    val age = measured?.let { System.currentTimeMillis() - it }
                    val ageLabel = age?.let { if (it < 60_000L) "刚刚" else "${it / 60_000L} 分钟前" } ?: "未知时间"
                    val usable = latestLocation != null && !latestLocation!!.isMock &&
                        latestLocation!!.accuracyM.isFinite() && latestLocation!!.accuracyM in 0f..150f &&
                        age != null && age in 0L..(15 * 60_000L)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (measured == null) "尚无已记录位置" else
                            "最后位置 ${formatClock(measured)} · $ageLabel · 约 ${latestLocation!!.accuracyM.toInt()} 米精度" +
                                if (usable) "" else " · 已过期或精度不足",
                            style = MaterialTheme.typography.bodySmall)
                        FilledTonalButton(onClick = { creatingAtLocation = true }, enabled = usable && !placeBusy) {
                            Text("标记最后记录位置")
                        }
                    }
                }
                if (places.isEmpty()) {
                    item { EmptyCard("尚无可管理地点", "确认首次停留后，地点会出现在这里。") }
                } else if (displayedPlaces.isEmpty()) {
                    item { EmptyCard("没有匹配的地点", "试试地点名称或定位地址中的其他文字。") }
                } else {
                    itemsIndexed(displayedPlaces, key = { _, item -> "library-${item.id}" }) { index, place ->
                        val summary = managementSummaries[place.id]
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 88.dp)
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(place.name, style = MaterialTheme.typography.titleMedium,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(when {
                                    place.mergedIntoPlaceId != null ->
                                        "已归入 ${places.firstOrNull { it.id == place.mergedIntoPlaceId }?.name ?: "其他地点"} · 到访已计入主地点"
                                    place.ignored -> "已隐藏 · 当前统计不含此地点"
                                    summary != null -> "${formatDuration(summary.totalMs)} · ${summary.visits} 次到访"
                                    else -> "尚无已记录停留"
                                }, style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary)
                                Text("${placeKindLabel(place.kind)} · 定位地址：${place.address.ifBlank { "未解析" }}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Row {
                                IconButton(onClick = { editing = place }, modifier = Modifier.testTag("placeEdit-${place.id}")) {
                                    Icon(Icons.Outlined.Edit, "命名")
                                }
                                Box {
                                    IconButton(onClick = { placeMenuId = place.id }, modifier = Modifier.testTag("placeMenu-${place.id}")) {
                                        Icon(Icons.Outlined.MoreVert, "更多地点操作")
                                    }
                                    DropdownMenu(
                                        expanded = placeMenuId == place.id,
                                        onDismissRequest = { placeMenuId = null },
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("归入另一地点") },
                                            leadingIcon = { Icon(Icons.Outlined.Merge, null) },
                                            enabled = !placeBusy && place.mergedIntoPlaceId == null && !place.ignored &&
                                                places.any { !it.ignored && it.mergedIntoPlaceId == null && it.id != place.id },
                                            onClick = { placeMenuId = null; merging = place },
                                        )
                                        if (place.mergedIntoPlaceId != null) DropdownMenuItem(
                                            text = { Text("撤销归并") },
                                            onClick = {
                                                placeMenuId = null
                                                submitPlaceAction({ viewModel.undoPlaceMergeChecked(place.id) })
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(if (place.ignored) "恢复显示地点及到访" else "隐藏地点及到访") },
                                            leadingIcon = {
                                                Icon(if (place.ignored) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff, null)
                                            },
                                            enabled = place.mergedIntoPlaceId == null,
                                            onClick = {
                                                placeMenuId = null
                                                if (place.ignored) submitPlaceAction({ viewModel.setPlaceIgnored(place, false) })
                                                else hiding = place
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        if (index < displayedPlaces.lastIndex) InstrumentDivider()
                    }
                }
            }
        }
    }

    selectedVisit?.let { visit ->
        LifeLogDetailSheet(
            title = visit.name,
            subtitle = "一次连续停留 · ${formatDuration(visit.endMs - visit.startMs)}",
            onDismiss = { selectedVisitId = null },
        ) {
            item {
                PlaceVisitCard(
                    visit = visit,
                    signalGapMs = placeSignalGapMs(visit, rawVisits),
                )
            }
            item {
                FilledTonalButton(
                    onClick = {
                        onOpenVisit(visit)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("在当天时间线查看") }
            }
            item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
            item {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("地点信息", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        visit.address.ifBlank {
                            if (!viewModel.settings.placeNameConsent) "地点名称反查未开启" else "系统与 OpenStreetMap 均未返回地址"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "${"%.6f".format(visit.latitude)}, ${"%.6f".format(visit.longitude)}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    selectedSummary?.let { summary ->
        val summaryVisits = continuousVisits.filter { it.placeId == summary.placeId }
        LifeLogDetailSheet(
            title = summary.name,
            subtitle = "${summary.visits} 次到访 · ${formatDuration(summary.totalMs)}",
            onDismiss = { selectedSummaryPlaceId = null },
        ) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    FootprintSummaryValue("覆盖日", "${summary.days} 天", Modifier.weight(1f))
                    FootprintSummaryValue("最近", summary.lastVisitedMs.dateTimeLabel(), Modifier.weight(1f))
                }
            }
            itemsIndexed(summaryVisits, key = { _, item -> "summary-visit-${item.id}-${item.startMs}" }) { _, visit ->
                PlaceVisitCard(
                    visit = visit,
                    signalGapMs = placeSignalGapMs(visit, rawVisits),
                    onClick = {
                        onOpenVisit(visit)
                    },
                )
            }
        }
    }

    editing?.let { place ->
        var name by remember(place.id) { mutableStateOf(place.name) }
        var kind by remember(place.id) { mutableStateOf(place.kind) }
        InstrumentDialog(
            title = "编辑地点",
            onDismiss = { if (!placeBusy) editing = null },
            content = {
                Text("定位地址：${place.address.ifBlank { "尚未解析" }}", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 60) name = it },
                    singleLine = true,
                    label = { Text("地点名称") },
                    modifier = Modifier.fillMaxWidth().testTag("placeNameInput"),
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    listOf(PlaceKind.HOME, PlaceKind.COMPANY, PlaceKind.CUSTOMER, PlaceKind.OTHER).chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            pair.forEach { candidate ->
                                FilterChip(selected = kind == candidate, onClick = { kind = candidate },
                                    label = { Text(placeKindLabel(candidate)) }, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            },
            actions = {
                TextButton(onClick = { editing = null }, enabled = !placeBusy) { Text("取消") }
                TextButton(onClick = {
                    submitPlaceAction({
                        viewModel.savePlaceMetadata(place.id, name, kind)
                    }) { editing = null }
                }, enabled = !placeBusy && name.trim().isNotEmpty()) { Text(if (placeBusy) "保存中" else "保存") }
            },
        )
    }

    hiding?.let { place ->
        InstrumentDialog(
            title = "隐藏“${place.name}”？",
            onDismiss = { if (!placeBusy) hiding = null },
            content = {
                Text("这个地点和它的到访会从时间档案、搜索和地点汇总中隐藏。原始坐标与停留记录仍保存在本机，之后可以在地点管理中恢复。")
            },
            actions = {
                TextButton(onClick = { hiding = null }, enabled = !placeBusy) { Text("取消") }
                TextButton(onClick = {
                    submitPlaceAction({ viewModel.setPlaceIgnored(place, true) }) { hiding = null }
                }, enabled = !placeBusy) { Text(if (placeBusy) "保存中" else "确认隐藏") }
            },
        )
    }
    merging?.let { source ->
        InstrumentDialog(
            title = "将“${source.name}”归入另一地点",
            onDismiss = { if (!placeBusy) merging = null },
            content = {
                Text(
                    "来源保留原始名称、地址、坐标和到访记录。选择目标后可查看预览并确认，之后可撤销。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("目标按距离从近到远排列；停留时长按当前管理范围统计。",
                    style = MaterialTheme.typography.bodySmall)
                Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    places.filter { it.id != source.id && !it.ignored && it.mergedIntoPlaceId == null }
                        .sortedWith(compareBy<PlaceEntity> {
                            distanceMeters(source.latitude, source.longitude, it.latitude, it.longitude)
                        }.thenByDescending { managementSummaries[it.id]?.totalMs ?: 0L })
                        .forEach { target ->
                            val stay = managementSummaries[target.id]
                            TextButton(onClick = { mergeSourceForPreview = source; mergeTarget = target; merging = null },
                                modifier = Modifier.fillMaxWidth()) {
                                Text("${target.name} · ${stay?.let { formatDuration(it.totalMs) } ?: "无已记录停留"} · ${stay?.visits ?: 0} 次 · ${distanceMeters(source.latitude, source.longitude, target.latitude, target.longitude).toInt()} 米")
                            }
                        }
                }
            },
            actions = { TextButton(onClick = { merging = null }) { Text("取消") } },
        )
    }
    mergeTarget?.let { target ->
        val source = mergeSourceForPreview?.let { prior -> places.firstOrNull { it.id == prior.id } ?: prior }
        if (source != null) InstrumentDialog(
            title = "确认地点归并",
            onDismiss = { if (!placeBusy) mergeTarget = null },
            content = {
                Text("把 ${source.name} 归入 ${target.name}")
                Text("来源定位地址：${source.address.ifBlank { "未解析" }} · ${"%.5f".format(source.latitude)}, ${"%.5f".format(source.longitude)}")
                Text("目标定位地址：${target.address.ifBlank { "未解析" }} · ${"%.5f".format(target.latitude)}, ${"%.5f".format(target.longitude)}")
                Text("相距约 ${distanceMeters(source.latitude, source.longitude, target.latitude, target.longitude).toInt()} 米 · 受影响到访 ${mergeAffectedVisits?.toString() ?: "统计中"} 条。历史展示与未来来源匹配将归到目标，原始记录保留。")
            },
            actions = {
                TextButton(onClick = { mergeTarget = null }, enabled = !placeBusy) { Text("取消") }
                TextButton(onClick = {
                    submitPlaceAction({ viewModel.mergePlaceChecked(source, target) }) { mergeTarget = null }
                }, enabled = !placeBusy) { Text(if (placeBusy) "保存中" else "确认归并") }
            },
        )
    }
    if (creatingAtLocation) {
        val point = latestLocation
        var name by remember { mutableStateOf("") }
        var kind by remember { mutableStateOf(PlaceKind.OTHER) }
        InstrumentDialog(
            title = "标记最后记录位置",
            onDismiss = { if (!placeBusy) creatingAtLocation = false },
            content = {
                Text(point?.let { "${formatClock(if (it.measuredMs > 0) it.measuredMs else it.recordedMs)} · ${((System.currentTimeMillis() - (if (it.measuredMs > 0) it.measuredMs else it.recordedMs)) / 60_000L).coerceAtLeast(0L)} 分钟前 · 约 ${it.accuracyM.toInt()} 米精度" }
                    ?: "尚无可用定位", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = name, onValueChange = { if (it.length <= 60) name = it },
                    label = { Text("地点名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    listOf(PlaceKind.HOME, PlaceKind.COMPANY, PlaceKind.CUSTOMER, PlaceKind.OTHER).chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            pair.forEach { candidate ->
                                FilterChip(selected = kind == candidate, onClick = { kind = candidate },
                                    label = { Text(placeKindLabel(candidate)) }, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
                Text("只建立地点标签，不生成到访记录。", style = MaterialTheme.typography.bodySmall)
                if (point != null) {
                    val nearby = places.filter { !it.ignored && it.mergedIntoPlaceId == null &&
                        distanceMeters(it.latitude, it.longitude, point.latitude, point.longitude) <= 150.0 }
                    if (nearby.isNotEmpty()) {
                        Text("或选择附近已有地点", style = MaterialTheme.typography.titleSmall)
                        nearby.take(4).forEach { existing ->
                            TextButton(onClick = { creatingAtLocation = false; editing = existing }) {
                                Text("编辑 ${existing.name}")
                            }
                        }
                    }
                }
            },
            actions = {
                TextButton(onClick = { creatingAtLocation = false }, enabled = !placeBusy) { Text("取消") }
                TextButton(onClick = {
                    if (point != null) submitPlaceAction({ viewModel.createPlaceAtLocation(point, name, kind) }) {
                        creatingAtLocation = false
                    }
                }, enabled = !placeBusy && point != null && name.trim().isNotEmpty()) { Text(if (placeBusy) "保存中" else "创建地点") }
            },
        )
    }
}

private fun placeKindLabel(kind: String): String = when (kind) {
    PlaceKind.HOME -> "家"
    PlaceKind.COMPANY -> "公司"
    PlaceKind.CUSTOMER -> "客户"
    else -> "其他"
}

@Composable
private fun FootprintPeriodSelector(selected: Int?, onSelected: (Int?) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(7 to "近 7 天", 30 to "近 30 天").forEach { (days, label) ->
            FilterChip(selected = selected == days, onClick = { onSelected(days) }, label = { Text(label) })
        }
        FilterChip(selected = selected == null, onClick = { onSelected(null) }, label = { Text("全部") })
    }
}

@Composable
private fun CurrentLocationPanel(
    latestLocation: LocationPointEntity?,
    currentPlace: PlaceEntity?,
    status: SourceStatusEntity?,
    settings: SettingsStore,
) {
    val locationState = status?.state?.let { value ->
        runCatching { SourceState.valueOf(value) }.getOrNull()
    }
    val locationAgeMs = latestLocation?.let { System.currentTimeMillis() - it.recordedMs }
    val locationFresh = locationAgeMs != null && locationAgeMs <= 5 * 60_000L
    val recordingNow = locationFresh && locationState == SourceState.ACTIVE
    val interrupted = locationState == SourceState.PERMISSION_REQUIRED ||
        locationState == SourceState.SYSTEM_BLOCKED ||
        locationState == SourceState.ERROR ||
        (latestLocation != null && !locationFresh && locationState != SourceState.PAUSED)
    val headline = when {
        recordingNow -> currentPlace?.name ?: "当前位置"
        latestLocation != null -> currentPlace?.let { "最后位置：${it.name}" } ?: "最后记录位置"
        else -> "当前位置"
    }
    val stateLabel = when {
        locationState == SourceState.PAUSED -> "定位记录已暂停"
        locationState == SourceState.PERMISSION_REQUIRED -> "定位权限缺失"
        locationState == SourceState.SYSTEM_BLOCKED -> "定位采集已中断"
        locationState == SourceState.ERROR -> "定位采集异常"
        locationState == SourceState.UNSUPPORTED -> "当前设备不支持定位记录"
        latestLocation == null -> "等待首次定位"
        !locationFresh -> "定位已超过 5 分钟未更新"
        recordingNow -> "实时位置正在记录"
        else -> "正在确认定位状态"
    }
    val stateColor = when {
        recordingNow -> MaterialTheme.colorScheme.primary
        interrupted -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val dwellStart = settings.getLong("dwell_start")
    val dwellLast = settings.getLong("dwell_last")
    val visitId = settings.getLong("dwell_visit")
    val requiredMs = settings.getLong("dwell_required_ms", 5 * 60_000L)
    val dwellMs = (dwellLast - dwellStart).coerceAtLeast(0L)
    val progress = (dwellMs.toFloat() / requiredMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
    InstrumentPanel {
        Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = if (interrupted) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Icon(
                        Icons.Outlined.LocationOn,
                        null,
                        modifier = Modifier.padding(10.dp),
                        tint = if (interrupted) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(headline, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        stateLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = stateColor,
                    )
                }
            }
            latestLocation?.let { point ->
                currentPlace?.address?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                } ?: Text(
                    if (!settings.placeNameConsent) "地点名称反查未开启 · 可在“我的”中免 Key 开启"
                    else "该地点暂未取得地址，可手动命名",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    if (locationFresh) {
                        "更新于 ${formatClock(point.recordedMs)} · 精度约 ±${point.accuracyM.toInt()}米"
                    } else {
                        "最后更新 ${formatClock(point.recordedMs)} · 已超过 5 分钟"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (locationFresh) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            } ?: Text("正在等待首次定位")
            if (dwellStart > 0L && recordingNow) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Text(
                    if (visitId > 0L) "自 ${formatClock(dwellStart)} 开始 · 已持续 ${formatDuration(dwellMs)}"
                    else "正在判断停留 · ${formatDuration(dwellMs)} / ${requiredMs / 60_000L}分钟",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            status?.detail
                ?.takeIf { it.isNotBlank() && !(interrupted && locationState == SourceState.ACTIVE) }
                ?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = stateColor) }
            Text(
                "坐标立即记录；已知地点约1分钟、新地点约5分钟确认。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LocationHealthStrip(latestLocation: LocationPointEntity?, gapCount: Int) {
    val age = latestLocation?.let { System.currentTimeMillis() - it.recordedMs }
    InstrumentPanel {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Outlined.LocationOn, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("定位采集", style = MaterialTheme.typography.titleSmall)
                Text(
                    when {
                        age == null -> "等待首次定位"
                        age <= 5 * 60_000L -> "最后更新 ${formatClock(latestLocation.recordedMs)}"
                        else -> "已超过 5 分钟未更新"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (age != null && age > 5 * 60_000L) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (gapCount > 0) Text("$gapCount 段断档", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun FootprintSummaryPanel(summaries: List<FootprintPlaceSummary>, gapMs: Long) {
    InstrumentPanel {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("这段时间去了哪里", style = MaterialTheme.typography.titleLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                FootprintSummaryValue("已观测停留", formatDuration(summaries.sumOf { it.totalMs }), Modifier.weight(1f))
                FootprintSummaryValue("地点", "${summaries.size} 个", Modifier.weight(1f))
                FootprintSummaryValue("到访", "${summaries.sumOf { it.visits }} 次", Modifier.weight(1f))
            }
            if (gapMs > 0L) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    "另有 ${formatDuration(gapMs)} 没有定位数据，汇总不会猜测这段时间的位置。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun FootprintSummaryValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun FootprintSummaryRow(summary: FootprintPlaceSummary, maxMs: Long, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(summary.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${summary.visits} 次到访 · ${summary.days} 天 · 最近 ${summary.lastVisitedMs.dateTimeLabel()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(formatDuration(summary.totalMs), modifier = Modifier.padding(start = 12.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        LinearProgressIndicator(
            progress = { if (maxMs <= 0L) 0f else (summary.totalMs.toFloat() / maxMs).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(4.dp),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

@Composable
private fun FootprintDateHeader(date: LocalDate, entries: List<FootprintItem>) {
    val visitCount = entries.count { it is FootprintItem.Visit }
    val gapCount = entries.count { it is FootprintItem.Gap }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.Bottom) {
        Text(date.footprintLabel(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text(
            buildString {
                append("$visitCount 次停留")
                if (gapCount > 0) append(" · $gapCount 段断档")
            },
            style = MaterialTheme.typography.labelMedium,
            color = if (gapCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FootprintVisitRow(
    visit: PlaceVisitView,
    isOngoing: Boolean,
    signalGapMs: Long,
    isLast: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickable(onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            formatClock(visit.startMs),
            modifier = Modifier.width(44.dp).padding(top = 15.dp),
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FootprintRail(isLast = isLast, error = false)
        Column(Modifier.weight(1f).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text(visit.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(formatDuration(visit.endMs - visit.startMs), modifier = Modifier.padding(start = 12.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1)
            }
            Text(visit.timeRangeLabel(isOngoing), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (visit.address.isNotBlank() && visit.address != visit.name) {
                Text(visit.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (signalGapMs > 0L) {
                Text("含 ${formatDuration(signalGapMs)} 定位信号中断", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun FootprintGapRow(gap: CollectionGapEntity, isLast: Boolean) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            formatClock(gap.startMs),
            modifier = Modifier.width(44.dp).padding(top = 15.dp),
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.error,
        )
        FootprintRail(isLast = isLast, error = true)
        Surface(
            modifier = Modifier.weight(1f).padding(vertical = 6.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = .35f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = .22f)),
        ) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text("定位采集中断", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text(formatDuration(gap.endMs - gap.startMs), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                }
                Text("${gap.startMs.dateTimeLabel()} – ${gap.endMs.dateTimeLabel()}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                Text("这段时间没有原始坐标，系统不会推测你在哪里。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun FootprintRail(isLast: Boolean, error: Boolean) {
    val color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Box(Modifier.width(18.dp).fillMaxHeight()) {
        if (!isLast) {
            Box(Modifier.width(2.dp).fillMaxHeight().align(Alignment.Center).background(color.copy(alpha = .25f)))
        }
        Surface(Modifier.size(9.dp).align(Alignment.TopCenter).offset(y = 18.dp), shape = MaterialTheme.shapes.small, color = color) {}
    }
}

private fun buildFootprintSummaries(
    rawVisits: List<PlaceVisitView>,
    fromMs: Long,
    toMs: Long,
): List<FootprintPlaceSummary> = rawVisits.groupBy { it.placeId }.mapNotNull { (placeId, raw) ->
    val observed = mergeObservedDuration(raw, fromMs, toMs)
    if (observed <= 0L) return@mapNotNull null
    val arrivals = intervalUnionCount(raw.map { MillisInterval(it.startMs, it.endMs) }, fromMs, toMs)
    val coveredDays = raw.flatMap { visit ->
        coveredDates(maxOf(visit.startMs, fromMs), minOf(visit.endMs, toMs))
    }.distinct().size
    FootprintPlaceSummary(
        placeId = placeId,
        name = raw.first().name,
        totalMs = observed,
        visits = arrivals,
        days = coveredDays,
        lastVisitedMs = raw.maxOf { minOf(it.endMs, toMs) },
    )
}.sortedByDescending { it.totalMs }

private fun mergeObservedDuration(visits: List<PlaceVisitView>, fromMs: Long, toMs: Long): Long {
    val intervals = visits.mapNotNull {
        val start = maxOf(it.startMs, fromMs)
        val end = minOf(it.endMs, toMs)
        if (end > start) start to end else null
    }.sortedBy { it.first }
    if (intervals.isEmpty()) return 0L
    var currentStart = intervals.first().first
    var currentEnd = intervals.first().second
    var total = 0L
    intervals.drop(1).forEach { (start, end) ->
        if (start <= currentEnd) currentEnd = maxOf(currentEnd, end)
        else {
            total += currentEnd - currentStart
            currentStart = start
            currentEnd = end
        }
    }
    return total + currentEnd - currentStart
}

private fun coveredDates(startMs: Long, endMs: Long): List<LocalDate> {
    if (endMs <= startMs) return emptyList()
    val start = startMs.localDate()
    val end = (endMs - 1L).localDate()
    val result = mutableListOf<LocalDate>()
    var date = start
    while (!date.isAfter(end)) {
        result += date
        date = date.plusDays(1)
    }
    return result
}

private fun clippedDuration(startMs: Long, endMs: Long, fromMs: Long, toMs: Long): Long =
    (minOf(endMs, toMs) - maxOf(startMs, fromMs)).coerceAtLeast(0L)

private fun PlaceVisitView.timeRangeLabel(isOngoing: Boolean): String {
    val start = Instant.ofEpochMilli(startMs).atZone(ZoneId.systemDefault())
    if (isOngoing) return "${start.format(CLOCK)}–现在"
    val end = Instant.ofEpochMilli(endMs).atZone(ZoneId.systemDefault())
    return if (start.toLocalDate() == end.toLocalDate()) {
        "${start.format(CLOCK)}–${end.format(CLOCK)}"
    } else {
        "${start.format(CROSS_DAY)} – ${end.format(CROSS_DAY)}"
    }
}

private fun LocalDate.footprintLabel(): String = when (this) {
    LocalDate.now() -> "今天 · ${format(MONTH_DAY)}"
    LocalDate.now().minusDays(1) -> "昨天 · ${format(MONTH_DAY)}"
    else -> format(DATE_WITH_WEEKDAY)
}

private fun Long.localDate(): LocalDate =
    Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()

private fun Long.dateTimeLabel(): String =
    Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).format(DATE_TIME)

@Composable
private fun EmptyCard(title: String, detail: String) {
    InstrumentPanel(contentPadding = PaddingValues(24.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")
private val CROSS_DAY = DateTimeFormatter.ofPattern("M月d日 HH:mm")
private val MONTH_DAY = DateTimeFormatter.ofPattern("M月d日")
private val DATE_WITH_WEEKDAY = DateTimeFormatter.ofPattern("M月d日 · EEEE")
private val DATE_TIME = DateTimeFormatter.ofPattern("M月d日 HH:mm")
