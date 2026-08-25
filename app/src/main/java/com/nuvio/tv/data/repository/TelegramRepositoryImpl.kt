package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.core.telegram.TelegramClientManager
import com.nuvio.tv.core.telegram.TelegramAuthState
import com.nuvio.tv.core.telegram.TelegramMediaParser
import com.nuvio.tv.core.telegram.TelegramTitleMatcher
import com.nuvio.tv.core.telegram.TelegramStreamProxy
import com.nuvio.tv.domain.repository.TelegramRepository
import com.nuvio.tv.domain.repository.TelegramStreamResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.drinkless.tdlib.TdApi
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class TelegramRepositoryImpl @Inject constructor(
    private val clientManager: TelegramClientManager,
    private val streamProxy: TelegramStreamProxy
) : TelegramRepository {

    companion object {
        private const val TAG = "TelegramRepo"
        private const val SEARCH_LIMIT = 100
        private const val SEARCH_TIMEOUT_MS = 20_000L
        private const val MIN_FILE_SIZE_BYTES = 50L * 1024 * 1024
        private const val MATCH_THRESHOLD = 0.75
        private const val MAX_TITLES_QUERIED = 2
        private const val MAX_RESULTS = 40
    }

    private val chatTitleCache = HashMap<Long, String?>()
    private val chatTitleMutex = Mutex()

    override fun isAvailable(): Boolean =
        clientManager.authState.value is TelegramAuthState.Ready

    override suspend fun searchStreams(
        type: String,
        titles: List<String>,
        releaseYear: Int?,
        season: Int?,
        episode: Int?
    ): List<TelegramStreamResult> {
        if (!isAvailable()) return emptyList()

        val candidateTitles = titles
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

        for (title in candidateTitles) {
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
                        type, candidateTitles, releaseYear, season, episode
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

        Log.i(
            TAG,
            "Telegram search \"${candidateTitles.first()}\" S=${season ?: '-'} E=${episode ?: '-'}: " +
                "found=$totalFound accepted=${results.size} " +
                "(size=$rejectedSize title=$rejectedTitle se=$rejectedSeasonEpisode dup=$duplicates)"
        )
        return results.sortedByDescending { it.sizeBytes }
    }

    private enum class MatchOutcome { ACCEPTED, DUPLICATE, REJECTED_SIZE, REJECTED_TITLE, REJECTED_SEASON_EPISODE }

    private suspend fun addToResultsIfMatch(
        message: TdApi.Message,
        results: MutableList<TelegramStreamResult>,
        seenFileIds: MutableSet<Int>,
        type: String,
        titles: List<String>,
        releaseYear: Int?,
        season: Int?,
        episode: Int?
    ): MatchOutcome {
        val extracted = extractFile(message) ?: return MatchOutcome.REJECTED_SIZE
        if (extracted.sizeBytes < MIN_FILE_SIZE_BYTES) return MatchOutcome.REJECTED_SIZE
        if (!seenFileIds.add(extracted.fileId)) return MatchOutcome.DUPLICATE

        val parsed = TelegramMediaParser.parse(extracted.fileName)

        val score = TelegramTitleMatcher.bestScore(titles, parsed.cleanTitle)
        if (score < MATCH_THRESHOLD) return MatchOutcome.REJECTED_TITLE

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

        results += extracted.copy(matchScore = score, quality = parsed.quality)
        return MatchOutcome.ACCEPTED
    }

    private fun extractFile(message: TdApi.Message): TelegramStreamResult? {
        val fileName: String
        val file: TdApi.File
        when (val content = message.content) {
            is TdApi.MessageDocument -> {
                val document = content.document ?: return null
                fileName = document.fileName.orEmpty()
                file = document.document ?: return null
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
