package com.nuvio.tv.ui.screens.settings

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
    // The buffer allocator is on the Java heap; on low-RAM reserve a slice for the UI/decoder/caches
    // and give the rest to the buffer (a flat % of max heap overcommits and starves them).
    private const val LOW_HEAP_RESERVE_MB = 210L
    // Hard ceiling on buffer memory for low-RAM devices. The heap ratio alone is not enough:
    // largeHeap reports the same ~512MB heap on a 2GB box as on an 8GB one, so the ratio would
    // hand a 2GB device ~435MB of buffers and get the process LMK-killed well before any OOM.
    private const val LOW_RAM_BUFFER_CEILING_MB = 250

    /** ParallelRangeDataSource schedules maxAhead = parallelConnections + 1 chunks concurrently */
    private const val BUFFER_OVERHEAD = 2

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
    const val MAX_BUFFER_MB = 1024 * 4
    private const val DEFAULT_EFFECTIVE_BUFFER_MB = 50

    val defaultBufferSizeMb: Int = if (BufferSettings.DEFAULT_TARGET_BUFFER_SIZE_MB > 0) {
        BufferSettings.DEFAULT_TARGET_BUFFER_SIZE_MB
    } else {
        DEFAULT_EFFECTIVE_BUFFER_MB
    }

    private val maxHeapMb: Long = Runtime.getRuntime().maxMemory() / (1024L * 1024L)

    /** True on low-RAM devices (Fire TV / TV-stick class). Keyed on physical RAM, not heap size. */
    val isLowRamTier: Boolean = DeviceMemoryTier.isLowRam

    // Pre-cap ratio budget; conversionBudgetMb derives from this so DV7 headroom isn't cut by the cap.
    private val rawBudgetMb: Int =
        (maxHeapMb * (if (isLowRamTier) LOW_HEAP_RATIO else HIGH_HEAP_RATIO)).toInt()

    val budgetMb: Int = computeBudgetMb(rawBudgetMb, maxHeapMb, isLowRamTier)

    internal fun computeBudgetMb(rawBudgetMb: Int, maxHeapMb: Long, isLowRamTier: Boolean): Int =
        if (isLowRamTier) {
            rawBudgetMb
                .coerceAtMost((maxHeapMb - LOW_HEAP_RESERVE_MB).toInt())
                .coerceAtMost(LOW_RAM_BUFFER_CEILING_MB)
                .coerceAtLeast(MIN_BUFFER_MB)
        } else {
            rawBudgetMb
        }

    // DV7 conversion headroom: a third of the raw budget on low-RAM, half on high-RAM; never above budget.
    val conversionBudgetMb: Int =
        (if (isLowRamTier) rawBudgetMb / 3 else rawBudgetMb / 2)
            .coerceAtMost(budgetMb).coerceAtLeast(MIN_BUFFER_MB)

    fun effectiveBufferMb(stored: Int): Int =
        if (stored > 0) stored else defaultBufferSizeMb

    /** Number of chunk-sized buffers alive concurrently */
    fun bufferCount(connectionCount: Int): Int =
        connectionCount + BUFFER_OVERHEAD

    fun parallelOverheadMb(connectionCount: Int, chunkSizeMb: Int): Int =
        bufferCount(connectionCount) * chunkSizeMb

    fun totalUsageMb(bufferMb: Int, connectionCount: Int, chunkSizeMb: Int, parallelEnabled: Boolean): Int =
        bufferMb + if (parallelEnabled) parallelOverheadMb(connectionCount, chunkSizeMb) else 0

    /** Hard chunk-size ceiling for this device tier; binds everywhere, including performance mode. */
    val tierMaxChunkMb: Int = if (isLowRamTier) LOW_RAM_MAX_CHUNK_MB else MAX_CHUNK_MB

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
        isLowRamTier: Boolean
    ): Pair<Int, Int> {
        if (!isLowRamTier) return connectionCount to chunkKb
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

