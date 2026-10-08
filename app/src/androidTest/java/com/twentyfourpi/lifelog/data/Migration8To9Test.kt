package com.twentyfourpi.lifelog.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration8To9Test {
    @Test fun fullV8SchemaMigratesAndRoomValidatesV9() {
        val name = "migration-8-9-full-schema-test.db"
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(name)
        val helper = MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(), LifeLogDatabase::class.java,
        )
        try {
            helper.createDatabase(name, 8).apply {
                execSQL("INSERT INTO places(name,address,latitude,longitude,ignored,is_custom_name,visit_count,last_visit_ms) VALUES('公司','保留原地址',31.2,121.4,0,1,0,0)")
                close()
            }
            helper.runMigrationsAndValidate(name, 9, true, DatabaseProvider.MIGRATION_8_9).use { db ->
                db.query("SELECT name,address,latitude,longitude,place_kind,merged_into_place_id FROM places").use {
                    assertTrue(it.moveToFirst())
                    assertEquals("公司", it.getString(0))
                    assertEquals("保留原地址", it.getString(1))
                    assertEquals(31.2, it.getDouble(2), 0.0)
                    assertEquals(121.4, it.getDouble(3), 0.0)
                    assertEquals("COMPANY", it.getString(4))
                    assertTrue(it.isNull(5))
                }
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun existingPlaceAndVisitEvidenceSurvivesWithSafeKindBackfill() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-8-9-place-test.db"
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(8) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("""CREATE TABLE places (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            name TEXT NOT NULL, address TEXT NOT NULL, latitude REAL NOT NULL,
                            longitude REAL NOT NULL, ignored INTEGER NOT NULL DEFAULT 0,
                            is_custom_name INTEGER NOT NULL DEFAULT 0,
                            visit_count INTEGER NOT NULL DEFAULT 0, last_visit_ms INTEGER NOT NULL DEFAULT 0)""")
                        db.execSQL("""CREATE TABLE place_visits (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            place_id INTEGER NOT NULL, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL,
                            confidence REAL NOT NULL)""")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        try {
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO places(id,name,address,latitude,longitude,is_custom_name) VALUES(1,'家','原始地址',31.1,121.2,1)")
            db.execSQL("INSERT INTO places(id,name,address,latitude,longitude,is_custom_name) VALUES(2,'公司','办公楼',31.3,121.4,1)")
            db.execSQL("INSERT INTO places(id,name,address,latitude,longitude,is_custom_name) VALUES(3,'公司','系统地址',31.5,121.6,0)")
            db.execSQL("INSERT INTO place_visits(id,place_id,start_ms,end_ms,confidence) VALUES(7,1,1000,2000,0.8)")
            DatabaseProvider.MIGRATION_8_9.migrate(db)
            db.query("SELECT name,address,latitude,longitude,place_kind,merged_into_place_id FROM places WHERE id=1").use {
                assertTrue(it.moveToFirst())
                assertEquals("家", it.getString(0))
                assertEquals("原始地址", it.getString(1))
                assertEquals(31.1, it.getDouble(2), 0.0)
                assertEquals(121.2, it.getDouble(3), 0.0)
                assertEquals("HOME", it.getString(4))
                assertTrue(it.isNull(5))
            }
            db.query("SELECT place_kind FROM places WHERE id=2").use { it.moveToFirst(); assertEquals("COMPANY", it.getString(0)) }
            db.query("SELECT place_kind FROM places WHERE id=3").use { it.moveToFirst(); assertEquals("OTHER", it.getString(0)) }
            db.query("SELECT place_id,start_ms,end_ms,confidence FROM place_visits WHERE id=7").use {
                assertTrue(it.moveToFirst())
                assertEquals(1L, it.getLong(0))
                assertEquals(1000L, it.getLong(1))
                assertEquals(2000L, it.getLong(2))
                assertEquals(0.8f, it.getFloat(3), 0.0001f)
            }
        } finally { helper.close(); context.deleteDatabase(name) }
    }
}
