package com.twentyfourpi.lifelog.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 防止已有 v0.10 / Room v4 用户升级时因通知列默认值不匹配而无法打开数据库。 */
@RunWith(AndroidJUnit4::class)
class Migration4To5Test {
    private lateinit var context: Context
    private val databaseName = "migration-4-5-test.db"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(databaseName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun legacyNotificationSurvivesAndReceivesHonestDefaults() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE notification_events (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                package_name TEXT NOT NULL,
                                app_label TEXT NOT NULL,
                                occurred_ms INTEGER NOT NULL,
                                action TEXT NOT NULL,
                                reason_code INTEGER,
                                notification_title TEXT,
                                notification_body TEXT
                            )
                            """.trimIndent(),
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        val database = helper.writableDatabase
        database.execSQL(
            "INSERT INTO notification_events(package_name,app_label,occurred_ms,action) VALUES('com.example','Example',123,'POSTED')",
        )

        DatabaseProvider.MIGRATION_4_5.migrate(database)

        database.query("SELECT notification_key_hash,channel_id,removed_ms,content_state,capture_origin FROM notification_events").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
            assertTrue(cursor.isNull(1))
            assertTrue(cursor.isNull(2))
            assertEquals("LEGACY_UNKNOWN", cursor.getString(3))
            assertEquals(NotificationCaptureOrigin.LEGACY, cursor.getString(4))
        }
        database.query("PRAGMA table_info(notification_events)").use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow("name")
            val defaultColumn = cursor.getColumnIndexOrThrow("dflt_value")
            var contentDefault: String? = null
            var originDefault: String? = null
            while (cursor.moveToNext()) {
                when (cursor.getString(nameColumn)) {
                    "content_state" -> contentDefault = cursor.getString(defaultColumn)
                    "capture_origin" -> originDefault = cursor.getString(defaultColumn)
                }
            }
            assertEquals("'LEGACY_UNKNOWN'", contentDefault)
            assertEquals("'LEGACY'", originDefault)
        }
        database.query("PRAGMA index_list(notification_events)").use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow("name")
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameColumn) == "index_notification_events_notification_key_hash") found = true
            }
            assertTrue(found)
        }
        helper.close()
    }
}
