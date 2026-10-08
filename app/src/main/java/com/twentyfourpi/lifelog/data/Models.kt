package com.twentyfourpi.lifelog.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class SourceId { USAGE, NOTIFICATIONS, LOCATION, STEPS, SLEEP }
enum class SourceState { ACTIVE, PAUSED, PERMISSION_REQUIRED, SYSTEM_BLOCKED, UNSUPPORTED, ERROR }

/** Stable values persisted with notification records and exposed by data export. */
object NotificationCaptureOrigin {
    const val REALTIME = "REALTIME"
    const val RECONNECT = "RECONNECT"
    const val LEGACY = "LEGACY"
}

@Entity(tableName = "app_sessions", indices = [Index("start_ms"), Index("package_name", "start_ms")])
data class AppSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "app_label") val appLabel: String,
    @ColumnInfo(name = "start_ms") val startMs: Long,
    @ColumnInfo(name = "end_ms") val endMs: Long,
)

@Entity(tableName = "screen_sessions", indices = [Index("start_ms")])
data class ScreenSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "start_ms") val startMs: Long,
    @ColumnInfo(name = "end_ms") val endMs: Long,
)

@Entity(
    tableName = "notification_events",
    indices = [
        Index("occurred_ms"),
        Index("package_name", "occurred_ms"),
        Index("notification_key_hash"),
    ],
)
data class NotificationEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "app_label") val appLabel: String,
    @ColumnInfo(name = "occurred_ms") val occurredMs: Long,
    val action: String,
    @ColumnInfo(name = "reason_code") val reasonCode: Int? = null,
    @ColumnInfo(name = "notification_title") val notificationTitle: String? = null,
    @ColumnInfo(name = "notification_body") val notificationBody: String? = null,
    @ColumnInfo(name = "notification_key_hash") val notificationKeyHash: String? = null,
    @ColumnInfo(name = "channel_id") val channelId: String? = null,
    @ColumnInfo(name = "removed_ms") val removedMs: Long? = null,
    @ColumnInfo(name = "content_state", defaultValue = "'LEGACY_UNKNOWN'")
    val contentState: String = "LEGACY_UNKNOWN",
    @ColumnInfo(name = "capture_origin", defaultValue = "'LEGACY'")
    val captureOrigin: String = NotificationCaptureOrigin.LEGACY,
)

@Entity(
    tableName = "location_points",
    indices = [Index("recorded_ms"), Index("measured_ms")],
)
data class LocationPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "recorded_ms") val recordedMs: Long,
    val latitude: Double,
    val longitude: Double,
    @ColumnInfo(name = "accuracy_m") val accuracyM: Float,
    val provider: String,
    /** Provider timestamp retained separately from the durable receipt timestamp. */
    @ColumnInfo(name = "measured_ms", defaultValue = "0") val measuredMs: Long = recordedMs,
    @ColumnInfo(name = "elapsed_realtime_nanos", defaultValue = "0") val elapsedRealtimeNanos: Long = 0,
    @ColumnInfo(name = "speed_mps") val speedMps: Float? = null,
    @ColumnInfo(name = "is_mock", defaultValue = "0") val isMock: Boolean = false,
)

object PlaceKind {
    const val HOME = "HOME"
    const val COMPANY = "COMPANY"
    const val CUSTOMER = "CUSTOMER"
    const val OTHER = "OTHER"
    val ALL = setOf(HOME, COMPANY, CUSTOMER, OTHER)
}

@Entity(tableName = "places", indices = [Index("ignored"), Index("merged_into_place_id")])
data class PlaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val address: String = "",
    val latitude: Double,
    val longitude: Double,
    val ignored: Boolean = false,
    @ColumnInfo(name = "is_custom_name") val isCustomName: Boolean = false,
    // v0.15 地点匹配权重体系：临时点（路过一次的充电站/洗车店等）不再与常驻地平权竞争。
    @ColumnInfo(name = "visit_count", defaultValue = "0") val visitCount: Int = 0,
    @ColumnInfo(name = "last_visit_ms", defaultValue = "0") val lastVisitMs: Long = 0,
    @ColumnInfo(name = "place_kind", defaultValue = "'OTHER'") val kind: String = PlaceKind.OTHER,
    @ColumnInfo(name = "merged_into_place_id") val mergedIntoPlaceId: Long? = null,
) {
    /** 是否为低置信度临时点：从未形成第二次独立停留，或最近 30 天没有新访问。 */
    fun isTransientAt(nowMs: Long): Boolean =
        !isCustomName && (visitCount < TRANSIENT_VISIT_THRESHOLD ||
            (lastVisitMs in 1..(nowMs - TRANSIENT_DECAY_MS))
        )

    companion object {
        const val TRANSIENT_VISIT_THRESHOLD = 2
        const val TRANSIENT_DECAY_MS = 30L * 24 * 60 * 60 * 1000
    }
}

@Entity(tableName = "place_visits", indices = [Index("start_ms"), Index(value = ["place_id", "start_ms"], unique = true)])
data class PlaceVisitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "place_id") val placeId: Long,
    @ColumnInfo(name = "start_ms") val startMs: Long,
    @ColumnInfo(name = "end_ms") val endMs: Long,
    @ColumnInfo(name = "confidence") val confidence: Float = 1f,
)

data class PlaceVisitView(
    val id: Long,
    @ColumnInfo(name = "place_id") val placeId: Long,
    @ColumnInfo(name = "start_ms") val startMs: Long,
    @ColumnInfo(name = "end_ms") val endMs: Long,
    val confidence: Float,
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
)

@Entity(tableName = "step_samples", indices = [Index("recorded_ms")])
data class StepSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "recorded_ms") val recordedMs: Long,
    val cumulative: Long,
    @ColumnInfo(name = "boot_count") val bootCount: Int,
)

@Entity(tableName = "sleep_sessions", indices = [Index("start_ms")])
data class SleepSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "start_ms") val startMs: Long,
    @ColumnInfo(name = "end_ms") val endMs: Long,
    val provider: String,
    val confidence: Float,
)

@Entity(tableName = "source_status")
data class SourceStatusEntity(
    @PrimaryKey val source: String,
    val state: String,
    @ColumnInfo(name = "last_updated_ms") val lastUpdatedMs: Long? = null,
    val detail: String = "",
)

@Entity(
    tableName = "collection_gaps",
    indices = [
        Index("start_ms"),
        Index(value = ["source", "start_ms", "end_ms"], unique = true),
    ],
)
data class CollectionGapEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: String,
    @ColumnInfo(name = "start_ms") val startMs: Long,
    @ColumnInfo(name = "end_ms") val endMs: Long,
    val reason: String,
)

data class SearchRow(
    val id: Long,
    val kind: String,
    @ColumnInfo(name = "start_ms") val startMs: Long,
    @ColumnInfo(name = "end_ms") val endMs: Long,
    val title: String,
    val subtitle: String,
)

data class NotificationBucket(
    val hourStartMs: Long,
    val appLabel: String,
    val posted: Int,
    val removed: Int,
)

/** 按应用聚合的通知统计，用于通知 Tab 的应用维度汇总。 */
data class NotificationAppSummary(
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "app_label") val appLabel: String,
    val count: Int,
    @ColumnInfo(name = "first_ms") val firstMs: Long,
    @ColumnInfo(name = "last_ms") val lastMs: Long,
    @ColumnInfo(name = "with_content") val withContent: Int,
)

/** 按地点聚合的停留统计，用于足迹 Tab 的汇总视图。 */
data class PlaceStaySummary(
    @ColumnInfo(name = "place_id") val placeId: Long,
    val name: String,
    val address: String,
    @ColumnInfo(name = "total_ms") val totalMs: Long,
    @ColumnInfo(name = "visit_count") val visitCount: Int,
    @ColumnInfo(name = "first_visit_ms") val firstVisitMs: Long,
    @ColumnInfo(name = "last_visit_ms") val lastVisitMs: Long,
)

data class TimelineDay(
    val apps: List<AppSessionEntity> = emptyList(),
    val screens: List<ScreenSessionEntity> = emptyList(),
    val notifications: List<NotificationEventEntity> = emptyList(),
    val visits: List<PlaceVisitView> = emptyList(),
    val steps: List<StepSampleEntity> = emptyList(),
    val sleeps: List<SleepSessionEntity> = emptyList(),
    val gaps: List<CollectionGapEntity> = emptyList(),
)

data class DailyArchiveSummary(
    val date: java.time.LocalDate,
    val appUsageMs: Long,
    val switchCount: Int,
    val notificationCount: Int,
    val placeCount: Int,
    val stepCount: Long,
    val topApp: String?,
    val mainPlace: String?,
    val firstActivityMs: Long?,
    val lastActivityMs: Long?,
    val usageGapMs: Long = 0,
    val notificationGapMs: Long = 0,
    val locationGapMs: Long = 0,
    val stepGapMs: Long = 0,
    val sleepGapMs: Long = 0,
) {
    val hasRecords: Boolean
        get() = appUsageMs > 0 || notificationCount > 0 || placeCount > 0 || stepCount > 0 ||
            SourceId.entries.any { gapMs(it) > 0 }

    /**
     * Returns how much of this calendar day is known to be missing for [source].
     * A zero here means that no gap was recorded; it must not be used to invent a
     * summary for a calendar day that is absent from a requested range.
     */
    fun gapMs(source: SourceId): Long = when (source) {
        SourceId.USAGE -> usageGapMs
        SourceId.NOTIFICATIONS -> notificationGapMs
        SourceId.LOCATION -> locationGapMs
        SourceId.STEPS -> stepGapMs
        SourceId.SLEEP -> sleepGapMs
    }
}

data class NamedDuration(
    val name: String,
    val durationMs: Long,
)

data class NamedCount(
    val name: String,
    val count: Int,
)

data class DayPlaceStop(
    val placeId: Long,
    val name: String,
    val startMs: Long,
    val endMs: Long,
)

/**
 * F 批（方案 16.5）：可追溯地点修订——用户确认/纠正与撤销记录。
 *
 * 只影响展示投影，不修改、不删除原始 PlaceVisit/Place。撤销通过追加一条带
 * revertedBy 的修订实现，保留历史证据链。导出/恢复时必须连同本表一起处理，
 * 否则换设备后“修好了又变回去”。
 */
@Entity(tableName = "attribution_revisions", indices = [Index("target_key"), Index("created_ms")])
data class AttributionRevisionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 目标标识：`visit:<visitId>` 单段修正，或 `place:<placeId>` 全部修正。 */
    @ColumnInfo(name = "target_key") val targetKey: String,
    @ColumnInfo(name = "from_place_id") val fromPlaceId: Long?,
    @ColumnInfo(name = "to_place_id") val toPlaceId: Long?,
    /** 生效的本地时间戳（UI 层用于排序/预览）。 */
    @ColumnInfo(name = "created_ms") val createdMs: Long,
    /** 来源：user / algorithm。 */
    val source: String,
    /** 规则版本；算法升级后旧修订按新投影重新评估。 */
    @ColumnInfo(name = "rule_version") val ruleVersion: Int,
    /** 被本修订替代的修订 ID；撤销通过在此写入替代者 id。 */
    @ColumnInfo(name = "reverted_by") val revertedBy: Long? = null,
    /** 人类可读原因（如“这段其实在家”）。 */
    val reason: String = "",
) {
    companion object {
        const val SOURCE_USER = "user"
        const val SOURCE_ALGORITHM = "algorithm"
    }
}

data class RecordDayOverview(
    val date: java.time.LocalDate,
    val firstActivityMs: Long?,
    val lastActivityMs: Long?,
    val phoneUsageMs: Long,
    val switchCount: Int,
    val notificationCount: Int,
    val topApps: List<NamedDuration>,
    val notificationApps: List<NamedCount>,
    val placeTrail: List<DayPlaceStop>,
    val gapSources: Set<String>,
)

data class AppUsageOverview(
    val packageName: String,
    val appLabel: String,
    val totalDurationMs: Long,
    val sessionCount: Int,
    val activeDays: Int,
    val lastUsedMs: Long,
)

data class RecordExplorerData(
    val days: List<RecordDayOverview> = emptyList(),
    val apps: List<AppUsageOverview> = emptyList(),
    val appSessions: List<AppSessionEntity> = emptyList(),
    val notifications: List<NotificationEventEntity> = emptyList(),
)
