package com.nuvio.tv.core.telegram

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TelegramStorageManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "TelegramStorage"
        private const val TG_FILES_DIR = "tdlib_files"
        private const val CAP_BYTES = 1_200L * 1024L * 1024L
        private const val SOFT_WATERMARK = 0.85
        private const val MIN_FREE_BYTES = 700L * 1024L * 1024L
        private const val TARGET_FREE_BYTES = 900L * 1024L * 1024L
        private const val TARGET_CAP_BYTES = 900L * 1024L * 1024L
        private const val TRIM_COOLDOWN_MS = 15_000L
    }

    @Volatile
    private var lastTrimMs: Long = 0L

    data class TrimResult(
        val scannedBytes: Long,
        val deletedBytes: Long,
        val deletedFiles: Int,
        val freeBytesAfter: Long,
        val trimmed: Boolean
    )

    fun maybeTrim(reason: String, protectedPath: String? = null): TrimResult {
        val now = System.currentTimeMillis()
        if (now - lastTrimMs < TRIM_COOLDOWN_MS) {
            return TrimResult(
                scannedBytes = 0L,
                deletedBytes = 0L,
                deletedFiles = 0,
                freeBytesAfter = context.filesDir.usableSpace,
                trimmed = false
            )
        }

        val dir = File(context.filesDir, TG_FILES_DIR)
        if (!dir.exists()) {
            return TrimResult(
                scannedBytes = 0L,
                deletedBytes = 0L,
                deletedFiles = 0,
                freeBytesAfter = context.filesDir.usableSpace,
                trimmed = false
            )
        }

        val files = dir.walkTopDown().filter { it.isFile }.toList()
        val totalBytes = files.sumOf { it.length().coerceAtLeast(0L) }
        val freeBefore = context.filesDir.usableSpace

        val overCap = totalBytes > (CAP_BYTES * SOFT_WATERMARK).toLong()
        val lowFree = freeBefore in 1 until MIN_FREE_BYTES
        if (!overCap && !lowFree) {
            return TrimResult(
                scannedBytes = totalBytes,
                deletedBytes = 0L,
                deletedFiles = 0,
                freeBytesAfter = freeBefore,
                trimmed = false
            )
        }

        lastTrimMs = now
        val protectedAbs = protectedPath?.let { File(it).absolutePath }
        val candidates = files
            .asSequence()
            .filterNot { f -> protectedAbs != null && f.absolutePath == protectedAbs }
            .sortedBy { it.lastModified() }
            .toList()

        var deletedBytes = 0L
        var deletedFiles = 0
        var remainingBytes = totalBytes

        for (file in candidates) {
            val freeNow = context.filesDir.usableSpace
            val capOk = remainingBytes <= TARGET_CAP_BYTES
            val freeOk = freeNow >= TARGET_FREE_BYTES
            if (capOk && freeOk) break

            val len = file.length().coerceAtLeast(0L)
            if (file.delete()) {
                deletedBytes += len
                deletedFiles += 1
                remainingBytes = (remainingBytes - len).coerceAtLeast(0L)
                cleanupEmptyParents(file, dir)
            }
        }

        val freeAfter = context.filesDir.usableSpace
        if (deletedFiles > 0) {
            Log.i(
                TAG,
                "TRIM reason=$reason deletedFiles=$deletedFiles freed=${deletedBytes / 1048576}MB totalBefore=${totalBytes / 1048576}MB freeAfter=${freeAfter / 1048576}MB"
            )
        }

        return TrimResult(
            scannedBytes = totalBytes,
            deletedBytes = deletedBytes,
            deletedFiles = deletedFiles,
            freeBytesAfter = freeAfter,
            trimmed = deletedFiles > 0
        )
    }

    fun clearAllDownloads(): Long {
        val dir = File(context.filesDir, TG_FILES_DIR)
        if (!dir.exists()) return 0L
        val total = dir.walkTopDown().filter { it.isFile }.sumOf { it.length().coerceAtLeast(0L) }
        runCatching {
            dir.listFiles()?.forEach { it.deleteRecursively() }
        }
        Log.i(TAG, "CLEAR ALL downloads freed=${total / 1048576}MB")
        return total
    }

    private fun cleanupEmptyParents(file: File, root: File) {
        var parent = file.parentFile
        while (parent != null && parent != root) {
            val children = parent.listFiles()
            if (children.isNullOrEmpty()) {
                parent.delete()
                parent = parent.parentFile
            } else {
                return
            }
        }
    }
}
