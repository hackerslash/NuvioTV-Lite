package com.nuvio.tv.ui.screens.settings.telegram

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.telegram.TelegramAuthState
import com.nuvio.tv.core.telegram.TelegramClientManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class TelegramAuthViewModel @Inject constructor(
    private val clientManager: TelegramClientManager
) : ViewModel() {

    val authState: StateFlow<TelegramAuthState> = clientManager.authState

    fun initialize() = clientManager.initialize()

    fun requestQrCode() = clientManager.requestQrCode()

    fun submitPhoneNumber(phone: String) = clientManager.submitPhoneNumber(phone)

    fun submitCode(code: String) = clientManager.submitCode(code)

    fun submitPassword(password: String) = clientManager.submitPassword(password)

    fun unbind() {
        viewModelScope.launch { clientManager.unbind() }
    }
}
