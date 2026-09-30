package com.zhangwenkang.cinefin.presentation.setup.addserver

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.presentation.components.TopBarAction
import com.zhangwenkang.cinefin.presentation.setup.components.DiscoveredServerItem
import com.zhangwenkang.cinefin.presentation.setup.components.LoadingButton
import com.zhangwenkang.cinefin.presentation.setup.components.RootLayout
import com.zhangwenkang.cinefin.presentation.setup.components.SetupBrandMark
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.setup.R as SetupR
import com.zhangwenkang.cinefin.setup.presentation.addserver.AddServerAction
import com.zhangwenkang.cinefin.setup.presentation.addserver.AddServerEvent
import com.zhangwenkang.cinefin.setup.presentation.addserver.AddServerState
import com.zhangwenkang.cinefin.setup.presentation.addserver.AddServerViewModel
import com.zhangwenkang.cinefin.utils.ObserveAsEvents

@Composable
fun AddServerScreen(
    onSuccess: () -> Unit,
    onBackClick: () -> Unit,
    viewModel: AddServerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(true) { viewModel.discoverServers() }

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            is AddServerEvent.Success -> onSuccess()
        }
    }

    AddServerScreenLayout(
        state = state,
        onAction = { action ->
            when (action) {
                is AddServerAction.OnBackClick -> onBackClick()
                else -> Unit
            }
            viewModel.onAction(action)
        },
    )
}

@Composable
private fun AddServerScreenLayout(state: AddServerState, onAction: (AddServerAction) -> Unit) {
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    val scrollState = rememberScrollState()

    var serverAddress by rememberSaveable { mutableStateOf("") }

    val doConnect = { onAction(AddServerAction.OnConnectClick(serverAddress)) }

    LaunchedEffect(true) { focusRequester.requestFocus() }

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
            SetupBrandMark(markSize = 44.dp, subtitle = stringResource(CoreR.string.app_tagline))
            Spacer(modifier = Modifier.height(CinefinSpacing.Space10))
            Text(
                text = stringResource(SetupR.string.add_server),
                style = CinefinType.HeadlineMedium,
                color = LocalCinefinColors.current.onSurface,
            )
            Spacer(modifier = Modifier.height(CinefinSpacing.Space4))
            AnimatedVisibility(state.discoveredServers.isNotEmpty()) {
                LazyRow {
                    items(state.discoveredServers) { discoveredServer ->
                        DiscoveredServerItem(
                            name = discoveredServer.name,
                            onClick = {
                                serverAddress = discoveredServer.address
                                onAction(AddServerAction.OnConnectClick(discoveredServer.address))
                            },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(CinefinSpacing.Space4))
            OutlinedTextField(
                value = serverAddress,
                leadingIcon = {
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_server),
                        contentDescription = null,
                    )
                },
                onValueChange = { serverAddress = it },
                label = {
                    Text(text = stringResource(SetupR.string.edit_text_server_address_hint))
                },
                singleLine = true,
                keyboardOptions =
                    KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go,
                    ),
                keyboardActions = KeyboardActions(onGo = { doConnect() }),
                isError = state.error != null,
                enabled = !state.isLoading,
                supportingText = {
                    if (state.error != null) {
                        Text(
                            text = state.error!!.joinToString { it.asString(context.resources) },
                            color = LocalCinefinColors.current.error,
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
            LoadingButton(
                text = stringResource(SetupR.string.add_server_btn_connect),
                onClick = { doConnect() },
                isLoading = state.isLoading,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        TopBarAction(
            icon = CoreR.drawable.ic_arrow_left,
            onClick = { onAction(AddServerAction.OnBackClick) },
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@PreviewScreenSizes
@Composable
private fun AddServerScreenLayoutPreview() {
    CinefinTheme { AddServerScreenLayout(state = AddServerState(), onAction = {}) }
}
