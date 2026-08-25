package com.nuvio.tv.core.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramBufferPolicyTest {

    private val plentySpace = 10L * 1024 * 1024 * 1024

    @Test
    fun `low storage clamps to minimum window`() {
        assertEquals(
            TelegramBufferPolicy.LOW_STORAGE_PREFETCH_BYTES,
            TelegramBufferPolicy.prefetchBytes(
                totalSize = 4L * 1024 * 1024 * 1024,
                usableSpace = 100L * 1024 * 1024
            )
        )
    }

    @Test
    fun `unknown size uses default window`() {
        assertEquals(
            TelegramBufferPolicy.DEFAULT_PREFETCH_BYTES,
            TelegramBufferPolicy.prefetchBytes(totalSize = 0L, usableSpace = plentySpace)
        )
    }

    @Test
    fun `window stays within configured bounds`() {
        val tinyFile = 512L * 1024 * 1024 // ~90min of <1 Mbps
        val window = TelegramBufferPolicy.prefetchBytes(tinyFile, plentySpace)

        assertTrue(window in TelegramBufferPolicy.MIN_PREFETCH_BYTES..
            TelegramBufferPolicy.MAX_PREFETCH_BYTES)
    }

    @Test
    fun `window never exceeds file size`() {
        val smallFile = 3L * 1024 * 1024
        val window = TelegramBufferPolicy.prefetchBytes(smallFile, plentySpace)

        assertTrue(window <= smallFile)
    }
}
