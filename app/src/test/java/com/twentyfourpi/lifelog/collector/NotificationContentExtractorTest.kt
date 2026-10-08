package com.twentyfourpi.lifelog.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationContentExtractorTest {
    @Test fun sanitizerFlattensWhitespaceAndTruncates() {
        assertEquals("一条 消息", NotificationContentExtractor.sanitize("  一条\n\t消息  ", 20))
        assertEquals("1234", NotificationContentExtractor.sanitize("123456", 4))
    }

    @Test fun sanitizerDropsBlankContent() {
        assertNull(NotificationContentExtractor.sanitize(" \n\t ", 20))
        assertNull(NotificationContentExtractor.sanitize(null, 20))
    }

    @Test fun visibleBodyKeepsDistinctLinesWithoutRepeatingTitle() {
        assertEquals(
            "第一条 · 第二条",
            NotificationContentExtractor.combineVisibleBody(
                listOf("标题", "第一条", "第一条", "第二条"),
                title = "标题",
            ),
        )
    }
}
