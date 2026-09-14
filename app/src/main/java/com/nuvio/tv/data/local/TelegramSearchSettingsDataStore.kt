// TG-ONLY-FILE: Telegram module — keep whole file on upstream merge
package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.nuvio.tv.core.profile.ProfileManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@Singleton
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TelegramSearchSettingsDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        internal const val FEATURE = "telegram_search_settings"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    private val allowChannelContextSeriesMatchKey =
        booleanPreferencesKey("allow_channel_context_series_match")
    private val moviesI18nEnabledKey =
        booleanPreferencesKey("movies_i18n_enabled")
    private val seriesI18nEnabledKey =
        booleanPreferencesKey("series_i18n_enabled")
    // TG-START: discard series files in movie searches (re-apply on upstream merge)
    private val discardSeriesInMoviesKey =
        booleanPreferencesKey("discard_series_in_movies")
    // TG-END

    val allowChannelContextSeriesMatch: StateFlow<Boolean> =
        booleanSetting(allowChannelContextSeriesMatchKey)

    // TG-START: per-type i18n toggles under "Búsqueda TG" (re-apply on upstream merge)
    val moviesI18nEnabled: StateFlow<Boolean> =
        booleanSetting(moviesI18nEnabledKey)

    val seriesI18nEnabled: StateFlow<Boolean> =
        booleanSetting(seriesI18nEnabledKey)
    // TG-END

    suspend fun setAllowChannelContextSeriesMatch(enabled: Boolean) {
        store().edit { prefs ->
            prefs[allowChannelContextSeriesMatchKey] = enabled
        }
    }

    // TG-START: per-type i18n toggles under "Búsqueda TG" (re-apply on upstream merge)
    suspend fun setMoviesI18nEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[moviesI18nEnabledKey] = enabled
        }
    }

    suspend fun setSeriesI18nEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[seriesI18nEnabledKey] = enabled
        }
    }
    // TG-END

    // TG-START: discard series files in movie searches (re-apply on upstream merge)
    val discardSeriesInMovies: StateFlow<Boolean> =
        booleanSetting(discardSeriesInMoviesKey)

    suspend fun setDiscardSeriesInMovies(enabled: Boolean) {
        store().edit { prefs ->
            prefs[discardSeriesInMoviesKey] = enabled
        }
    }
    // TG-END

    private fun booleanSetting(key: Preferences.Key<Boolean>): StateFlow<Boolean> =
        profileManager.activeProfileId.flatMapLatest { pid ->
            factory.get(pid, FEATURE).data.map { prefs ->
                prefs[key] ?: true
            }
        }.stateIn(scope, SharingStarted.Eagerly, true)
}
