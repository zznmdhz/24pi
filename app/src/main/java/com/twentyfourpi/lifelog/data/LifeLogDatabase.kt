package com.twentyfourpi.lifelog.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.concurrent.atomic.AtomicReference

@Database(
    entities = [
        AppSessionEntity::class, ScreenSessionEntity::class, NotificationEventEntity::class,
        LocationPointEntity::class, PlaceEntity::class, PlaceVisitEntity::class,
        StepSampleEntity::class, SleepSessionEntity::class, SourceStatusEntity::class,
        CollectionGapEntity::class, AttributionRevisionEntity::class,
    ],
    version = 9,
    exportSchema = true,
)
abstract class LifeLogDatabase : RoomDatabase() {
    abstract fun dao(): LifeLogDao
}

class DatabaseProvider(private val context: Context, private val databaseName: String = DATABASE_NAME) {
    private val reference = AtomicReference<LifeLogDatabase?>()

    fun get(): LifeLogDatabase = reference.get() ?: synchronized(this) {
        reference.get() ?: Room.databaseBuilder(
            context.applicationContext,
            LifeLogDatabase::class.java,
            databaseName,
        ).addMigrations(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
            MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
        ).build().also(reference::set)
    }

    fun close() = synchronized(this) {
        reference.getAndSet(null)?.close()
    }

    fun databaseFile() = context.getDatabasePath(databaseName)

    companion object {
        const val DATABASE_NAME = "life-log.db"
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notification_events ADD COLUMN notification_title TEXT")
                db.execSQL("ALTER TABLE notification_events ADD COLUMN notification_body TEXT")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // v0.5.0 中多个定位回调可能同时为同一候选停留建档。先把同地点、同起点
                // 的记录保留为一条并取最晚结束时间，再用唯一索引从数据层阻止再次发生。
                db.execSQL("DROP INDEX IF EXISTS index_place_visits_place_id_start_ms")
                db.execSQL("""
                    UPDATE place_visits
                    SET end_ms = (
                        SELECT MAX(duplicate.end_ms)
                        FROM place_visits AS duplicate
                        WHERE duplicate.place_id = place_visits.place_id
                          AND duplicate.start_ms = place_visits.start_ms
                    )
                    WHERE id IN (
                        SELECT MAX(id) FROM place_visits GROUP BY place_id, start_ms
                    )
                """.trimIndent())
                db.execSQL("""
                    DELETE FROM place_visits
                    WHERE id NOT IN (
                        SELECT MAX(id) FROM place_visits GROUP BY place_id, start_ms
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_place_visits_place_id_start_ms ON place_visits(place_id, start_ms)")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS collection_gaps (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        source TEXT NOT NULL,
                        start_ms INTEGER NOT NULL,
                        end_ms INTEGER NOT NULL,
                        reason TEXT NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_collection_gaps_start_ms ON collection_gaps(start_ms)")
                db.execSQL("""
                    CREATE UNIQUE INDEX IF NOT EXISTS index_collection_gaps_source_start_ms_end_ms
                    ON collection_gaps(source, start_ms, end_ms)
                """.trimIndent())
            }
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notification_events ADD COLUMN notification_key_hash TEXT")
                db.execSQL("ALTER TABLE notification_events ADD COLUMN channel_id TEXT")
                db.execSQL("ALTER TABLE notification_events ADD COLUMN removed_ms INTEGER")
                db.execSQL("ALTER TABLE notification_events ADD COLUMN content_state TEXT NOT NULL DEFAULT 'LEGACY_UNKNOWN'")
                db.execSQL("ALTER TABLE notification_events ADD COLUMN capture_origin TEXT NOT NULL DEFAULT 'LEGACY'")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_notification_events_notification_key_hash ON notification_events(notification_key_hash)")
            }
        }
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // v0.15 地点匹配权重：为存量地点回填访问统计，让临时点识别立即生效。
                db.execSQL("ALTER TABLE places ADD COLUMN visit_count INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE places ADD COLUMN last_visit_ms INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """
                    UPDATE places SET
                        visit_count = (SELECT COUNT(*) FROM place_visits v WHERE v.place_id = places.id),
                        last_visit_ms = (SELECT COALESCE(MAX(v.end_ms), 0) FROM place_visits v WHERE v.place_id = places.id)
                    """.trimIndent(),
                )
            }
        }
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Raw points remain append-only, so later route algorithms can reprocess history.
                db.execSQL("ALTER TABLE location_points ADD COLUMN measured_ms INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE location_points SET measured_ms = recorded_ms WHERE measured_ms = 0")
                db.execSQL("ALTER TABLE location_points ADD COLUMN elapsed_realtime_nanos INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE location_points ADD COLUMN speed_mps REAL")
                db.execSQL("ALTER TABLE location_points ADD COLUMN is_mock INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_location_points_measured_ms ON location_points(measured_ms)")
            }
        }
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // F 批（方案 16.5）：可追溯地点修订表。只影响展示投影；
                // 旧数据不受影响、不重置任何采集信号。
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS attribution_revisions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        target_key TEXT NOT NULL,
                        from_place_id INTEGER,
                        to_place_id INTEGER,
                        created_ms INTEGER NOT NULL,
                        source TEXT NOT NULL,
                        rule_version INTEGER NOT NULL,
                        reverted_by INTEGER,
                        reason TEXT NOT NULL DEFAULT ''
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_attribution_revisions_target_key ON attribution_revisions(target_key)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_attribution_revisions_created_ms ON attribution_revisions(created_ms)")
            }
        }
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE places ADD COLUMN place_kind TEXT NOT NULL DEFAULT 'OTHER'")
                db.execSQL("UPDATE places SET place_kind='HOME' WHERE is_custom_name=1 AND name='家'")
                db.execSQL("UPDATE places SET place_kind='COMPANY' WHERE is_custom_name=1 AND name='公司'")
                db.execSQL("ALTER TABLE places ADD COLUMN merged_into_place_id INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_places_merged_into_place_id ON places(merged_into_place_id)")
            }
        }
    }
}
