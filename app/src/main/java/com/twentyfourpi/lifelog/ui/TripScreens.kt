@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.twentyfourpi.lifelog.ui

import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.FrameLayout
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amap.api.maps.*
import com.amap.api.maps.model.*
import com.twentyfourpi.lifelog.data.*
import com.twentyfourpi.lifelog.util.formatClock
import com.twentyfourpi.lifelog.util.formatDuration
import com.twentyfourpi.lifelog.util.wgs84ToGcj02
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.*
import java.time.format.DateTimeFormatter

@Composable
private fun TripHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = onBack) { Text("返回") }
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 12.dp))
    }
}

@Composable
fun TripArchiveScreen(viewModel: MainViewModel, initialDate: LocalDate, onBack: () -> Unit, onTrip: (TripSummary) -> Unit) {
    var fromDay by rememberSaveable { mutableLongStateOf(initialDate.toEpochDay()) }
    var toDay by rememberSaveable { mutableLongStateOf(initialDate.toEpochDay()) }
    var picker by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var summaries by remember { mutableStateOf<List<TripSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf("") }
    var generation by remember { mutableIntStateOf(0) }
    val corrections by viewModel.attributionRevisions.collectAsStateWithLifecycle()
    LaunchedEffect(fromDay, toDay, revision, corrections) {
        val token = ++generation
        loading = true; error = null; summaries = emptyList()
        try {
            val result = mutableListOf<TripSummary>()
            // Bounded one-day reads; never retain a month of raw points or MapViews.
            for (day in toDay downTo fromDay) {
                val date = LocalDate.ofEpochDay(day)
                progress = "正在读取 $date"
                val route = viewModel.repository.dailyRoute(date) ?: error("行程读取失败")
                result += route.tripSummaries(date).sortedByDescending { it.startMs }
            }
            summaries = result
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = "读取失败，请重试" }
        finally { if(token == generation) loading = false }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { TripHeader("行程", onBack) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("当天", "本周", "本月").forEach { label ->
                    TextButton(onClick = {
                        val today = LocalDate.now()
                        toDay = (if (label == "当天") initialDate else today).toEpochDay()
                        fromDay = when(label) {
                            "本周" -> today.minusDays((today.dayOfWeek.value - 1).toLong()).toEpochDay()
                            "本月" -> today.withDayOfMonth(1).toEpochDay()
                            else -> toDay
                        }
                    }) { Text(label) }
                }
                TextButton(onClick = { picker = "from" }) { Text("自定义") }
            }
            Row {
                TextButton(onClick = { picker = "from" }) { Text(LocalDate.ofEpochDay(fromDay).toString()) }
                Text("—", Modifier.padding(top = 12.dp))
                TextButton(onClick = { picker = "to" }) { Text(LocalDate.ofEpochDay(toDay).toString()) }
            }
        }
        item {
            if (loading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(progress) }
            else if (error != null) { Text(error!!); TextButton(onClick = { revision++ }) { Text("重试") } }
            else {
                Text(formatDistance(summaries.sumOf { it.distanceMeters }), style = MaterialTheme.typography.headlineLarge)
                Text("已记录行程里程 · ${summaries.size} 段 · ${formatDuration(summaries.sumOf { it.endMs - it.startMs })}")
                Text("按当天部分归档；定位中断不计里程，未形成行程的零散移动不计入此汇总。", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { revision++ }) { Text("刷新") }
            }
        }
        if (!loading && error == null && summaries.isEmpty()) item { Text("这个范围没有形成行程；原始位置记录仍保留。") }
        if (!loading && error == null) {
            summaries.groupBy { it.date }.forEach { (date, trips) ->
                item(key = "day:$date") {
                    TextButton(onClick = { fromDay = date.toEpochDay(); toDay = fromDay }) {
                        Text("$date · ${formatDistance(trips.sumOf { it.distanceMeters })}")
                    }
                }
                items(trips, key = { "${it.date}:${it.key}" }) { trip ->
                    Column(Modifier.fillMaxWidth().clickable { onTrip(trip) }.padding(vertical = 12.dp)) {
                        Text("${formatClock(trip.startMs)}—${formatClock(trip.endMs)}", style = MaterialTheme.typography.titleMedium)
                        Text("${trip.startPlace ?: "起点"} → ${trip.endPlace ?: "终点"}", maxLines = 2)
                        Text("${formatDistance(trip.distanceMeters)} · ${formatDuration(trip.endMs-trip.startMs)}　›")
                        if (trip.interruptionCount > 0) Text("${trip.interruptionCount} 处定位中断", style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
    picker?.let { which ->
      key(which) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = LocalDate.ofEpochDay(if (which == "from") fromDay else toDay).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate() <= LocalDate.now()
            })
        DatePickerDialog(onDismissRequest = { picker = null }, confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { ms ->
                    val d = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
                    if (which == "from") { fromDay=d; if (toDay<d) toDay=d; picker="to" }
                    else { toDay=d; if (fromDay>d) fromDay=d; picker=null }
                }
            }) { Text("确定") }
        }) { DatePicker(state) }
      }
    }
}

@Composable
fun TripDetailScreen(viewModel: MainViewModel, date: LocalDate, tripKey: String, onBack: () -> Unit, onRecords: (Long, Long) -> Unit) {
    var trip by remember { mutableStateOf<RouteTrip?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(date, tripKey, refresh) {
        loading=true; error=null
        try { trip = viewModel.repository.dailyRoute(date)?.trips?.firstOrNull { it.key == tripKey } }
        catch(e: CancellationException) { throw e }
        catch(e: Exception) { error="行程读取失败" }
        finally { loading=false }
    }
    Column(Modifier.fillMaxSize().padding(horizontal=20.dp)) {
        TripHeader("行程详情", onBack)
        when {
            loading -> CircularProgressIndicator()
            error != null -> { Text(error!!); TextButton(onClick={refresh++}) { Text("重试") } }
            trip == null -> Text("行程分组已更新，请返回列表重新选择。")
            else -> trip?.let { t ->
                AmapRouteSurface(viewModel, t.sections, t.interruptions, Modifier.fillMaxWidth().weight(1f), null)
                LazyColumn(Modifier.fillMaxWidth().weight(0.8f), verticalArrangement=Arrangement.spacedBy(10.dp), contentPadding=PaddingValues(vertical=14.dp)) {
                    item { Text(formatDistance(t.distanceMeters), style=MaterialTheme.typography.headlineLarge) }
                    item { Text("$date · ${formatDuration(t.durationMs)}") }
                    item { Text("起 ${formatClock(t.first.timeMs)}　${t.startPlaceName ?: "记录起点"}") }
                    item { Text("终 ${formatClock(t.last.timeMs)}　${t.endPlaceName ?: "记录终点"}") }
                    item { TextButton(onClick={onRecords(t.first.timeMs,t.last.timeMs)}) { Text("查看这一时段的记录") } }
                    items(t.stops) { Text("${formatClock(it.startMs)}—${formatClock(it.endMs)} · 停留 ${formatDuration(it.durationMs)}") }
                    items(t.interruptions) { Text("${formatClock(it.lostAt.timeMs)}—${formatClock(it.recoveredAt.timeMs)} 定位中断 · 不计里程") }
                    item { Text("路线连接已保存的测量点；缺失区间不会用规划路线补齐。", style=MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable
fun CurrentLocationScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val location by viewModel.latestLocation.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while(true) { delay(15_000); now=System.currentTimeMillis() } }
    val fix = location?.takeIf { !it.isMock && it.accuracyM.isFinite() && it.accuracyM in 0f..200f && it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
        TripHeader("当前位置", onBack)
        if (fix == null) Text("暂时没有可信位置，请在“我的”检查定位采集状态。")
        else {
            val measured = fix.measuredMs.takeIf { it > 0 } ?: fix.recordedMs
            val fresh = now-measured in 0..120_000
            Text(if(fresh) "最近定位" else "最后记录位置", style=MaterialTheme.typography.titleLarge)
            Text("${Instant.ofEpochMilli(measured).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm:ss"))} · 精度约 ${fix.accuracyM.toInt()} 米")
            AmapRouteSurface(viewModel, emptyList(), emptyList(), Modifier.fillMaxWidth().weight(1f),
                RoutePoint(fix.id, measured, fix.latitude, fix.longitude, fix.accuracyM))
            Text("使用现有采集的位置；拖动地图后可点击“回到位置”。",style=MaterialTheme.typography.bodySmall)
        }
    }
}

private data class MapGeometry(val lines: List<List<LatLng>>, val gaps: List<List<LatLng>>, val first: LatLng?, val last: LatLng?)

@Composable
internal fun AmapRouteSurface(
    viewModel: MainViewModel,
    sections: List<RouteSection>,
    gaps: List<RouteInterruption>,
    modifier: Modifier,
    livePoint: RoutePoint?,
    compact: Boolean = false,
    onOpen: (() -> Unit)? = null,
) {
    val context=LocalContext.current
    val owner=LocalLifecycleOwner.current
    var consent by remember { mutableStateOf(viewModel.settings.getBoolean("amap_map_consent",false)) }
    val keyReady=remember {
        context.packageManager.getApplicationInfo(context.packageName,PackageManager.GET_META_DATA).metaData?.getString("com.amap.api.v2.apikey")?.isNotBlank()==true
    }
    var mapView by remember { mutableStateOf<TextureMapView?>(null) }
    var failure by remember { mutableStateOf(false) }
    var geometry by remember { mutableStateOf<MapGeometry?>(null) }
    fun coordinate(p: RoutePoint): LatLng { val c=wgs84ToGcj02(p.latitude,p.longitude);return LatLng(c.latitude,c.longitude) }
    LaunchedEffect(sections,gaps) {
        geometry=withContext(Dispatchers.Default) {
            // Contiguous measured edges only. Gaps remain separate overlays, never bridges.
            val lines=sections.flatMap { section ->
                val result=mutableListOf<MutableList<RoutePoint>>()
                section.observedEdges.forEach { (a,b) ->
                    if(result.lastOrNull()?.lastOrNull()==a) result.last().add(b) else result.add(mutableListOf(a,b))
                }
                result.map { line -> line.map(::coordinate) }
            }
            MapGeometry(lines,gaps.map { listOf(coordinate(it.lostAt),coordinate(it.recoveredAt)) },
                sections.firstOrNull()?.first?.let(::coordinate),sections.lastOrNull()?.last?.let(::coordinate))
        }
    }
    DisposableEffect(owner,mapView) {
        // Capture this exact view. A consent change may create the replacement
        // before the previous effect is disposed; never destroy a newer view.
        val view=mapView
        val observer=LifecycleEventObserver { _,event ->
            when(event) { Lifecycle.Event.ON_RESUME -> view?.onResume(); Lifecycle.Event.ON_PAUSE -> view?.onPause(); else -> Unit }
        }
        owner.lifecycle.addObserver(observer)
        if(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view?.onResume()
        onDispose { owner.lifecycle.removeObserver(observer); view?.onPause(); view?.onDestroy() }
    }
    val latestOnOpen by rememberUpdatedState(onOpen)
    LaunchedEffect(mapView, geometry, livePoint, compact) {
        val view = mapView ?: return@LaunchedEffect
        val shape = geometry ?: return@LaunchedEffect
        val map = view.map
        map.clear()
        map.mapType = AMap.MAP_TYPE_NORMAL
        map.isMyLocationEnabled = false
        map.uiSettings.isRotateGesturesEnabled = false
        map.uiSettings.isTiltGesturesEnabled = false
        map.uiSettings.isScaleControlsEnabled = !compact
        map.uiSettings.isZoomControlsEnabled = false
        map.uiSettings.isScrollGesturesEnabled = !compact
        map.uiSettings.isZoomGesturesEnabled = !compact
        shape.lines.forEach { map.addPolyline(PolylineOptions().addAll(it).width(if (compact) 10f else 8f).color(0xff087c86.toInt())) }
        shape.gaps.forEach { map.addPolyline(PolylineOptions().addAll(it).width(5f).setDottedLine(true).color(0xff777777.toInt())) }
        shape.first?.let { map.addMarker(MarkerOptions().position(it).title("起点").icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))) }
        shape.last?.let { map.addMarker(MarkerOptions().position(it).title("终点").icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))) }
        val liveLatLng = livePoint?.let(::coordinate)
        liveLatLng?.let { map.addMarker(MarkerOptions().position(it).title("最近定位")) }
        map.setOnMapClickListener { latestOnOpen?.invoke() }
        val fit = {
            val points = shape.lines.flatten() + shape.gaps.flatten() + listOfNotNull(shape.first, shape.last, liveLatLng)
            when {
                points.size > 1 -> {
                    val bounds = LatLngBounds.builder()
                    points.forEach(bounds::include)
                    map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), if (compact) 24 else 60))
                }
                liveLatLng != null -> map.moveCamera(CameraUpdateFactory.newLatLngZoom(liveLatLng, 16f))
            }
        }
        map.setOnMapLoadedListener { fit() }
        fit()
    }
    Column(modifier) {
        when {
            !keyReady -> Text(
                "地图未配置",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            !consent -> {
                Column(
                    Modifier.fillMaxSize().padding(if (compact) 10.dp else 0.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        if (compact) "启用高德地图查看真实轨迹" else "高德地图用于显示底图与路线，会连接高德服务并处理必要的设备和地图请求信息。位置历史继续保存在本机。",
                        style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                    )
                    if (!compact) TextButton(onClick={ android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse("https://lbs.amap.com/pages/privacy/")).also(context::startActivity) }) { Text("高德隐私说明") }
                    TextButton(onClick={viewModel.settings.putBoolean("amap_map_consent",true);consent=true}) { Text("启用地图") }
                }
            }
            failure -> Text(
                "地图暂时无法打开",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            geometry != null -> {
                AndroidView(modifier=Modifier.fillMaxWidth().weight(1f),factory={ctx ->
                    try {
                        MapsInitializer.updatePrivacyShow(ctx,true,true)
                        MapsInitializer.updatePrivacyAgree(ctx,true)
                        TextureMapView(ctx).also { view ->
                            mapView=view;view.onCreate(Bundle())
                        }
                    } catch(e: Exception) { failure=true;FrameLayout(ctx) }
                }, onRelease={ view -> if(view === mapView) mapView=null })
                if (!compact) {
                    val shape = geometry!!
                    TextButton(onClick={
                        val map=mapView?.map ?: return@TextButton
                        livePoint?.let { map.animateCamera(CameraUpdateFactory.newLatLngZoom(coordinate(it),16f)) }
                            ?: run { val pts=shape.lines.flatten()+shape.gaps.flatten();if(pts.size>1) { val b=LatLngBounds.builder();pts.forEach(b::include);map.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(),60)) } }
                    }) { Text(if(livePoint==null) "查看全程" else "回到位置") }
                    TextButton(onClick={viewModel.settings.putBoolean("amap_map_consent",false);consent=false}) { Text("停用在线地图") }
                }
            }
        }
    }
}
