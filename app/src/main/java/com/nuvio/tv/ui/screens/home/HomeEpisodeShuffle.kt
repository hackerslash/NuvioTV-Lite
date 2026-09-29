package com.nuvio.tv.ui.screens.home

import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.EpisodeShuffleProfile
import com.nuvio.tv.domain.model.ContinueWatchingSortMode
import com.nuvio.tv.domain.model.EpisodeShuffle
import com.nuvio.tv.domain.model.ShuffleSurface
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal data class HomeShuffleRefresh(val visit: Long = System.nanoTime(), val metadata: Int = 0)

// Until a show has shuffle on, Home state passes straight through: the shuffle combine and its
// watch-history grouping would otherwise run on every state emission and every progress save.
@OptIn(ExperimentalCoroutinesApi::class)
internal fun HomeViewModel.createShuffleHomeState(): StateFlow<HomeUiState> =
    episodeShuffleStore.profiles
        .map { profile -> profile.available && profile.shows.values.any { it.enabled } }
        .distinctUntilChanged()
        .flatMapLatest { active -> if (active) shuffledHomeState() else _uiState }
        .stateIn(viewModelScope, SharingStarted.Eagerly, _uiState.value)

private fun HomeViewModel.shuffledHomeState(): Flow<HomeUiState> = channelFlow {
    val finished = finishedShuffleSeeds().stateIn(this, SharingStarted.Eagerly, emptyList())
    launch {
        finished.collectLatest { seeds -> seeds.forEach { resolveCwMeta(it) } }
    }
    combine(
        _uiState,
        episodeShuffleStore.profiles,
        combine(watchProgressRepository.watchedItems, watchProgressRepository.allProgress) { watched, progress ->
            val keys = watched.mapNotNull { item ->
                item.season?.let { season -> item.episode?.let { item.contentId to (season to it) } }
            } + progress.filter { it.isCompleted() }.mapNotNull { item ->
                item.season?.let { season -> item.episode?.let { item.contentId to (season to it) } }
            }
            keys.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }
        }.distinctUntilChanged().flowOn(Dispatchers.Default),
        shuffleHomeRefresh,
        finished
    ) { state, profile, watched, refresh, seeds ->
        val cards = seeds.mapNotNull { seed -> cachedCwMeta(seed.contentType, seed.contentId)?.let(seed::toShuffleCard) }
        applyHomeShuffle(state, profile, watched, episodeShuffle, refresh.visit, continueWatchingSortMode, cards) { id, type ->
            cachedCwMeta(type, id)?.videos?.map { video ->
                Video(video.id, video.title.orEmpty(), video.released, video.thumbnail,
                    season = video.season, episode = video.episode, overview = video.overview, available = video.available)
            }
        }
    }.collect { send(it) }
}

private fun HomeViewModel.finishedShuffleSeeds() = combine(
    watchProgressRepository.observeNextUpSeeds(),
    traktSettingsDataStore.continueWatchingDaysCap,
    traktSettingsDataStore.dismissedNextUpKeys,
    episodeShuffleStore.profiles
) { seeds, daysCap, dismissed, profile ->
    val cutoff = watchProgressRepository.activeProviderContinueWatchingCutoffEpochMs(daysCap, System.currentTimeMillis())
    seeds.filter { seed ->
        profile.settings(seed.contentId, seed.contentType).enabled &&
            (seed.season ?: 0) > 0 && seed.episode != null &&
            (cutoff == null || seed.lastWatched >= cutoff) &&
            nextUpDismissKey(seed.contentId, seed.season, seed.episode) !in dismissed &&
            !watchProgressRepository.isDroppedShow(seed.contentId) &&
            shouldUseAsCompletedSeed(seed)
    }.sortedByDescending { it.lastWatched }.distinctBy { it.contentId }
}.distinctUntilChanged()

private fun HomeViewModel.cachedCwMeta(type: String, id: String) =
    cwMetaCache["$type:$id"] ?: cwMetaCache["series:$id"] ?: cwMetaCache["tv:$id"]

private fun WatchProgress.toShuffleCard(meta: CwMetaSummary) = ContinueWatchingItem.NextUp(NextUpInfo(
    contentId = contentId, contentType = contentType, name = name.ifBlank { meta.name },
    poster = poster ?: meta.poster, backdrop = backdrop ?: meta.backdropUrl, logo = logo ?: meta.logo,
    videoId = videoId, season = season ?: 1, episode = episode ?: 1, episodeTitle = null, thumbnail = null,
    lastWatched = lastWatched, sortTimestamp = lastWatched, seedSeason = season, seedEpisode = episode
))

internal fun applyHomeShuffle(
    state: HomeUiState,
    profile: EpisodeShuffleProfile,
    watched: Map<String, Set<Pair<Int, Int>>>,
    shuffle: EpisodeShuffle,
    visit: Long,
    sortMode: ContinueWatchingSortMode,
    finished: List<ContinueWatchingItem.NextUp> = emptyList(),
    videos: (String, String) -> List<Video>?
): HomeUiState {
    if (!profile.available || profile.shows.values.none { it.enabled }) return state
    val native = state.continueWatchingItems + state.upcomingItems
    val items = native + finished.filter { card ->
        profile.settings(card.info.contentId, card.info.contentType).enabled &&
            native.none { it.contentId() == card.info.contentId }
    }
    val resumable = items.filterIsInstance<ContinueWatchingItem.InProgress>()
        .filter { profile.settings(it.progress.contentId, it.progress.contentType).enabled }
        .groupBy { it.progress.contentId }
        .mapValues { (_, episodes) -> episodes.maxBy { it.progress.lastWatched } }
    val projected = items.mapNotNull { item ->
        when (item) {
            is ContinueWatchingItem.InProgress -> {
                val latest = resumable[item.progress.contentId]
                if (latest != null && latest != item) null else item.copy(shufflePlayback = latest != null)
            }
            is ContinueWatchingItem.NextUp -> {
                val info = item.info
                if (info.contentId in resumable) return@mapNotNull null
                val settings = profile.settings(info.contentId, info.contentType)
                if (!settings.enabled) item else {
                    val catalogue = videos(info.contentId, info.contentType) ?: return@mapNotNull null
                    val selected = shuffle.select(
                        profile.profileId, info.contentId, catalogue, settings.includeWatched,
                        watched[info.contentId].orEmpty(), surface = ShuffleSurface.HOME, visit = visit
                    ) ?: return@mapNotNull null
                    item.copy(shufflePlayback = true, info = info.copy(
                        videoId = selected.id, season = selected.season!!, episode = selected.episode!!,
                        episodeTitle = selected.title, episodeDescription = selected.overview,
                        thumbnail = selected.thumbnail, released = selected.released,
                        hasAired = true, airDateLabel = null, releaseTimestamp = null,
                        isReleaseAlert = false, isNewSeasonRelease = false
                    ))
                }
            }
        }
    }
    val unique = projected.distinctBy { it.shuffleFocusKey ?: continueWatchingItemKey(it) }
    val (current, upcoming) = splitUpcomingItems(unique, sortMode)
    return state.copy(continueWatchingItems = current, upcomingItems = upcoming)
}
