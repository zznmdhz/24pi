package com.twentyfourpi.lifelog.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.twentyfourpi.lifelog.data.*
import com.twentyfourpi.lifelog.util.formatClock
import com.twentyfourpi.lifelog.util.formatDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun VisitCorrectionEditor(viewModel: MainViewModel, visit: PlaceVisitView) {
    val places by viewModel.places.collectAsStateWithLifecycle()
    val revisions by viewModel.attributionRevisions.collectAsStateWithLifecycle()
    var expanded by remember(visit.id) { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope=rememberCoroutineScope()
    fun apply(target: Long?, undo: Boolean=false) {
        busy=true;message=null
        scope.launch {
            try {
                if(undo) viewModel.undoVisitCorrection(visit.id) else viewModel.correctVisit(visit.id,target)
                message=if(undo) "已撤销上次纠正" else if(target==null) "已恢复原始归属" else "已保存本次停留的地点"
                expanded=false
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { message="保存失败，请重试" }
            finally { busy=false }
        }
    }
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        TextButton(onClick={expanded=!expanded},enabled=!busy) { Text(if(expanded) "收起地点纠正" else "纠正这次停留的地点") }
        if(expanded) {
            Text("只影响这次停留的展示，原始记录保留。",style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(value=query,onValueChange={query=it},label={Text("查找已记录的地点")},modifier=Modifier.fillMaxWidth(),singleLine=true)
            Column(Modifier.fillMaxWidth().heightIn(max=240.dp).verticalScroll(rememberScrollState())) {
                places.filter { !it.ignored && (query.isBlank() || it.name.contains(query,true) || it.address.contains(query,true)) }.forEach { place ->
                    TextButton(onClick={apply(place.id)},enabled=!busy,modifier=Modifier.fillMaxWidth()) { Text(place.name, maxLines=2) }
                }
            }
            TextButton(onClick={apply(null)},enabled=!busy) { Text("恢复原始归属") }
        }
        val history=revisions.filter { it.targetKey=="visit:${visit.id}" }
        if(history.any { it.revertedBy==null }) TextButton(onClick={apply(null,true)},enabled=!busy) { Text("撤销上次纠正") }
        if(history.isNotEmpty()) {
            var showHistory by remember { mutableStateOf(false) }
            TextButton(onClick={showHistory=!showHistory}) { Text(if(showHistory) "收起修订历史" else "修订历史（${history.size}）") }
            if(showHistory) history.forEach { revision ->
                Text("${formatClock(revision.createdMs)} · ${revision.reason} · ${revision.toPlaceId?.let { id -> places.firstOrNull { it.id==id }?.name } ?: "原始归属"}${if(revision.revertedBy!=null) "（已撤销）" else ""}",style=MaterialTheme.typography.bodySmall)
            }
        }
        message?.let { Text(it) }
    }
}

@Composable
internal fun RecordGapExplanation(viewModel: MainViewModel, startMs: Long, endMs: Long) {
    val date by viewModel.selectedDate.collectAsStateWithLifecycle()
    val projections by viewModel.dayProjections.collectAsStateWithLifecycle()
    val raw by viewModel.gapProjectionRaw.collectAsStateWithLifecycle()
    LaunchedEffect(date,raw) { viewModel.refreshDayProjection(date) }
    val projection=projections[date]
    var expanded by remember(startMs,endMs) { mutableStateOf(false) }
    val gaps=projection?.gaps?.filter { it.rawStartMs<endMs && it.rawEndMs>startMs }.orEmpty()
    if(gaps.isNotEmpty()) Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        TextButton(onClick={expanded=!expanded}) { Text(if(expanded) "收起记录依据" else "查看记录依据与缺失时段") }
        if(expanded) {
            Text("实测：有停留证据；推断：有限连续性判断；未知：缺少依据。",style=MaterialTheme.typography.bodySmall)
            gaps.forEach { gap ->
                Text(gap.source.archiveSourceLabel(),style=MaterialTheme.typography.titleSmall)
                gap.intervals.filter { it.startMs<endMs && it.endMs>startMs }.forEach { interval ->
                    val start=maxOf(startMs,interval.startMs);val end=minOf(endMs,interval.endMs)
                    val label=when(interval.support) {
                        GapSupport.MEASURED -> "实测停留"
                        GapSupport.INFERRED -> "有限推断"
                        GapSupport.UNKNOWN -> "未知"
                    }
                    Text("${formatClock(start)}—${formatClock(end)} · $label · ${formatDuration(end-start)}")
                    if(interval.evidence.isNotEmpty()) Text("依据："+interval.evidence.joinToString { "${if(it.kind=="visit") "停留" else "位置点"} #${it.refId}" },style=MaterialTheme.typography.bodySmall)
                }
            }
            Text("解释截止 ${formatClock(projection!!.asOfMs)} · 规则 ${projection.ruleVersion}；原始记录未修改。",style=MaterialTheme.typography.bodySmall)
        }
    }
}
