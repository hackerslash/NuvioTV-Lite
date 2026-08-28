package com.nuvio.tv.core.telegram

import android.content.Context
import android.util.Log
import com.nuvio.tv.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TelegramApiException(
    val code: Int,
    message: String
) : Exception(message)

/**
 * Owns the single TDLib client instance. Started lazily (first search / auth screen),
 * never at app start, to keep the resident footprint off the playback path.
 *
 * Memory rules for the low-RAM projector target:
 *  - every optional TDLib database is disabled (file/chat-info/message);
 *    the session key still persists in [databaseDirectory].
 *  - downloads land in [filesDirectory] and are bounded by the streaming proxy.
 */
@Singleton
class TelegramClientManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storageManager: TelegramStorageManager
) {
    companion object {
        private const val TAG = "TelegramClient"
        private const val DEFAULT_TIMEOUT_MS = 10_000L
        private const val DATABASE_DIR = "tdlib"
        private const val FILES_DIR = "tdlib_files"
        private const val MIN_FREE_BYTES_FOR_TDLIB = 128L * 1024L * 1024L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _authState = MutableStateFlow<TelegramAuthState>(TelegramAuthState.Idle)
    val authState: StateFlow<TelegramAuthState> = _authState.asStateFlow()

    @Volatile
    private var client: Client? = null

    @Volatile
    private var libraryChecked = false

    /** True when libtdjni.so loaded successfully on this ABI. */
    var isLibraryAvailable: Boolean = false
        private set

    fun initialize() {
        if (client != null || _authState.value is TelegramAuthState.Ready) return
        if (_authState.value is TelegramAuthState.Error) return

        scope.launch {
            if (!checkLibrary()) {
                _authState.value = TelegramAuthState.Unavailable
                return@launch
            }
            if (!hasCredentials()) {
                _authState.value = TelegramAuthState.MissingCredentials
                return@launch
            }
            if (client != null) return@launch

            _authState.value = TelegramAuthState.Initializing
            try {
                client = Client.create(::handleUpdate, { Log.e(TAG, "TDLib update error", it) }, null)
                // TDLib's internal logging floods logcat (DLTD) and churns memory on
                // this 1GB device; keep errors only.
                val setVerbosity = TdApi.SetLogVerbosityLevel()
                setVerbosity.newVerbosityLevel = 1
                Client.execute(setVerbosity)
            } catch (e: Throwable) {
                Log.e(TAG, "Client.create failed", e)
                _authState.value = TelegramAuthState.Error(e.message ?: "TDLib init failed")
            }
        }
    }

    /**
     * Re-initializes TDLib only when a previous login left a session binlog.
     * Called at app start so stream searches work without visiting the auth
     * screen first, while never paying TDLib's native memory cost for users
     * who have not linked an account.
     */
    fun resumePersistedSession() {
        if (_authState.value != TelegramAuthState.Idle) return
        storageManager.maybeTrim(reason = "app_start")
        val binlog = File(context.filesDir, "$DATABASE_DIR/td.binlog")
        if (!binlog.exists() || binlog.length() == 0L) {
            Log.d(TAG, "No persisted Telegram session; skipping resume")
            return
        }
        if (!hasMinimumFreeSpace()) {
            Log.w(TAG, "Low storage before TDLib resume; clearing Telegram download cache")
            runCatching {
                storageManager.clearAllDownloads()
            }
            if (!hasMinimumFreeSpace()) {
                Log.w(TAG, "Skipping persisted Telegram resume: insufficient free storage")
                return
            }
        }
        Log.i(TAG, "Resuming persisted Telegram session")
        initialize()
    }

    private fun hasMinimumFreeSpace(): Boolean {
        val free = context.filesDir.usableSpace
        return free >= MIN_FREE_BYTES_FOR_TDLIB
    }

    private fun checkLibrary(): Boolean {
        if (libraryChecked) return isLibraryAvailable
        libraryChecked = true
        isLibraryAvailable = try {
            System.loadLibrary("tdjni")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "libtdjni.so unavailable — Telegram disabled: ${e.message}")
            false
        }
        return isLibraryAvailable
    }

    private fun hasCredentials(): Boolean =
        BuildConfig.TELEGRAM_API_ID != 0 && BuildConfig.TELEGRAM_API_HASH.isNotBlank()

    private fun sendTdlibParameters() {
        val params = TdApi.SetTdlibParameters().apply {
            useTestDc = false
            databaseDirectory = File(context.filesDir, DATABASE_DIR).absolutePath
            filesDirectory = File(context.filesDir, FILES_DIR).absolutePath
            useFileDatabase = false
            useChatInfoDatabase = false
            useMessageDatabase = false
            useSecretChats = false
            apiId = BuildConfig.TELEGRAM_API_ID
            apiHash = BuildConfig.TELEGRAM_API_HASH
            systemLanguageCode = "es"
            deviceModel = "NuvioTV Lite"
            systemVersion = "Android ${android.os.Build.VERSION.RELEASE}"
            applicationVersion = BuildConfig.VERSION_NAME
        }
        client?.send(params) { }
    }

    private fun handleUpdate(update: TdApi.Object) {
        when (update) {
            is TdApi.UpdateAuthorizationState -> handleAuthState(update.authorizationState)
            is TdApi.Error -> {
                val state = _authState.value
                if (state !is TelegramAuthState.Ready && state !is TelegramAuthState.Idle) {
                    _authState.value = TelegramAuthState.Error(update.message)
                }
            }
        }
    }

    private fun handleAuthState(state: TdApi.AuthorizationState) {
        Log.d(TAG, "authState -> ${state::class.simpleName}")
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> sendTdlibParameters()
            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation ->
                _authState.value = TelegramAuthState.WaitingQrCode(state.link)
            is TdApi.AuthorizationStateWaitPhoneNumber ->
                _authState.value = TelegramAuthState.WaitingPhoneNumber
            is TdApi.AuthorizationStateWaitCode -> {
                val length = when (val type = state.codeInfo.type) {
                    is TdApi.AuthenticationCodeTypeTelegramMessage -> type.length
                    is TdApi.AuthenticationCodeTypeSms -> type.length
                    else -> 5
                }
                _authState.value = TelegramAuthState.WaitingCode(length)
            }
            is TdApi.AuthorizationStateWaitPassword ->
                _authState.value = TelegramAuthState.WaitingPassword
            is TdApi.AuthorizationStateReady -> scope.launch {
                val me = runCatching {
                    sendRequest(TdApi.GetMe()) as? TdApi.User
                }.getOrNull()
                _authState.value = TelegramAuthState.Ready(
                    firstName = me?.firstName.orEmpty(),
                    userId = me?.id ?: 0L
                )
            }
            is TdApi.AuthorizationStateLoggingOut,
            is TdApi.AuthorizationStateClosing,
            is TdApi.AuthorizationStateClosed -> Unit
            else -> Unit
        }
    }

    // ── Auth actions ─────────────────────────────────────────────────────────

    /** QR-first: switch to the "link device" flow shown on TV. */
    fun requestQrCode() {
        client?.send(TdApi.RequestQrCodeAuthentication(LongArray(0)), null)
    }

    /** Fallback path when QR cannot be scanned. */
    fun submitPhoneNumber(phone: String) {
        client?.send(TdApi.SetAuthenticationPhoneNumber(phone, null), null)
    }

    fun submitCode(code: String) {
        client?.send(TdApi.CheckAuthenticationCode(code), null)
    }

    fun submitPassword(password: String) {
        client?.send(TdApi.CheckAuthenticationPassword(password), null)
    }

    /**
     * Logs out and wipes every TDLib trace from internal storage.
     * Safe to call from any state; resolves once cleanup finished.
     */
    suspend fun unbind() {
        withTimeoutOrNull(DEFAULT_TIMEOUT_MS) {
            try {
                sendRequest(TdApi.LogOut())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "LogOut failed; forcing close: ${e.message}")
                client?.send(TdApi.Close(), null)
            }
        }
        client = null
        _authState.value = TelegramAuthState.Idle
        listOf(DATABASE_DIR, FILES_DIR).forEach { name ->
            File(context.filesDir, name).deleteRecursively()
        }
    }

    // ── Requests ─────────────────────────────────────────────────────────────

    /**
     * Sends a function to TDLib and suspends until the result arrives.
     * Returns null on timeout or missing client; throws [TelegramApiException]
     * on TDLib errors.
     */
    suspend fun sendRequest(
        function: TdApi.Function<out TdApi.Object>,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): TdApi.Object? = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { continuation ->
            val c = client
            if (c == null) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }
            c.send(function) { result ->
                if (!continuation.isActive) return@send
                if (result is TdApi.Error) {
                    continuation.resumeWithException(
                        TelegramApiException(result.code, result.message ?: "Unknown TDLib error")
                    )
                } else {
                    continuation.resume(result)
                }
            }
        }
    }
}
