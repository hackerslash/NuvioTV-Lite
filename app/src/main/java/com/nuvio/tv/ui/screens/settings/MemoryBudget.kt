package com.nuvio.tv.ui.screens.settings

import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.core.device.DeviceMemoryTier
import com.nuvio.tv.data.local.BufferSettings

/**
 * Shared memory budget constants and helpers for buffer + parallel connection settings.
 * Used by both PlaybackSettingsViewModel and PlaybackBufferNetworkSettings UI.
 */
@UnstableApi
object MemoryBudget {
    const val TAG = "MemoryBudget"

    // Heap-tiered budget ratios. Low-RAM devices (Fire TV class) need more
    // headroom for codec/surface/UI; high-RAM devices can dedicate more to buffering.
    private const val LOW_HEAP_RATIO = 0.65
    private const val HIGH_HEAP_RATIO = 0.85
    private const val HIGH_HEAP_THRESHOLD_MB = 512L
    private const val ONE_GB_CLASS_MAX_BYTES = (1.15 * 1024 * 1024 * 1024).toLong()
    // The buffer allocator is on the Java heap; on low-RAM reserve a slice for the UI/decoder/caches
    // and give the rest to the buffer (a flat % of max heap overcommits and starves them).
    private const val LOW_HEAP_RESERVE_MB = 210L
    // Hard ceiling on buffer memory for low-RAM devices. The heap ratio alone is not enough:
    // largeHeap reports the same ~512MB heap on a 2GB box as on an 8GB one, so the ratio would
    // hand a 2GB device ~435MB of buffers and get the process LMK-killed well before any OOM.
    private const val LOW_RAM_BUFFER_CEILING_MB = 250

    // Conversion overhead tracks the video rather than the device, so these only hold back a margin;
    // high-RAM keeps a larger one because it still retains a back buffer during conversion.
    private const val LOW_RAM_CONVERSION_RATIO = 0.95f
    private const val HIGH_RAM_CONVERSION_RATIO = 0.75f

    private const val BUFFER_OVERHEAD = 2
    private const val PREFETCH_DEPTH_LOWER_MULTIPLE = 2
    private const val PREFETCH_DEPTH_UPPER_MULTIPLE = 4

    const val MIN_CONNECTIONS = 2
    const val MAX_CONNECTIONS = 4
    const val MIN_CHUNK_MB = 8
    const val MAX_CHUNK_MB = 128
    // Low-RAM devices (1-2 GB class): the session's protected set (playhead
    // window + tail moov chunks + pinned side chunks) puts the worst-case
    // retained floor at roughly connections + 10 chunks on a scatter-heavy
    // file, so chunk size is hard-capped at 16 MB on that tier — above it
    // the floor alone exceeds what those devices can hold. Applies in
    // performance mode too; the cap is a tier property, not a budget one.
    private const val LOW_RAM_MAX_CHUNK_MB = 16
    const val BUFFER_STEP_MB = 25
    const val MIN_BUFFER_MB = 25
    // Half the device default floors the slider above what a low-RAM stick can sustain.
    const val MIN_TARGET_BUFFER_MB = 50
    private const val MIN_PLAYBACK_BUDGET_MB = 100
    const val MAX_BUFFER_MB = 1024 * 4
    private const val DEFAULT_EFFECTIVE_BUFFER_MB = 50

    val defaultBufferSizeMb: Int = if (BufferSettings.DEFAULT_TARGET_BUFFER_SIZE_MB > 0) {
        BufferSettings.DEFAULT_TARGET_BUFFER_SIZE_MB
    } else {
        DEFAULT_EFFECTIVE_BUFFER_MB
    }

    /** Everything derived from the device's memory, fixed for the life of the process. */
    internal data class Snapshot(
        /** Heap the budgets are taken from; half of maxMemory() on a 1 GB device with a 512 MB+ largeHeap. */
        val heapMb: Long,
        val isConstrainedTier: Boolean,
        val budgetMb: Int,
        val conversionBudgetMb: Int,
        val tierMaxChunkMb: Int,
    )

    @Volatile
    private var snapshot: Snapshot? = null

    @Volatile
    private var loggedReadBeforeInit = false

    /**
     * Takes the device's memory snapshot once. Must run before the budget is first read
     * (Application.onCreate, before super.onCreate(), after DeviceMemoryTier.init).
     */
    fun init() {
        snapshot = snapshotFor(
            maxHeapMb = runtimeMaxHeapMb(),
            physicalRamBytes = DeviceMemoryTier.totalRamBytes,
            constrainedDevice = DeviceMemoryTier.isConstrained,
        )
        Log.i(TAG, "Memory budget: $snapshot")
    }

    // A read before init() gets the max-heap budget for this read only, so a later init() still applies.
    private val current: Snapshot
        get() = snapshot ?: run {
            if (!loggedReadBeforeInit) {
                loggedReadBeforeInit = true
                Log.w(TAG, "Memory budget read before init(); using maxMemory() without the RAM check")
            }
            snapshotFor(runtimeMaxHeapMb(), physicalRamBytes = 0L)
        }

    /** Heap the budgets and the image cache are sized from. */
    val heapMb: Long get() = current.heapMb

    /** Every knob here is allocation safety, so it keys on the higher cut, not the comfort one. */
    val isConstrainedTier: Boolean get() = current.isConstrainedTier

    val budgetMb: Int get() = current.budgetMb

    val conversionBudgetMb: Int get() = current.conversionBudgetMb

    /** Hard chunk-size ceiling for this device tier; binds everywhere, including performance mode. */
    val tierMaxChunkMb: Int get() = current.tierMaxChunkMb

    private fun runtimeMaxHeapMb(): Long = Runtime.getRuntime().maxMemory() / (1024L * 1024L)

    // constrainedDevice comes from physical RAM, not the heap: largeHeap reports the same ~512MB
    // on a 2GB box as on an 8GB one, and the heap cut below only catches the 1GB class. Unknown
    // defaults to constrained, so a read before init() and the tests both take the safe tier.
    internal fun snapshotFor(
        maxHeapMb: Long,
        physicalRamBytes: Long,
        constrainedDevice: Boolean = true,
    ): Snapshot {
        val heapMb = budgetHeapMb(maxHeapMb, physicalRamBytes)
        val constrained = constrainedDevice || heapMb < HIGH_HEAP_THRESHOLD_MB
        // Pre-cap ratio budget, before the low-RAM reserve trims it below.
        val rawBudgetMb = (heapMb * (if (constrained) LOW_HEAP_RATIO else HIGH_HEAP_RATIO)).toInt()
        val budgetMb = computeBudgetMb(rawBudgetMb, heapMb, constrained)
        val conversionBudgetMb = (budgetMb * (if (constrained) LOW_RAM_CONVERSION_RATIO else HIGH_RAM_CONVERSION_RATIO)).toInt()
            .coerceAtMost(budgetMb).coerceAtLeast(MIN_BUFFER_MB)
        return Snapshot(
            heapMb = heapMb,
            isConstrainedTier = constrained,
            budgetMb = budgetMb,
            conversionBudgetMb = conversionBudgetMb,
            tierMaxChunkMb = if (constrained) LOW_RAM_MAX_CHUNK_MB else MAX_CHUNK_MB,
        )
    }

    internal fun computeBudgetMb(rawBudgetMb: Int, maxHeapMb: Long, isConstrainedTier: Boolean): Int =
        if (isConstrainedTier) {
            rawBudgetMb
                .coerceAtMost((maxHeapMb - LOW_HEAP_RESERVE_MB).toInt())
                .coerceAtMost(LOW_RAM_BUFFER_CEILING_MB)
                // The flat reserve collapses the budget on small heaps, so hold a floor that still
                // leaves the smallest devices half their heap.
                .coerceAtLeast(
                    MIN_PLAYBACK_BUDGET_MB.coerceAtMost((maxHeapMb / 2).toInt()).coerceAtLeast(MIN_BUFFER_MB)
                )
        } else {
            rawBudgetMb
        }

    // Some 1 GB TVs ship dalvik.vm.heapsize=512m, so largeHeap reports a high-RAM heap on a device
    // that can't back it. Only that case is resized: half the heap, the same 1 GB class as
    // NuvioExoPlayerPerformanceHelper.getSafeNativeMemoryLimitMb().
    internal fun budgetHeapMb(maxHeapMb: Long, physicalRamBytes: Long): Long =
        if (maxHeapMb >= HIGH_HEAP_THRESHOLD_MB && physicalRamBytes in 1 until ONE_GB_CLASS_MAX_BYTES) {
            maxHeapMb / 2
        } else {
            maxHeapMb
        }

    fun effectiveBufferMb(stored: Int): Int =
        if (stored > 0) stored else defaultBufferSizeMb


    fun bufferCount(connectionCount: Int): Int =
        connectionCount + BUFFER_OVERHEAD

    fun parallelOverheadMb(connectionCount: Int, chunkSizeMb: Int): Int =
        bufferCount(connectionCount) * chunkSizeMb

    fun totalUsageMb(bufferMb: Int, connectionCount: Int, chunkSizeMb: Int, parallelEnabled: Boolean): Int =
        bufferMb + if (parallelEnabled) parallelOverheadMb(connectionCount, chunkSizeMb) else 0

    fun prefetchDepthChunks(
        connections: Int,
        chunkSizeMb: Int,
        safeNativeLimitMb: Int,
        reserveBufferMb: Int,
    ): Int {
        val chunkMb = chunkSizeMb.coerceAtLeast(1)
        val chunkBudgetMb = (safeNativeLimitMb - reserveBufferMb.coerceAtLeast(0))
            .coerceAtLeast(chunkMb * PREFETCH_DEPTH_LOWER_MULTIPLE)
        val byBudget = chunkBudgetMb / chunkMb
        return byBudget.coerceIn(
            connections * PREFETCH_DEPTH_LOWER_MULTIPLE,
            connections * PREFETCH_DEPTH_UPPER_MULTIPLE
        )
    }

    /** Max chunk size that fits budget given current buffer size */
    fun maxChunkMb(bufferMb: Int, connectionCount: Int): Int =
        ((budgetMb - bufferMb) / bufferCount(connectionCount)).coerceIn(MIN_CHUNK_MB, tierMaxChunkMb)

    /** Max buffer size that fits budget given current parallel overhead */
    fun maxBufferMb(parallelOverheadMb: Int): Int =
        ((budgetMb - parallelOverheadMb) / BUFFER_STEP_MB * BUFFER_STEP_MB)
            .coerceIn(MIN_BUFFER_MB, MAX_BUFFER_MB)

    /**
     * Perf mode lets the UI store 16 connections x 128MB chunks; those are direct buffers, so
     * low-RAM devices keep the budget-aware caps. Returns (connectionCount, chunkKb).
     */
    fun clampParallel(
        connectionCount: Int,
        chunkKb: Int,
        bufferMb: Int,
        isConstrainedTier: Boolean
    ): Pair<Int, Int> {
        if (!isConstrainedTier) return connectionCount to chunkKb
        val connections = connectionCount.coerceAtMost(MAX_CONNECTIONS)
        return connections to chunkKb.coerceAtMost(maxChunkMb(bufferMb, connections) * 1024)
    }

    /**
     * Enforce budget: reduce chunk first, then buffer as last resort.
     * Returns (adjustedBufferMb, adjustedChunkMb).
     */
    fun enforce(bufferMb: Int, chunkMb: Int, connectionCount: Int): Pair<Int, Int> {
        val buffers = bufferCount(connectionCount)
        if (bufferMb + buffers * chunkMb <= budgetMb) return bufferMb to chunkMb

        val newChunkMb = maxChunkMb(bufferMb, connectionCount)
        if (bufferMb + buffers * newChunkMb <= budgetMb) return bufferMb to newChunkMb

        // Even min chunk doesn't fit, also reduce buffer
        val newBufferMb = ((budgetMb - buffers * MIN_CHUNK_MB) / BUFFER_STEP_MB * BUFFER_STEP_MB)
            .coerceAtLeast(MIN_BUFFER_MB)
        return newBufferMb to MIN_CHUNK_MB
    }

    /**
     * Determines the memory usage status based on total usage, safe limit, and warning limit.
     */
    fun getUsageStatus(
        totalUsageMb: Int,
        safeLimitMb: Int,
        warningLimitMb: Int
    ): MemoryUsageStatus {
        return when {
            totalUsageMb > warningLimitMb -> MemoryUsageStatus.DANGER
            totalUsageMb > safeLimitMb -> MemoryUsageStatus.WARNING
            else -> MemoryUsageStatus.SAFE
        }
    }
}

@UnstableApi
enum class MemoryUsageStatus {
    SAFE,
    WARNING,
    DANGER
}

