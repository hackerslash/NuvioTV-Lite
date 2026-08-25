package com.nuvio.tv.core.telegram

import org.junit.Assert.assertEquals
import org.junit.Test

class TelegramRangeParserTest {

    private val totalSize = 1000L

    @Test
    fun `null header returns full range`() {
        assertEquals(0L to 999L, TelegramRangeParser.parse(null, totalSize))
    }

    @Test
    fun `open ended range clamps to file end`() {
        assertEquals(200L to 999L, TelegramRangeParser.parse("bytes=200-", totalSize))
    }

    @Test
    fun `bounded range is respected`() {
        assertEquals(100L to 199L, TelegramRangeParser.parse("bytes=100-199", totalSize))
    }

    @Test
    fun `end beyond file size is clamped`() {
        assertEquals(900L to 999L, TelegramRangeParser.parse("bytes=900-5000", totalSize))
    }

    @Test
    fun `suffix range returns last N bytes`() {
        assertEquals(600L to 999L, TelegramRangeParser.parse("bytes=-400", totalSize))
    }

    @Test
    fun `suffix larger than file returns whole file`() {
        assertEquals(0L to 999L, TelegramRangeParser.parse("bytes=-99999", totalSize))
    }

    @Test
    fun `start beyond size falls back to full range`() {
        assertEquals(0L to 999L, TelegramRangeParser.parse("bytes=2000-", totalSize))
    }

    @Test
    fun `malformed header falls back to full range`() {
        assertEquals(0L to 999L, TelegramRangeParser.parse("chunks=1-2", totalSize))
        assertEquals(0L to 999L, TelegramRangeParser.parse("bytes=abc", totalSize))
    }
}
