package com.nuvio.tv.data.repository

import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import com.nuvio.tv.core.telegram.TelegramClientManager
import com.nuvio.tv.core.telegram.TelegramAuthState
import com.nuvio.tv.core.telegram.TelegramMediaParser
import com.nuvio.tv.core.telegram.TelegramTitleMatcher
import com.nuvio.tv.core.telegram.TelegramStreamProxy
import com.nuvio.tv.domain.repository.TelegramRepository
import com.nuvio.tv.domain.repository.TelegramStreamResult
import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.drinkless.tdlib.TdApi
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import java.util.Locale

@Singleton
class TelegramRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clientManager: TelegramClientManager,
    private val streamProxy: TelegramStreamProxy
) : TelegramRepository {

    companion object {
        private const val TAG = "TelegramRepo"
        private const val SEARCH_LIMIT = 100
        private const val SEARCH_TIMEOUT_MS = 20_000L
        private const val MIN_FILE_SIZE_BYTES = 50L * 1024 * 1024
        private const val MATCH_THRESHOLD = 0.70
        private const val MAX_TITLES_QUERIED = 6
        private const val MAX_RESULTS = 40
        private val SEARCH_STOPWORDS = setOf(
            "the", "a", "an", "of", "and", "to", "in", "on", "for", "with",
            "el", "la", "los", "las", "un", "una", "de", "del", "y", "en"
        )

        private val VIDEO_EXTENSIONS = setOf(
            "mkv", "mp4", "avi", "mov", "wmv", "flv", "webm", "m4v", "mpg", "mpeg", "m2ts", "ts", "3gp"
        )
        private val EXCLUDED_NON_VIDEO_EXTENSIONS = setOf(
            "cbz", "cbr", "cb7", "pdf", "epub", "mobi", "azw", "azw3", "djvu"
        )
    }

    private val chatTitleCache = HashMap<Long, String?>()
    private val chatTitleMutex = Mutex()

    override fun isAvailable(): Boolean =
        clientManager.authState.value is TelegramAuthState.Ready

    override suspend fun searchStreams(
        type: String,
        titles: List<String>,
        releaseYear: Int?,
        imdbId: String?,
        season: Int?,
        episode: Int?
    ): List<TelegramStreamResult> {
        if (!isAvailable()) return emptyList()

        val candidateTitles = buildCandidateTitles(titles)
            .filter { it.isNotBlank() }
            .distinctBy { TelegramMediaParser.normalizeForMatch(it) }
            .take(MAX_TITLES_QUERIED)
        if (candidateTitles.isEmpty()) return emptyList()

        val seenFileIds = HashSet<Int>()
        val results = mutableListOf<TelegramStreamResult>()
        var totalFound = 0
        var rejectedSize = 0
        var rejectedTitle = 0
        var rejectedSeasonEpisode = 0
        var duplicates = 0

        val queryTerms = buildSearchQueries(candidateTitles, imdbId)
        Log.d(TAG, "Query terms: ${queryTerms.take(8)}")

        for (title in queryTerms) {
            for (filter in listOf(TdApi.SearchMessagesFilterDocument(), TdApi.SearchMessagesFilterVideo())) {
                val request = TdApi.SearchMessages()
                request.query = title
                request.offset = ""
                request.limit = SEARCH_LIMIT
                request.filter = filter

                val found = runCatching {
                    clientManager.sendRequest(request, SEARCH_TIMEOUT_MS)
                }.getOrNull() as? TdApi.FoundMessages
                if (found == null) {
                    Log.w(TAG, "Search failed for \"$title\"")
                    continue
                }

                for (message in found.messages ?: emptyArray()) {
                    if (results.size >= MAX_RESULTS) break
                    totalFound++
                    when (addToResultsIfMatch(
                        message, results, seenFileIds,
                        type, title, candidateTitles, releaseYear, imdbId, season, episode
                    )) {
                        MatchOutcome.ACCEPTED -> Unit
                        MatchOutcome.DUPLICATE -> duplicates++
                        MatchOutcome.REJECTED_SIZE -> rejectedSize++
                        MatchOutcome.REJECTED_TITLE -> rejectedTitle++
                        MatchOutcome.REJECTED_SEASON_EPISODE -> rejectedSeasonEpisode++
                    }
                }
            }
        }

        val preferredLanguage = preferredUiLanguageCode()
        Log.i(
            TAG,
            "Telegram search \"${candidateTitles.first()}\" S=${season ?: '-'} E=${episode ?: '-'}: " +
                "found=$totalFound accepted=${results.size} " +
                "(size=$rejectedSize title=$rejectedTitle se=$rejectedSeasonEpisode dup=$duplicates)"
        )
        return results
            .sortedWith(
                compareByDescending<TelegramStreamResult> { it.matchScore }
                    .thenByDescending { languagePriorityScore(it.fileName, preferredLanguage) }
                    .thenByDescending { qualityRank(it.quality) }
                    .thenByDescending { it.sizeBytes }
            )
    }

    private fun buildCandidateTitles(titles: List<String>): List<String> {
        return titles
            .filter { it.isNotBlank() }
            .distinctBy { TelegramMediaParser.normalizeForMatch(it) }
    }

    private fun buildSearchQueries(candidateTitles: List<String>, imdbId: String?): List<String> {
        val normalizedImdbId = imdbId
            ?.trim()
            ?.lowercase(Locale.US)
            ?.takeIf { it.matches(Regex("""tt\d{7,9}""")) }

        if (normalizedImdbId == null) {
            return candidateTitles
                .flatMap(::queryVariants)
                .distinctBy { TelegramMediaParser.normalizeForMatch(it) }
        }

        val queries = LinkedHashSet<String>()
        for (title in candidateTitles) {
            val variants = queryVariants(title)
            for (variant in variants) {
                queries += "$variant $normalizedImdbId"
                queries += variant
            }
        }
        queries += normalizedImdbId
        return queries.toList()
    }

    private fun queryVariants(rawTitle: String): List<String> {
        val base = rawTitle.trim()
        if (base.isBlank()) return emptyList()

        val variants = LinkedHashSet<String>()
        variants += base

        val withoutYear = base.replace(Regex("""\b(19|20)\d{2}\b"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (withoutYear.isNotBlank()) variants += withoutYear

        val withoutSequelMarker = withoutYear.replace(
            Regex("""\s+(?:part\s+)?(?:\d+|[ivxlcdm]{1,6})$""", RegexOption.IGNORE_CASE),
            ""
        ).trim()
        if (withoutSequelMarker.isNotBlank()) variants += withoutSequelMarker

        val normalizedTokens = TelegramMediaParser.normalizeForMatch(withoutSequelMarker)
            .split(' ')
            .filter { token -> token.isNotBlank() && token.length > 2 && token !in SEARCH_STOPWORDS }

        if (normalizedTokens.size >= 2) {
            variants += normalizedTokens.joinToString(" ")
            variants += normalizedTokens.takeLast(2).joinToString(" ")
        }

        return variants.toList()
    }

    private enum class MatchOutcome { ACCEPTED, DUPLICATE, REJECTED_SIZE, REJECTED_TITLE, REJECTED_SEASON_EPISODE }

    private suspend fun addToResultsIfMatch(
        message: TdApi.Message,
        results: MutableList<TelegramStreamResult>,
        seenFileIds: MutableSet<Int>,
        type: String,
        queryTerm: String,
        titles: List<String>,
        releaseYear: Int?,
        imdbId: String?,
        season: Int?,
        episode: Int?
    ): MatchOutcome {
        val extracted = extractFile(message) ?: return MatchOutcome.REJECTED_SIZE
        if (extracted.sizeBytes < MIN_FILE_SIZE_BYTES) return MatchOutcome.REJECTED_SIZE
        if (!seenFileIds.add(extracted.fileId)) return MatchOutcome.DUPLICATE

        val parsed = TelegramMediaParser.parse(extracted.fileName)
        val normalizedImdbId = imdbId
            ?.trim()
            ?.lowercase(Locale.US)
            ?.takeIf { it.matches(Regex("""tt\d{7,9}""")) }
        val normalizedQueryTerm = TelegramMediaParser.normalizeForMatch(queryTerm)
        val isImdbOnlyQuery = normalizedImdbId != null && normalizedQueryTerm == normalizedImdbId
        val normalizedFileName = TelegramMediaParser.normalizeForMatch(extracted.fileName)
        val hasImdbTag = normalizedImdbId != null && normalizedFileName.contains(normalizedImdbId)

        val score = TelegramTitleMatcher.bestScore(titles, parsed.cleanTitle)
        if (!isImdbOnlyQuery && !hasImdbTag && score < MATCH_THRESHOLD) return MatchOutcome.REJECTED_TITLE

        when (type.lowercase()) {
            "series" -> {
                if (season != null && parsed.season != null && parsed.season != season) {
                    return MatchOutcome.REJECTED_SEASON_EPISODE
                }
                if (episode != null && parsed.episode != null && parsed.episode != episode) {
                    return MatchOutcome.REJECTED_SEASON_EPISODE
                }
                // If the file carries no S/E markers at all, keep it only for movies.
                if ((season != null || episode != null) &&
                    parsed.season == null && parsed.episode == null
                ) return MatchOutcome.REJECTED_SEASON_EPISODE
            }
            else -> {
                if (parsed.year != null && releaseYear != null &&
                    abs(parsed.year - releaseYear) > 1
                ) return MatchOutcome.REJECTED_TITLE
            }
        }

        results += extracted.copy(
            matchScore = when {
                hasImdbTag -> maxOf(score, 0.95)
                isImdbOnlyQuery -> maxOf(score, 0.85)
                else -> score
            },
            quality = parsed.quality
        )
        return MatchOutcome.ACCEPTED
    }

    private fun extractFile(message: TdApi.Message): TelegramStreamResult? {
        val fileName: String
        val file: TdApi.File
        var mimeType: String? = null
        when (val content = message.content) {
            is TdApi.MessageDocument -> {
                val document = content.document ?: return null
                fileName = document.fileName.orEmpty()
                file = document.document ?: return null
                mimeType = document.mimeType
                if (!isPlayableVideoDocument(fileName, mimeType, message.id)) return null
            }
            is TdApi.MessageVideo -> {
                val video = content.video ?: return null
                fileName = video.fileName.orEmpty()
                file = video.video ?: return null
            }
            else -> return null
        }
        if (fileName.isBlank()) return null

        val size = file.size.takeIf { it > 0 } ?: file.expectedSize
        if (size <= 0L) return null

        return TelegramStreamResult(
            chatId = message.chatId,
            messageId = message.id,
            fileId = file.id,
            fileName = fileName,
            sizeBytes = size,
            quality = null,
            year = null,
            matchScore = 0.0,
            chatTitle = null
        )
    }

    private fun isPlayableVideoDocument(fileName: String, mimeType: String?, messageId: Long): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isNotEmpty()) {
            if (ext in EXCLUDED_NON_VIDEO_EXTENSIONS) {
                Log.d(TAG, "Reject non-video document: ext=$ext file=$fileName messageId=$messageId")
                return false
            }
            if (ext in VIDEO_EXTENSIONS) return true
        }
        val mime = mimeType?.lowercase().orEmpty()
        if (mime.startsWith("video/")) return true
        if (mime.startsWith("application/")) {
            val accepted = ext in VIDEO_EXTENSIONS
            if (!accepted) {
                Log.d(TAG, "Reject application document: mime=$mime ext=$ext file=$fileName messageId=$messageId")
            }
            return accepted
        }
        return false
    }

    private fun preferredUiLanguageCode(): String {
        val locale = context.resources.configuration.locales.get(0)
        return locale?.language?.lowercase(Locale.US).orEmpty()
    }

    private fun languagePriorityScore(fileName: String, preferredLanguageCode: String): Int {
        val normalized = TelegramMediaParser.normalizeForMatch(fileName)
        val hasSpanish = containsAny(normalized, listOf("castellano", "espanol", "spanish", "latino"))
        val hasCastilian = containsAny(normalized, listOf("castellano", "espana", "espanol espana"))
        val hasLatam = containsAny(normalized, listOf("latino", "latam"))
        val hasEnglish = containsAny(normalized, listOf("english", "ingles"))
        val isDual = containsAny(normalized, listOf("dual", "multi audio", "multiaudio"))

        return when (preferredLanguageCode) {
            "es" -> when {
                hasCastilian -> 40
                hasSpanish -> 34
                hasLatam -> 30
                isDual && (hasSpanish || hasEnglish) -> 26
                isDual -> 22
                hasEnglish -> 10
                else -> 0
            }
            "en" -> when {
                hasEnglish -> 40
                isDual && hasEnglish -> 30
                isDual -> 20
                hasSpanish -> 8
                else -> 0
            }
            else -> when {
                isDual -> 24
                hasEnglish || hasSpanish -> 16
                else -> 0
            }
        }
    }

    private fun containsAny(normalizedText: String, terms: List<String>): Boolean =
        terms.any { term -> normalizedText.contains(term) }

    private fun qualityRank(quality: String?): Int {
        val q = quality?.lowercase() ?: return -1
        return when {
            q.contains("4k") || q.contains("2160") -> 2160
            q.contains("1080") -> 1080
            q.contains("720") -> 720
            q.contains("480") -> 480
            q.contains("360") -> 360
            else -> -1
        }
    }

    /** Resolves and caches a human-readable chat name; never throws. */
    override suspend fun chatTitle(chatId: Long): String? {
        chatTitleMutex.withLock {
            if (chatTitleCache.containsKey(chatId)) return chatTitleCache[chatId]
        }
        val title = runCatching {
            val request = TdApi.GetChat()
            request.chatId = chatId
            (clientManager.sendRequest(request, 10_000L) as? TdApi.Chat)?.title
        }.getOrNull()
        chatTitleMutex.withLock {
            chatTitleCache[chatId] = title
        }
        return title
    }
}
