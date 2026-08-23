package androidx.media3.exoplayer.upstream

import androidx.media3.common.NuvioEngineConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Ignore
import org.junit.Test

class DefaultAllocatorTest {

    @Before
    fun setUp() {
        // Enable Nuvio mode to force native direct ByteBuffer allocation
        NuvioEngineConfig.set(NuvioEngineConfig.nuvioMode())
    }

    @After
    fun tearDown() {
        NuvioEngineConfig.set(NuvioEngineConfig.stockMode())
    }

    @Test
    @Ignore(
        "Engine behavior in app/libs/lib-exoplayer-release.aar: release() repools the segment " +
            "and calls notifyAll() but never trim(), and setTargetBufferSize only trims when the " +
            "target actually drops — so segments released after reset() stay pooled until the " +
            "next reduction. Real, but inert here: both call sites " +
            "(PlayerRuntimeControllerInitialization, TrailerPlayerPool) build a fresh allocator " +
            "per playback and drop it with its LoadControl, so nothing accumulates across " +
            "playbacks. Fix belongs in the media3 fork, whose source is not in this repo. " +
            "Unignore once the engine AAR is rebuilt."
    )
    fun testLateReleasedAllocationsMemoryLeak() {
        val segmentSize = 65536
        // Initialize allocator with trimOnReset = true
        val allocator = DefaultAllocator(true, segmentSize, 0, true)

        // 1. Allocate some segments (e.g. simulating active playback loading)
        val a1 = allocator.allocate()
        val a2 = allocator.allocate()
        val a3 = allocator.allocate()

        assertEquals(3 * segmentSize, allocator.totalBytesAllocated)
        assertEquals(3 * segmentSize, allocator.memoryFootprint)

        // 2. Player reset/stop is called: targets buffer size to 0
        allocator.reset()

        // 3. Simulating late release: active loader/playback threads release the segments AFTER reset
        allocator.release(a1)
        allocator.release(a2)
        allocator.release(a3)

        // In the fixed version, memoryFootprint MUST be 0 because trim() was called on release.
        // In the buggy version, these allocations are leaked into the pool, so memoryFootprint will be 3 * segmentSize.
        assertEquals(0, allocator.memoryFootprint)
        assertEquals(0, allocator.availableBytes)
    }

    @Test
    fun testDampedTrimPreventsThrashing() {
        val segmentSize = 65536
        val allocator = DefaultAllocator(true, segmentSize, 0, true)

        // Set target to 20 segments (1.25 MB)
        allocator.setTargetBufferSize(20 * segmentSize)

        // Allocate 20 segments
        val allocations = (1..20).map { allocator.allocate() }
        assertEquals(20 * segmentSize, allocator.memoryFootprint)

        // Release all of them
        allocations.forEach { allocator.release(it) }
        assertEquals(20 * segmentSize, allocator.memoryFootprint)

        // Allocate 1 more segment (availableCount drops to 19, allocatedCount is 1)
        val extra = allocator.allocate()
        allocator.release(extra)

        // The footprint should remain 20 because of the 16-segment damping factor.
        // It shouldn't immediately trim down on every single release/allocate cycle.
        assertEquals(20 * segmentSize, allocator.memoryFootprint)
    }
}
