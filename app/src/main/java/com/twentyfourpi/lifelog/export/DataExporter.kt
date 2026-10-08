package com.twentyfourpi.lifelog.export

import android.content.Context
import android.net.Uri
import com.twentyfourpi.lifelog.data.DatabaseProvider
import com.twentyfourpi.lifelog.data.NotificationEventEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 用户主动创建的可读数据副本。与诊断包不同，这里会包含已保存的通知正文和精确停留坐标。 */
class DataExporter(
    private val context: Context,
    private val databaseProvider: DatabaseProvider,
) {
    suspend fun export(uri: Uri) = withContext(Dispatchers.IO) {
        val dao = databaseProvider.get().dao()
        val exportedAtMs = System.currentTimeMillis()
        val summaryToMs = exportedAtMs
        val summaryFromMs = summaryToMs - SUMMARY_RANGE_MS
        context.contentResolver.openOutputStream(uri, "w")?.use { raw ->
            ZipOutputStream(raw.buffered()).use { zip ->
                zip.writeText("README.txt", """
                    24π·人生记录可读数据导出
                    生成时间：${Instant.ofEpochMilli(exportedAtMs)}

                    文件为未加密 CSV，可能包含通知标题和正文、应用使用记录、地点名称及精确停留坐标。
                    请勿上传到公开网盘或发送给不信任的人。需要长期安全保存时，请使用应用内的加密备份。

                    notifications.csv 同时保留新版通知生命周期与旧版独立 REMOVED 事件。旧数据没有可靠关联键时，
                    lifecycleStatus 会标记为 legacy_lifecycle_unknown 或 legacy_removed_event，不代表通知仍处于活动状态。

                    两个 *-rolling-30-days.csv 是以导出时刻为终点、向前精确 30×24 小时的滚动汇总，
                    不是自然月或最近 30 个日历日。跨越范围边界的地点停留只计算落在该区间内的部分。
                    places.csv 保留地点标记和可撤销归并关系；place-visits.csv 保留原始地点归属。
                """.trimIndent())
                zip.writeText("app-usage.csv", buildString {
                    appendLine("start,end,package,app,durationMs")
                    dao.debugAppSessions(0).forEach {
                        appendLine("${csv(Instant.ofEpochMilli(it.startMs).toString())},${csv(Instant.ofEpochMilli(it.endMs).toString())},${csv(it.packageName)},${csv(it.appLabel)},${it.endMs - it.startMs}")
                    }
                })
                zip.putNextEntry(ZipEntry("notifications.csv"))
                zip.writeLine("recordId,eventTime,eventType,captureOrigin,removedAt,package,app,channel,keyHash,lifecycleStatus,contentState,removalReason,title,body")
                var lastNotificationId = 0L
                while (true) {
                    val page = dao.notificationEventsPage(lastNotificationId, NOTIFICATION_EXPORT_PAGE_SIZE)
                    if (page.isEmpty()) break
                    page.forEach { event ->
                        val removedAt = when {
                            event.removedMs != null -> event.removedMs
                            event.action == "REMOVED" -> event.occurredMs
                            else -> null
                        }
                        zip.writeLine(
                            "${event.id},${csv(Instant.ofEpochMilli(event.occurredMs).toString())},${csv(event.action)},${csv(event.captureOrigin)}," +
                                "${csv(removedAt?.let { Instant.ofEpochMilli(it).toString() }.orEmpty())}," +
                                "${csv(event.packageName)},${csv(event.appLabel)},${csv(event.channelId.orEmpty())}," +
                                "${csv(event.notificationKeyHash.orEmpty())},${csv(lifecycleStatus(event))},${csv(event.contentState)}," +
                                "${event.reasonCode ?: ""},${csv(event.notificationTitle.orEmpty())},${csv(event.notificationBody.orEmpty())}",
                        )
                    }
                    lastNotificationId = page.last().id
                }
                zip.closeEntry()
                zip.writeText("places.csv", buildString {
                    appendLine("id,name,address,latitude,longitude,ignored,isCustomName,kind,mergedIntoPlaceId,visitCount,lastVisitMs")
                    dao.debugPlaces().forEach {
                        appendLine("${it.id},${csv(it.name)},${csv(it.address)},${it.latitude},${it.longitude},${it.ignored},${it.isCustomName},${csv(it.kind)},${it.mergedIntoPlaceId ?: ""},${it.visitCount},${it.lastVisitMs}")
                    }
                })
                zip.writeText("place-visits.csv", buildString {
                    appendLine("start,end,place,name,address,latitude,longitude,durationMs")
                    dao.debugVisits(0).forEach {
                        appendLine("${csv(Instant.ofEpochMilli(it.startMs).toString())},${csv(Instant.ofEpochMilli(it.endMs).toString())},${it.placeId},${csv(it.name)},${csv(it.address)},${it.latitude},${it.longitude},${it.endMs - it.startMs}")
                    }
                })
                zip.writeText("location-points.csv", buildString {
                    appendLine("id,recordedTime,measuredTime,elapsedRealtimeNanos,latitude,longitude,accuracyM,provider,speedMps,isMock")
                    dao.debugLocationPoints(0).forEach {
                        appendLine(
                            "${it.id},${csv(Instant.ofEpochMilli(it.recordedMs).toString())}," +
                                "${csv(Instant.ofEpochMilli(it.measuredMs.takeIf { value -> value > 0 } ?: it.recordedMs).toString())}," +
                                "${it.elapsedRealtimeNanos},${it.latitude},${it.longitude},${it.accuracyM}," +
                                "${csv(it.provider)},${it.speedMps ?: ""},${it.isMock}",
                        )
                    }
                })
                zip.writeText("collection-gaps.csv", buildString {
                    appendLine("source,start,end,durationMs,reason")
                    dao.debugCollectionGaps(0).forEach {
                        appendLine("${it.source},${csv(Instant.ofEpochMilli(it.startMs).toString())},${csv(Instant.ofEpochMilli(it.endMs).toString())},${it.endMs - it.startMs},${it.reason}")
                    }
                })
                // F 批：地点修订随可读导出一起保留，换设备后“修好了”不会又变回去。
                zip.writeText("attribution-revisions.csv", buildString {
                    appendLine("id,targetKey,fromPlaceId,toPlaceId,createdTime,source,ruleVersion,revertedBy,reason")
                    dao.debugAttributionRevisions().forEach {
                        appendLine(
                            "${it.id},${csv(it.targetKey)},${it.fromPlaceId ?: ""},${it.toPlaceId ?: ""}," +
                                "${csv(Instant.ofEpochMilli(it.createdMs).toString())},${it.source},${it.ruleVersion},${it.revertedBy ?: ""},${csv(it.reason)}",
                        )
                    }
                })
                zip.writeText("rolling-30-days-scope.txt", """
                    范围起点（含）：${Instant.ofEpochMilli(summaryFromMs)}
                    范围终点（不含）：${Instant.ofEpochMilli(summaryToMs)}
                    口径：导出时刻向前精确 30×24 小时，不是自然月或 30 个日历日。
                """.trimIndent())
                // 通知按应用汇总；文件名明确其滚动区间，避免被误认为全量统计。
                zip.writeText("notification-summary-rolling-30-days.csv", buildString {
                    appendLine("rangeStart,rangeEnd,package,app,count,firstTime,lastTime,withContent")
                    dao.notificationAppSummary(summaryFromMs, summaryToMs).forEach {
                        appendLine(
                            "${csv(Instant.ofEpochMilli(summaryFromMs).toString())},${csv(Instant.ofEpochMilli(summaryToMs).toString())}," +
                                "${csv(it.packageName)},${csv(it.appLabel)},${it.count},${csv(Instant.ofEpochMilli(it.firstMs).toString())}," +
                                "${csv(Instant.ofEpochMilli(it.lastMs).toString())},${it.withContent}",
                        )
                    }
                })
                // 足迹按地点汇总；DAO 已把跨边界访问裁剪到同一滚动区间。
                zip.writeText("place-stay-summary-rolling-30-days.csv", buildString {
                    appendLine("rangeStart,rangeEnd,place,name,address,totalMs,visitCount,firstVisit,lastVisit")
                    dao.placeStaySummary(summaryFromMs, summaryToMs).forEach {
                        appendLine(
                            "${csv(Instant.ofEpochMilli(summaryFromMs).toString())},${csv(Instant.ofEpochMilli(summaryToMs).toString())}," +
                                "${it.placeId},${csv(it.name)},${csv(it.address)},${it.totalMs},${it.visitCount}," +
                                "${csv(Instant.ofEpochMilli(it.firstVisitMs).toString())},${csv(Instant.ofEpochMilli(it.lastVisitMs).toString())}",
                        )
                    }
                })
            }
        } ?: error("无法创建导出文件")
    }

    private fun ZipOutputStream.writeText(name: String, text: String) {
        putNextEntry(ZipEntry(name)); write(text.toByteArray(Charsets.UTF_8)); closeEntry()
    }

    private fun ZipOutputStream.writeLine(line: String) {
        write(line.toByteArray(Charsets.UTF_8))
        write('\n'.code)
    }

    private fun csv(value: String): String = CsvEscaper.cell(value)

    private fun lifecycleStatus(event: NotificationEventEntity): String = when {
        event.action == "REMOVED" -> "legacy_removed_event"
        event.action != "POSTED" -> "legacy_${event.action.lowercase()}_event"
        event.removedMs != null -> when (event.reasonCode) {
            REASON_SUPERSEDED -> "superseded_by_update"
            REASON_RECONCILED -> "closed_during_reconciliation"
            REASON_COLLECTION_PAUSED -> "closed_when_collection_paused"
            else -> "removed"
        }
        event.notificationKeyHash.isNullOrBlank() || event.contentState == "LEGACY_UNKNOWN" -> "legacy_lifecycle_unknown"
        else -> "not_closed_in_record"
    }

    companion object {
        private const val SUMMARY_RANGE_MS = 30L * 24 * 60 * 60_000L
        private const val NOTIFICATION_EXPORT_PAGE_SIZE = 1_000
        private const val REASON_SUPERSEDED = -100
        private const val REASON_RECONCILED = -101
        private const val REASON_COLLECTION_PAUSED = -102
    }
}
