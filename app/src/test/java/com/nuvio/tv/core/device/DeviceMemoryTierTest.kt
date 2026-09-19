package com.nuvio.tv.core.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceMemoryTierTest {

    @Test
    fun unknownRamCountsAsConstrainedOnBothCuts() {
        assertTrue(DeviceMemoryTier.computeIsLowRam(totalRamMb = 0, isLowRamDevice = false))
        assertTrue(DeviceMemoryTier.computeIsConstrained(totalRamMb = 0, isLowRamDevice = false))
    }

    @Test
    fun twoGigBoxPaysSafetyButNotComfort() {
        // A 2GB box reports ~1.8GB: clamped buffers, but full cache and fan-out.
        assertFalse(DeviceMemoryTier.computeIsLowRam(1800, isLowRamDevice = false))
        assertTrue(DeviceMemoryTier.computeIsConstrained(1800, isLowRamDevice = false))
    }

    @Test
    fun cutsSitWhereTheyAreDocumented() {
        assertTrue(DeviceMemoryTier.computeIsLowRam(1600, isLowRamDevice = false))
        assertFalse(DeviceMemoryTier.computeIsLowRam(1601, isLowRamDevice = false))
        assertTrue(DeviceMemoryTier.computeIsConstrained(2560, isLowRamDevice = false))
        assertFalse(DeviceMemoryTier.computeIsConstrained(2561, isLowRamDevice = false))
    }

    @Test
    fun isLowRamDeviceWinsOverAmpleRamOnBothCuts() {
        assertTrue(DeviceMemoryTier.computeIsLowRam(8192, isLowRamDevice = true))
        assertTrue(DeviceMemoryTier.computeIsConstrained(8192, isLowRamDevice = true))
    }
}
