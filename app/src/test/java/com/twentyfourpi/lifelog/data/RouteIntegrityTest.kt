package com.twentyfourpi.lifelog.data

import com.twentyfourpi.lifelog.ui.ArchiveNavigationState
import com.twentyfourpi.lifelog.ui.ArchiveRoute
import com.twentyfourpi.lifelog.util.distanceMeters
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class RouteIntegrityTest {
    private fun point(id: Long, time: Long, lat: Double) = LocationPointEntity(
        id=id, recordedMs=time, measuredMs=time, latitude=lat, longitude=121.0,
        accuracyM=10f, provider="test", speedMps=2f,
    )

    @Test fun `minor gap stays one trip but is absent from measured edges and distance`() {
        val points=listOf(point(1,1_000,31.0),point(2,31_000,31.005),point(3,151_000,31.006),point(4,181_000,31.011))
        val route=DailyRouteBuilder.build(points)
        assertEquals(1,route.trips.size)
        assertEquals(1,route.interruptions.size)
        val expected=distanceMeters(31.0,121.0,31.005,121.0)+distanceMeters(31.006,121.0,31.011,121.0)
        assertEquals(expected,route.observedDistanceMeters,0.01)
        assertEquals(expected,route.trips.single().distanceMeters,0.01)
        assertEquals(2,route.sections.single().observedEdges.size)
        assertEquals(points.map { it.latitude },route.sections.single().points.map { it.latitude })
    }

    @Test fun `display attribution does not replace measured route geometry`() {
        val raw=listOf(point(1,1_000,31.0),point(2,31_000,31.005),point(3,61_000,31.01))
        val visit=PlaceVisitView(7,9,0,100_000,1f,"家","",32.0,122.0)
        val route=DailyRouteBuilder.build(raw,listOf(visit))
        assertEquals(31.0,route.firstRecorded!!.latitude,0.000001)
        assertEquals(10f,route.firstRecorded!!.accuracyM)
        assertTrue(route.observedDistanceMeters>1000)
        assertEquals("家",route.trips.single().startPlaceName)
    }

    @Test fun `active trip keeps its key when new end points arrive`() {
        val points=listOf(point(1,1_000,31.0),point(2,31_000,31.005),point(3,61_000,31.01))
        val before=DailyRouteBuilder.build(points.take(2)).trips.single()
        val after=DailyRouteBuilder.build(points).trips.single()
        assertEquals(before.key,after.key)
        assertTrue(after.last.timeMs>before.last.timeMs)
    }

    @Test fun `day summaries add exactly the displayed trips`() {
        val route=DailyRouteBuilder.build(listOf(point(1,1_000,31.0),point(2,31_000,31.005),point(3,61_000,31.01)))
        val date=LocalDate.of(2026,9,12)
        assertEquals(route.trips.sumOf { it.distanceMeters },route.tripSummaries(date).sumOf { it.distanceMeters },0.0)
        assertEquals(route.trips.single().key,route.tripSummaries(date).single().key)
    }

    @Test fun `trip navigation survives restoration and returns to its selected archive`() {
        val date=LocalDate.of(2026,9,12).toEpochDay()
        val list=ArchiveNavigationState.initial(date).push(ArchiveRoute.Trips(date))
        val details=list.push(ArchiveRoute.Trip(date,"1:1000:3:61000"))
        val restored=ArchiveNavigationState.decode(details.encode())!!
        assertEquals(details,restored)
        assertEquals(list,restored.pop())
        assertEquals(ArchiveRoute.CurrentLocation,ArchiveNavigationState.decode(list.push(ArchiveRoute.CurrentLocation).encode())!!.currentRoute)
    }
}
