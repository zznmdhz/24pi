package com.twentyfourpi.lifelog.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VisitCorrectionDatabaseTest {
    @Test fun correctionIsConsistentAcrossVisitsSearchSummaryAndRawExport() = runBlocking {
        val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),LifeLogDatabase::class.java).build()
        try {
            val dao=db.dao()
            val a=dao.insertPlace(PlaceEntity(name="原地点",latitude=31.0,longitude=121.0))
            val b=dao.insertPlace(PlaceEntity(name="正确地点",latitude=31.1,longitude=121.1))
            val visit=dao.insertVisit(PlaceVisitEntity(placeId=a,startMs=1000,endMs=61_000))
            val correction=dao.insertAttributionRevision(AttributionRevisionEntity(targetKey="visit:$visit",fromPlaceId=a,toPlaceId=b,createdMs=100,source="user",ruleVersion=1))
            assertEquals(b,dao.visitsInRange(0,100_000).single().placeId)
            assertEquals(b,dao.placeStaySummary(0,100_000).single().placeId)
            assertEquals(a,dao.debugVisits(0).single().placeId)
            assertEquals(a,dao.visitById(visit)!!.placeId)
            assertEquals("正确地点",dao.searchByPlace(b,0,100_000,200,null,null).single().title)
            val reset=dao.insertAttributionRevision(AttributionRevisionEntity(targetKey="visit:$visit",fromPlaceId=b,toPlaceId=null,createdMs=101,source="user",ruleVersion=1))
            assertEquals(a,dao.visitsInRange(0,100_000).single().placeId)
            dao.revertAttributionRevision(reset,reset)
            assertEquals(b,dao.visitsInRange(0,100_000).single().placeId)
            dao.revertAttributionRevision(correction,reset)
            assertEquals(a,dao.visitsInRange(0,100_000).single().placeId)
        } finally { db.close() }
    }
}
