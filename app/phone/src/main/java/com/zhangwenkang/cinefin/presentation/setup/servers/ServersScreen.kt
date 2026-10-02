package com.zhangwenkang.cinefin.presentation.setup.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButton
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonSize
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonVariant
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyServer
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyServerAddress
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.models.ServerWithAddresses
import com.zhangwenkang.cinefin.presentation.components.TopBarAction
import com.zhangwenkang.cinefin.presentation.setup.components.RootLayout
import com.zhangwenkang.cinefin.presentation.setup.components.ServerBottomSheet
import com.zhangwenkang.cinefin.presentation.setup.components.ServerItem
import com.zhangwenkang.cinefin.presentation.setup.components.SetupBrandMark
import com.zhangwenkang.cinefin.presentation.setup.components.TrustedCertificatesDialog
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.setup.R as SetupR
import com.zhangwenkang.cinefin.setup.presentation.servers.ServersAction
import com.zhangwenkang.cinefin.setup.presentation.servers.ServersEvent
import com.zhangwenkang.cinefin.setup.presentation.servers.ServersState
import com.zhangwenkang.cinefin.setup.presentation.servers.ServersViewModel
import com.zhangwenkang.cinefin.utils.ObserveAsEvents
import kotlinx.coroutines.launch

@Composable
fun ServersScreen(
    navigateToUsers: () -> Unit,
    navigateToAddresses: (serverId: String) -> Unit,
    onAddClick: () -> Unit,
    onBackClick: () -> Unit,
    showBack: Boolean = true,
    viewModel: ServersViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(true) { viewModel.loadServers() }

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            is ServersEvent.ServerChanged -> navigateToUsers()
            else -> Unit
        }
    }

    ServersScreenLayout(
        state = state,
        showBack = showBack,
        onAction = { action ->
            when (action) {
                is ServersAction.OnAddClick -> onAddClick()
                is ServersAction.OnBackClick -> onBackClick()
                is ServersAction.NavigateToAddresses -> navigateToAddresses(action.serverId)
                else -> Unit
            }
            viewModel.onAction(action)
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServersScreenLayout(
    state: ServersState,
    showBack: Boolean = true,
    onAction: (ServersAction) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState()
    var openDeleteDialog by remember { mutableStateOf(false) }
    var showBottomSheet by remember { mutableStateOf(false) }
    var showTrustedCertificatesDialog by remember { mutableStateOf(false) }
    var selectedServer by remember { mutableStateOf<ServerWithAddresses?>(null) }
    val colors = LocalCinefinColors.current

    RootLayout {
        Column(
            modifier =
                Modifier.padding(horizontal = CinefinSpacing.Space8)
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .align(Alignment.Center)
        ) {
            Spacer(modifier = Modifier.weight(0.15f))
            SetupBrandMark(markSize = 44.dp, subtitle = stringResource(CoreR.string.app_tagline))
            Spacer(modifier = Modifier.height(CinefinSpacing.Space10))
            Text(
                text = stringResource(SetupR.string.servers),
                style = CinefinType.HeadlineMedium,
                color = colors.onSurface,
            )
            Spacer(modifier = Modifier.height(CinefinSpacing.Space8))
            if (state.servers.isEmpty()) {
                Text(
                    text = stringResource(SetupR.string.servers_no_servers),
                    style = CinefinType.BodyMedium,
                    color = colors.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.weight(1f))
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) {
                    items(state.servers) { server ->
                        ServerItem(
                            name = server.server.name,
                            address = server.addresses.first().address,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                onAction(ServersAction.OnServerClick(serverId = server.server.id))
                            },
                            onLongClick = {
                                selectedServer = server
                                showBottomSheet = true
                            },
                        )
                    }
                }
            }
        }
        if (showBack) {
            TopBarAction(
                icon = CoreR.drawable.ic_arrow_left,
                onClick = { onAction(ServersAction.OnBackClick) },
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        CinefinButton(
            text = stringResource(SetupR.string.servers_btn_add_server),
            onClick = { onAction(ServersAction.OnAddClick) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(CinefinSpacing.Space6),
            variant = CinefinButtonVariant.Filled,
            size = CinefinButtonSize.Medium,
            icon = { tint ->
                Icon(
                    painter = painterResource(CoreR.drawable.ic_plus),
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(18.dp),
                )
            },
        )
        TopBarAction(
            icon = CoreR.drawable.ic_certificate,
            contentDescription = stringResource(SetupR.string.certificate_trusted_title),
            onClick = {
                showTrustedCertificatesDialog = true
                onAction(ServersAction.LoadTrustedCertificates)
            },
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 8.dp),
        )
    }

    if (showTrustedCertificatesDialog) {
        TrustedCertificatesDialog(
            certificates = state.trustedCertificates,
            onClear = { trustKey -> onAction(ServersAction.ClearTrustedCertificate(trustKey)) },
            onDismiss = { showTrustedCertificatesDialog = false },
        )
    }

    if (openDeleteDialog && selectedServer != null) {
        AlertDialog(
            title = { Text(text = stringResource(SetupR.string.remove_server_dialog)) },
            text = {
                Text(
                    text =
                        stringResource(
                            SetupR.string.remove_server_dialog_text,
                            selectedServer!!.server.name,
                        )
                )
            },
            onDismissRequest = { openDeleteDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        openDeleteDialog = false
                        scope
                            .launch { sheetState.hide() }
                            .invokeOnCompletion {
                                if (!sheetState.isVisible) {
                                    showBottomSheet = false
                                }
                            }
                        onAction(ServersAction.DeleteServer(selectedServer!!.server.id))
                    }
                ) {
                    Text(text = stringResource(SetupR.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { openDeleteDialog = false }) {
                    Text(text = stringResource(SetupR.string.cancel))
                }
            },
        )
    }

    if (showBottomSheet && selectedServer != null) {
        ServerBottomSheet(
            name = selectedServer!!.server.name,
            address = selectedServer!!.addresses.first().address,
            onAddresses = {
                showBottomSheet = false
                onAction(ServersAction.NavigateToAddresses(selectedServer!!.server.id))
            },
            onRemoveServer = { openDeleteDialog = true },
            onDismissRequest = { showBottomSheet = false },
            sheetState = sheetState,
        )
    }
}

@PreviewScreenSizes
@Composable
private fun ServersScreenLayoutPreview() {
    CinefinTheme {
        ServersScreenLayout(
            state =
                ServersState(
                    servers =
                        listOf(
                            ServerWithAddresses(
                                server = dummyServer,
                                addresses = listOf(dummyServerAddress),
                                user = null,
                            )
                        )
                ),
            onAction = {},
        )
    }
}
