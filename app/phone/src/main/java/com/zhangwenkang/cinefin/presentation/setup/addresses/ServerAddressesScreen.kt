package com.zhangwenkang.cinefin.presentation.setup.addresses

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButton
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonSize
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonVariant
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyServerAddress
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.ProvideLumen
import com.zhangwenkang.cinefin.models.ServerAddress
import com.zhangwenkang.cinefin.presentation.components.TopBarAction
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.presentation.utils.rememberPageGutter
import com.zhangwenkang.cinefin.presentation.utils.rememberSafePadding
import com.zhangwenkang.cinefin.setup.R as SetupR
import com.zhangwenkang.cinefin.setup.presentation.addresses.ServerAddressesAction
import com.zhangwenkang.cinefin.setup.presentation.addresses.ServerAddressesState
import com.zhangwenkang.cinefin.setup.presentation.addresses.ServerAddressesViewModel

@Composable
fun ServerAddressesScreen(
    serverId: String,
    navigateBack: () -> Unit,
    viewModel: ServerAddressesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(true) { viewModel.loadAddresses(serverId) }

    ServerAddressesLayout(
        state = state,
        onAction = { action ->
            when (action) {
                is ServerAddressesAction.OnBackClick -> navigateBack()
                else -> Unit
            }
            viewModel.onAction(action)
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerAddressesLayout(state: ServerAddressesState, onAction: (ServerAddressesAction) -> Unit) {
    val layoutDirection = LocalLayoutDirection.current
    val safePadding = rememberSafePadding()

    val pageGutter = rememberPageGutter()
    val colors = LocalCinefinColors.current
    val paddingStart = safePadding.start + pageGutter
    val paddingTop = pageGutter
    val paddingEnd = safePadding.end + pageGutter
    val paddingBottom = safePadding.bottom + pageGutter

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var selectedAddress by remember { mutableStateOf<ServerAddress?>(null) }
    var openAddDialog by remember { mutableStateOf(false) }
    var openDeleteDialog by remember { mutableStateOf(false) }

    // 向导子页（服务器地址）同样属于影视域：统一 Lumen 皮肤（W6-VIS D23）
    ProvideLumen {
        Scaffold(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(SetupR.string.addresses),
                            style = CinefinType.TitleLarge,
                            color = colors.onSurface,
                        )
                    },
                    navigationIcon = {
                        TopBarAction(
                            icon = CoreR.drawable.ic_arrow_left,
                            onClick = { onAction(ServerAddressesAction.OnBackClick) },
                        )
                    },
                    windowInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout),
                    scrollBehavior = scrollBehavior,
                )
            },
            floatingActionButton = {
                CinefinButton(
                    text = stringResource(SetupR.string.add_address),
                    onClick = { openAddDialog = true },
                    variant = CinefinButtonVariant.Filled,
                    size = CinefinButtonSize.Medium,
                    icon = { tint ->
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_plus),
                            contentDescription = null,
                            tint = tint,
                        )
                    },
                )
            },
        ) { innerPadding ->
            Column(modifier = Modifier.padding(top = innerPadding.calculateTopPadding())) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding =
                        PaddingValues(
                            start =
                                paddingStart + innerPadding.calculateStartPadding(layoutDirection),
                            top = paddingTop,
                            end = paddingEnd + innerPadding.calculateEndPadding(layoutDirection),
                            bottom = paddingBottom + innerPadding.calculateBottomPadding(),
                        ),
                    verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space3),
                ) {
                    items(items = state.addresses, key = { it.id }) { address ->
                        Box(
                            modifier =
                                Modifier.fillMaxWidth()
                                    .clip(CinefinShapes.Md)
                                    .background(colors.surfaceContainer)
                                    .combinedClickable(
                                        onClick = {},
                                        onLongClick = {
                                            selectedAddress = address
                                            openDeleteDialog = true
                                        },
                                    )
                                    .padding(CinefinSpacing.Space4)
                        ) {
                            Text(
                                text = address.address,
                                style = CinefinType.BodyMedium,
                                color = colors.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }

    if (openAddDialog) {
        AddServerAddressDialog(
            onAdd = { address ->
                onAction(ServerAddressesAction.AddAddress(address))
                openAddDialog = false
            },
            onDismiss = { openAddDialog = false },
        )
    }

    if (openDeleteDialog && selectedAddress != null) {
        DeleteServerAddressDialog(
            address = selectedAddress!!.address,
            onConfirm = {
                onAction(ServerAddressesAction.DeleteAddress(selectedAddress!!.id))
                openDeleteDialog = false
            },
            onDismiss = { openDeleteDialog = false },
        )
    }
}

@PreviewScreenSizes
@Composable
private fun ServerAddressesLayoutPreview() {
    CinefinTheme {
        ServerAddressesLayout(
            state = ServerAddressesState(addresses = listOf(dummyServerAddress)),
            onAction = {},
        )
    }
}
