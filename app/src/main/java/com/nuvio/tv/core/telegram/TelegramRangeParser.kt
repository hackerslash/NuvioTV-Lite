package com.nuvio.tv.core.telegram

/**
 * HTTP Range header parsing shared by the streaming proxy.
 * Returns inclusive [start, end] clamped to the file size.
 */
internal object TelegramRangeParser {

    fun parse(header: String?, totalSize: Long): Pair<Long, Long> {
        val fullRange = 0L to (totalSize - 1)
        if (header.isNullOrBlank() || !header.startsWith("bytes=")) return fullRange
        return try {
            val spec = header.removePrefix("bytes=").trim()
            val dash = spec.indexOf('-')
            if (dash < 0) return fullRange
            val startStr = spec.substring(0, dash).trim()
            val endStr = spec.substring(dash + 1).trim()
            when {
                startStr.isEmpty() -> {
                    // suffix range: last N bytes
                    val suffix = endStr.toLongOrNull()?.takeIf { it > 0 } ?: return fullRange
                    val start = maxOf(0L, totalSize - suffix)
                    start to (totalSize - 1)
                }
                else -> {
                    val start = startStr.toLongOrNull()?.takeIf { it >= 0 } ?: return fullRange
                    if (start >= totalSize) return fullRange
                    val end = if (endStr.isEmpty()) {
                        totalSize - 1
                    } else {
                        endStr.toLongOrNull()?.coerceAtMost(totalSize - 1) ?: (totalSize - 1)
                    }
                    start to maxOf(start, end)
                }
            }
        } catch (_: Exception) {
            fullRange
        }
    }
}
