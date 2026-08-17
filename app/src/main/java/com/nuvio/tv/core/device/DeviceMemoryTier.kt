package com.nuvio.tv.core.device

import android.app.ActivityManager
import android.content.Context

/**
 * Single source of truth for the device memory tier.
 *
 * Physical RAM decides the tier, not heap size: `largeHeap` reports the same
 * ~512MB from [Runtime.maxMemory] on a 2GB box as on an 8GB one, so heap size
 * cannot tell them apart. [init] must run before the first read (see
 * NuvioApplication.onCreate); until then the old heap heuristic stands in so
 * JVM unit tests keep working without a Context.
 */
object DeviceMemoryTier {
    /** 2GB devices report ~1.8GB of totalMem, so the cut sits above that. */
    private const val LOW_RAM_THRESHOLD_MB = 2560L
    private const val FALLBACK_HEAP_THRESHOLD_MB = 512L

    private class Tier(val totalRamMb: Long, val isLowRam: Boolean)

    @Volatile
    private var tier: Tier? = null

    fun init(context: Context) {
        if (tier != null) return
        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val totalRamMb = memoryInfo.totalMem / (1024L * 1024L)
        tier = Tier(
            totalRamMb = totalRamMb,
            // isLowRamDevice is opt-in and false on most 2GB TV boxes, so the
            // physical-RAM cut has to back it up.
            isLowRam = activityManager.isLowRamDevice ||
                (totalRamMb in 1..LOW_RAM_THRESHOLD_MB)
        )
    }

    /** Physical RAM in MB, or 0 when [init] has not run. */
    val totalRamMb: Long
        get() = tier?.totalRamMb ?: 0L

    val isLowRam: Boolean
        get() = tier?.isLowRam
            ?: (Runtime.getRuntime().maxMemory() / (1024L * 1024L) < FALLBACK_HEAP_THRESHOLD_MB)

    /**
     * Ceiling on addon stream fetches running at once. Each one holds a response body, its
     * parsed DTOs and the mapped stream list simultaneously, so a user with 20 stream addons
     * previously peaked at 20 of those at once.
     * ponytail: flat per-tier numbers, measure with `adb shell dumpsys meminfo` before tuning.
     */
    val streamFetchConcurrency: Int
        get() = if (isLowRam) 3 else 8
}
