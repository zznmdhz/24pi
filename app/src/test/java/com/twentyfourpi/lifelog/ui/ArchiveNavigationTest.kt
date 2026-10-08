package com.twentyfourpi.lifelog.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ArchiveNavigationTest {
    @Test
    fun `each root retains an independent stack`() {
        val timeChapter = ArchiveRoute.Chapter(10, 1_000, 2_000)
        val archiveChapter = ArchiveRoute.Chapter(9, 3_000, 4_000)

        val state = ArchiveNavigationState.initial(10)
            .push(timeChapter)
            .selectRoot(ArchiveRoot.ARCHIVE)
            .push(archiveChapter)
            .selectRoot(ArchiveRoot.MANAGE)
            .push(ArchiveRoute.Sources)

        assertEquals(ArchiveRoute.Sources, state.currentRoute)
        assertEquals(timeChapter, state.selectRoot(ArchiveRoot.TIME).currentRoute)
        assertEquals(archiveChapter, state.selectRoot(ArchiveRoot.ARCHIVE).currentRoute)
        assertEquals(ArchiveRoute.Sources, state.selectRoot(ArchiveRoot.MANAGE).currentRoute)
    }

    @Test
    fun `chapter opened from archive pops back to archive instead of time`() {
        val timeState = ArchiveNavigationState.initial(10)
            .push(ArchiveRoute.Chapter(10, 1_000, 2_000))
        val archiveState = timeState
            .selectRoot(ArchiveRoot.ARCHIVE)
            .push(ArchiveRoute.Chapter(8, 5_000, 8_000))

        val returned = archiveState.pop()

        assertEquals(ArchiveRoot.ARCHIVE, returned.selectedRoot)
        assertEquals(ArchiveRoute.ArchiveHome, returned.currentRoute)
        assertEquals(2, returned.stack(ArchiveRoot.TIME).size)
    }

    @Test
    fun `paging to another day replaces instead of growing history`() {
        val nextDay = ArchiveRoute.Day(11)
        val state = ArchiveNavigationState.initial(10).replace(nextDay)

        assertEquals(listOf(nextDay), state.stack())
        assertSame(state, state.pop())
    }

    @Test
    fun `pop never removes a root route`() {
        val state = ArchiveNavigationState.initial(10)

        assertSame(state, state.pop())
        assertEquals(ArchiveRoute.Day(10), state.currentRoute)
    }

    @Test
    fun `clear to root affects only the selected root`() {
        val state = ArchiveNavigationState.initial(10)
            .push(ArchiveRoute.Chapter(10, 1_000, 2_000))
            .push(ArchiveRoute.Evidence(EvidenceKind.APP_SESSION, 7, 1_100, 1_900))
            .selectRoot(ArchiveRoot.MANAGE)
            .push(ArchiveRoute.Sources)
            .clearToRoot()

        assertEquals(listOf(ArchiveRoute.ManageHome), state.stack(ArchiveRoot.MANAGE))
        assertEquals(3, state.stack(ArchiveRoot.TIME).size)
    }

    @Test
    fun `encoded state restores all roots routes and selection`() {
        val original = ArchiveNavigationState.initial(20)
            .push(ArchiveRoute.Chapter(20, 10_000, 20_000))
            .push(ArchiveRoute.Evidence(EvidenceKind.NOTIFICATION, 99, 15_000, 15_000))
            .selectRoot(ArchiveRoot.ARCHIVE)
            .push(ArchiveRoute.PlaceArchive)
            .push(ArchiveRoute.NotificationSettings)
            .push(ArchiveRoute.Chapter(19, 30_000, 40_000))
            .selectRoot(ArchiveRoot.MANAGE)
            .push(ArchiveRoute.Sources)
            .selectRoot(ArchiveRoot.ARCHIVE)

        assertEquals(original, ArchiveNavigationState.decode(original.encode()))
    }

    @Test
    fun `decoder rejects malformed or structurally invalid state`() {
        assertNull(ArchiveNavigationState.decode(""))
        assertNull(ArchiveNavigationState.decode("2|TIME|TIME=d,1|ARCHIVE=a|MANAGE=m"))
        assertNull(ArchiveNavigationState.decode("1|TIME|TIME=a|ARCHIVE=a|MANAGE=m"))
        assertNull(ArchiveNavigationState.decode("1|TIME|TIME=d,1|ARCHIVE=a|MANAGE="))
    }

    @Test
    fun `AI review and settings preserve the originating root and day`() {
        val day = ArchiveRoute.Day(20)
        val state = ArchiveNavigationState.initial(20)
            .push(ArchiveRoute.AiReview)
            .push(ArchiveRoute.AiSettings)
        assertEquals(state, ArchiveNavigationState.decode(state.encode()))
        assertEquals(ArchiveRoute.AiReview, state.pop().currentRoute)
        assertEquals(day, state.pop().pop().currentRoute)
        assertEquals(listOf(ArchiveRoute.ArchiveHome), state.stack(ArchiveRoot.ARCHIVE))
    }

    @Test
    fun `place management from My returns to My while archive keeps its own search context`() {
        val state = ArchiveNavigationState.initial(20)
            .selectRoot(ArchiveRoot.ARCHIVE)
            .push(ArchiveRoute.Evidence(EvidenceKind.PLACE_VISIT, 7, 1_000, 2_000))
            .selectRoot(ArchiveRoot.MANAGE)
            .push(ArchiveRoute.PlaceArchive)
        assertEquals(ArchiveRoute.ManageHome, state.pop().currentRoute)
        assertEquals(ArchiveRoute.Evidence(EvidenceKind.PLACE_VISIT, 7, 1_000, 2_000),
            state.selectRoot(ArchiveRoot.ARCHIVE).currentRoute)
    }
}
