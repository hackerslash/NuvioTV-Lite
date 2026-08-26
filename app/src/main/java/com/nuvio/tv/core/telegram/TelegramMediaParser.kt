package com.nuvio.tv.core.telegram

/**
 * Turns heterogeneous Spanish/international release file names from Telegram
 * channels into structured media metadata.
 *
 * Input examples handled:
 *  - "Dune.2021.1080p.WEB-DL.Dual.Latino.Castellano.mkv"
 *  - "La Casa de Papel S03E05 1080p NF WEB-DL x264.mkv"
 *  - "La que se avecina 12x09.avi"
 *  - "Cuéntame cómo pasó Temporada 24 Capítulo 3.mp4"
 *  - "Mujeres.T1.E05.mp4"
 */
object TelegramMediaParser {

    data class Parsed(
        val rawName: String,
        val cleanTitle: String,
        val season: Int?,
        val episode: Int?,
        val quality: String?,
        val year: Int?
    )

    private const val MAX_SEASON = 100
    private const val MAX_EPISODE = 2000

    // Ordered by specificity: the first match wins and marks where the title ends.
    private val SEASON_EPISODE_PATTERNS = listOf(
        Regex("""(?:^|[^A-Za-z0-9])S(\d{1,2})[\s._-]?E(\d{1,4})(?![0-9])""", RegexOption.IGNORE_CASE),
        Regex("""(?<![0-9])(\d{1,2})[xX](\d{1,4})(?![0-9p])"""),
        Regex("""Temporada\s+(\d{1,3})[^A-Za-z0-9]{0,20}(?:Cap[ií]tulo|Episodio|\bEp\b|\bCap\b)\s*(\d{1,4})""", RegexOption.IGNORE_CASE),
        Regex("""(?:^|[^A-Za-z0-9])T(\d{1,2})[\s._-]*E(\d{1,4})(?![0-9])""", RegexOption.IGNORE_CASE)
    )

    private val QUALITY_PATTERN =
        Regex("""(?<![0-9A-Za-z])(2160|1080|720|480|360)[pi](?![0-9A-Za-z])""", RegexOption.IGNORE_CASE)

    private val YEAR_PATTERN = Regex("""(?<![0-9])(19\d{2}|20\d{2})(?![0-9])""")

    private val NOISE_WORDS = setOf(
        "1080p", "720p", "480p", "2160p", "4k", "uhd", "hd", "fullhd", "hdr", "hdr10", "dv",
        "webdl", "web-dl", "web", "webrip", "bdrip", "brrip", "bluray", "blu-ray", "dvdrip",
        "dvd", "hdtv", "hdrip", "remux", "microhd", "x264", "x265", "h264", "h265", "hevc",
        "avc", "aac", "aac2", "aac5", "ac3", "eac3", "dts", "dtshd", "truehd", "atmos",
        "ddp", "dd5", "5ch", "2ch", "mp3", "dual", "latino", "castellano", "espanol",
        "español", "spanish", "ingles", "english", "sub", "subs", "subtitulado", "subtitulada",
        "vose", "vosee", "vosi", "vesre", "netflix", "nf", "amzn", "hmax", "atvp", "dsnp",
        "proper", "repack", "extended", "unrated", "criterion", "imax", "10bit", "8bit",
        "60fps", "hq", "vip", "mkv", "mp4", "avi", "dl", "dd", "ma", "hdrplus"
    )

    private val ARTICLES = setOf(
        "el", "la", "los", "las", "un", "una", "unos", "unas",
        "the", "a", "an", "de", "del", "al", "of", "and", "y"
    )

    /** Parses a document/file name into structured media info. */
    fun parse(rawName: String): Parsed {
        val withoutExtension = rawName.substringBeforeLast('.', rawName)
            .replace('_', ' ')
            .replace('.', ' ')

        val serMatch = SEASON_EPISODE_PATTERNS.firstNotNullOfOrNull { it.find(withoutExtension) }
        var season: Int? = null
        var episode: Int? = null
        var titleSource = withoutExtension

        if (serMatch != null && serMatch.range.first > 0) {
            val s = serMatch.groupValues[1].toIntOrNull()?.takeIf { it in 1..MAX_SEASON }
            val e = serMatch.groupValues[2].toIntOrNull()?.takeIf { it in 1..MAX_EPISODE }
            if (s != null && e != null) {
                season = s
                episode = e
                titleSource = withoutExtension.substring(0, serMatch.range.first)
            }
        }

        val quality = QUALITY_PATTERN.find(titleSource)?.value?.lowercase()
            ?: QUALITY_PATTERN.find(withoutExtension)?.value?.lowercase()
            ?: detectUhdKeyword(withoutExtension)

        val year = YEAR_PATTERN.findAll(titleSource).lastOrNull()?.value?.toIntOrNull()

        val cleaned = stripNoise(titleSource)

        return Parsed(
            rawName = rawName,
            cleanTitle = cleaned,
            season = season,
            episode = episode,
            quality = when {
                quality == null -> null
                quality == "2160p" || quality == "2160i" -> "4K"
                else -> quality
            },
            year = year
        )
    }

    /**
     * Normalizes a title for comparison: lowercases, strips diacritics and
     * punctuation, collapses whitespace.
     */
    fun normalizeForMatch(input: String): String =
        java.text.Normalizer.normalize(input.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    private fun detectUhdKeyword(input: String): String? =
        if (Regex("""\b4k\b""", RegexOption.IGNORE_CASE).containsMatchIn(input)) "4K" else null

    private fun stripNoise(source: String): String {
        var text = source.replace(Regex("""[(\[{}].*?[)\]}]"""), " ")

        YEAR_PATTERN.findAll(text).toList().lastOrNull()?.let { text = text.replaceRange(it.range, " ") }
        QUALITY_PATTERN.find(text)?.let { text = text.replaceRange(it.range, " ") }

        text = text.replace(Regex("[\\-–—_]+"), " ")
        val kept = StringBuilder()
        text.split(Regex("\\s+")).forEach { tokenRaw ->
            val token = tokenRaw.trim(',', ';', ':', '-', '.', '!', '¡', '?', '¿')
            if (token.isEmpty()) return@forEach
            val bare = token.trim('\'', '"').lowercase().let { java.text.Normalizer.normalize(it, java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "") }
            if (bare.isEmpty() || bare.length > 30 || bare.all { !it.isLetterOrDigit() }) return@forEach
            if (bare.length == 1 && bare[0].isDigit()) return@forEach
            if (bare in NOISE_WORDS) return@forEach
            kept.append(tokenRaw).append(' ')
        }

        return kept.toString().trim().trim('-', ':', ',').ifEmpty {
            source.substringBeforeLast('.', source).replace(Regex("[._]+"), " ").trim()
        }
    }

    /** Tokens used for matching; articles and release-noise dropped on both sides. */
    fun matchTokens(input: String): List<String> =
        normalizeForMatch(input)
            .split(' ')
            .filter { it.isNotEmpty() && it !in ARTICLES && it !in NOISE_WORDS }
}
