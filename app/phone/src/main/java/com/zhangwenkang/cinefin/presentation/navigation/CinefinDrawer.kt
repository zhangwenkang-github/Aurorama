package com.zhangwenkang.cinefin.presentation.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.theme.Spacings
import com.zhangwenkang.cinefin.presentation.theme.spacings

/**
 * 应用主导航抽屉：品牌区 + 主导航 + 服务器信息。
 *
 * 采用抽屉而非常驻侧栏，是为了让内容区（海报墙）获得最大宽度；
 * 服务器信息也从首页顶部移到这里，避免干扰首页视觉。
 */
@Composable
fun CinefinDrawer(
    currentRoute: String?,
    homeRoute: Any,
    mediaRoute: Any,
    downloadsRoute: Any,
    settingsRoute: Any,
    serversRoute: Any,
    showMedia: Boolean,
    onNavigate: (Any) -> Unit,
    onClose: () -> Unit,
    viewModel: DrawerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.load() }

    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        drawerContentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.width(320.dp),
    ) {
        Spacer(modifier = Modifier.height(MaterialTheme.spacings.medium))

        // 品牌区
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(horizontal = MaterialTheme.spacings.default),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(CoreR.drawable.ic_logo),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(44.dp),
            )
            Spacer(modifier = Modifier.width(MaterialTheme.spacings.medium))
            Column {
                Text(
                    text = stringResource(CoreR.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                )
                state.userName?.let { userName ->
                    Text(
                        text = userName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(MaterialTheme.spacings.large))

        DrawerItem(
            titleRes = CoreR.string.title_home,
            iconRes = CoreR.drawable.ic_home,
            selected = currentRoute == homeRoute::class.qualifiedName,
            onClick = { onNavigate(homeRoute) },
        )
        if (showMedia) {
            DrawerItem(
                titleRes = CoreR.string.title_media,
                iconRes = CoreR.drawable.ic_library,
                selected = currentRoute == mediaRoute::class.qualifiedName,
                onClick = { onNavigate(mediaRoute) },
            )
        }
        DrawerItem(
            titleRes = CoreR.string.title_download,
            iconRes = CoreR.drawable.ic_download,
            selected = currentRoute == downloadsRoute::class.qualifiedName,
            onClick = { onNavigate(downloadsRoute) },
        )

        Spacer(modifier = Modifier.height(MaterialTheme.spacings.medium))
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = MaterialTheme.spacings.default),
            color = MaterialTheme.colorScheme.outlineVariant,
        )

        DrawerItem(
            titleRes = CoreR.string.title_settings,
            iconRes = CoreR.drawable.ic_settings,
            selected = false,
            onClick = { onNavigate(settingsRoute) },
        )

        Spacer(modifier = Modifier.height(MaterialTheme.spacings.medium))
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = MaterialTheme.spacings.default),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Spacer(modifier = Modifier.height(MaterialTheme.spacings.medium))

        // 服务器区：显示当前服务器与地址，点击进入服务器管理
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(horizontal = MaterialTheme.spacings.default),
            verticalArrangement = Arrangement.spacedBy(Spacings.extraSmall),
        ) {
            Text(
                text = stringResource(CoreR.string.drawer_server_section),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            NavigationDrawerItem(
                icon = {
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_server),
                        contentDescription = null,
                    )
                },
                label = {
                    Column {
                        Text(
                            text = state.serverName ?: stringResource(CoreR.string.drawer_no_server),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        state.serverAddress?.let { address ->
                            Text(
                                text = address,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                selected = false,
                onClick = { onNavigate(serversRoute) },
                colors = NavigationDrawerItemDefaults.colors(),
            )
        }

        Spacer(modifier = Modifier.height(MaterialTheme.spacings.default))
    }
}

@Composable
private fun DrawerItem(
    titleRes: Int,
    iconRes: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    NavigationDrawerItem(
        label = { Text(text = stringResource(titleRes)) },
        selected = selected,
        onClick = onClick,
        icon = { Icon(painter = painterResource(iconRes), contentDescription = null) },
        modifier =
            Modifier.padding(
                horizontal = MaterialTheme.spacings.small,
                vertical = Spacings.extraSmall / 2,
            ),
    )
}
