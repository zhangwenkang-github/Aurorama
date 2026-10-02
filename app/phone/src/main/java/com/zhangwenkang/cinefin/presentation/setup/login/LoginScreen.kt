package com.zhangwenkang.cinefin.presentation.setup.login

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButton
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonSize
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonVariant
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.presentation.components.TopBarAction
import com.zhangwenkang.cinefin.presentation.setup.components.CertificateTrustDialog
import com.zhangwenkang.cinefin.presentation.setup.components.LoadingButton
import com.zhangwenkang.cinefin.presentation.setup.components.RootLayout
import com.zhangwenkang.cinefin.presentation.setup.components.SetupBrandMark
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.setup.R as SetupR
import com.zhangwenkang.cinefin.setup.presentation.login.LoginAction
import com.zhangwenkang.cinefin.setup.presentation.login.LoginEvent
import com.zhangwenkang.cinefin.setup.presentation.login.LoginState
import com.zhangwenkang.cinefin.setup.presentation.login.LoginViewModel
import com.zhangwenkang.cinefin.utils.ObserveAsEvents

@Composable
fun LoginScreen(
    onSuccess: () -> Unit,
    onChangeServerClick: () -> Unit,
    onBackClick: () -> Unit,
    prefilledUsername: String? = null,
    /** W36：无账号离线模式入口。 */
    onOfflineClick: () -> Unit = {},
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(true) {
        viewModel.loadServer()
        viewModel.loadDisclaimer()
        viewModel.loadQuickConnectEnabled()
    }

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            is LoginEvent.Success -> onSuccess()
        }
    }

    LoginScreenLayout(
        state = state,
        onAction = { action ->
            when (action) {
                is LoginAction.OnChangeServerClick -> onChangeServerClick()
                is LoginAction.OnBackClick -> onBackClick()
                else -> Unit
            }
            viewModel.onAction(action)
        },
        prefilledUsername = prefilledUsername,
        onOfflineClick = onOfflineClick,
    )
}

@Composable
private fun LoginScreenLayout(
    state: LoginState,
    onAction: (LoginAction) -> Unit,
    prefilledUsername: String? = null,
    onOfflineClick: () -> Unit = {},
) {
    val scrollState = rememberScrollState()
    var username by rememberSaveable { mutableStateOf(prefilledUsername ?: "") }
    var password by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }

    val doLogin = { onAction(LoginAction.OnLoginClick(username, password)) }
    val colors = LocalCinefinColors.current

    RootLayout {
        Column(
            verticalArrangement = Arrangement.Center,
            modifier =
                Modifier.fillMaxHeight()
                    .padding(horizontal = CinefinSpacing.Space8)
                    .widthIn(max = 480.dp)
                    .align(Alignment.Center)
                    .verticalScroll(scrollState),
        ) {
            SetupBrandMark(markSize = 44.dp, subtitle = state.serverName)
            Spacer(modifier = Modifier.height(CinefinSpacing.Space10))
            Text(
                text = stringResource(SetupR.string.login),
                style = CinefinType.HeadlineMedium,
                color = colors.onSurface,
            )
            Spacer(modifier = Modifier.height(CinefinSpacing.Space1))
            Text(
                text = stringResource(SetupR.string.server_subtitle, state.serverName ?: ""),
                style = CinefinType.BodyMedium,
                color = colors.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(CinefinSpacing.Space8))
            OutlinedTextField(
                value = username,
                leadingIcon = {
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_user),
                        contentDescription = null,
                    )
                },
                onValueChange = { username = it },
                label = { Text(text = stringResource(SetupR.string.edit_text_username_hint)) },
                singleLine = true,
                keyboardOptions =
                    KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
                isError = state.error != null,
                enabled = !state.isLoading,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = password,
                leadingIcon = {
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_lock),
                        contentDescription = null,
                    )
                },
                trailingIcon = {
                    TopBarAction(
                        icon =
                            if (passwordVisible) CoreR.drawable.ic_eye_off
                            else CoreR.drawable.ic_eye,
                        onClick = { passwordVisible = !passwordVisible },
                    )
                },
                onValueChange = { password = it },
                label = { Text(text = stringResource(SetupR.string.edit_text_password_hint)) },
                singleLine = true,
                keyboardOptions =
                    KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Go,
                    ),
                keyboardActions = KeyboardActions(onGo = { doLogin() }),
                visualTransformation =
                    if (passwordVisible) VisualTransformation.None
                    else PasswordVisualTransformation(),
                isError = state.error != null,
                enabled = !state.isLoading,
                supportingText = {
                    if (state.error != null) {
                        Text(
                            text = state.error!!.asString(),
                            color = colors.error,
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            LoadingButton(
                text = stringResource(SetupR.string.login_btn_login),
                onClick = { doLogin() },
                isLoading = state.isLoading,
                modifier = Modifier.fillMaxWidth(),
            )
            AnimatedVisibility(state.quickConnectEnabled) {
                Column {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HorizontalDivider(
                            modifier =
                                Modifier.weight(1f).padding(horizontal = CinefinSpacing.Space3)
                        )
                        Text(
                            text = stringResource(SetupR.string.or),
                            color = DividerDefaults.color,
                            style = CinefinType.BodySmall,
                        )
                        HorizontalDivider(
                            modifier =
                                Modifier.weight(1f).padding(horizontal = CinefinSpacing.Space3)
                        )
                    }
                    Spacer(modifier = Modifier.height(CinefinSpacing.Space2))
                    CinefinButton(
                        text =
                            state.quickConnectCode
                                ?: stringResource(SetupR.string.login_btn_quick_connect),
                        onClick = { onAction(LoginAction.OnQuickConnectClick) },
                        modifier = Modifier.fillMaxWidth(),
                        variant = CinefinButtonVariant.Outlined,
                        size = CinefinButtonSize.Large,
                        icon =
                            if (state.quickConnectCode != null) {
                                { tint ->
                                    CircularProgressIndicator(
                                        color = tint,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            } else {
                                null
                            },
                    )
                }
            }
            if (state.disclaimer != null) {
                Spacer(modifier = Modifier.height(CinefinSpacing.Space3))
                Text(text = state.disclaimer!!, style = CinefinType.BodySmall)
            }
            Spacer(modifier = Modifier.height(CinefinSpacing.Space3))
            CinefinButton(
                text = "离线模式",
                onClick = onOfflineClick,
                modifier = Modifier.fillMaxWidth(),
                variant = CinefinButtonVariant.Text,
                size = CinefinButtonSize.Medium,
            )
        }
        TopBarAction(
            icon = CoreR.drawable.ic_arrow_left,
            onClick = { onAction(LoginAction.OnBackClick) },
            modifier = Modifier.padding(start = 8.dp),
        )
        TopBarAction(
            icon = CoreR.drawable.ic_server,
            onClick = { onAction(LoginAction.OnChangeServerClick) },
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 8.dp),
        )
    }

    state.certificatePrompt?.let { prompt ->
        CertificateTrustDialog(
            prompt = prompt,
            onTrust = { onAction(LoginAction.OnTrustCertificate) },
            onDismiss = { onAction(LoginAction.OnDismissCertificatePrompt) },
        )
    }
}

@PreviewScreenSizes
@Composable
private fun AddServerScreenLayoutPreview() {
    CinefinTheme {
        LoginScreenLayout(
            state =
                LoginState(
                    serverName = "Demo Server",
                    quickConnectEnabled = true,
                    disclaimer = "Sample disclaimer",
                ),
            onAction = {},
        )
    }
}
