@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.twentyfourpi.lifelog.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.twentyfourpi.lifelog.data.AppUsageOverview
import com.twentyfourpi.lifelog.data.GapImpact
import com.twentyfourpi.lifelog.data.MillisInterval
import com.twentyfourpi.lifelog.data.SearchRow
import com.twentyfourpi.lifelog.data.SourceId
import com.twentyfourpi.lifelog.data.classifyGapImpact
import com.twentyfourpi.lifelog.data.intervalUnionDurationMs
import com.twentyfourpi.lifelog.data.intervalUnionCount
import com.twentyfourpi.lifelog.data.userFacingGapReason
import com.twentyfourpi.lifelog.util.formatDuration
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class ArchiveIndexType(val label: String, val searchValue: String) {
    ALL("全部", ""),
    APP("应用", "APP"),
    NOTIFICATION("通知", "NOTIFICATION"),
    PLACE("地点", "PLACE"),
}

/** U09/U28：档案范围五档（今天/昨天/7天/30天/自选）。 */
private enum class ArchiveRangeKey(val label: String) {
    TODAY("今天"),
    YESTERDAY("昨天"),
    D7("7天"),
    D30("30天"),
    CUSTOM("自选"),
}

private data class NotificationSourceIndex(
    val packageName: String,
    val appLabel: String,
    val count: Int,
    val lastMs: Long,
)

private data class PlaceIndex(
    val placeId: Long,
    val name: String,
    val address: String,
    val totalMs: Long,
    val visits: Int,
)

/**
 * A single cross-time index. Data types are filters, never separate navigation
 * modes, so every result leads back to the same time archive.
 */
@Composable
fun ArchiveIndexScreen(
    viewModel: MainViewModel,
    onOpenEvidence: (SearchRow) -> Unit,
    onOpenPlaces: () -> Unit,
    onOpenAi: () -> Unit = {},
    initialPlaceRange: Pair<LocalDate, LocalDate>? = null,
    initialPlaceRangeToken: Long = 0L,
    onPlaceRangeApplied: () -> Unit = {},
    onOpenTrips: () -> Unit = {},
    onOpenNotificationSettings: () -> Unit,
) {
    val explorer by viewModel.recordExplorer.collectAsStateWithLifecycle()
    val visits by viewModel.recentVisits.collectAsStateWithLifecycle()
    val collectionGaps by viewModel.collectionGaps.collectAsStateWithLifecycle()
    val results by viewModel.searchResults.collectAsStateWithLifecycle()
    val searchLoading by viewModel.searchLoading.collectAsStateWithLifecycle()
    val searchHasMore by viewModel.searchHasMore.collectAsStateWithLifecycle()
    // R09：搜索错误态（首屏错误与没有记录区分）。
    val searchError by viewModel.searchError.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var selectedTypeName by rememberSaveable { mutableStateOf(ArchiveIndexType.ALL.name) }
    var rangeKey by rememberSaveable { mutableStateOf(ArchiveRangeKey.TODAY.name) }
        // R05：打开自选前的原范围（取消自选时恢复，不悄悄改筛选）。
        var pendingRangeReplacement by rememberSaveable { mutableStateOf<String?>(null) }
    var gapDetailsOpen by rememberSaveable { mutableStateOf(false) }
    // U28：自选日期区间（起止 LocalDate.toEpochDay，null=未选择）。
    var customFromEpochDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var customToEpochDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var customPickerOpen by rememberSaveable { mutableStateOf(false) }
    var appliedPlaceRangeToken by rememberSaveable { mutableLongStateOf(-1L) }
    val placeHandoffPending = initialPlaceRange != null && appliedPlaceRangeToken != initialPlaceRangeToken
    LaunchedEffect(initialPlaceRange, initialPlaceRangeToken) {
        initialPlaceRange?.let { (from, to) ->
            customFromEpochDay = from.toEpochDay()
            customToEpochDay = to.toEpochDay()
            query = ""
            rangeKey = ArchiveRangeKey.CUSTOM.name
            selectedTypeName = ArchiveIndexType.PLACE.name
            appliedPlaceRangeToken = initialPlaceRangeToken
            onPlaceRangeApplied()
        }
    }
    val selectedType = ArchiveIndexType.entries.firstOrNull { it.name == selectedTypeName }
        ?: ArchiveIndexType.ALL
    val selectedRange = ArchiveRangeKey.entries.firstOrNull { it.name == rangeKey } ?: ArchiveRangeKey.D30
    // 计算统一生效范围（方案 16.7）：今天/昨天固定单日，7/30 含今天前 N 天，自选用起止。
    // P1-D：界面与查询共用同一口径——早于启用日或晚于今天的日期在此就地收敛，
    // 不再出现「界面按原始日期算、ViewModel 按裁切日期算」的两套条件。
    val archiveToday = LocalDate.now()
    val earliestDate = remember(viewModel.settings.startedAt) { viewModel.earliestSupportedDate() }
    val effectiveFrom: LocalDate? = when (selectedRange) {
        ArchiveRangeKey.TODAY -> archiveToday
        ArchiveRangeKey.YESTERDAY -> archiveToday.minusDays(1)
        ArchiveRangeKey.D7 -> archiveToday.minusDays(6)
        ArchiveRangeKey.D30 -> archiveToday.minusDays(29)
        ArchiveRangeKey.CUSTOM -> customFromEpochDay?.let(LocalDate::ofEpochDay)
    }?.coerceIn(earliestDate, archiveToday)
    val effectiveTo: LocalDate? = when (selectedRange) {
        ArchiveRangeKey.TODAY -> archiveToday
        ArchiveRangeKey.YESTERDAY -> archiveToday.minusDays(1)
        ArchiveRangeKey.D7, ArchiveRangeKey.D30 -> archiveToday
        ArchiveRangeKey.CUSTOM -> customToEpochDay?.let(LocalDate::ofEpochDay)
    }?.coerceIn(earliestDate, archiveToday)
    val rangeDays: Int? = when (selectedRange) {
        ArchiveRangeKey.TODAY, ArchiveRangeKey.YESTERDAY, ArchiveRangeKey.CUSTOM -> null
        ArchiveRangeKey.D7 -> 7
        ArchiveRangeKey.D30 -> 30
    }
    val focusManager = LocalFocusManager.current
    // U08：对象筛选标签（点击应用/地点/通知源索引后非空；用于决定是否显示结果区）。
    val objectFilterLabel by viewModel.objectFilterLabel.collectAsState()

    // R02：范围驱动统一——以生效起止日期为键（今天↔昨天切换也重新计算），
    // 汇总/索引/缺口/搜索共用同一解析区间。
    LaunchedEffect(effectiveFrom, effectiveTo, placeHandoffPending) {
        if (placeHandoffPending) return@LaunchedEffect
        val from = effectiveFrom ?: rangeDays?.let { LocalDate.now().minusDays((it - 1).toLong()) }
            ?: LocalDate.now().minusDays(6)
        val to = effectiveTo ?: LocalDate.now()
        viewModel.applyResolvedRange(from, to)
    }
    LaunchedEffect(query, selectedType, effectiveFrom, effectiveTo, rangeDays, placeHandoffPending) {
        if (placeHandoffPending) return@LaunchedEffect
        // R03：对象筛选模式下也必须重查——先更新对象查询的范围，再按新条件重查；
        // 输入框/范围/类型变化不再只是改标题。
        val hasObject = viewModel.objectFilterLabel.value != null
        if (hasObject) {
            if (effectiveFrom != null && effectiveTo != null) {
                viewModel.updateObjectFilterRange(effectiveFrom, effectiveTo)
            }
            // P1-C：关键词/类型一起送进重查；类型与对象不兼容时返回 false（对象已被清除），
            // 落到下面的普通搜索分支，不再挂着对象标签按旧条件出结果。
            if (viewModel.rerunObjectSearchWithCriteria(query, selectedType.searchValue)) return@LaunchedEffect
        }
        // The visible criteria changed: old results must disappear immediately,
        // while debounce only delays starting the replacement query.
        viewModel.invalidateSearch()
        if (query.isBlank()) return@LaunchedEffect
        delay(250)
        viewModel.searchQuery.value = query.trim()
        viewModel.searchType.value = selectedType.searchValue
        // U09/U28：统一由生效范围驱动（今天/昨天/自选用起止日期，7/30 用天数）。
        if (effectiveFrom != null && effectiveTo != null) {
            viewModel.setSearchRange(effectiveFrom, effectiveTo, recordRangeDays = rangeDays)
        } else {
            viewModel.setSearchRange(rangeDays?.toLong())
        }
    }

    val rangeStartMs = remember(effectiveFrom, rangeDays, viewModel.settings.startedAt) {
        val startDate = effectiveFrom
            ?: rangeDays?.let { LocalDate.now().minusDays((it - 1).toLong()) }
            ?: viewModel.settings.startedAt.takeIf { it > 0L }?.let {
                Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
            }
            ?: LocalDate.now()
        startDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
    // R02：索引/缺口的结束边界必须是所选范围结束日的次日 0 点；
    // 昨天=昨日 24:00，历史=范围末日的次日 0 点，今天=现在（含 30s 心跳语义）。
    // P1-B：查询时刻只有一个来源——「今天」随 30 秒心跳推进（此前 remember 只依赖日期，
    // 停留在页面上时新增到访会被旧结束时刻裁掉），范围边界/地点索引/缺口共用同一个 nowMs。
    // 注意（子代理复核 #5）：记录浏览器与搜索结果是一次性快照，只在条件变化时重查，
    // 不随心跳自动刷新——不要把它们算进「随停留刷新」的验收项。
    val nowMs by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    val rangeEndMs = remember(effectiveTo, rangeDays, nowMs) {
        val endDate = effectiveTo ?: archiveToday
        if (endDate >= archiveToday) nowMs + 1L
        else endDate.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
    val relevantGaps = remember(collectionGaps, rangeStartMs, rangeEndMs, selectedType) {
        val source = when (selectedType) {
            ArchiveIndexType.APP -> SourceId.USAGE.name
            ArchiveIndexType.NOTIFICATION -> SourceId.NOTIFICATIONS.name
            ArchiveIndexType.PLACE -> SourceId.LOCATION.name
            ArchiveIndexType.ALL -> null
        }
        collectionGaps.filter { gap ->
            gap.startMs < rangeEndMs && gap.endMs > rangeStartMs && (source == null || gap.source == source)
        }.filter { classifyGapImpact(it) == GapImpact.IMPORTANT }
    }
    val notificationSources = remember(explorer.notifications) {
        explorer.notifications.groupBy { it.packageName }.map { (packageName, events) ->
            val latest = events.maxBy { it.occurredMs }
            NotificationSourceIndex(packageName, latest.appLabel, events.size, latest.occurredMs)
        }.sortedByDescending { it.count }
    }
    val placeIndexes = remember(visits, rangeStartMs, rangeEndMs) {
        visits.asSequence()
            .filter { it.startMs < rangeEndMs && it.endMs > rangeStartMs }
            .groupBy { it.placeId }
            .map { (placeId, rows) ->
                val latest = rows.maxBy { it.endMs }
                PlaceIndex(
                    placeId = placeId,
                    name = latest.name,
                    address = latest.address,
                    totalMs = intervalUnionDurationMs(
                        rows.map { MillisInterval(it.startMs, it.endMs) }, rangeStartMs, rangeEndMs,
                    ),
                    visits = intervalUnionCount(
                        rows.map { MillisInterval(it.startMs, it.endMs) }, rangeStartMs, rangeEndMs,
                    ),
                )
            }
            .sortedByDescending { it.totalMs }
    }

    fun chooseIndex(type: ArchiveIndexType, value: String, objectKey: String? = null) {
        // U08：点击索引对象 → 对象精确筛选（placeId/包名），不再只是把名称填进搜索框导致重名混查。
        val from = effectiveFrom
        val to = effectiveTo
        if (objectKey != null && from != null && to != null) {
            when (type) {
                ArchiveIndexType.PLACE -> viewModel.searchByPlaceObject(objectKey.toLong(), value, from, to)
                ArchiveIndexType.NOTIFICATION ->
                    viewModel.searchByPackageNotificationsObject(objectKey, value, from, to)
                ArchiveIndexType.APP, ArchiveIndexType.ALL ->
                    viewModel.searchByPackageObject(objectKey, value, from, to)
            }
            selectedTypeName = type.name
            return
        }
        selectedTypeName = type.name
        query = value
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("档案", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "按时间与对象，找回生活的细节",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item(key = "archive-shortcuts") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ArchiveShortcut("地点管理", "命名 · 归并 · 撤销", Icons.Outlined.LocationOn, onOpenPlaces, Modifier.weight(1f))
                ArchiveShortcut("AI 回顾", "按日期汇总记录", Icons.Outlined.Apps, onOpenAi, Modifier.weight(1f))
            }
        }
        if (!viewModel.settings.notificationContentEnabled) {
            item {
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onOpenNotificationSettings),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Outlined.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "通知标题和正文未保存，仍可按应用与时间查找",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text("设置", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索应用、通知标题正文或地点") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                shape = RoundedCornerShape(14.dp),
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ArchiveIndexType.entries.forEach { type ->
                        FilterChip(
                            selected = selectedType == type,
                            onClick = { selectedTypeName = type.name },
                            label = { Text(type.label) },
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("范围", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // U09/U28：今天/昨天/7天/30天/自选 五档，小屏横向滚动不挤成多行。
                    ArchiveRangeKey.entries.forEach { key ->
                        FilterChip(
                            selected = selectedRange == key,
                            onClick = {
                                if (key == ArchiveRangeKey.CUSTOM) {
                                    // R05：打开自选前记住当前范围；取消时恢复，不悄悄改筛选。
                                    pendingRangeReplacement = rangeKey
                                    customPickerOpen = true
                                } else {
                                    rangeKey = key.name
                                }
                            },
                            label = { Text(key.label) },
                        )
                    }
                }
            }
        }
        if (relevantGaps.isNotEmpty()) {
            item {
                Row(
                    Modifier.fillMaxWidth().clickable { gapDetailsOpen = true }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Outlined.WarningAmber,
                        null,
                        modifier = Modifier.size(17.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "部分时间未记录，查询结果可能不完整",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("查看", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        if (query.isBlank() && objectFilterLabel == null) {
            if (selectedType == ArchiveIndexType.ALL || selectedType == ArchiveIndexType.APP) {
                item { ArchiveIndexHeading("常用应用", "按使用时长") }
                if (explorer.apps.isEmpty()) {
                    item { ArchiveIndexEmpty("这个范围还没有应用使用记录") }
                } else if (selectedType == ArchiveIndexType.APP) {
                    itemsIndexed(
                        items = explorer.apps,
                        key = { _, app -> "archive-app-${app.packageName}" },
                    ) { index, app ->
                        Column {
                            AppIndexRow(app) { chooseIndex(ArchiveIndexType.APP, app.appLabel, app.packageName) }
                            if (index < explorer.apps.lastIndex) InstrumentDivider()
                        }
                    }
                } else {
                    item {
                        ArchiveIndexPanel {
                            explorer.apps.take(5).forEachIndexed { index, app ->
                                AppIndexRow(app) { chooseIndex(ArchiveIndexType.APP, app.appLabel, app.packageName) }
                                if (index < minOf(4, explorer.apps.lastIndex)) InstrumentDivider()
                            }
                        }
                    }
                }
            }
            if (selectedType == ArchiveIndexType.ALL || selectedType == ArchiveIndexType.NOTIFICATION) {
                item { ArchiveIndexHeading("主要通知来源", "按通知数量") }
                if (notificationSources.isEmpty()) {
                    item { ArchiveIndexEmpty("这个范围还没有通知记录") }
                } else if (selectedType == ArchiveIndexType.NOTIFICATION) {
                    itemsIndexed(
                        items = notificationSources,
                        key = { _, source -> "archive-notification-${source.packageName}" },
                    ) { index, source ->
                        Column {
                            NotificationSourceRow(source) {
                                chooseIndex(ArchiveIndexType.NOTIFICATION, source.appLabel, source.packageName)
                            }
                            if (index < notificationSources.lastIndex) InstrumentDivider()
                        }
                    }
                } else {
                    item {
                        ArchiveIndexPanel {
                            notificationSources.take(5).forEachIndexed { index, source ->
                                NotificationSourceRow(source) {
                                    chooseIndex(ArchiveIndexType.NOTIFICATION, source.appLabel, source.packageName)
                                }
                                if (index < minOf(4, notificationSources.lastIndex)) InstrumentDivider()
                            }
                        }
                    }
                }
            }
            if (selectedType == ArchiveIndexType.ALL || selectedType == ArchiveIndexType.PLACE) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        ArchiveIndexHeading("常去地点", "按已记录停留", Modifier.weight(1f))
                        TextButton(onClick = onOpenTrips) { Text("行程地图与历史") }
                    TextButton(onClick = onOpenPlaces) { Text("管理地点") }
                    }
                }
                if (placeIndexes.isEmpty()) {
                    item { ArchiveIndexEmpty("这个范围还没有确认的地点停留") }
                } else if (selectedType == ArchiveIndexType.PLACE) {
                    itemsIndexed(
                        items = placeIndexes,
                        key = { _, place -> "archive-place-${place.placeId}" },
                    ) { index, place ->
                        Column {
                            PlaceIndexRow(place) { chooseIndex(ArchiveIndexType.PLACE, place.name, place.placeId.toString()) }
                            if (index < placeIndexes.lastIndex) InstrumentDivider()
                        }
                    }
                } else {
                    item {
                        ArchiveIndexPanel {
                            placeIndexes.take(5).forEachIndexed { index, place ->
                                PlaceIndexRow(place) { chooseIndex(ArchiveIndexType.PLACE, place.name, place.placeId.toString()) }
                                if (index < minOf(4, placeIndexes.lastIndex)) InstrumentDivider()
                            }
                        }
                    }
                }
            }
        } else {
            val groupedResults = results
                .filter { selectedType == ArchiveIndexType.ALL || it.kind == selectedType.searchValue }
                .groupBy { it.startMs.archiveIndexDate() }
                .toList()
                .sortedByDescending { it.first }
            item {
                // P1-C：对象模式下说明白「现在到底按什么筛的」——关键词是否参与、地点为什么不参与。
                val objectCriteriaNote = when {
                    objectFilterLabel == null -> null
                    selectedType == ArchiveIndexType.PLACE -> "地点筛选按时间范围，关键词不参与（到访记录没有文字）"
                    query.isNotBlank() -> "含「${query.trim()}」"
                    else -> null
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    // U08/16.7：对象筛选时显示“当前筛选对象”，标题说明正在看这个对象的全部记录。
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            when {
                                objectFilterLabel != null && results.isEmpty() -> "“$objectFilterLabel” 记录"
                                objectFilterLabel != null -> "“$objectFilterLabel” · 已显示 ${results.size} 条"
                                results.isEmpty() -> "查询结果"
                                else -> "查询结果 · 已显示 ${results.size} 条"
                            },
                            style = MaterialTheme.typography.titleLarge,
                        )
                        objectCriteriaNote?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    AssistChip(
                        onClick = { query = ""; viewModel.clearObjectFilter() },
                        label = { Text("清除") },
                    )
                }
            }
            if (groupedResults.isEmpty() && !searchLoading) {
                // R09：首屏错误与没有记录区分——错误给重试，空结果给引导。
                if (searchError != null) {
                    item {
                        ArchiveIndexEmpty("查询失败", searchError, actionLabel = "重试", onAction = { viewModel.runSearch() })
                    }
                } else {
                    item { ArchiveIndexEmpty("没有找到相关记录", "换个关键词、扩大日期范围，或检查对应记录项目是否已开启。") }
                }
            } else {
                groupedResults.forEach { (date, rows) ->
                    item(key = "archive-result-date-$date") {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                            Text(date.archiveIndexDateLabel(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Text("${rows.size} 条", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    items(rows, key = { "archive-result-${it.kind}-${it.id}-${it.startMs}" }) { result ->
                        ArchiveSearchResultRow(result, onClick = { onOpenEvidence(result) })
                    }
                }
            }
            if (searchLoading) {
                item {
                    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
            } else if (searchHasMore) {
                item {
                    // R04：普通搜索与对象筛选都支持继续读取更早记录。
                    TextButton(
                        onClick = {
                            if (objectFilterLabel != null) viewModel.loadMoreObjectSearch()
                            else viewModel.loadMoreSearchResults()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (objectFilterLabel != null) "继续加载更早记录" else "继续加载下一批记录") }
                }
            }
        }
    }

    if (gapDetailsOpen) {
        LifeLogDetailSheet(
            title = "所选范围未记录的时间",
            subtitle = "没有相关事件，与当时没有记录到数据，是两种不同情况",
            onDismiss = { gapDetailsOpen = false },
            contentKey = "archive-index-gaps-$rangeKey-${selectedType.name}",
        ) {
            items(relevantGaps, key = { "archive-index-gap-${it.id}-${it.source}-${it.startMs}" }) { gap ->
                GapRow(
                    startMs = maxOf(gap.startMs, rangeStartMs),
                    endMs = minOf(gap.endMs, nowMs),
                    sourceLabel = gap.source.archiveIndexSourceLabel(),
                    detail = gap.userFacingGapReason(),
                )
            }
        }
    }

    // U28/P1-A：自选日期范围——独立选择层。
    // 不再把两个完整月历塞进普通 AlertDialog（M3 的 AlertDialog 文本槽不滚动，
    // 窄屏下必然裁切/重叠）；改为「起/止两个紧凑字段 + 各自单日期弹窗 + 底部固定应用筛选」。
    if (customPickerOpen) {
        ArchiveRangePickerSheet(
            initialFromEpochDay = customFromEpochDay,
            initialToEpochDay = customToEpochDay,
            earliestDate = earliestDate,
            latestDate = archiveToday,
            onDismiss = {
                // R05：取消不改变任何筛选。
                pendingRangeReplacement?.let { rangeKey = it }
                pendingRangeReplacement = null
                customPickerOpen = false
            },
            onApply = { from, to ->
                customFromEpochDay = from.toEpochDay()
                customToEpochDay = to.toEpochDay()
                rangeKey = ArchiveRangeKey.CUSTOM.name
                pendingRangeReplacement = null
                customPickerOpen = false
            },
        )
    }
}

/**
 * P1-A：日期范围选择层。
 * 顶部标题 → 起/止两个紧凑字段（点哪个开哪个的单日期弹窗）→ 汇总与校验 → 底部固定「应用筛选」。
 * 底部操作区不随内容滚动消失；取消或系统返回不改动任何筛选，只有「应用筛选」才生效。
 */
@Composable
private fun ArchiveRangePickerSheet(
    initialFromEpochDay: Long?,
    initialToEpochDay: Long?,
    earliestDate: LocalDate,
    latestDate: LocalDate,
    onDismiss: () -> Unit,
    onApply: (LocalDate, LocalDate) -> Unit,
) {
    // 子代理复核 #4：初始值也要收敛到 [启用日..今天]——否则新用户首屏会出现
    // 「字段写着 7 天、查询只覆盖启用后 3 天」的第二套口径。
    var fromEpochDay by rememberSaveable {
        mutableStateOf(
            (initialFromEpochDay ?: latestDate.minusDays(6).toEpochDay())
                .coerceIn(earliestDate.toEpochDay(), latestDate.toEpochDay()),
        )
    }
    var toEpochDay by rememberSaveable {
        mutableStateOf(
            (initialToEpochDay ?: latestDate.toEpochDay()).coerceIn(earliestDate.toEpochDay(), latestDate.toEpochDay()),
        )
    }
    var editingField by rememberSaveable { mutableStateOf<String?>(null) }
    val fromDate = LocalDate.ofEpochDay(fromEpochDay)
    val toDate = LocalDate.ofEpochDay(toEpochDay)
    val invalid = fromDate > toDate
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        dragHandle = { BottomSheetDefaults.DragHandle(width = 34.dp, height = 3.dp, color = MaterialTheme.colorScheme.outline) },
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Column(
                // 子代理复核 #2：滚动区必须带 weight——否则内容超高时它会吃掉全部高度，
                // 后面固定高度的按钮行只能拿到 maxHeight=0（横屏/130%+ 字号下「应用筛选」不可达）。
                Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("选择日期范围", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "点开始或结束日期，各自用日历单选；结束日期含当天。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ArchiveDateField(label = "开始日期", date = fromDate, onClick = { editingField = RANGE_FIELD_FROM })
                ArchiveDateField(label = "结束日期（含当天）", date = toDate, onClick = { editingField = RANGE_FIELD_TO })
                Text(
                    if (invalid) {
                        "开始日期不能晚于结束日期"
                    } else {
                        val days = java.time.temporal.ChronoUnit.DAYS.between(fromDate, toDate) + 1
                        "${fromDate.format(DateTimeFormatter.ofPattern("M月d日"))} — ${toDate.format(DateTimeFormatter.ofPattern("M月d日"))} · 共 $days 天"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (invalid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(
                    onClick = { onApply(fromDate, toDate) },
                    enabled = !invalid,
                    modifier = Modifier.weight(1f),
                ) { Text("应用筛选") }
            }
        }
    }
    when (editingField) {
        RANGE_FIELD_FROM -> ArchiveSingleDateDialog(
            initialDate = fromDate,
            earliestDate = earliestDate,
            latestDate = latestDate,
            onDismiss = { editingField = null },
            onSelected = {
                fromEpochDay = it.toEpochDay()
                editingField = null
            },
        )
        RANGE_FIELD_TO -> ArchiveSingleDateDialog(
            initialDate = toDate,
            earliestDate = earliestDate,
            latestDate = latestDate,
            onDismiss = { editingField = null },
            onSelected = {
                toEpochDay = it.toEpochDay()
                editingField = null
            },
        )
        else -> Unit
    }
}

/** P1-A：单个日期字段——点开一个标准单日期弹窗，不做同屏双月历。 */
@Composable
private fun ArchiveDateField(label: String, date: LocalDate, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    // 子代理复核 #7：150% 字号下「yyyy年M月d日 · EEEE」会在 360dp 屏被省略号截断，改用短格式。
                    date.format(DateTimeFormatter.ofPattern("M月d日 · EEEE")),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.Outlined.DateRange, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * P1-A/P1-D：单日期选择弹窗——沿用档案页同一套 UTC 编码口径（探针 P05），
 * 并用 [SelectableDates] 把可选范围限制在「启用日..今天」，
 * 从源头消灭「界面能选、查询偷偷改成另一套」的两套条件。
 */
@Composable
private fun ArchiveSingleDateDialog(
    initialDate: LocalDate,
    earliestDate: LocalDate,
    latestDate: LocalDate,
    onDismiss: () -> Unit,
    onSelected: (LocalDate) -> Unit,
) {
    val selectableDates = remember(earliestDate, latestDate) {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                Instant.ofEpochMilli(utcTimeMillis).atZone(java.time.ZoneOffset.UTC).toLocalDate() in earliestDate..latestDate

            override fun isSelectableYear(year: Int): Boolean = year in earliestDate.year..latestDate.year
        }
    }
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initialDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(),
        yearRange = earliestDate.year..latestDate.year,
        selectableDates = selectableDates,
    )
    // 子代理复核 #3：不再用 M3 的 DatePickerDialog——它内部没有滚动容器
    // （字节码确认 material3 全包只有 DropdownMenu 用 verticalScroll），
    // 窗口高度不足时末位按钮行拿到 maxHeight=0，横屏/大字体下「选择」不可达。
    // 这里自绘：日期区可滚动，按钮行固定固有高度。
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.padding(horizontal = 12.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.fillMaxWidth()) {
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                ) {
                    DatePicker(state = state, showModeToggle = false)
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    TextButton(onClick = {
                        state.selectedDateMillis?.let {
                            onSelected(Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC).toLocalDate())
                        }
                    }) { Text("选择") }
                }
            }
        }
    }
}

private const val RANGE_FIELD_FROM = "from"
private const val RANGE_FIELD_TO = "to"

@Composable
private fun ArchiveIndexHeading(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ArchiveIndexPanel(content: @Composable ColumnScope.() -> Unit) {
    InstrumentPanel { content() }
}

@Composable
private fun ArchiveShortcut(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}

@Composable
private fun AppIndexRow(app: AppUsageOverview, onClick: () -> Unit) {
    ArchiveObjectRow(
        title = app.appLabel,
        detail = "${app.activeDays} 天 · ${app.sessionCount} 段使用",
        trailing = formatDuration(app.totalDurationMs),
        icon = { ArchiveIndexAppIcon(app.packageName, app.appLabel, 34.dp) },
        onClick = onClick,
    )
}

@Composable
private fun NotificationSourceRow(source: NotificationSourceIndex, onClick: () -> Unit) {
    ArchiveObjectRow(
        title = source.appLabel,
        detail = "最近 ${source.lastMs.archiveIndexDateTime()}",
        trailing = "${source.count} 条",
        icon = { ArchiveIndexAppIcon(source.packageName, source.appLabel, 34.dp) },
        onClick = onClick,
    )
}

@Composable
private fun PlaceIndexRow(place: PlaceIndex, onClick: () -> Unit) {
    ArchiveObjectRow(
        title = place.name,
        detail = place.address.ifBlank { "${place.visits} 次到访" },
        trailing = formatDuration(place.totalMs),
        icon = {
            Surface(Modifier.size(34.dp), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.LocationOn, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        onClick = onClick,
    )
}

@Composable
private fun ArchiveObjectRow(
    title: String,
    detail: String,
    trailing: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icon()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            trailing,
            modifier = Modifier.widthIn(max = 104.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ArchiveSearchResultRow(result: SearchRow, onClick: () -> Unit) {
    val icon = when (result.kind) {
        "APP" -> Icons.Outlined.Apps
        "NOTIFICATION" -> Icons.Outlined.NotificationsNone
        "PLACE" -> Icons.Outlined.LocationOn
        else -> Icons.Outlined.Search
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ArchiveIndexGlyph(icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(result.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                result.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            result.startMs.archiveIndexClock(),
            modifier = Modifier.widthIn(min = 42.dp),
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun ArchiveIndexGlyph(icon: ImageVector) {
    Surface(Modifier.size(34.dp), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ArchiveIndexAppIcon(packageName: String, appLabel: String, size: Dp) {
    val context = LocalContext.current
    val icon = remember(packageName) {
        runCatching { context.packageManager.getApplicationIcon(packageName).toBitmap(56, 56).asImageBitmap() }.getOrNull()
    }
    if (icon != null) {
        Image(icon, contentDescription = "$appLabel 图标", modifier = Modifier.size(size))
    } else {
        Surface(Modifier.size(size), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Box(contentAlignment = Alignment.Center) { Text(appLabel.take(1), style = MaterialTheme.typography.titleSmall) }
        }
    }
}

@Composable
private fun ArchiveIndexEmpty(message: String, detail: String? = null, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    InstrumentPanel(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction, modifier = Modifier.padding(top = 6.dp)) { Text(actionLabel) }
            }
        }
    }
}

private fun Long.archiveIndexDate(): LocalDate =
    Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()

private fun Long.archiveIndexClock(): String =
    Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))

private fun String.archiveIndexSourceLabel(): String = when (this) {
    SourceId.USAGE.name -> "应用"
    SourceId.NOTIFICATIONS.name -> "通知"
    SourceId.LOCATION.name -> "地点"
    SourceId.STEPS.name -> "步数"
    SourceId.SLEEP.name -> "睡眠"
    else -> this
}

private fun Long.archiveIndexDateTime(): String =
    Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))

private fun LocalDate.archiveIndexDateLabel(): String = when (this) {
    LocalDate.now() -> "今天 · ${format(DateTimeFormatter.ofPattern("M月d日"))}"
    LocalDate.now().minusDays(1) -> "昨天 · ${format(DateTimeFormatter.ofPattern("M月d日"))}"
    else -> format(DateTimeFormatter.ofPattern("M月d日 · EEEE"))
}
