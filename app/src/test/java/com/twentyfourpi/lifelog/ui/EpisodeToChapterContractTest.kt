package com.twentyfourpi.lifelog.ui

import com.twentyfourpi.lifelog.data.TimelineDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3：适配层契约——首页、详情、诊断导出三处都消费 [episodesToChapters]，
 * 章卡的时间边界与 key 必须逐字来自同一版 DayEpisode 投影，
 * 否则「点进去的那张卡」会变成相邻的另一张（子代理复核 #2）。
 */
class EpisodeToChapterContractTest {

    private fun episode(id: String, startMs: Long, endMs: Long, kind: DayEpisodeKind, placeId: Long? = null) =
        DayEpisode(
            id = id,
            startMs = startMs,
            endMs = endMs,
            kind = kind,
            placeId = placeId,
            reason = "test",
        )

    @Test
    fun `chapter boundaries and keys come from the episode projection`() {
        val day = TimelineDay()
        val episodes = listOf(
            episode("ep:a", 1_000, 2_000, DayEpisodeKind.MOVE),
            episode("ep:b", 2_000, 3_500, DayEpisodeKind.STAY, placeId = 7L),
        )
        val chapters = episodesToChapters(episodes, day)
        assertEquals(listOf("ep:a", "ep:b"), chapters.map { it.key })
        assertEquals(listOf(1_000L, 2_000L), chapters.map { it.startMs })
        assertEquals(listOf(2_000L, 3_500L), chapters.map { it.endMs })
    }

    @Test
    fun `episode without id still yields a unique chapter key`() {
        val day = TimelineDay()
        val chapters = episodesToChapters(listOf(episode("", 1_000, 2_000, DayEpisodeKind.MOVE)), day)
        assertTrue("key must stay non-empty", chapters.single().key.startsWith("chapter:"))
    }
}
