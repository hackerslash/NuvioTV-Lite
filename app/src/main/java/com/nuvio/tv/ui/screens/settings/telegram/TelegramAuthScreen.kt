@file:OptIn(ExperimentalTvMaterial3Api::class)

// TG-ONLY-FILE: Telegram module — keep whole file on upstream merge
package com.nuvio.tv.ui.screens.settings.telegram

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.core.telegram.TelegramAuthState
import com.nuvio.tv.ui.screens.settings.SettingsGroupCard
import com.nuvio.tv.ui.screens.settings.SettingsToggleRow

private val TgPaneBackground = Color.White.copy(alpha = 0.022f)
private val TgPaneBorder = Color.White.copy(alpha = 0.07f)

@Composable
fun TelegramAuthScreen(
    onBackPress: () -> Unit = {},
    viewModel: TelegramAuthViewModel = hiltViewModel()
) {
    val authState by viewModel.authState.collectAsState()
    val allowChannelContextSeriesMatch by viewModel.allowChannelContextSeriesMatch.collectAsState()
    // TG-START: search toggles under "Búsqueda TG" (re-apply on upstream merge)
    val moviesI18nEnabled by viewModel.moviesI18nEnabled.collectAsState()
    val seriesI18nEnabled by viewModel.seriesI18nEnabled.collectAsState()
    val discardSeriesInMovies by viewModel.discardSeriesInMovies.collectAsState()
    // TG-END
    // TG-START: download cache readout (re-apply on upstream merge)
    val downloadCacheSizeMb by viewModel.downloadCacheSizeMb.collectAsState()
    val lastFreedMb by viewModel.lastFreedMb.collectAsState()
    LaunchedEffect(authState) {
        if (authState is TelegramAuthState.Ready) viewModel.refreshDownloadCacheSize()
    }
    // TG-END

    BackHandler { onBackPress() }

    LaunchedEffect(Unit) { viewModel.initialize() }

    // QR-first: TDLib lands on WaitPhoneNumber; flip it to the link-device flow.
    LaunchedEffect(authState) {
        if (authState is TelegramAuthState.WaitingPhoneNumber) {
            viewModel.requestQrCode()
        }
    }

    // TG-START: TV focus + scroll (Mi Box D-pad: content overflows without scroll,
    // nothing requests focus on entry; re-apply on upstream merge)
    val scrollState = rememberScrollState()
    val backFocusRequester = remember { FocusRequester() }
    val readyActionRequester = remember { FocusRequester() }
    // States with no actionable control of their own: park focus on Back.
    // NOTE: WaitingQrCode is deliberately excluded — yanking focus to the bottom
    // scrolled the QR out of view on D-pad boxes.
    LaunchedEffect(authState) {
        when (authState) {
            is TelegramAuthState.Idle,
            is TelegramAuthState.Initializing,
            is TelegramAuthState.Unavailable,
            is TelegramAuthState.Error,
            is TelegramAuthState.WaitingPhoneNumber -> backFocusRequester.requestFocus()
            is TelegramAuthState.Ready -> readyActionRequester.requestFocus()
            else -> Unit
        }
    }
    // TG-END

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        // TG-START: top-align so overflowing content scrolls instead of clipping
        contentAlignment = Alignment.TopCenter
        // TG-END
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // TG-START: scroll keeps every field reachable on 1080p boxes
                .verticalScroll(scrollState)
                // TG-END
                .padding(horizontal = 64.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.telegram_settings_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(24.dp))

            when (val state = authState) {
                is TelegramAuthState.Idle,
                is TelegramAuthState.Initializing -> StatusText(stringResource(R.string.telegram_status_initializing))

                is TelegramAuthState.Unavailable ->
                    StatusText(stringResource(R.string.telegram_error_unavailable))

                // TG-START: per-device API credentials, option B (re-apply on upstream merge)
                // Keys are entered here precisely when missing; no dead-end state.
                is TelegramAuthState.MissingCredentials ->
                    CredentialsForm(viewModel)
                // TG-END

                is TelegramAuthState.WaitingQrCode -> QrPanel(state.link, onBackPress)
                is TelegramAuthState.WaitingPhoneNumber -> PhoneForm(viewModel)
                is TelegramAuthState.WaitingCode -> CodeForm(state.codeLength, viewModel)
                is TelegramAuthState.WaitingPassword -> PasswordForm(viewModel)

                is TelegramAuthState.Ready -> ReadyPanel(
                    firstName = state.firstName,
                    onUnbind = { viewModel.unbind() },
                    // TG-START: TV focus + scroll (re-apply on upstream merge)
                    focusRequester = readyActionRequester
                    // TG-END
                )

                is TelegramAuthState.Error -> StatusText(
                    stringResource(R.string.telegram_error_generic, state.message)
                )
            }

            // TG-START: search settings only once linked (re-apply on upstream merge)
            // Showing the "Búsqueda TG" hierarchy before the account is linked pushes
            // the QR and the key fields out of a 1080p viewport on D-pad boxes.
            if (authState is TelegramAuthState.Ready) {
            Spacer(Modifier.height(20.dp))
            SettingsGroupCard(
                modifier = Modifier.fillMaxWidth(),
                title = stringResource(R.string.telegram_search_group_title)
            ) {
                TgSearchSectionHeader(
                    text = stringResource(R.string.telegram_search_advanced_movies_title)
                )
                SettingsToggleRow(
                    title = stringResource(R.string.telegram_search_i18n_movies_title),
                    subtitle = stringResource(R.string.telegram_search_i18n_movies_subtitle),
                    checked = moviesI18nEnabled,
                    onToggle = {
                        viewModel.setMoviesI18nEnabled(!moviesI18nEnabled)
                    }
                )
                // TG-START: discard series files in movie searches (re-apply on upstream merge)
                SettingsToggleRow(
                    title = stringResource(R.string.telegram_search_discard_series_title),
                    subtitle = stringResource(R.string.telegram_search_discard_series_subtitle),
                    checked = discardSeriesInMovies,
                    onToggle = {
                        viewModel.setDiscardSeriesInMovies(!discardSeriesInMovies)
                    }
                )
                // TG-END
                TgSearchSectionHeader(
                    text = stringResource(R.string.telegram_search_advanced_series_title)
                )
                SettingsToggleRow(
                    title = stringResource(R.string.telegram_search_i18n_series_title),
                    subtitle = stringResource(R.string.telegram_search_i18n_series_subtitle),
                    checked = seriesI18nEnabled,
                    onToggle = {
                        viewModel.setSeriesI18nEnabled(!seriesI18nEnabled)
                    }
                )
                SettingsToggleRow(
                    title = stringResource(R.string.telegram_search_channel_context_title),
                    subtitle = stringResource(R.string.telegram_search_channel_context_subtitle),
                    checked = allowChannelContextSeriesMatch,
                    onToggle = {
                        viewModel.setAllowChannelContextSeriesMatch(!allowChannelContextSeriesMatch)
                    }
                )
            }
            // TG-START: download cache management (re-apply on upstream merge)
            Spacer(Modifier.height(20.dp))
            SettingsGroupCard(
                modifier = Modifier.fillMaxWidth(),
                title = stringResource(R.string.telegram_cache_group_title)
            ) {
                val cacheText = if (downloadCacheSizeMb < 0) {
                    stringResource(R.string.telegram_cache_measuring)
                } else {
                    stringResource(R.string.telegram_cache_size_mb, downloadCacheSizeMb)
                }
                val freedText = lastFreedMb?.let {
                    stringResource(R.string.telegram_cache_freed_mb, it)
                }
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = if (freedText != null) "$cacheText · $freedText" else cacheText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { viewModel.clearDownloadCache() }) {
                        Text(stringResource(R.string.telegram_cache_clear_action))
                    }
                }
            }
            // TG-END
            }
            // TG-END

            Spacer(Modifier.height(24.dp))
            OutlinedButton(
                onClick = onBackPress,
                // TG-START: TV focus + scroll (re-apply on upstream merge)
                modifier = Modifier.focusRequester(backFocusRequester)
                // TG-END
            ) {
                Text(stringResource(R.string.action_back))
            }
        }
    }
}

@Composable
private fun StatusText(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        textAlign = TextAlign.Center
    )
}

// TG-START: "Búsqueda TG" hierarchy (re-apply on upstream merge)
@Composable
private fun TgSearchSectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, top = 6.dp, bottom = 2.dp)
    )
}
// TG-END

@Composable
private fun QrPanel(link: String, onBackPress: () -> Unit) {
    val qrBitmap = remember(link) { QrCodeGenerator.generate(link, size = 420, margin = 2) }

    Column(
        modifier = Modifier
            .background(TgPaneBackground, RoundedCornerShape(16.dp))
            .border(1.dp, TgPaneBorder, RoundedCornerShape(16.dp))
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            bitmap = qrBitmap.asImageBitmap(),
            contentDescription = stringResource(R.string.telegram_qr_content_description),
            modifier = Modifier.size(320.dp)
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.telegram_qr_instructions),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ReadyPanel(
    firstName: String,
    onUnbind: () -> Unit,
    // TG-START: TV focus + scroll (re-apply on upstream merge)
    focusRequester: FocusRequester? = null
    // TG-END
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = if (firstName.isNotBlank()) {
                stringResource(R.string.telegram_linked_with_name, firstName)
            } else {
                stringResource(R.string.telegram_linked)
            },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onUnbind,
            // TG-START: TV focus + scroll (re-apply on upstream merge)
            modifier = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
            // TG-END
        ) {
            Text(stringResource(R.string.telegram_unbind))
        }
    }
}

// TG-START: per-device API credentials, option B (re-apply on upstream merge)
@Composable
private fun CredentialsForm(viewModel: TelegramAuthViewModel) {
    var apiId by remember { mutableStateOf("") }
    var apiHash by remember { mutableStateOf("") }
    val showError by viewModel.credentialsError.collectAsState()
    // TG-START: TV focus + scroll (Mi Box D-pad; re-apply on upstream merge)
    val apiIdFieldRequester = remember { FocusRequester() }
    val apiHashFieldRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { apiIdFieldRequester.requestFocus() }
    // TG-END

    LaunchedEffect(apiId, apiHash) {
        if (showError) viewModel.clearCredentialsError()
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.telegram_credentials_prompt),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
        )
        Spacer(Modifier.height(12.dp))
        TgInputField(
            value = apiId,
            onValueChange = { apiId = it },
            placeholder = stringResource(R.string.telegram_credentials_api_id_placeholder),
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Next,
            onImeAction = { apiHashFieldRequester.requestFocus() },
            // TG-START: TV focus + scroll (re-apply on upstream merge)
            modifier = Modifier.widthIn(max = 380.dp),
            fieldFocusRequester = apiIdFieldRequester
            // TG-END
        )
        Spacer(Modifier.height(12.dp))
        TgInputField(
            value = apiHash,
            onValueChange = { apiHash = it },
            placeholder = stringResource(R.string.telegram_credentials_api_hash_placeholder),
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Done,
            onImeAction = { viewModel.saveCredentials(apiId, apiHash) },
            // TG-START: TV focus + scroll (re-apply on upstream merge)
            modifier = Modifier.widthIn(max = 380.dp),
            fieldFocusRequester = apiHashFieldRequester
            // TG-END
        )
        if (showError) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.telegram_credentials_invalid),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { viewModel.saveCredentials(apiId, apiHash) },
            enabled = apiId.isNotBlank() && apiHash.isNotBlank()
        ) {
            Text(stringResource(R.string.action_continue))
        }
    }
}
// TG-END

@Composable
private fun PhoneForm(viewModel: TelegramAuthViewModel) {
    var phone by remember { mutableStateOf("") }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.telegram_phone_prompt),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
        )
        Spacer(Modifier.height(12.dp))
        TgInputField(
            value = phone,
            onValueChange = { phone = it },
            placeholder = stringResource(R.string.telegram_phone_placeholder),
            keyboardType = KeyboardType.Phone,
            imeAction = ImeAction.Done,
            onImeAction = { viewModel.submitPhoneNumber(phone) },
            modifier = Modifier.widthIn(max = 380.dp)
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { viewModel.submitPhoneNumber(phone) },
            enabled = phone.isNotBlank()
        ) {
            Text(stringResource(R.string.action_continue))
        }
    }
}

@Composable
private fun CodeForm(codeLength: Int, viewModel: TelegramAuthViewModel) {
    var code by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.telegram_code_prompt, codeLength),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
        )
        Spacer(Modifier.height(12.dp))
        TgInputField(
            value = code,
            onValueChange = { code = it },
            placeholder = stringResource(R.string.telegram_code_placeholder),
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done,
            onImeAction = { viewModel.submitCode(code) },
            // TG-START: D-pad text entry (re-apply on upstream merge)
            modifier = Modifier.widthIn(max = 380.dp),
            fieldFocusRequester = focusRequester
            // TG-END
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { viewModel.submitCode(code) },
            enabled = code.isNotBlank()
        ) {
            Text(stringResource(R.string.action_continue))
        }
    }
}

@Composable
private fun PasswordForm(viewModel: TelegramAuthViewModel) {
    var password by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.telegram_password_prompt),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
        )
        Spacer(Modifier.height(12.dp))
        TgInputField(
            value = password,
            onValueChange = { password = it },
            placeholder = stringResource(R.string.telegram_password_placeholder),
            keyboardType = KeyboardType.Password,
            isPassword = true,
            imeAction = ImeAction.Done,
            onImeAction = { viewModel.submitPassword(password) },
            // TG-START: D-pad text entry (re-apply on upstream merge)
            modifier = Modifier.widthIn(max = 380.dp),
            fieldFocusRequester = focusRequester
            // TG-END
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { viewModel.submitPassword(password) },
            enabled = password.isNotBlank()
        ) {
            Text(stringResource(R.string.action_continue))
        }
    }
}

/** TV-friendly single-line input modeled after the account screen's InputField. */
@Composable
private fun TgInputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType,
    imeAction: ImeAction,
    onImeAction: () -> Unit,
    modifier: Modifier = Modifier,
    isPassword: Boolean = false,
    // TG-START: D-pad text entry (Mi Box: Surface consumed focus, IME never opened;
    // re-apply on upstream merge)
    fieldFocusRequester: FocusRequester? = null
    // TG-END
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    var isEditing by remember { mutableStateOf(false) }
    // TG-START: D-pad text entry (re-apply on upstream merge)
    val innerRequester = remember { FocusRequester() }
    val editorRequester = fieldFocusRequester ?: innerRequester
    var editorFocused by remember { mutableStateOf(false) }
    LaunchedEffect(editorFocused) {
        if (editorFocused) {
            keyboardController?.show()
        } else {
            keyboardController?.hide()
        }
    }
    // TG-END

    Surface(
        // TG-START: D-pad text entry (re-apply on upstream merge)
        onClick = {
            isEditing = true
            editorRequester.requestFocus()
        },
        // TG-END
        modifier = modifier,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                // TG-START: D-pad text entry (re-apply on upstream merge)
                .focusRequester(editorRequester)
                .onFocusChanged { editorFocused = it.isFocused }
                // TG-END
                .padding(horizontal = 18.dp, vertical = 14.dp),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            keyboardActions = KeyboardActions(
                onDone = {
                    onImeAction()
                    isEditing = false
                    keyboardController?.hide()
                },
                onNext = {
                    onImeAction()
                    isEditing = false
                    keyboardController?.hide()
                }
            ),
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            visualTransformation = if (isPassword) {
                PasswordVisualTransformation()
            } else {
                androidx.compose.ui.text.input.VisualTransformation.None
            },
            decorationBox = { innerTextField ->
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                    )
                }
                innerTextField()
            }
        )
    }
}
