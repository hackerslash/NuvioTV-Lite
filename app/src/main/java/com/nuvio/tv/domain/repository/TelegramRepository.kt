package com.nuvio.tv.domain.repository

/**
 * A playable media file found on the user's Telegram account.
 */
data class TelegramStreamResult(
    val chatId: Long,
    val messageId: Long,
    val fileId: Int,
    val fileName: String,
    val sizeBytes: Long,
    val quality: String?,
    val year: Int?,
    val matchScore: Double,
    val chatTitle: String?
)

interface TelegramRepository {
    /**
     * Searches all chats for video files matching the given titles and, for
     * series, the requested season/episode.
     *
     * @param type "movie" or "series"
     * @param titles Candidate titles (localized + original), best first
     * @param releaseYear Release/first-air year, when known
     */
    suspend fun searchStreams(
        type: String,
        titles: List<String>,
        releaseYear: Int?,
        season: Int?,
        episode: Int?
    ): List<TelegramStreamResult>

    /** True when TDLib is authenticated; searches are skipped otherwise. */
    fun isAvailable(): Boolean

    /** Human-readable chat name for a chat id, or null. */
    suspend fun chatTitle(chatId: Long): String?
}
