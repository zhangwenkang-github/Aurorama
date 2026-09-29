package com.zhangwenkang.cinefin.presentation.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.models.CollectionType
import com.zhangwenkang.cinefin.models.FindroidCollection
import com.zhangwenkang.cinefin.presentation.theme.spacings

/**
 * 主导航抽屉：品牌 → 浏览 → 系统 → 当前账号。
 *
 * 视觉上刻意"安静"：没有卡片、没有图标底色，只有发丝线与一处朱砂—— 选中的那一项。信息层级靠字号与明度差建立，服务器与账号收在抽屉底部， 首页因此可以把整屏留给海报。
 *
 * 「服务器控制台」是管理员专属入口：普通账号打开也只会看到空白与无权限提示， 因此这里直接按账号权限决定是否显示。
 */
@Composable
fun CinefinDrawer(
    currentRoute: String?,
    homeRoute: Any,
    mediaRoute: Any,
    downloadsRoute: Any,
    settingsRoute: Any,
    serversRoute: Any,
    consoleRoute: Any,
    metadataRoute: Any,
    showMedia: Boolean,
    isOpen: Boolean = false,
    onNavigate: (Any) -> Unit,
    onOpenLibrary: (FindroidCollection) -> Unit,
    onClose: () -> Unit,
    viewModel: DrawerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 每次打开抽屉都刷新一次账号信息：切换用户、修改权限后不必重启应用
    LaunchedEffect(isOpen) { if (isOpen) viewModel.load() }

    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        drawerContentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.width(304.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = MaterialTheme.spacings.medium)
        ) {
            Spacer(modifier = Modifier.height(MaterialTheme.spacings.large))

            // 品牌与账号：一行标识，一行身份
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_logo),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(40.dp),
                )
                Spacer(modifier = Modifier.width(MaterialTheme.spacings.medium))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(CoreR.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text =
                            listOfNotNull(state.userName, state.serverName)
                                .takeIf { it.isNotEmpty() }
                                ?.joinToString(" · ")
                                ?: stringResource(CoreR.string.drawer_no_server),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(modifier = Modifier.height(MaterialTheme.spacings.large))

            DrawerNavItem(
                titleRes = CoreR.string.title_home,
                iconRes = CoreR.drawable.ic_home,
                selected = currentRoute == homeRoute::class.qualifiedName,
                onClick = { onNavigate(homeRoute) },
            )
            if (showMedia) {
                DrawerSectionLabel(textRes = CoreR.string.drawer_section_media)
                DrawerNavItem(
                    titleRes = CoreR.string.title_media,
                    iconRes = CoreR.drawable.ic_library,
                    selected = currentRoute == mediaRoute::class.qualifiedName,
                    onClick = { onNavigate(mediaRoute) },
                )
                // 服务器上的每一个库都直接列出来（含音乐库 / 图书库 / 家庭视频 / 播放列表）
                state.libraries.forEach { library ->
                    DrawerNavItemRaw(
                        title = library.name,
                        iconRes = libraryIcon(library.type),
                        selected = false,
                        onClick = { onOpenLibrary(library) },
                    )
                }
            }
            DrawerNavItem(
                titleRes = CoreR.string.title_download,
                iconRes = CoreR.drawable.ic_download,
                selected = currentRoute == downloadsRoute::class.qualifiedName,
                onClick = { onNavigate(downloadsRoute) },
            )

            // 仅管理员可见：控制台与媒体资料管理器（普通账号打开也只会看到无权限提示）
            if (state.isAdministrator) {
                DrawerSectionLabel(textRes = CoreR.string.drawer_section_management)
                DrawerNavItem(
                    titleRes = CoreR.string.title_console,
                    iconRes = CoreR.drawable.ic_globe,
                    selected = currentRoute == consoleRoute::class.qualifiedName,
                    onClick = { onNavigate(consoleRoute) },
                )
                DrawerNavItem(
                    titleRes = CoreR.string.title_metadata_manager,
                    iconRes = CoreR.drawable.ic_database,
                    selected = currentRoute == metadataRoute::class.qualifiedName,
                    onClick = { onNavigate(metadataRoute) },
                )
            }

            DrawerSectionLabel(textRes = CoreR.string.drawer_section_user)

            DrawerNavItem(
                titleRes = CoreR.string.title_settings,
                iconRes = CoreR.drawable.ic_settings,
                selected = currentRoute == settingsRoute::class.qualifiedName,
                onClick = { onNavigate(settingsRoute) },
            )

            Spacer(modifier = Modifier.height(MaterialTheme.spacings.medium))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(MaterialTheme.spacings.medium))

            // 服务器：一行地址，点击进入服务器管理
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier.fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .clickable { onNavigate(serversRoute) }
                        .padding(vertical = MaterialTheme.spacings.small),
            ) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_server),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(MaterialTheme.spacings.medium))
                Column(modifier = Modifier.weight(1f)) {
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
                Icon(
                    painter = painterResource(CoreR.drawable.ic_arrow_right),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(16.dp),
                )
            }

            Spacer(modifier = Modifier.height(MaterialTheme.spacings.large))
        }
    }
}

/** 抽屉里的一行导航：左侧是选中时出现的朱砂竖线，右侧是图标与文字。 未选中项不画任何底色，让抽屉保持一片安静的墨色。 */
@Composable
private fun DrawerNavItem(
    titleRes: Int,
    iconRes: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    DrawerNavItemRaw(
        title = stringResource(titleRes),
        iconRes = iconRes,
        selected = selected,
        onClick = onClick,
    )
}

/** 与 [DrawerNavItem] 相同，只是标题直接给字符串（用于服务器上的库名）。 */
@Composable
private fun DrawerNavItemRaw(
    title: String,
    iconRes: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val contentColor = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.fillMaxWidth()
                .height(48.dp)
                .clip(MaterialTheme.shapes.small)
                .clickable(onClick = onClick),
    ) {
        Box(
            modifier =
                Modifier.width(3.dp)
                    .height(20.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (selected) accent else Color.Transparent)
        )
        Spacer(modifier = Modifier.width(MaterialTheme.spacings.medium))
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(MaterialTheme.spacings.medium))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 库类型 → 图标：每一类库都有自己的图标，一眼能分清电影、动漫、音乐、图书。 */
private fun libraryIcon(type: CollectionType): Int =
    when (type) {
        CollectionType.Music -> CoreR.drawable.ic_music
        CollectionType.Books -> CoreR.drawable.ic_book
        CollectionType.Playlists -> CoreR.drawable.ic_playlist
        CollectionType.HomeVideos -> CoreR.drawable.ic_video
        CollectionType.BoxSets -> CoreR.drawable.ic_star
        CollectionType.Mixed,
        CollectionType.Folders -> CoreR.drawable.ic_library
        else -> CoreR.drawable.ic_film
    }

/** 抽屉分区标题：一行小字，上方留白，下方一条发丝线。 */
@Composable
private fun DrawerSectionLabel(textRes: Int) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = MaterialTheme.spacings.large)) {
        Text(
            text = stringResource(textRes),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(bottom = MaterialTheme.spacings.small),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(modifier = Modifier.height(MaterialTheme.spacings.small))
    }
}
