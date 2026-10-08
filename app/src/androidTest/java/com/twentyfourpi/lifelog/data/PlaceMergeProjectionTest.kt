package com.twentyfourpi.lifelog.data

import androidx.room.Room
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaceMergeProjectionTest {
    @Test fun repositoryMergeUndoAndCycleGuardKeepOriginalRows() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "place-merge-repository-test.db"
        context.deleteDatabase(databaseName)
        val provider = DatabaseProvider(context, databaseName)
        try {
            val dao = provider.get().dao()
            val repository = LifeLogRepository(provider)
            val a = dao.insertPlace(PlaceEntity(name="A",latitude=31.0,longitude=121.0))
            val b = dao.insertPlace(PlaceEntity(name="B",latitude=32.0,longitude=122.0))
            val c = dao.insertPlace(PlaceEntity(name="C",latitude=33.0,longitude=123.0))
            val visit = dao.insertVisit(PlaceVisitEntity(placeId=a,startMs=1000,endMs=3000))
            repository.mergePlaces(dao.placeById(a)!!, dao.placeById(b)!!)
            repository.mergePlaces(dao.placeById(b)!!, dao.placeById(c)!!)
            assertEquals(c, dao.visitsInRange(0,4000).single().placeId)
            assertEquals(a, dao.visitById(visit)!!.placeId)
            try {
                repository.mergePlaces(dao.placeById(c)!!, dao.placeById(a)!!)
                fail("A merged source cannot become a target")
            } catch (_: IllegalArgumentException) { }
            try {
                repository.mergePlaces(dao.placeById(c)!!, dao.placeById(c)!!)
                fail("Self merge must fail")
            } catch (_: IllegalArgumentException) { }
            repository.undoPlaceMerge(b)
            assertEquals(b, dao.visitsInRange(0,4000).single().placeId)
            assertEquals(b, dao.placeById(a)!!.mergedIntoPlaceId)
            assertNull(dao.placeById(b)!!.mergedIntoPlaceId)
        } finally { provider.close(); context.deleteDatabase(databaseName) }
    }

    @Test fun chainCorrectionUnionAndUndoKeepRawEvidence() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LifeLogDatabase::class.java).build()
        try {
            val dao = db.dao()
            val a = dao.insertPlace(PlaceEntity(name="A",address="A raw",latitude=31.0,longitude=121.0))
            val b = dao.insertPlace(PlaceEntity(name="B",address="B raw",latitude=32.0,longitude=122.0))
            val c = dao.insertPlace(PlaceEntity(name="C",address="C raw",latitude=33.0,longitude=123.0))
            val v1 = dao.insertVisit(PlaceVisitEntity(placeId=a,startMs=1000,endMs=5000))
            dao.insertVisit(PlaceVisitEntity(placeId=b,startMs=3000,endMs=7000))
            val v3 = dao.insertVisit(PlaceVisitEntity(placeId=c,startMs=9000,endMs=10_000))
            dao.updatePlace(dao.placeById(a)!!.copy(mergedIntoPlaceId=b))
            dao.updatePlace(dao.placeById(b)!!.copy(mergedIntoPlaceId=c))
            assertEquals(listOf(c,c,c), dao.visitsInRange(0,11_000).map { it.placeId })
            assertEquals(7000L, dao.placeStaySummary(0,11_000).single().totalMs)
            assertEquals(2, dao.placeStaySummary(0,11_000).single().visitCount)
            assertEquals(5000L, dao.placeStaySummary(2000,9000).single().totalMs)
            assertEquals(1, dao.placeStaySummary(2000,9000).single().visitCount)
            assertEquals(a, dao.visitById(v1)!!.placeId)
            assertEquals("A raw", dao.placeById(a)!!.address)
            assertEquals(31.0, dao.placeById(a)!!.latitude, 0.0)
            val correction = dao.insertAttributionRevision(AttributionRevisionEntity(
                targetKey="visit:$v3",fromPlaceId=c,toPlaceId=a,createdMs=100,source="user",ruleVersion=1))
            assertEquals(c, dao.visitsInRange(0,11_000).first { it.id == v3 }.placeId)
            dao.updatePlace(dao.placeById(b)!!.copy(mergedIntoPlaceId=null))
            assertEquals(listOf(b,b,b), dao.visitsInRange(0,11_000).map { it.placeId })
            assertEquals(7000L, dao.placeStaySummary(0,11_000).single().totalMs)
            dao.revertAttributionRevision(correction, correction)
            assertEquals(c, dao.visitsInRange(0,11_000).first { it.id == v3 }.placeId)
            assertEquals(6000L, dao.placeStaySummary(0,11_000).first { it.placeId == b }.totalMs)
        } finally { db.close() }
    }
}
