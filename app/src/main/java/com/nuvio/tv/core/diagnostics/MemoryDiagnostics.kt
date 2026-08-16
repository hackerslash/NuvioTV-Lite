package com.nuvio.tv.core.diagnostics

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Debug
import android.os.SystemClock
import android.util.Log

/**
 * Lightweight, opt-in memory diagnostics for the low-RAM edition.
 *
 * Logs a PSS breakdown (total / dalvik / native / graphics), Java heap usage and
 * system available memory, plus onTrimMemory pressure levels — enough to measure
 * RAM behaviour on a real 2GB device without attaching a profiler. Snapshots are
 * throttled and everything is a no-op unless [enabled], so it costs nothing in a
 * normal build. Filter logcat with tag "NuvioMem".
 */
object MemoryDiagnostics {
    private const val TAG = "NuvioMem"
    private const val MIN_SNAPSHOT_INTERVAL_MS = 2_000L

    @Volatile
    var enabled: Boolean = false

    @Volatile
    private var lastSnapshotAt = 0L

    /** Logs a memory snapshot, throttled to at most one every 2s. [reason] tags the moment. */
    fun snapshot(context: Context, reason: String) {
        if (!enabled) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastSnapshotAt < MIN_SNAPSHOT_INTERVAL_MS) return
        lastSnapshotAt = now
        runCatching {
            val mi = Debug.MemoryInfo()
            Debug.getMemoryInfo(mi)
            val rt = Runtime.getRuntime()
            val heapUsedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
            val heapMaxMb = rt.maxMemory() / (1024 * 1024)
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val avail = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val graphicsKb = mi.getMemoryStat("summary.graphics")?.toIntOrNull() ?: -1
            Log.i(
                TAG,
                "[$reason] totalPss=${mi.totalPss / 1024}MB " +
                    "dalvik=${mi.dalvikPss / 1024}MB native=${mi.nativePss / 1024}MB " +
                    "graphics=${if (graphicsKb >= 0) graphicsKb / 1024 else -1}MB " +
                    "heap=$heapUsedMb/${heapMaxMb}MB " +
                    "sysAvail=${avail.availMem / (1024 * 1024)}MB lowMem=${avail.lowMemory}"
            )
        }
    }

    fun onTrim(level: Int) {
        if (!enabled) return
        Log.w(TAG, "onTrimMemory level=$level (${trimName(level)})")
    }

    private fun trimName(level: Int): String = when (level) {
        ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "COMPLETE"
        ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "MODERATE"
        ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "BACKGROUND"
        ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "RUNNING_MODERATE"
        else -> "OTHER"
    }
}
