package com.twentyfourpi.lifelog.export

import org.junit.Assert.assertEquals
import org.junit.Test

class CsvEscaperTest {
    @Test fun `quotes and flattens ordinary text`() {
        assertEquals("\"一条 \"\"通知\"\"\"", CsvEscaper.cell("一条\n\"通知\""))
    }

    @Test fun `neutralizes spreadsheet formula prefixes`() {
        assertEquals("\"'=HYPERLINK(\"\"https://example.com\"\")\"", CsvEscaper.cell("=HYPERLINK(\"https://example.com\")"))
        assertEquals("\"'+SUM(1,2)\"", CsvEscaper.cell("+SUM(1,2)"))
        assertEquals("\"普通文本\"", CsvEscaper.cell("普通文本"))
    }
}
