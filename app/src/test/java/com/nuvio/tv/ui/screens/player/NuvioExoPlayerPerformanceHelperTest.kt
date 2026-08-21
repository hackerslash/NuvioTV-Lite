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
        assertEquals(250, getSafeNativeMemoryLimitMb(0L))
        assertEquals(150, getSafeNativeMemoryLimitMb(reported(0.9)))
        assertEquals(200, getSafeNativeMemoryLimitMb(reported(1.3)))
        assertEquals(250, getSafeNativeMemoryLimitMb(reported(1.7)))
        assertEquals(500, getSafeNativeMemoryLimitMb(reported(2.6)))
        assertEquals(1000, getSafeNativeMemoryLimitMb(reported(3.6)))
        assertEquals(1600, getSafeNativeMemoryLimitMb(reported(5.4)))
        assertEquals(2000, getSafeNativeMemoryLimitMb(reported(14.8)))
    }

    @Test
    fun `warning native memory limit follows reported totalMem`() {
        assertEquals(325, getWarningNativeMemoryLimitMb(0L))
        assertEquals(180, getWarningNativeMemoryLimitMb(reported(0.9)))
        assertEquals(250, getWarningNativeMemoryLimitMb(reported(1.3)))
        assertEquals(325, getWarningNativeMemoryLimitMb(reported(1.7)))
        assertEquals(650, getWarningNativeMemoryLimitMb(reported(2.6)))
        assertEquals(1200, getWarningNativeMemoryLimitMb(reported(3.6)))
        assertEquals(2000, getWarningNativeMemoryLimitMb(reported(5.4)))
        assertEquals(2500, getWarningNativeMemoryLimitMb(reported(14.8)))
    }
}
