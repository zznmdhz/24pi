package com.twentyfourpi.lifelog.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AppLabelResolverTest {
    @Test fun `known packages retain familiar application names`() {
        assertEquals("微信", knownPackageLabel("com.tencent.mm"))
        assertEquals("哔哩哔哩", knownPackageLabel("tv.danmaku.bili"))
    }

    @Test fun `fallback never exposes a full package or host`() {
        val label = humanReadablePackageFallback("www.reader.example.com")

        assertEquals("Example", label)
        assertFalse(label.contains('.'))
    }

    @Test fun `empty package has an honest neutral fallback`() {
        assertEquals("未知应用", humanReadablePackageFallback(""))
    }
}
