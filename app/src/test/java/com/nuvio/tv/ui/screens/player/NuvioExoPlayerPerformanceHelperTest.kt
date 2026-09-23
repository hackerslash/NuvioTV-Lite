package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.ui.screens.player.NuvioExoPlayerPerformanceHelper.getFriendlyRamLabel
import com.nuvio.tv.ui.screens.player.NuvioExoPlayerPerformanceHelper.getSafeNativeMemoryLimitMb
import com.nuvio.tv.ui.screens.player.NuvioExoPlayerPerformanceHelper.getWarningNativeMemoryLimitMb
import org.junit.Assert.assertEquals
import org.junit.Test

class NuvioExoPlayerPerformanceHelperTest {

    private val gb = 1024L * 1024L * 1024L

    // Reported totalMem runs up to 20% under the marketed size, hence 0.9 for a 1GB box.
    private fun reported(gigabytes: Double): Long = (gigabytes * gb).toLong()

    @Test
    fun `friendly ram label follows reported totalMem`() {
        assertEquals("Unknown", getFriendlyRamLabel(0L))
        assertEquals("1 GB", getFriendlyRamLabel(reported(0.9)))
        assertEquals("1.5 GB", getFriendlyRamLabel(reported(1.3)))
        assertEquals("2 GB", getFriendlyRamLabel(reported(1.7)))
        assertEquals("3 GB", getFriendlyRamLabel(reported(2.6)))
        assertEquals("4 GB", getFriendlyRamLabel(reported(3.6)))
        assertEquals("6 GB", getFriendlyRamLabel(reported(5.4)))
        assertEquals("8 GB", getFriendlyRamLabel(reported(7.4)))
        assertEquals("12 GB", getFriendlyRamLabel(reported(11.0)))
        assertEquals("16 GB", getFriendlyRamLabel(reported(14.8)))
    }

    @Test
    fun `safe native memory limit follows reported totalMem`() {
        assertEquals(200, getSafeNativeMemoryLimitMb(0L))
        assertEquals(100, getSafeNativeMemoryLimitMb(reported(0.9)))
        assertEquals(200, getSafeNativeMemoryLimitMb(reported(1.3)))
        assertEquals(200, getSafeNativeMemoryLimitMb(reported(1.7)))
        assertEquals(500, getSafeNativeMemoryLimitMb(reported(2.6)))
        assertEquals(1000, getSafeNativeMemoryLimitMb(reported(3.6)))
        assertEquals(1600, getSafeNativeMemoryLimitMb(reported(5.4)))
        assertEquals(2000, getSafeNativeMemoryLimitMb(reported(14.8)))
    }

    @Test
    fun `warning native memory limit follows reported totalMem`() {
        assertEquals(260, getWarningNativeMemoryLimitMb(0L))
        assertEquals(130, getWarningNativeMemoryLimitMb(reported(0.9)))
        assertEquals(260, getWarningNativeMemoryLimitMb(reported(1.3)))
        assertEquals(260, getWarningNativeMemoryLimitMb(reported(1.7)))
        assertEquals(650, getWarningNativeMemoryLimitMb(reported(2.6)))
        assertEquals(1200, getWarningNativeMemoryLimitMb(reported(3.6)))
        assertEquals(2000, getWarningNativeMemoryLimitMb(reported(5.4)))
        assertEquals(2500, getWarningNativeMemoryLimitMb(reported(14.8)))
    }

    @Test
    fun `test buildLoadControl deducts chunkOverheadMb from targetBufferSizeMb`() {
        val enabledField = NuvioExoPlayerPerformanceHelper::class.java.getDeclaredField("enabled")
        enabledField.isAccessible = true
        enabledField.setBoolean(NuvioExoPlayerPerformanceHelper, true)
        NuvioExoPlayerPerformanceHelper.targetBufferSizeMb = 250

        // Overhead = 96 MB -> target should be 250 - 96 = 154 MB
        val loadControl = NuvioExoPlayerPerformanceHelper.buildLoadControl(chunkOverheadMb = 96)
        val targetBufferBytesField = androidx.media3.exoplayer.DefaultLoadControl::class.java.getDeclaredField("targetBufferBytesOverwrite")
        targetBufferBytesField.isAccessible = true
        val targetBytes = targetBufferBytesField.getInt(loadControl)

        assertEquals(154 * 1024 * 1024, targetBytes)
    }

    @Test
    fun `test buildLoadControl respects MIN_BUFFER_MB floor`() {
        val enabledField = NuvioExoPlayerPerformanceHelper::class.java.getDeclaredField("enabled")
        enabledField.isAccessible = true
        enabledField.setBoolean(NuvioExoPlayerPerformanceHelper, true)
        NuvioExoPlayerPerformanceHelper.targetBufferSizeMb = 50

        // Overhead = 100 MB -> 50 - 100 = -50 -> coerceAtLeast MIN_BUFFER_MB (25 MB)
        val loadControl = NuvioExoPlayerPerformanceHelper.buildLoadControl(chunkOverheadMb = 100)
        val targetBufferBytesField = androidx.media3.exoplayer.DefaultLoadControl::class.java.getDeclaredField("targetBufferBytesOverwrite")
        targetBufferBytesField.isAccessible = true
        val targetBytes = targetBufferBytesField.getInt(loadControl)

        assertEquals(com.nuvio.tv.ui.screens.settings.MemoryBudget.MIN_BUFFER_MB * 1024 * 1024, targetBytes)
    }
}
