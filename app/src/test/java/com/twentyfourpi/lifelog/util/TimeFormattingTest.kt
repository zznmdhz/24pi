package com.twentyfourpi.lifelog.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeFormattingTest {
    @Test fun shortDurationsUseSecondsInsteadOfZeroMinutes() {
        assertEquals("37秒", formatDuration(37_526))
        assertEquals("不足1秒", formatDuration(500))
        assertEquals("0秒", formatDuration(0))
    }

    @Test fun longerDurationsUseMinutesAndHours() {
        assertEquals("2分钟", formatDuration(125_000))
        assertEquals("1小时5分", formatDuration(3_900_000))
    }
}
