package com.nuvio.tv.core.telegram

/**
 * Lifecycle of the TDLib client and its authorization flow.
 * The UI (TelegramAuthScreen) renders one screen per state.
 */
sealed interface TelegramAuthState {
    /** Client not started yet. */
    data object Idle : TelegramAuthState

    /** Library loaded, TDLib actor starting up. */
    data object Initializing : TelegramAuthState

    /** libtdjni.so could not be loaded on this device ABI. */
    data object Unavailable : TelegramAuthState

    /** TELEGRAM_API_ID / TELEGRAM_API_HASH missing from the build. */
    data object MissingCredentials : TelegramAuthState

    /** A QR link is available at [link]; scan it from the phone app. */
    data class WaitingQrCode(val link: String) : TelegramAuthState

    /** Phone number required (QR fallback path). */
    data object WaitingPhoneNumber : TelegramAuthState

    /** Verification code required; [codeLength] when known. */
    data class WaitingCode(val codeLength: Int) : TelegramAuthState

    /** Two-factor password required. */
    data object WaitingPassword : TelegramAuthState

    /** Fully authorized. */
    data class Ready(val firstName: String, val userId: Long) : TelegramAuthState

    /** Terminal failure; message is user-presentable. */
    data class Error(val message: String) : TelegramAuthState
}
