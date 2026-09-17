// TG-ONLY-FILE: Telegram module — keep whole file on upstream merge
package com.nuvio.tv.ui.screens.settings.telegram

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.telegram.TelegramAuthState
import com.nuvio.tv.core.telegram.TelegramClientManager
import com.nuvio.tv.core.telegram.TelegramCredentialsValidator
import com.nuvio.tv.data.local.TelegramSearchSettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class TelegramAuthViewModel @Inject constructor(
    private val clientManager: TelegramClientManager,
    private val telegramSearchSettingsDataStore: TelegramSearchSettingsDataStore,
    // TG-START: download cache management (re-apply on upstream merge)
    private val tgDownloadSessionManager: com.nuvio.tv.core.telegram.TgDownloadSessionManager,
    private val telegramStorageManager: com.nuvio.tv.core.telegram.TelegramStorageManager
    // TG-END
) : ViewModel() {

    val authState: StateFlow<TelegramAuthState> = clientManager.authState
    val allowChannelContextSeriesMatch: StateFlow<Boolean> =
        telegramSearchSettingsDataStore.allowChannelContextSeriesMatch
    // TG-START: per-type i18n toggles under "Búsqueda TG" (re-apply on upstream merge)
    val moviesI18nEnabled: StateFlow<Boolean> =
        telegramSearchSettingsDataStore.moviesI18nEnabled
    val seriesI18nEnabled: StateFlow<Boolean> =
        telegramSearchSettingsDataStore.seriesI18nEnabled
    // TG-END

    fun initialize() = clientManager.initialize()

    fun requestQrCode() = clientManager.requestQrCode()

    fun submitPhoneNumber(phone: String) = clientManager.submitPhoneNumber(phone)

    fun submitCode(code: String) = clientManager.submitCode(code)

    fun submitPassword(password: String) = clientManager.submitPassword(password)

    fun setAllowChannelContextSeriesMatch(enabled: Boolean) {
        viewModelScope.launch {
            telegramSearchSettingsDataStore.setAllowChannelContextSeriesMatch(enabled)
        }
    }

    // TG-START: per-type i18n toggles under "Búsqueda TG" (re-apply on upstream merge)
    fun setMoviesI18nEnabled(enabled: Boolean) {
        viewModelScope.launch {
            telegramSearchSettingsDataStore.setMoviesI18nEnabled(enabled)
        }
    }

    fun setSeriesI18nEnabled(enabled: Boolean) {
        viewModelScope.launch {
            telegramSearchSettingsDataStore.setSeriesI18nEnabled(enabled)
        }
    }
    // TG-END

    // TG-START: discard series files in movie searches (re-apply on upstream merge)
    val discardSeriesInMovies: StateFlow<Boolean> =
        telegramSearchSettingsDataStore.discardSeriesInMovies

    fun setDiscardSeriesInMovies(enabled: Boolean) {
        viewModelScope.launch {
            telegramSearchSettingsDataStore.setDiscardSeriesInMovies(enabled)
        }
    }
    // TG-END

    fun unbind() {
        viewModelScope.launch { clientManager.unbind() }
    }

    // TG-START: per-device API credentials, option B (re-apply on upstream merge)
    private val _credentialsError = MutableStateFlow(false)
    /** True when the last save attempt failed validation. UI maps it to text. */
    val credentialsError: StateFlow<Boolean> = _credentialsError.asStateFlow()

    fun saveCredentials(apiIdText: String, apiHashText: String) {
        val apiId = TelegramCredentialsValidator.parseApiId(apiIdText)
        if (apiId == null || !TelegramCredentialsValidator.isValidApiHash(apiHashText)) {
            _credentialsError.value = true
            return
        }
        _credentialsError.value = false
        viewModelScope.launch { clientManager.saveCredentials(apiId, apiHashText.trim()) }
    }

    fun clearCredentialsError() {
        _credentialsError.value = false
    }
    // TG-END

    // TG-START: download cache management (re-apply on upstream merge)
    private val _cacheSizeMb = MutableStateFlow(-1L)
    /** -1 = aún no medido. */
    val downloadCacheSizeMb: StateFlow<Long> = _cacheSizeMb.asStateFlow()

    private val _lastFreedMb = MutableStateFlow<Long?>(null)
    val lastFreedMb: StateFlow<Long?> = _lastFreedMb.asStateFlow()

    fun refreshDownloadCacheSize() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _cacheSizeMb.value = telegramStorageManager.downloadsSizeMb()
        }
    }

    fun clearDownloadCache() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val freed = tgDownloadSessionManager.clearCacheExceptActive()
            _lastFreedMb.value = freed
            _cacheSizeMb.value = telegramStorageManager.downloadsSizeMb()
        }
    }
    // TG-END
}
