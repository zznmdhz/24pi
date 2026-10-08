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

/**
 * v0.15 地点权重体系迁移：存量 places 必须从 place_visits 回填访问统计，
 * 让"临时点降权"在升级后立即对历史数据生效（无需等新访问积累）。
 */
@RunWith(AndroidJUnit4::class)
class Migration5To6Test {
    private lateinit var context: Context
    private val databaseName = "migration-5-6-test.db"

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
    fun existingPlacesBackfillVisitStatsFromVisits() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        // 与 Room v5 schema 一致的 places / place_visits 最小结构
                        db.execSQL(
                            """
                            CREATE TABLE places (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                name TEXT NOT NULL,
                                address TEXT NOT NULL,
                                latitude REAL NOT NULL,
                                longitude REAL NOT NULL,
                                ignored INTEGER NOT NULL,
                                is_custom_name INTEGER NOT NULL
                            )
                            """.trimIndent(),
                        )
                        db.execSQL(
                            """
                            CREATE TABLE place_visits (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                place_id INTEGER NOT NULL,
                                start_ms INTEGER NOT NULL,
                                end_ms INTEGER NOT NULL,
                                confidence REAL NOT NULL,
                                FOREIGN KEY(place_id) REFERENCES places(id) ON DELETE NO ACTION
                            )
                            """.trimIndent(),
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        val database = helper.writableDatabase

        // 家：3 次到访；洗车店：1 次到访；从未去过的空地点：0 次
        database.execSQL("INSERT INTO places(name,address,latitude,longitude,ignored,is_custom_name) VALUES('家','',31.23,121.47,0,0)")
        database.execSQL("INSERT INTO places(name,address,latitude,longitude,ignored,is_custom_name) VALUES('洗车店','',31.24,121.48,0,0)")
        database.execSQL("INSERT INTO places(name,address,latitude,longitude,ignored,is_custom_name) VALUES('孤儿点','',31.25,121.49,0,0)")
        database.execSQL("INSERT INTO place_visits(place_id,start_ms,end_ms,confidence) VALUES(1,1000,2000,0.9)")
        database.execSQL("INSERT INTO place_visits(place_id,start_ms,end_ms,confidence) VALUES(1,3000,9000,0.9)")
        database.execSQL("INSERT INTO place_visits(place_id,start_ms,end_ms,confidence) VALUES(2,4000,5000,0.9)")

        DatabaseProvider.MIGRATION_5_6.migrate(database)

        database.query(
            "SELECT name, visit_count, last_visit_ms FROM places ORDER BY id",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            // 家：2 次到访（测试数据插了 2 行），最近 end_ms=9000
            assertEquals("家", cursor.getString(0))
            assertEquals(2, cursor.getInt(1))
            assertEquals(9000L, cursor.getLong(2))
            cursor.moveToNext()
            // 洗车店：1 次，end=5000 → 仍是临时点
            assertEquals("洗车店", cursor.getString(0))
            assertEquals(1, cursor.getInt(1))
            assertEquals(5000L, cursor.getLong(2))
            cursor.moveToNext()
            // 孤儿点：0 次到访
            assertEquals("孤儿点", cursor.getString(0))
            assertEquals(0, cursor.getInt(1))
            assertEquals(0L, cursor.getLong(2))
        }
        helper.close()
    }
}
