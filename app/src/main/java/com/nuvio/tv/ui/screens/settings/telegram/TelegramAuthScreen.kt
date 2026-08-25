@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings.telegram

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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

private val TgPaneBackground = Color.White.copy(alpha = 0.022f)
private val TgPaneBorder = Color.White.copy(alpha = 0.07f)

@Composable
fun TelegramAuthScreen(
    onBackPress: () -> Unit = {},
    viewModel: TelegramAuthViewModel = hiltViewModel()
) {
    val authState by viewModel.authState.collectAsState()

    BackHandler { onBackPress() }

    LaunchedEffect(Unit) { viewModel.initialize() }

    // QR-first: TDLib lands on WaitPhoneNumber; flip it to the link-device flow.
    LaunchedEffect(authState) {
        if (authState is TelegramAuthState.WaitingPhoneNumber) {
            viewModel.requestQrCode()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 64.dp),
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

                is TelegramAuthState.MissingCredentials ->
                    StatusText(stringResource(R.string.telegram_error_missing_credentials))

                is TelegramAuthState.WaitingQrCode -> QrPanel(state.link, onBackPress)
                is TelegramAuthState.WaitingPhoneNumber -> PhoneForm(viewModel)
                is TelegramAuthState.WaitingCode -> CodeForm(state.codeLength, viewModel)
                is TelegramAuthState.WaitingPassword -> PasswordForm(viewModel)

                is TelegramAuthState.Ready -> ReadyPanel(
                    firstName = state.firstName,
                    onUnbind = { viewModel.unbind() }
                )

                is TelegramAuthState.Error -> StatusText(
                    stringResource(R.string.telegram_error_generic, state.message)
                )
            }

            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = onBackPress) {
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
private fun ReadyPanel(firstName: String, onUnbind: () -> Unit) {
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
        Button(onClick = onUnbind) {
            Text(stringResource(R.string.telegram_unbind))
        }
    }
}

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
            modifier = Modifier.widthIn(max = 380.dp).focusRequester(focusRequester)
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
            modifier = Modifier.widthIn(max = 380.dp).focusRequester(focusRequester)
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
    isPassword: Boolean = false
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    var isEditing by remember { mutableStateOf(false) }

    Surface(
        onClick = { isEditing = true },
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
