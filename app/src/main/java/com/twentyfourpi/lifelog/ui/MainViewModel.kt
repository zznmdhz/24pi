package com.twentyfourpi.lifelog.ui

import android.Manifest
import android.app.Application
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.twentyfourpi.lifelog.LifeLogApp
import com.twentyfourpi.lifelog.collector.*
import com.twentyfourpi.lifelog.data.*
import com.twentyfourpi.lifelog.debug.DiagnosticLog
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as LifeLogApp
    internal val repository = app.repository
    val settings get() = app.settings

    val selectedDate = MutableStateFlow(LocalDate.now())

    // U03：日期与数据绑定。TimelineDay 本身不带日期，旧版用独立 selectedDate + 无日期
    // 初值，切换瞬间可能把旧日期数据标到新日期标题下，或加载中先显示“无记录”。
    // 现在每个状态都携带 date + loading；切换日期先发 loading（data 保持空快照，不再
    // 把上一日期数据当新日期内容），查询返回后只发与 date 匹配的数据。
    // UI 只有在 day.date == selectedDate 时才把 day.data 画到标题下；读取中不是无数据。
    val day: StateFlow<DayUiState> = dayStateFlow(selectedDate, repository::observeDay)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            DayUiState(date = LocalDate.now(), loading = true, data = TimelineDay()),
        )

    // v0.16.1：按日期缓存已构建的全天轨迹；仅在用户展开面板时加载一次，
    // 不随每个新定位点重算（v0.16.0 事故的发热根因之一）。
    // 审核#1：今天（isToday）每次展开都强制重载以反映最新记录；历史日期保持快照。
    private val dailyRouteCache = linkedMapOf<LocalDate, DailyRoute>()

    // R06：首页/详情消费新语义聚合（DayEpisodeBuilder）而不是旧 TimeChapterComposer；
    // 短停折叠、夹停裁决、稳定归属都在这一层生效（文档 8.2 步骤 5 之后）。
    private val _dailyEpisodes = MutableStateFlow<Map<LocalDate, List<DayEpisode>>>(emptyMap())
    val dailyEpisodes = _dailyEpisodes.asStateFlow()

    /**
     * P3：语义时段投影缓存（唯一计算入口，见 [refreshDailyEpisodes]）。
     * 实例由 [LifeLogApp] 持有并与诊断导出共用——两个出口必须看见同一份投影与截止时刻。
     */
    private val episodeSummaryCache: EpisodeSummaryCache
        get() = getApplication<LifeLogApp>().episodeSummaryCache

    /**
     * 第四批：有效缺口投影（首页/详情/汇总/导出共用）。
     *
     * 计算入口只有这一个，与语义时段一起刷新——同一天不允许出现两套解释。
     * 「撤销投影」见 [setGapProjectionRaw]。
     */
    private val _dayProjections = MutableStateFlow<Map<LocalDate, GapProjection>>(emptyMap())
    val dayProjections = _dayProjections.asStateFlow()

    private val _gapProjectionRaw = MutableStateFlow(settings.getBoolean(GAP_PROJECTION_RAW_SETTING_KEY, false))

    /** true = 撤销投影，回到原始缺口解释（原始记录一字节不改）。 */
    val gapProjectionRaw = _gapProjectionRaw.asStateFlow()

    fun setGapProjectionRaw(raw: Boolean) {
        if (_gapProjectionRaw.value == raw) return
        settings.putBoolean(GAP_PROJECTION_RAW_SETTING_KEY, raw)
        _gapProjectionRaw.value = raw
        val today = LocalDate.now()
        refreshDayProjection(today)
        refreshDailyEpisodes(today)
        refreshArchive()
    }

    /** 当前投影模式（唯一判定口，避免各页面各自读设置）。 */
    private fun projectionMode(): ProjectionMode =
        if (_gapProjectionRaw.value) ProjectionMode.RAW else ProjectionMode.EFFECTIVE

    fun refreshDayProjection(date: LocalDate) {
        viewModelScope.launch {
            val projection = runCatching { repository.dayProjection(date, projectionMode()) }.getOrNull() ?: return@launch
            _dayProjections.update { it + (date to projection) }
        }
    }

    /**
     * R06/P3：首页语义时段统一走 [EpisodeSummaryCache]——有界缓存、可观察结果、修订失效
     * （F 批 R12 的缓存此前是孤儿代码，首页在重复直算；现在这里是唯一入口）。
     */
    fun refreshDailyEpisodes(date: LocalDate) {
        val isToday = date == LocalDate.now()
        val ready = episodeSummaryCache.getOrCompute(
            date = date,
            isToday = isToday,
            // 今天每次都重算（反映最新记录）；历史日保留快照，除非修订版本变化。
            forceRefresh = isToday,
            compute = { repository.dailyEpisodes(it) },
        )
        if (ready != null) {
            _dailyEpisodes.update { it + (date to ready.episodes) }
        }
        // 第四批：缺口投影与语义时段同步刷新，扫码式卡片提示才不会滞后一拍。
        refreshDayProjection(date)
        // 未命中缓存时由 [EpisodeSummaryCache.results] 在计算完成后推送（见 init 订阅）。
    }

    suspend fun loadDailyRoute(date: LocalDate, forceRefresh: Boolean = false): DailyRoute? {
        if (!forceRefresh) dailyRouteCache[date]?.let { return it }
        return repository.dailyRoute(date)?.also {
            dailyRouteCache[date] = it
            while (dailyRouteCache.size > 7) dailyRouteCache.remove(dailyRouteCache.keys.first())
        }
    }

    val attributionRevisions = repository.observeAttributionRevisions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Closing the editor must not cancel cache invalidation after the transaction commits.
    suspend fun correctVisit(visitId: Long, targetPlaceId: Long?) = viewModelScope.async {
        repository.correctVisit(visitId, targetPlaceId)
        refreshAfterCorrection()
    }.await()

    suspend fun undoVisitCorrection(visitId: Long) = viewModelScope.async {
        repository.undoVisitCorrection(visitId)
        refreshAfterCorrection()
    }.await()

    private fun refreshAfterCorrection() {
        dailyRouteCache.clear()
        episodeSummaryCache.bumpRevisionVersion()
        _dailyEpisodes.value = emptyMap()
        _dayProjections.value = emptyMap()
        refreshDailyEpisodes(selectedDate.value)
        refreshArchive()
        refreshRecords()
        if (objectFilter != null) rerunObjectSearch() else runSearch()
    }

    val places = repository.observePlaces()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val latestLocation = repository.observeLatestLocation()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val recentVisits = repository.observeAllVisits()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val collectionGaps = repository.observeAllCollectionGaps()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val statuses = repository.observeStatuses()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _archiveSummaries = MutableStateFlow<List<DailyArchiveSummary>>(emptyList())
    val archiveSummaries = _archiveSummaries.asStateFlow()
    private val _recordExplorer = MutableStateFlow(RecordExplorerData())
    val recordExplorer = _recordExplorer.asStateFlow()
    val recordRangeDays = MutableStateFlow<Int?>(7)
    // R02：解析后的记录/索引区间（统一由 preset/custom 得出，汇总与索引共用）。
    private val _recordFrom = MutableStateFlow(LocalDate.now().minusDays(6))
    val recordFrom = _recordFrom.asStateFlow()
    private val _recordTo = MutableStateFlow(LocalDate.now())
    val recordTo = _recordTo.asStateFlow()

    val searchQuery = MutableStateFlow("")
    val searchType = MutableStateFlow("")
    val searchFrom = MutableStateFlow(LocalDate.now().minusDays(30))
    val searchTo = MutableStateFlow(LocalDate.now())
    private val _searchResults = MutableStateFlow<List<SearchRow>>(emptyList())
    val searchResults = _searchResults.asStateFlow()
    private val _searchHasMore = MutableStateFlow(false)
    val searchHasMore = _searchHasMore.asStateFlow()
    private val _searchLoading = MutableStateFlow(false)
    val searchLoading = _searchLoading.asStateFlow()
    // R09：搜索错误态（首屏错误与没有记录区分；分页错误保留已加载内容）。
    private val _searchError = MutableStateFlow<String?>(null)
    val searchError = _searchError.asStateFlow()
    private var searchJob: Job? = null
    private var searchGeneration = 0L

    /** P1-B：记录/索引查询代次（搜索侧已有 searchGeneration，索引侧此前缺失）。 */
    private var recordsGeneration = 0L
    private val _operationMessage = MutableStateFlow<String?>(null)
    val operationMessage = _operationMessage.asStateFlow()
    // U15：备份/导出等长操作进行中状态（防重复点击 + 过程可见）。
    private val _operationInProgress = MutableStateFlow(false)
    val operationInProgress = _operationInProgress.asStateFlow()
    // R15：正在执行的操作类型（区分任务——备份进行中时 CSV 不误显示“正在导出”）。
    private val _operationType = MutableStateFlow<String?>(null)
    val operationType = _operationType.asStateFlow()
    // R15：操作是否失败（结构化判断，不靠中文字符串前缀）。
    private val _operationFailed = MutableStateFlow(false)
    val operationFailed = _operationFailed.asStateFlow()
    private val _darkMode = MutableStateFlow(settings.darkMode)
    val darkMode = _darkMode.asStateFlow()
    private val _timelineFocusMs = MutableStateFlow<Long?>(null)
    val timelineFocusMs = _timelineFocusMs.asStateFlow()

    init {
        refreshHealth()
        if (settings.collectionEnabled) UsageSyncWorker.schedule(getApplication())
        refreshArchive()
        refreshRecords()
        // P3：投影结果统一由缓存推送——算完立即更新状态流，订阅它的页面即刻重组，
        // 不再依赖「别的状态刚好变了」顺带刷新。
        viewModelScope.launch {
            episodeSummaryCache.results.collect { entries ->
                // P3：以缓存整份快照为准（含删除）——只做累加会抹掉有界缓存的边界，
                // 而且缓存 clear() 推来的 emptyMap 会被吞掉，失效信号传不到首页（子代理复核 #8）。
                _dailyEpisodes.update { entries.mapValues { it.value.episodes } }
            }
        }
        refreshDailyEpisodes(LocalDate.now()) // R06：进入应用即计算今天的语义时段
    }

    fun onAppResumed() {
        refreshHealth()
        refreshArchive()
        refreshRecords()
        refreshDailyEpisodes(LocalDate.now()) // R06：回到前台刷新今天的语义时段
        if (settings.collectionEnabled) {
            UsageSyncWorker.schedule(getApplication())
            CollectorWatchdogScheduler.schedule(getApplication())
            if (getApplication<LifeLogApp>().shouldRunCollectorService(settings)) {
                runCatching { LifeLogCollectorService.start(getApplication()) }
                    .onFailure { DiagnosticLog.error("service", "foreground_recovery_failed", it) }
            }
        }
        if (settings.collectionEnabled && settings.sourceEnabled(SourceId.USAGE)) viewModelScope.launch {
            // 给系统少量时间写入“24π 已进入前台”事件，以便立即关闭上一个应用会话。
            delay(500)
            runCatching { UsageCollector(getApplication(), repository, settings).collect() }
                .onFailure { DiagnosticLog.error("usage", "foreground_sync_failed", it) }
        }
    }

    fun refreshHealth() = viewModelScope.launch {
        val context = getApplication<LifeLogApp>()
        val usage = if (context.hasUsageAccess()) SourceState.ACTIVE else SourceState.PERMISSION_REQUIRED
        updatePermissionHealth(SourceId.USAGE, usage,
            if (usage == SourceState.ACTIVE) "使用情况访问已开启，等待同步" else "需要使用情况访问权限；侧载安装可能需先允许受限设置")
        val notification = when {
            !context.hasNotificationListenerAccess() -> SourceState.PERMISSION_REQUIRED
            !MetadataNotificationListener.isConnected() -> SourceState.SYSTEM_BLOCKED
            else -> SourceState.ACTIVE
        }
        updatePermissionHealth(SourceId.NOTIFICATIONS, notification,
            if (notification == SourceState.ACTIVE) {
                if (settings.notificationContentEnabled) "统计普通通知，并保存标题和正文" else "只记录普通通知的应用、时间和数量"
            } else if (notification == SourceState.SYSTEM_BLOCKED) "通知监听未连接，系统正在尝试恢复"
            else "需要通知使用权；侧载安装可能需先允许受限设置")
        val hasLocationPermission = context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) &&
            context.hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val collectorHeartbeat = settings.getLong(CollectionGapDetector.HEARTBEAT_KEY)
        val collectorStale = settings.collectionEnabled && System.currentTimeMillis() - collectorHeartbeat > 5 * 60_000L
        val location = when {
            !hasLocationPermission -> SourceState.PERMISSION_REQUIRED
            collectorStale -> SourceState.SYSTEM_BLOCKED
            else -> SourceState.ACTIVE
        }
        updatePermissionHealth(SourceId.LOCATION, location,
            when (location) {
                SourceState.ACTIVE -> "定位权限已开启，等待首次位置"
                SourceState.SYSTEM_BLOCKED -> "采集心跳中断，系统正在尝试恢复"
                else -> "需要始终允许精确位置"
            })
        val stepSensor = context.getSystemService(SensorManager::class.java).getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        val steps = when {
            stepSensor == null -> SourceState.UNSUPPORTED
            !context.hasPermission(Manifest.permission.ACTIVITY_RECOGNITION) -> SourceState.PERMISSION_REQUIRED
            else -> SourceState.ACTIVE
        }
        updatePermissionHealth(SourceId.STEPS, steps,
            when (steps) { SourceState.UNSUPPORTED -> "设备没有计步传感器"; SourceState.PERMISSION_REQUIRED -> "需要身体活动权限"; else -> "计步权限已开启" })
        repository.setStatus(SourceId.SLEEP, SourceState.UNSUPPORTED, "小米健康未向个人应用开放读取接口")
    }

    private suspend fun updatePermissionHealth(source: SourceId, permissionState: SourceState, detail: String) {
        if (!settings.sourceEnabled(source)) {
            repository.setStatus(source, SourceState.PAUSED, "已暂停")
            return
        }
        val current = repository.sourceStatus(source)
        val shouldUpdate = permissionState != SourceState.ACTIVE || current == null ||
            current.state == SourceState.PERMISSION_REQUIRED.name || current.state == SourceState.PAUSED.name
        // 权限检查不能覆盖采集器已经报告的“系统暂停/异常”，否则状态页会产生假正常。
        if (shouldUpdate) repository.setStatus(source, permissionState, detail)
    }

    fun setCollection(enabled: Boolean) {
        settings.collectionEnabled = enabled
        if (enabled) {
            if (settings.startedAt == 0L) settings.startedAt = System.currentTimeMillis()
            UsageSyncWorker.schedule(getApplication())
            CollectorWatchdogScheduler.schedule(getApplication(), soon = true)
            if (getApplication<LifeLogApp>().shouldRunCollectorService(settings)) {
                LifeLogCollectorService.start(getApplication())
            }
        } else {
            UsageSyncWorker.cancel(getApplication())
            CollectorWatchdogScheduler.cancel(getApplication())
            CollectionGapDetector(app.databaseProvider, settings).clearHeartbeat()
            LifeLogCollectorService.stop(getApplication())
        }
    }

    fun completeOnboarding() { settings.onboardingCompleted = true }
    fun setDarkMode(enabled: Boolean) {
        settings.darkMode = enabled
        _darkMode.value = enabled
    }
    fun previousDay() { selectedDate.value = selectedDate.value.minusDays(1) }
    fun nextDay() { if (selectedDate.value < LocalDate.now()) selectedDate.value = selectedDate.value.plusDays(1) }
    fun focusTimelineAt(timeMs: Long) {
        selectedDate.value = Instant.ofEpochMilli(timeMs).atZone(ZoneId.systemDefault()).toLocalDate()
        _timelineFocusMs.value = timeMs
    }
    fun consumeTimelineFocus() { _timelineFocusMs.value = null }

    fun invalidateSearch() {
        searchJob?.cancel()
        searchGeneration++
        _searchResults.value = emptyList()
        _searchHasMore.value = false
        _searchLoading.value = false
        _searchError.value = null
    }

    fun runSearch(reset: Boolean = true) {
        if (!reset && (_searchLoading.value || !_searchHasMore.value)) return
        if (reset) invalidateSearch()
        val generation = searchGeneration
        val query = searchQuery.value
        val type = searchType.value
        val from = searchFrom.value
        val to = searchTo.value
        val cursor = if (reset) null else _searchResults.value.lastOrNull()
        searchJob = viewModelScope.launch {
            _searchLoading.value = true
            try {
                val page = repository.search(query, from, to, type, SEARCH_PAGE_SIZE, cursor)
                // A stale query must never append into the newly selected range or type.
                if (generation != searchGeneration || query != searchQuery.value || type != searchType.value || from != searchFrom.value || to != searchTo.value) return@launch
                val existing = if (reset) emptyList() else _searchResults.value
                val existingKeys = existing.asSequence().map { Triple(it.kind, it.id, it.startMs) }.toHashSet()
                val uniquePage = page.filter { existingKeys.add(Triple(it.kind, it.id, it.startMs)) }
                _searchResults.value = existing + uniquePage
                _searchHasMore.value = page.size == SEARCH_PAGE_SIZE
                _searchError.value = null
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t // 取消不是错误，不记录
            } catch (t: Throwable) {
                // R09：查询异常是可见错误；分页失败保留已加载内容（results 不清空）。
                DiagnosticLog.error("search", "search_failed", t)
                if (generation == searchGeneration) _searchError.value = t.message ?: "查询失败"
            } finally {
                if (generation == searchGeneration) _searchLoading.value = false
            }
        }
    }

    fun loadMoreSearchResults() = runSearch(reset = false)

    // ── U08/R03：对象精确筛选（点击应用/地点/通知源索引；重名不混查）──────────

    /** 当前是否处于对象筛选模式（显示 chips 标签而非查询结果）。 */
    val objectFilterLabel = MutableStateFlow<String?>(null)

    /** R03：对象身份（可重新查询，不丢失）。 */
    private var objectFilter: ObjectFilter? = null
    private var objectFilterFrom: LocalDate? = null
    private var objectFilterTo: LocalDate? = null

    /** P1-C：对象筛选下同时生效的关键词（空=不加关键词）。 */
    private var objectKeyword: String = ""

    sealed interface ObjectFilter {
        /** R01：package 对象可按“应用会话”或“通知”两种结果集查询。 */
        data class Package(val packageName: String, val asNotifications: Boolean = false) : ObjectFilter
        data class Place(val placeId: Long) : ObjectFilter

        /** P1-C：该对象对应的结果类型（与界面类型筛选的取值对齐）。 */
        val resultType: String
            get() = when (this) {
                is Place -> "PLACE"
                is Package -> if (asNotifications) "NOTIFICATION" else "APP"
            }

        /** P1-C：类型筛选是否与该对象兼容（空=全部，视为兼容）。 */
        fun isCompatibleWith(type: String): Boolean = type.isBlank() || type == resultType
    }

    fun runObjectSearch(label: String, filter: ObjectFilter, from: LocalDate, to: LocalDate) {
        objectFilter = filter
        objectFilterFrom = from
        objectFilterTo = to
        // P1-C：进对象模式时沿用当前关键词，避免“点对象时按旧条件、敲字后又不生效”的两套行为。
        objectKeyword = searchQuery.value.trim()
        objectFilterLabel.value = label
        invalidateSearch()
        _searchLoading.value = true
        val generation = ++searchGeneration
        searchJob = viewModelScope.launch {
            try {
                val page = queryObject(filter, from, to, null)
                if (generation != searchGeneration) return@launch
                _searchResults.value = page
                _searchHasMore.value = page.size == SEARCH_PAGE_SIZE
                _searchError.value = null
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                DiagnosticLog.error("search", "object_search_failed", t)
                if (generation == searchGeneration) _searchError.value = t.message ?: "查询失败"
            } finally {
                if (generation == searchGeneration) _searchLoading.value = false
            }
        }
    }

    /** R03：按当前对象身份 + 生效范围重查（范围/关键词/类型变化后重查同一对象）。 */
    fun updateObjectFilterRange(from: LocalDate, to: LocalDate) {
        objectFilterFrom = from
        objectFilterTo = to
    }

    fun rerunObjectSearch() {
        val filter = objectFilter ?: return
        val from = objectFilterFrom ?: return
        val to = objectFilterTo ?: return
        rerunObjectSearchWithCursor(filter, from, to, null)
    }

    /**
     * P1-C：对象模式下让关键词和类型真正参与筛选（此前只有日期变化会触发重查）。
     * - 类型与对象不兼容（例如选中「家」却切到「通知」）→ 清除对象筛选并返回 false，
     *   由普通搜索流程接管，不再挂着对象标签却按旧条件出结果。
     * - 兼容（全部或同类型）→ 带上关键词按 对象+范围+关键词 重查，返回 true。
     */
    fun rerunObjectSearchWithCriteria(keyword: String, type: String): Boolean {
        val filter = objectFilter ?: return false
        searchQuery.value = keyword.trim()
        searchType.value = type
        if (!filter.isCompatibleWith(type)) {
            clearObjectFilter()
            return false
        }
        objectKeyword = keyword.trim()
        rerunObjectSearch()
        return true
    }

    /** R04：对象查询分页——继续读更早记录（游标 lastOrNull）。 */
    fun loadMoreObjectSearch() {
        val filter = objectFilter ?: return
        val from = objectFilterFrom ?: return
        val to = objectFilterTo ?: return
        if (_searchLoading.value || !_searchHasMore.value) return
        rerunObjectSearchWithCursor(filter, from, to, _searchResults.value.lastOrNull())
    }

    private fun rerunObjectSearchWithCursor(filter: ObjectFilter, from: LocalDate, to: LocalDate, cursor: SearchRow?) {
        _searchLoading.value = true
        val generation = ++searchGeneration
        searchJob = viewModelScope.launch {
            try {
                val page = queryObject(filter, from, to, cursor)
                if (generation != searchGeneration) return@launch
                val existing = if (cursor == null) emptyList() else _searchResults.value
                val existingKeys = existing.asSequence().map { Triple(it.kind, it.id, it.startMs) }.toHashSet()
                val uniquePage = page.filter { existingKeys.add(Triple(it.kind, it.id, it.startMs)) }
                _searchResults.value = existing + uniquePage
                _searchHasMore.value = page.size == SEARCH_PAGE_SIZE
                _searchError.value = null
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                DiagnosticLog.error("search", "object_paging_failed", t)
                if (generation == searchGeneration) _searchError.value = t.message ?: "查询失败"
            } finally {
                if (generation == searchGeneration) _searchLoading.value = false
            }
        }
    }

    private suspend fun queryObject(filter: ObjectFilter, from: LocalDate, to: LocalDate, after: SearchRow?): List<SearchRow> =
        when (filter) {
            // P1-C：地点对象只按时间范围筛选——到访记录没有可匹配文本，不做假组合。
            is ObjectFilter.Place -> repository.searchByPlace(filter.placeId, from, to, after = after)
            is ObjectFilter.Package ->
                if (filter.asNotifications) repository.searchByPackageNotifications(filter.packageName, objectKeyword, from, to, after = after)
                else repository.searchByPackage(filter.packageName, objectKeyword, from, to, after = after)
        }

    /** U08：按地点对象筛选（点击地点索引时调用）。 */
    fun searchByPlaceObject(placeId: Long, name: String, from: LocalDate, to: LocalDate) =
        runObjectSearch(name, ObjectFilter.Place(placeId), from, to)

    /** U08：按应用包名筛选（点击应用索引时调用）。 */
    fun searchByPackageObject(packageName: String, label: String, from: LocalDate, to: LocalDate) =
        runObjectSearch(label, ObjectFilter.Package(packageName), from, to)

    /** R01：按包名查通知对象（点击通知来源索引时调用；不拿应用会话凑数）。 */
    fun searchByPackageNotificationsObject(packageName: String, label: String, from: LocalDate, to: LocalDate) =
        runObjectSearch(label, ObjectFilter.Package(packageName, asNotifications = true), from, to)

    fun clearObjectFilter() {
        // P1-C：退出对象模式时同时丢掉对象身份与关键词，避免下一次重查把旧对象复活。
        objectFilter = null
        objectKeyword = ""
        objectFilterLabel.value = null
        invalidateSearch()
    }
    fun refreshArchive() = viewModelScope.launch {
        val today = LocalDate.now()
        val started = settings.startedAt.takeIf { it > 0 }?.let(repository::dayFor) ?: today
        val from = maxOf(started, today.minusDays(89))
        _archiveSummaries.value = repository.archiveSummaries(from, today, projectionMode())
    }
    fun refreshRecords() = viewModelScope.launch {
        val today = LocalDate.now()
        // R02：索引/记录统一使用解析后的起止区间（recordFrom/recordTo），
        // 不再把 TODAY/YESTERDAY/CUSTOM 的 null 解释成“启用以来全程”。
        val from = _recordFrom.value
        val to = _recordTo.value.coerceAtMost(today)
        // P1-B：请求代次校验——连续快速切换范围时，先发出的旧查询不得覆盖后发出的新结果。
        val generation = ++recordsGeneration
        val explorer = repository.recordExplorer(from, to)
        if (generation != recordsGeneration) return@launch
        _recordExplorer.value = explorer
    }
    fun setRecordRange(days: Int?) {
        recordRangeDays.value = days
        val today = LocalDate.now()
        val from = days?.let { today.minusDays((it - 1).toLong()) }
            ?: settings.startedAt.takeIf { it > 0 }?.let(repository::dayFor)
            ?: today
        _recordFrom.value = from
        _recordTo.value = today
        refreshRecords()
    }

    /** R02：统一范围解析——无论预设，返回明确的 (from,to) 闭区间（to<=今天）。 */
    fun resolveRange(from: LocalDate, to: LocalDate): Pair<LocalDate, LocalDate> {
        // P1-D：收敛与「永不反向」的保证抽成纯函数 [resolveRangeBounds]，单测盯着它。
        return resolveRangeBounds(
            from = from,
            to = to,
            startedFloor = supportedStartFloor(),
            today = LocalDate.now(),
        )
    }

    /**
     * P1-D：界面与查询共用的「启用日」口径。
     * 未知启用日（startedAt==0）时两处必须用同一个兜底值——此前界面兜 2020-01-01、
     * 查询兜「今天」，自选历史日期会被裁成 from>to 的反向区间（子代理复核 #1）。
     */
    private fun supportedStartFloor(): LocalDate =
        settings.startedAt.takeIf { it > 0 }?.let { repository.dayFor(it) } ?: LocalDate.of(2020, 1, 1)

    /**
     * P1-D：界面允许选择的最早日期（启用日；未知时按 2020-01-01 兜底）。
     * 与 [resolveRange] 的裁切口径同源，避免界面按原始日期算、查询按裁切日期算的两套条件。
     */
    fun earliestSupportedDate(): LocalDate = supportedStartFloor()

    /** R02：按起止日期刷新所有范围驱动数据（记录/索引/缺口统一区间）。 */
    fun applyResolvedRange(from: LocalDate, to: LocalDate) {
        val (fromResolved, toResolved) = resolveRange(from, to)
        // P1-B/D5：原来 if/else 两个分支代码完全相同（死分支）。预设档与自选档在这里共用同一解析区间；
        // 预设天数只保留在 recordRangeDays（搜索侧分页口径），不再制造分叉。
        refreshRecordsWith(fromResolved, toResolved)
        // P1-D：搜索范围只由 [setSearchRange] 写（同一状态单一写入者）——
        // 这里再写一次会与搜索侧用屏幕原始值算出的区间形成两套裁切（子代理复核 #1）。
    }

    private fun refreshRecordsWith(from: LocalDate, to: LocalDate) {
        _recordFrom.value = from
        _recordTo.value = to
        refreshRecords()
    }
    fun setSearchRange(days: Long?) {
        searchTo.value = LocalDate.now()
        searchFrom.value = days?.let { LocalDate.now().minusDays(it - 1) }
            ?: if (settings.startedAt > 0) repository.dayFor(settings.startedAt) else LocalDate.now()
        runSearch()
    }

    /** U09/U28：按起止日期设置搜索范围（今天/昨天/自选等精确区间）。 */
    fun setSearchRange(from: LocalDate, to: LocalDate, recordRangeDays: Int? = null) {
        if (recordRangeDays != null) this.recordRangeDays.value = recordRangeDays
        searchTo.value = if (to.isAfter(LocalDate.now())) LocalDate.now() else to
        searchFrom.value = from
        runSearch()
    }

    companion object {
        private const val SEARCH_PAGE_SIZE = 200

        /** 「撤销投影」开关的持久化键（原始解释 / 有效投影），定义见数据层。 */
        internal const val GAP_PROJECTION_RAW_KEY = GAP_PROJECTION_RAW_SETTING_KEY
    }

    fun renamePlace(place: PlaceEntity, name: String) = viewModelScope.launch {
        savePlaceName(place, name)
    }
    fun ignorePlace(place: PlaceEntity) = viewModelScope.launch {
        setPlaceIgnored(place, !place.ignored)
    }
    fun mergePlace(source: PlaceEntity, target: PlaceEntity) = viewModelScope.launch {
        mergePlaceChecked(source, target)
    }

    suspend fun savePlaceName(place: PlaceEntity, name: String) {
        repository.renamePlace(place, name)
        refreshAfterCorrection()
    }
    suspend fun savePlaceKind(placeId: Long, kind: String) {
        repository.setPlaceKind(placeId, kind)
        refreshAfterCorrection()
    }
    suspend fun savePlaceMetadata(placeId: Long, name: String, kind: String) {
        repository.updatePlaceMetadata(placeId, name, kind)
        refreshAfterCorrection()
    }
    suspend fun setPlaceIgnored(place: PlaceEntity, ignored: Boolean) {
        repository.ignorePlace(place, ignored)
        refreshAfterCorrection()
    }
    suspend fun mergePlaceChecked(source: PlaceEntity, target: PlaceEntity) {
        repository.mergePlaces(source, target)
        refreshAfterCorrection()
    }
    suspend fun affectedVisitsForMerge(sourceId: Long) = repository.affectedVisitsForMerge(sourceId)
    suspend fun undoPlaceMergeChecked(sourceId: Long) {
        repository.undoPlaceMerge(sourceId)
        refreshAfterCorrection()
    }
    suspend fun createPlaceAtLocation(point: LocationPointEntity, name: String, kind: String): Long {
        val id = repository.createPlaceAtLocation(point, name, kind)
        refreshAfterCorrection()
        return id
    }

    fun setSourceEnabled(source: SourceId, enabled: Boolean) {
        settings.setSourceEnabled(source, enabled)
        if (source == SourceId.NOTIFICATIONS && !enabled) MetadataNotificationListener.clearGapMarker(settings)
        refreshHealth()
        if (settings.collectionEnabled) {
            if (getApplication<LifeLogApp>().shouldRunCollectorService(settings)) {
                LifeLogCollectorService.reconfigure(getApplication())
            } else {
                CollectionGapDetector(app.databaseProvider, settings).clearHeartbeat()
                LifeLogCollectorService.stop(getApplication())
            }
        }
    }
    fun savePlaceNaming(consent: Boolean, key: String) = viewModelScope.launch {
        settings.placeNameConsent = consent
        settings.amapConsent = consent && key.isNotBlank()
        settings.amapKey = key
        if (consent) {
            val resolver = PlaceNameResolver(getApplication(), settings)
            resolver.scheduleBackfill()
            _operationMessage.value = "地点名称服务已开启；空地址会在联网后自动补全"
        } else {
            _operationMessage.value = "已关闭地点名称联网反查；坐标和停留记录不受影响"
        }
    }

    fun setNotificationContentEnabled(enabled: Boolean) = viewModelScope.launch {
        settings.notificationContentEnabled = enabled
        repository.sourceStatus(SourceId.NOTIFICATIONS)?.takeIf { it.state == SourceState.ACTIVE.name }?.let {
            repository.setStatus(
                SourceId.NOTIFICATIONS,
                SourceState.ACTIVE,
                if (enabled) "统计普通通知，并保存标题和正文" else "仅统计可清除的普通通知，不读取内容",
            )
        }
        refreshHealth()
        _operationMessage.value = if (enabled) "已开启：只保存此后收到的通知标题和正文" else "已停止保存后续通知内容；历史内容仍然保留"
    }

    fun createBackup(uri: Uri, password: String) = viewModelScope.launch {
        runOperation("backup", "备份") {
            val ok = runCatching { app.backupManager.create(uri, password.toCharArray()); "加密备份已创建" }
                .onSuccess { DiagnosticLog.event("backup", "backup_created") }
                .getOrElse { DiagnosticLog.error("backup", "backup_create_failed", it); "备份失败：${it.message}" }
            ok
        }
    }
    fun restoreBackup(uri: Uri, password: String) = viewModelScope.launch {
        runOperation("restore", "恢复") {
            val ok = runCatching { app.backupManager.restore(uri, password.toCharArray()); "恢复完成；恢复前数据库已永久归档，请重新打开应用刷新记录" }
                .onSuccess { DiagnosticLog.event("backup", "backup_restored") }
                .getOrElse { DiagnosticLog.error("backup", "backup_restore_failed", it); "恢复失败：${it.message}" }
            ok
        }
    }
    fun exportDiagnostics(uri: Uri) = viewModelScope.launch {
        runOperation("export", "导出") {
            val ok = runCatching { app.diagnosticsExporter.export(uri); "诊断日志已导出，可以把 ZIP 文件直接发给我分析" }
                .getOrElse { DiagnosticLog.error("debug", "diagnostic_export_failed", it); "日志导出失败：${it.message}" }
            ok
        }
    }
    fun addDiagnosticMarker(kind: String) {
        DiagnosticLog.event("user", "observation_marker", mapOf(
            "kind" to kind,
            "selectedDate" to selectedDate.value.toString(),
        ))
        _operationMessage.value = "已标记：$kind"
    }
    fun exportReadableData(uri: Uri) = viewModelScope.launch {
        runOperation("export", "导出") {
            val ok = runCatching { app.dataExporter.export(uri); "可读记录已导出。文件未加密，请妥善保存" }
                .onSuccess { DiagnosticLog.event("export", "readable_export_completed") }
                .getOrElse { DiagnosticLog.error("export", "readable_export_failed", it); "导出失败：${it.message}" }
            ok
        }
    }

    /** R15：统一长操作执行——防重复、记录类型与失败、过程消息。 */
    private suspend fun runOperation(type: String, stageLabel: String, block: suspend () -> String) {
        if (_operationInProgress.value) return // 进行中不重复启动
        _operationInProgress.value = true
        _operationType.value = type
        _operationFailed.value = false
        _operationMessage.value = "正在$stageLabel…"
        val result = block()
        _operationMessage.value = result
        _operationFailed.value = result.startsWith("备份失败") || result.startsWith("恢复失败") || result.startsWith("导出失败") || result.startsWith("日志导出失败")
        _operationInProgress.value = false
        _operationType.value = null
    }
    fun clearOperationMessage() { _operationMessage.value = null }
}

/**
 * U03：日期页状态——日期、加载位与数据绑在一起。
 *
 * 旧实现把 selectedDate 与 TimelineDay 分开，TimelineDay() 初值没有日期标识；切换日期时
 * 查询尚未返回，旧日期数据会短暂冒充新日期，或空初值被当成“无记录”。现在每个状态都带
 * date，UI 只在 state.date == 当前选中日期时才绘制 state.data；loading=true 表示读取中，
 * 不当作空数据（空态只允许出现在成功读取且确实为空之后）。
 */
data class DayUiState(
    val date: LocalDate,
    val loading: Boolean,
    val data: TimelineDay,
)

/**
 * U03：把 selectedDate 变成携带日期的 DayUiState 流。
 *
 * 每次日期变化后：先发一个该日期的 loading 快照（data 为空），随后由 observeDay 提供
 * 与 date 绑定的数据。flatMapLatest 保证切换日期时旧日期的订阅被取消，慢查询返回的旧
 * 日期结果不会混入新日期。抽取为纯函数便于用假 flow 做竞态测试。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal fun dayStateFlow(
    selectedDate: Flow<LocalDate>,
    observeDay: (LocalDate) -> Flow<TimelineDay>,
): Flow<DayUiState> = selectedDate.flatMapLatest { date ->
    observeDay(date)
        .map<TimelineDay, DayUiState> { DayUiState(date = date, loading = false, data = it) }
        .onStart { emit(DayUiState(date = date, loading = true, data = TimelineDay())) }
}
