package com.nuvio.tv.core.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceMemoryTierTest {

    @Test
    fun unknownRamCountsAsLowRam() {
        // Regression guard: this used to report high-RAM and hand a 2GB box the 8GB budgets.
        assertTrue(DeviceMemoryTier.computeIsLowRam(totalRamMb = 0, isLowRamDevice = false))
    }

    @Test
    fun physicalRamDecidesTheTier() {
        // A 2GB box reports ~1.8GB of totalMem.
        assertTrue(DeviceMemoryTier.computeIsLowRam(1800, isLowRamDevice = false))
        assertTrue(DeviceMemoryTier.computeIsLowRam(2560, isLowRamDevice = false))
        assertFalse(DeviceMemoryTier.computeIsLowRam(2561, isLowRamDevice = false))
    }

    @Test
    fun isLowRamDeviceWinsOverAmpleRam() {
        assertTrue(DeviceMemoryTier.computeIsLowRam(8192, isLowRamDevice = true))
    }
}
