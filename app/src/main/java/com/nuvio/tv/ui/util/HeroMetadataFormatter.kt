package com.nuvio.tv.ui.util

private val HOURS_REGEX = "(\\d+)\\s*h".toRegex()
private val MINUTES_REGEX = "(\\d+)\\s*m(?:in)?".toRegex()

fun formatHeroRuntime(runtime: String?): String? {
    val normalized = runtime?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
    val hours = HOURS_REGEX.find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()
    val minutes = MINUTES_REGEX.find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()
    val totalMinutes = when {
        hours != null || minutes != null -> (hours ?: 0) * 60 + (minutes ?: 0)
        else -> normalized.filter(Char::isDigit).toIntOrNull()
    } ?: return runtime

    val wholeHours = totalMinutes / 60
    val remainingMinutes = totalMinutes % 60
    return when {
        wholeHours > 0 && remainingMinutes > 0 -> "${wholeHours}h ${remainingMinutes}m"
        wholeHours > 0 -> "${wholeHours}h"
        else -> "${remainingMinutes}m"
    }
}
