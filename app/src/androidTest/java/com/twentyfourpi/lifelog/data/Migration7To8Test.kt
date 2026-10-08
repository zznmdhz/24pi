package com.twentyfourpi.lifelog.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * F 批：v7 → v8 迁移测试——attribution_revisions 建表且不影响既有数据。
 */
@RunWith(AndroidJUnit4::class)
class Migration7To8Test {
    private lateinit var context: Context
    private val databaseName = "migration-7-8-test.db"

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(databaseName)
    }

    @After fun tearDown() { context.deleteDatabase(databaseName) }

    @Test fun revisionTableIsCreatedAndExistingPlacesSurvive() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(7) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE places (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                name TEXT NOT NULL,
                                address TEXT NOT NULL,
                                latitude REAL NOT NULL,
                                longitude REAL NOT NULL,
                                ignored INTEGER NOT NULL DEFAULT 0,
                                is_custom_name INTEGER NOT NULL DEFAULT 0,
                                visit_count INTEGER NOT NULL DEFAULT 0,
                                last_visit_ms INTEGER NOT NULL DEFAULT 0
                            )
                            """.trimIndent(),
                        )
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        val database = helper.writableDatabase
        database.execSQL("INSERT INTO places(name,address,latitude,longitude) VALUES('家','',31.0,121.0)")

        DatabaseProvider.MIGRATION_7_8.migrate(database)

        // 既有地点保留。
        database.query("SELECT name FROM places").use {
            assertTrue(it.moveToFirst())
            assertEquals("家", it.getString(0))
        }
        // 修订表存在且结构正确。
        database.query(
            "SELECT target_key, from_place_id, to_place_id, created_ms, source, rule_version, reverted_by, reason FROM attribution_revisions",
        ).use {
            assertTrue("空表查询应可执行", it.columnCount == 8)
            assertEquals(0, it.count)
        }
        // 可写入一条修订并读回。
        database.execSQL(
            "INSERT INTO attribution_revisions(target_key,from_place_id,to_place_id,created_ms,source,rule_version,reason) " +
                "VALUES('visit:1',1,7,1000,'user',1,'这段其实在家')",
        )
        database.query("SELECT target_key, to_place_id FROM attribution_revisions").use {
            assertTrue(it.moveToFirst())
            assertEquals("visit:1", it.getString(0))
            assertEquals(7L, it.getLong(1))
        }
        helper.close()
    }

    @Test fun migrationIsUpgradeSafeWithExistingRows() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(7) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE places (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                name TEXT NOT NULL,
                                address TEXT NOT NULL,
                                latitude REAL NOT NULL,
                                longitude REAL NOT NULL,
                                ignored INTEGER NOT NULL DEFAULT 0,
                                is_custom_name INTEGER NOT NULL DEFAULT 0,
                                visit_count INTEGER NOT NULL DEFAULT 0,
                                last_visit_ms INTEGER NOT NULL DEFAULT 0
                            )
                            """.trimIndent(),
                        )
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        val database = helper.writableDatabase
        database.execSQL("INSERT INTO places(name,address,latitude,longitude) VALUES('公司','',31.1,121.1)")
        database.execSQL("INSERT INTO places(name,address,latitude,longitude) VALUES('家','',31.0,121.0)")

        DatabaseProvider.MIGRATION_7_8.migrate(database)

        database.query("SELECT COUNT(*) FROM places").use {
            it.moveToFirst()
            assertEquals(2L, it.getLong(0))
        }
        helper.close()
    }
}