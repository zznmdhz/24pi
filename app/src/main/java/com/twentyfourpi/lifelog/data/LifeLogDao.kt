package com.twentyfourpi.lifelog.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface LifeLogDao {
    @Insert suspend fun insertAppSession(value: AppSessionEntity): Long
    @Update suspend fun updateAppSession(value: AppSessionEntity)
    @Query("SELECT * FROM app_sessions WHERE package_name=:pkg AND end_ms<=:before ORDER BY end_ms DESC LIMIT 1")
    suspend fun latestAppSession(pkg: String, before: Long): AppSessionEntity?
    @Query("SELECT * FROM app_sessions WHERE start_ms<:end AND end_ms>:start ORDER BY start_ms")
    fun observeAppSessions(start: Long, end: Long): Flow<List<AppSessionEntity>>
    @Query("UPDATE app_sessions SET app_label=:label WHERE package_name=:packageName AND app_label!=:label")
    suspend fun updateAppSessionLabels(packageName: String, label: String): Int

    @Insert suspend fun insertScreenSession(value: ScreenSessionEntity): Long
    @Update suspend fun updateScreenSession(value: ScreenSessionEntity)
    @Query("SELECT * FROM screen_sessions WHERE end_ms<=:before ORDER BY end_ms DESC LIMIT 1")
    suspend fun latestScreenSession(before: Long): ScreenSessionEntity?
    @Query("SELECT * FROM screen_sessions WHERE start_ms<:end AND end_ms>:start ORDER BY start_ms")
    fun observeScreenSessions(start: Long, end: Long): Flow<List<ScreenSessionEntity>>
    @Query("SELECT * FROM screen_sessions WHERE start_ms<:end AND end_ms>:start ORDER BY start_ms")
    suspend fun screenSessionsInRange(start: Long, end: Long): List<ScreenSessionEntity>

    @Insert suspend fun insertNotification(value: NotificationEventEntity): Long
    @Query("""
        UPDATE notification_events
        SET removed_ms=:removedMs, reason_code=:reasonCode
        WHERE action='POSTED' AND notification_key_hash=:keyHash AND removed_ms IS NULL
    """)
    suspend fun closeOpenNotification(keyHash: String, removedMs: Long, reasonCode: Int): Int
    @Query("""
        SELECT DISTINCT notification_key_hash FROM notification_events
        WHERE action='POSTED' AND notification_key_hash IS NOT NULL AND removed_ms IS NULL
    """)
    suspend fun openNotificationKeyHashes(): List<String>
    @Query("""
        SELECT DISTINCT notification_key_hash FROM notification_events
        WHERE action='POSTED' AND notification_key_hash IS NOT NULL
          AND removed_ms IS NULL AND occurred_ms<:beforeMs
    """)
    suspend fun openNotificationKeyHashesBefore(beforeMs: Long): List<String>
    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM notification_events
            WHERE action='POSTED' AND notification_key_hash=:keyHash AND removed_ms IS NULL
        )
    """)
    suspend fun hasOpenNotification(keyHash: String): Boolean
    @Query("SELECT * FROM notification_events WHERE occurred_ms>=:start AND occurred_ms<:end ORDER BY occurred_ms")
    fun observeNotifications(start: Long, end: Long): Flow<List<NotificationEventEntity>>
    @Query("SELECT * FROM notification_events WHERE occurred_ms>=:start AND occurred_ms<:end ORDER BY occurred_ms DESC")
    suspend fun notificationsAllInRange(start: Long, end: Long): List<NotificationEventEntity>
    @Query("SELECT * FROM notification_events WHERE id>:afterId ORDER BY id LIMIT :limit")
    suspend fun notificationEventsPage(afterId: Long, limit: Int): List<NotificationEventEntity>
    @Query("""
        SELECT package_name, app_label,
               COUNT(*) AS count,
               MIN(occurred_ms) AS first_ms, MAX(occurred_ms) AS last_ms,
               SUM(CASE WHEN notification_title IS NOT NULL OR notification_body IS NOT NULL THEN 1 ELSE 0 END) AS with_content
        FROM notification_events
        WHERE action='POSTED' AND occurred_ms>=:fromMs AND occurred_ms<:toMs
        GROUP BY package_name ORDER BY count DESC
    """)
    suspend fun notificationAppSummary(fromMs: Long, toMs: Long): List<NotificationAppSummary>
    @Query("""
        SELECT * FROM notification_events
        WHERE action='POSTED' AND occurred_ms>=:fromMs AND occurred_ms<:toMs
          AND (:query='' OR app_label LIKE '%' || :query || '%' OR package_name LIKE '%' || :query || '%'
               OR notification_title LIKE '%' || :query || '%' OR notification_body LIKE '%' || :query || '%')
        ORDER BY occurred_ms DESC LIMIT :limit
    """)
    suspend fun notificationSearch(fromMs: Long, toMs: Long, query: String, limit: Int = 500): List<NotificationEventEntity>
    @Query("UPDATE notification_events SET app_label=:label WHERE package_name=:packageName AND app_label!=:label")
    suspend fun updateNotificationLabels(packageName: String, label: String): Int

    @Query("""
        SELECT package_name FROM app_sessions
        UNION
        SELECT package_name FROM notification_events
    """)
    suspend fun knownRecordedPackages(): List<String>

    @Insert suspend fun insertLocationPoint(value: LocationPointEntity): Long
    @Query("SELECT * FROM location_points WHERE recorded_ms>=:since ORDER BY recorded_ms")
    suspend fun recentLocationPoints(since: Long): List<LocationPointEntity>
    @Query("SELECT * FROM location_points WHERE (CASE WHEN measured_ms>0 THEN measured_ms ELSE recorded_ms END)>=:start AND (CASE WHEN measured_ms>0 THEN measured_ms ELSE recorded_ms END)<:end ORDER BY measured_ms, recorded_ms")
    suspend fun locationPointsInRange(start: Long, end: Long): List<LocationPointEntity>
    @Query("SELECT * FROM location_points WHERE recorded_ms>=:start AND recorded_ms<:end ORDER BY measured_ms, recorded_ms")
    fun observeLocationPoints(start: Long, end: Long): Flow<List<LocationPointEntity>>
    @Query("SELECT * FROM location_points ORDER BY recorded_ms DESC LIMIT 1")
    fun observeLatestLocationPoint(): Flow<LocationPointEntity?>

    @Insert suspend fun insertPlace(value: PlaceEntity): Long
    @Update suspend fun updatePlace(value: PlaceEntity)
    @Query("SELECT * FROM places ORDER BY ignored, name")
    fun observePlaces(): Flow<List<PlaceEntity>>
    @Query("SELECT * FROM places WHERE ignored=0")
    suspend fun activePlaces(): List<PlaceEntity>
    @Query("SELECT * FROM places WHERE id=:id")
    suspend fun placeById(id: Long): PlaceEntity?
    @Query("SELECT * FROM places ORDER BY id")
    suspend fun allPlaces(): List<PlaceEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertVisit(value: PlaceVisitEntity): Long
    @Update suspend fun updateVisit(value: PlaceVisitEntity)
    @Query("SELECT * FROM place_visits WHERE id=:id")
    suspend fun visitById(id: Long): PlaceVisitEntity?
    @Query("SELECT * FROM place_visits WHERE place_id=:placeId AND start_ms=:startMs LIMIT 1")
    suspend fun visitByPlaceAndStart(placeId: Long, startMs: Long): PlaceVisitEntity?
    @Query("""
        SELECT v.id, v.place_id, v.start_ms, v.end_ms, v.confidence,
               p.name, p.address, p.latitude, p.longitude
        FROM $DISPLAY_VISITS v JOIN places p ON p.id=v.place_id
        WHERE v.start_ms<:end AND v.end_ms>:start AND p.ignored=0 ORDER BY v.start_ms
    """)
    fun observeVisits(start: Long, end: Long): Flow<List<PlaceVisitView>>
    @Query("""
        SELECT v.id, v.place_id, v.start_ms, v.end_ms, v.confidence,
               p.name, p.address, p.latitude, p.longitude
        FROM $DISPLAY_VISITS v JOIN places p ON p.id=v.place_id
        WHERE p.ignored=0 ORDER BY v.start_ms DESC LIMIT :limit
    """)
    fun observeRecentVisits(limit: Int): Flow<List<PlaceVisitView>>
    @Query("""
        SELECT v.id, v.place_id, v.start_ms, v.end_ms, v.confidence,
               p.name, p.address, p.latitude, p.longitude
        FROM $DISPLAY_VISITS v JOIN places p ON p.id=v.place_id
        WHERE p.ignored=0 ORDER BY v.start_ms DESC
    """)
    fun observeAllVisits(): Flow<List<PlaceVisitView>>
    @Query("SELECT COUNT(*) FROM $DISPLAY_VISITS v WHERE v.place_id=:sourceId")
    suspend fun affectedVisitsForMerge(sourceId: Long): Int
    @Query("""
        WITH clipped AS (
            SELECT v.place_id, MAX(v.start_ms, :fromMs) AS start_ms,
                   MIN(v.end_ms, :toMs) AS end_ms
            FROM $DISPLAY_VISITS v JOIN places p ON p.id=v.place_id
            WHERE v.start_ms<:toMs AND v.end_ms>:fromMs AND p.ignored=0
        ), marked AS (
            SELECT place_id, start_ms, end_ms,
                   MAX(end_ms) OVER (PARTITION BY place_id ORDER BY start_ms, end_ms
                       ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING) AS prior_end
            FROM clipped
        ), islanded AS (
            SELECT place_id, start_ms, end_ms,
                   SUM(CASE WHEN prior_end IS NULL OR start_ms>prior_end THEN 1 ELSE 0 END)
                       OVER (PARTITION BY place_id ORDER BY start_ms, end_ms) AS island
            FROM marked
        ), unions AS (
            SELECT place_id, island, MIN(start_ms) AS start_ms, MAX(end_ms) AS end_ms
            FROM islanded GROUP BY place_id, island
        )
        SELECT u.place_id, p.name, p.address, SUM(u.end_ms-u.start_ms) AS total_ms,
               COUNT(*) AS visit_count, MIN(u.start_ms) AS first_visit_ms,
               MAX(u.end_ms) AS last_visit_ms
        FROM unions u JOIN places p ON p.id=u.place_id
        GROUP BY u.place_id ORDER BY total_ms DESC
    """)
    suspend fun placeStaySummary(fromMs: Long, toMs: Long): List<PlaceStaySummary>

    @Insert suspend fun insertStep(value: StepSampleEntity): Long
    @Query("SELECT * FROM step_samples WHERE recorded_ms>=:start AND recorded_ms<:end ORDER BY recorded_ms")
    fun observeSteps(start: Long, end: Long): Flow<List<StepSampleEntity>>

    @Insert suspend fun insertSleep(value: SleepSessionEntity): Long
    @Query("SELECT * FROM sleep_sessions WHERE start_ms<:end AND end_ms>:start ORDER BY start_ms")
    fun observeSleeps(start: Long, end: Long): Flow<List<SleepSessionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setSourceStatus(value: SourceStatusEntity)
    @Query("SELECT * FROM source_status ORDER BY source")
    fun observeSourceStatuses(): Flow<List<SourceStatusEntity>>
    @Query("SELECT * FROM source_status WHERE source=:source")
    suspend fun sourceStatus(source: String): SourceStatusEntity?
    @Query("SELECT * FROM source_status ORDER BY source")
    suspend fun debugStatuses(): List<SourceStatusEntity>
    @Query("SELECT * FROM app_sessions WHERE end_ms>=:since ORDER BY start_ms")
    suspend fun debugAppSessions(since: Long): List<AppSessionEntity>
    @Query("SELECT * FROM notification_events WHERE occurred_ms>=:since ORDER BY occurred_ms")
    suspend fun debugNotificationEvents(since: Long): List<NotificationEventEntity>
    @Query("SELECT * FROM location_points WHERE recorded_ms>=:since ORDER BY recorded_ms")
    suspend fun debugLocationPoints(since: Long): List<LocationPointEntity>
    @Query("SELECT * FROM places ORDER BY id")
    suspend fun debugPlaces(): List<PlaceEntity>
    @Query("""
        SELECT v.id, v.place_id, v.start_ms, v.end_ms, v.confidence,
               p.name, p.address, p.latitude, p.longitude
        FROM place_visits v JOIN places p ON p.id=v.place_id
        WHERE v.end_ms>=:since ORDER BY v.start_ms
    """)
    suspend fun debugVisits(since: Long): List<PlaceVisitView>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCollectionGap(value: CollectionGapEntity): Long
    @Query("SELECT * FROM collection_gaps WHERE start_ms<:end AND end_ms>:start ORDER BY start_ms")
    fun observeCollectionGaps(start: Long, end: Long): Flow<List<CollectionGapEntity>>
    @Query("SELECT * FROM collection_gaps ORDER BY start_ms DESC")
    fun observeAllCollectionGaps(): Flow<List<CollectionGapEntity>>
    @Query("SELECT * FROM collection_gaps WHERE end_ms>=:since ORDER BY start_ms")
    suspend fun debugCollectionGaps(since: Long): List<CollectionGapEntity>

    @Query("SELECT * FROM app_sessions WHERE start_ms<:end AND end_ms>:start ORDER BY start_ms")
    suspend fun appSessionsInRange(start: Long, end: Long): List<AppSessionEntity>
    @Query("SELECT * FROM notification_events WHERE occurred_ms>=:start AND occurred_ms<:end ORDER BY occurred_ms")
    suspend fun notificationsInRange(start: Long, end: Long): List<NotificationEventEntity>
    @Query("""
        SELECT v.id, v.place_id, v.start_ms, v.end_ms, v.confidence,
               p.name, p.address, p.latitude, p.longitude
        FROM $DISPLAY_VISITS v JOIN places p ON p.id=v.place_id
        WHERE v.start_ms<:end AND v.end_ms>:start AND p.ignored=0 ORDER BY v.start_ms
    """)
    suspend fun visitsInRange(start: Long, end: Long): List<PlaceVisitView>
    @Query("SELECT * FROM step_samples WHERE recorded_ms>=:start AND recorded_ms<:end ORDER BY recorded_ms")
    suspend fun stepsInRange(start: Long, end: Long): List<StepSampleEntity>
    @Query("SELECT * FROM collection_gaps WHERE start_ms<:end AND end_ms>:start ORDER BY start_ms")
    suspend fun collectionGapsInRange(start: Long, end: Long): List<CollectionGapEntity>

    @Query("""
        SELECT * FROM (
        SELECT id, 'APP' AS kind, start_ms, end_ms, app_label AS title, package_name AS subtitle
        FROM app_sessions
        WHERE start_ms<:toMs AND end_ms>:fromMs AND (:query='' OR app_label LIKE '%' || :query || '%' OR package_name LIKE '%' || :query || '%')
          AND (:type='' OR :type='APP')
        UNION ALL
        SELECT v.id, 'PLACE' AS kind, v.start_ms, v.end_ms, p.name AS title, p.address AS subtitle
        FROM $DISPLAY_VISITS v JOIN places p ON p.id=v.place_id
        WHERE v.start_ms<:toMs AND v.end_ms>:fromMs AND p.ignored=0
          AND (:query='' OR p.name LIKE '%' || :query || '%' OR p.address LIKE '%' || :query || '%')
          AND (:type='' OR :type='PLACE')
        UNION ALL
        SELECT id, 'NOTIFICATION' AS kind, occurred_ms AS start_ms, occurred_ms + 1 AS end_ms,
               COALESCE(NULLIF(notification_title, ''), app_label) AS title,
               COALESCE(NULLIF(notification_body, ''), package_name) AS subtitle
        FROM notification_events
        WHERE action='POSTED' AND occurred_ms>=:fromMs AND occurred_ms<:toMs
          AND (:query='' OR app_label LIKE '%' || :query || '%' OR package_name LIKE '%' || :query || '%'
               OR notification_title LIKE '%' || :query || '%' OR notification_body LIKE '%' || :query || '%')
          AND (:type='' OR :type='NOTIFICATION')
        ) AS archive_rows
        WHERE :cursorStartMs IS NULL
           OR start_ms < :cursorStartMs
           OR (start_ms = :cursorStartMs AND kind > :cursorKind)
           OR (start_ms = :cursorStartMs AND kind = :cursorKind AND id < :cursorId)
        ORDER BY start_ms DESC, kind ASC, id DESC
        LIMIT :limit
    """)
    suspend fun search(
        query: String,
        fromMs: Long,
        toMs: Long,
        type: String,
        limit: Int,
        cursorStartMs: Long?,
        cursorKind: String?,
        cursorId: Long?,
    ): List<SearchRow>

    // ── U08：对象精确筛选（点击应用/地点索引 → placeId/包名精确，重名不混查）──

    @Query(
        """
        SELECT v.id, 'PLACE' AS kind, v.start_ms, v.end_ms, p.name AS title, p.address AS subtitle
        FROM $DISPLAY_VISITS v JOIN places p ON p.id=v.place_id
        WHERE v.start_ms<:toMs AND v.end_ms>:fromMs AND p.ignored=0
          AND p.id=:placeId
          AND (:cursorStartMs IS NULL
               OR v.start_ms < :cursorStartMs
               OR (v.start_ms = :cursorStartMs AND v.id < :cursorId))
        ORDER BY v.start_ms DESC, v.id DESC
        LIMIT :limit
        """,
    )
    suspend fun searchByPlace(
        placeId: Long,
        fromMs: Long,
        toMs: Long,
        limit: Int,
        cursorStartMs: Long?,
        cursorId: Long?,
    ): List<SearchRow>

    @Query(
        """
        SELECT id, 'APP' AS kind, start_ms, end_ms, app_label AS title, package_name AS subtitle
        FROM app_sessions
        WHERE start_ms<:toMs AND end_ms>:fromMs AND package_name=:packageName
          AND (:query='' OR app_label LIKE '%' || :query || '%' OR package_name LIKE '%' || :query || '%')
          AND (:cursorStartMs IS NULL
               OR start_ms < :cursorStartMs
               OR (start_ms = :cursorStartMs AND id < :cursorId))
        ORDER BY start_ms DESC, id DESC
        LIMIT :limit
        """,
    )
    suspend fun searchByPackage(
        packageName: String,
        query: String,
        fromMs: Long,
        toMs: Long,
        limit: Int,
        cursorStartMs: Long?,
        cursorId: Long?,
    ): List<SearchRow>

    // R01：通知来源应查通知表，不能拿应用会话凑数（与 search NOTIFICATION 分支同规则）。
    @Query(
        """
        SELECT id, 'NOTIFICATION' AS kind, occurred_ms AS start_ms, occurred_ms + 1 AS end_ms,
               COALESCE(NULLIF(notification_title, ''), app_label) AS title,
               COALESCE(NULLIF(notification_body, ''), package_name) AS subtitle
        FROM notification_events
        WHERE action='POSTED' AND occurred_ms>=:fromMs AND occurred_ms<:toMs
          AND package_name=:packageName
          AND (:query='' OR app_label LIKE '%' || :query || '%' OR package_name LIKE '%' || :query || '%'
               OR notification_title LIKE '%' || :query || '%' OR notification_body LIKE '%' || :query || '%')
          AND (:cursorStartMs IS NULL
               OR occurred_ms < :cursorStartMs
               OR (occurred_ms = :cursorStartMs AND id < :cursorId))
        ORDER BY occurred_ms DESC, id DESC
        LIMIT :limit
        """,
    )
    suspend fun searchByPackageNotifications(
        packageName: String,
        query: String,
        fromMs: Long,
        toMs: Long,
        limit: Int,
        cursorStartMs: Long?,
        cursorId: Long?,
    ): List<SearchRow>

    // ── F 批：可追溯地点修订（attribution_revisions）─────────────────

    @Insert suspend fun insertAttributionRevision(value: AttributionRevisionEntity): Long

    @Query("SELECT * FROM attribution_revisions ORDER BY created_ms DESC, id DESC")
    fun observeAttributionRevisions(): Flow<List<AttributionRevisionEntity>>

    @Query("SELECT * FROM attribution_revisions ORDER BY created_ms DESC, id DESC")
    suspend fun attributionRevisions(): List<AttributionRevisionEntity>

    @Query("SELECT * FROM attribution_revisions ORDER BY created_ms DESC, id DESC")
    suspend fun debugAttributionRevisions(): List<AttributionRevisionEntity>

    @Query("SELECT * FROM attribution_revisions WHERE target_key = :targetKey ORDER BY created_ms DESC, id DESC")
    suspend fun attributionRevisionsFor(targetKey: String): List<AttributionRevisionEntity>

    @Query("UPDATE attribution_revisions SET reverted_by = :revertedBy WHERE id = :id")
    suspend fun revertAttributionRevision(id: Long, revertedBy: Long)

    @Query("SELECT id FROM attribution_revisions WHERE target_key = :targetKey AND reverted_by IS NULL ORDER BY created_ms DESC, id DESC LIMIT 1")
    suspend fun latestActiveRevisionFor(targetKey: String): Long?

}

// A null target is a user-authored reset to the original. Raw rows remain untouched.
private const val DISPLAY_VISITS = """(SELECT pv.id, pv.start_ms, pv.end_ms, pv.confidence,
    (WITH RECURSIVE chain(place_id, depth) AS (
        SELECT COALESCE((SELECT r.to_place_id FROM attribution_revisions r
            WHERE r.reverted_by IS NULL AND r.target_key='visit:' || pv.id
            ORDER BY r.created_ms DESC, r.id DESC LIMIT 1), pv.place_id), 0
        UNION ALL
        SELECT p.merged_into_place_id, chain.depth+1 FROM chain
            JOIN places p ON p.id=chain.place_id
            WHERE p.merged_into_place_id IS NOT NULL AND chain.depth<64
    ) SELECT place_id FROM chain ORDER BY depth DESC LIMIT 1) AS place_id
    FROM place_visits pv)"""
