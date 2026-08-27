package com.nuvio.tv.core.util

// Hoisted: this runs per hero item and per card recomposition, and Regex(..) compiles the
// pattern every call.
private val HOURS_REGEX = "(\\d+)\\s*h".toRegex()
private val MINUTES_REGEX = "(\\d+)\\s*m(?:in)?".toRegex()

fun parseRuntimeMinutes(runtime: String?): Int? {
    val normalized = runtime?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
    val hours = HOURS_REGEX.find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()
    val minutes = MINUTES_REGEX.find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()
    if (hours != null || minutes != null) return (hours ?: 0) * 60 + (minutes ?: 0)
    return normalized.filter(Char::isDigit).toIntOrNull()
}
