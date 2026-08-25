package com.nuvio.tv.core.telegram

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Scores how well a Telegram file title matches an expected TMDB title.
 * Returns 0.0..1.0; the repository layer applies the acceptance threshold.
 */
object TelegramTitleMatcher {

    private const val CONTAINMENT_WEIGHT = 1.0
    private const val LEVENSHTEIN_WEIGHT = 0.9

    fun score(expectedTitle: String, candidateCleanTitle: String): Double {
        val expectedTokens = TelegramMediaParser.matchTokens(expectedTitle)
        val candidateTokens = TelegramMediaParser.matchTokens(candidateCleanTitle)

        if (expectedTokens.isEmpty() || candidateTokens.isEmpty()) return 0.0

        val expectedSet = expectedTokens.toSet()
        val candidateSet = candidateTokens.toSet()
        val intersection = expectedSet.intersect(candidateSet).size

        val containment = intersection.toDouble() / expectedSet.size

        val normalizedExpected = expectedTokens.joinToString(" ")
        val normalizedCandidate = candidateTokens.joinToString(" ")
        val levenshtein = levenshteinRatio(normalizedExpected, normalizedCandidate)

        val base = max(containment * CONTAINMENT_WEIGHT, levenshtein * LEVENSHTEIN_WEIGHT)

        // Penalize candidates that add a lot of unrelated content (e.g. saga/episode titles).
        val extraTokens = (candidateSet - expectedSet).size
        val penalty = sqrt(expectedSet.size.toDouble() / (expectedSet.size + extraTokens))

        return (base * penalty).coerceIn(0.0, 1.0)
    }

    /** Best score across several accepted titles (localized + original). */
    fun bestScore(expectedTitles: List<String>, candidateCleanTitle: String): Double =
        expectedTitles.filter { it.isNotBlank() }
            .maxOfOrNull { score(it, candidateCleanTitle) } ?: 0.0

    private fun levenshteinRatio(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0

        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitutionCost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = min(
                    min(current[j - 1] + 1, previous[j] + 1),
                    previous[j - 1] + substitutionCost
                )
            }
            System.arraycopy(current, 0, previous, 0, current.size)
        }
        val distance = previous[b.length]
        return 1.0 - distance.toDouble() / max(a.length, b.length)
    }
}
