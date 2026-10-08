package com.twentyfourpi.lifelog.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration6To7Test {
    private lateinit var context: Context
    private val databaseName = "migration-6-7-test.db"

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(databaseName)
    }

    @After fun tearDown() { context.deleteDatabase(databaseName) }

    @Test fun rawLocationRowsArePreservedAndBackfilled() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(6) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """CREATE TABLE location_points (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                recorded_ms INTEGER NOT NULL,
                                latitude REAL NOT NULL,
                                longitude REAL NOT NULL,
                                accuracy_m REAL NOT NULL,
                                provider TEXT NOT NULL
                            )""".trimIndent(),
                        )
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        val database = helper.writableDatabase
        database.execSQL("INSERT INTO location_points(recorded_ms,latitude,longitude,accuracy_m,provider) VALUES(1234,31.2,121.4,20,'gps')")

        DatabaseProvider.MIGRATION_6_7.migrate(database)

        database.query("SELECT recorded_ms,measured_ms,elapsed_realtime_nanos,speed_mps,is_mock FROM location_points").use {
            it.moveToFirst()
            assertEquals(1234L, it.getLong(0))
            assertEquals(1234L, it.getLong(1))
            assertEquals(0L, it.getLong(2))
            assertEquals(true, it.isNull(3))
            assertEquals(0, it.getInt(4))
        }
        helper.close()
    }
}
