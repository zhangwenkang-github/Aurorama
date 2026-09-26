package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyServer
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyServerAddress
import com.zhangwenkang.cinefin.models.ServerWithAddresses
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.presentation.theme.spacings
import com.zhangwenkang.cinefin.setup.presentation.servers.ServersAction
import com.zhangwenkang.cinefin.setup.presentation.servers.ServersEvent
import com.zhangwenkang.cinefin.setup.presentation.servers.ServersState
import com.zhangwenkang.cinefin.setup.presentation.servers.ServersViewModel
import com.zhangwenkang.cinefin.utils.ObserveAsEvents

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSelectionBottomSheet(
    currentServerId: String,
    onUpdate: () -> Unit,
    onManage: () -> Unit,
    onDismissRequest: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(),
    viewModel: ServersViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(true) { viewModel.loadServers() }

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            is ServersEvent.ServerChanged -> onUpdate()
            is ServersEvent.AddressChanged -> onUpdate()
        }
    }

    ServerSelectionBottomSheetLayout(
        currentServerId = currentServerId,
        state = state,
        onManage = onManage,
        onAction = { action -> viewModel.onAction(action) },
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerSelectionBottomSheetLayout(
    currentServerId: String,
    state: ServersState,
    onManage: () -> Unit,
    onAction: (action: ServersAction) -> Unit,
    onDismissRequest: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(),
) {
    ModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = sheetState) {
        LazyColumn(
            contentPadding =
                PaddingValues(
                    start = MaterialTheme.spacings.medium,
                    end = MaterialTheme.spacings.medium,
                    bottom = MaterialTheme.spacings.default,
                ),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
        ) {
            items(items = state.servers, key = { it.server.id }) { server ->
                ServerSelectionItem(
                    server = server,
                    selected = server.server.id == currentServerId,
                    onClick = { onAction(ServersAction.OnServerClick(server.server.id)) },
                    onClickAddress = { addressId ->
                        onAction(ServersAction.OnAddressClick(addressId = addressId))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item(key = "manage") {
                OutlinedButton(onClick = onManage, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(CoreR.string.manage_servers))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Preview
private fun ServerSelectionBottomSheetPreview() {
    CinefinTheme {
        ServerSelectionBottomSheetLayout(
            currentServerId = "",
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
            onManage = {},
            onAction = {},
            onDismissRequest = {},
            sheetState = rememberStandardBottomSheetState(initialValue = SheetValue.Expanded),
        )
    }
}
