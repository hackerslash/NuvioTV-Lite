package com.nuvio.tv.core.device

import android.app.ActivityManager
import android.content.Context
import com.nuvio.tv.core.build.AppFeaturePolicy
import java.io.File

/**
 * Single source of truth for the device memory tier.
 *
 * Physical RAM decides the tier, not heap size: `largeHeap` reports the same
 * ~512MB from [Runtime.maxMemory] on a 2GB box as on an 8GB one, so heap size
 * cannot tell them apart. [init] runs from NuvioApplication.onCreate before any
 * injected singleton can read it. An unknown tier — unread, or a device reporting
 * 0 — counts as low-RAM: over-budgeting a 2GB box gets the process LMK-killed,
 * under-budgeting an 8GB one does not.
 */
object DeviceMemoryTier {
    /**
     * 2GB devices report ~1.8GB of totalMem, so the cut sits above that. Deliberately disagrees
     * with the perf-mode ladder's 2.3GB cut: that one governs native buffers, and it persists.
     */
    private const val LOW_RAM_THRESHOLD_MB = 2560L
    private const val BYTES_PER_MB = 1024L * 1024L

    private class Tier(val totalRamBytes: Long, val isLowRam: Boolean)

    @Volatile
    private var tier: Tier? = null

    fun init(context: Context) {
        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val totalRamBytes = activityManager
            ?.let { ActivityManager.MemoryInfo().also(it::getMemoryInfo).totalMem }
            ?.takeIf { it > 0L }
            ?: ramFromMemInfo()
        tier = Tier(
            totalRamBytes = totalRamBytes,
            isLowRam = computeIsLowRam(
                totalRamMb = totalRamBytes / BYTES_PER_MB,
                // isLowRamDevice is opt-in and false on most 2GB TV boxes, so the
                // physical-RAM cut has to back it up.
                isLowRamDevice = activityManager?.isLowRamDevice == true
            )
        )
    }

    internal fun computeIsLowRam(totalRamMb: Long, isLowRamDevice: Boolean): Boolean =
        isLowRamDevice || totalRamMb <= LOW_RAM_THRESHOLD_MB

    /** Physical RAM in bytes, or 0 when [init] has not run. */
    val totalRamBytes: Long
        get() = tier?.totalRamBytes ?: 0L

    val totalRamMb: Long
        get() = totalRamBytes / BYTES_PER_MB

    val isLowRam: Boolean
        get() = tier?.isLowRam ?: true

    /**
     * Whether the memory cuts apply: the Lite edition always, plus any build running on a
     * low-tier device, so a full build on a 2GB box gets Lite's cuts too.
     */
    val lowMemoryProfile: Boolean
        get() = AppFeaturePolicy.liteMode || isLowRam

    /**
     * Ceiling on addon stream fetches running at once. Each one holds a response body, its
     * parsed DTOs and the mapped stream list simultaneously, so a user with 20 stream addons
     * previously peaked at 20 of those at once.
     * ponytail: flat per-tier numbers, measure with `adb shell dumpsys meminfo` before tuning.
     */
    val streamFetchConcurrency: Int
        get() = if (isLowRam) 3 else 8

    /** Fallback for when ActivityManager is unavailable or reports 0. */
    private fun ramFromMemInfo(): Long = try {
        File("/proc/meminfo").useLines { lines ->
            lines.firstOrNull { it.startsWith("MemTotal") }
                ?.let { Regex("\\d+").find(it)?.value?.toLong()?.times(1024L) }
                ?: 0L
        }
    } catch (_: Exception) {
        0L
    }
}
