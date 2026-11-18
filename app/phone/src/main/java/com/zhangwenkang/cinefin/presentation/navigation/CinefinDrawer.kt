package com.zhangwenkang.cinefin.presentation.navigation

import androidx.annotation.DrawableRes
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.navSelectedContent
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.models.CollectionType

/**
 * 抽屉头部（设计规范 §8.6）：品牌 + 当前账号 / 服务器一行，服务器可点进服务器管理。
 *
 * 视觉保持"安静"：无卡片、无底色，只用字号与明度差建立层级；不再出现选中竖条 / 色点。
 */
@Composable
fun CinefinDrawerHeader(
    userName: String?,
    serverName: String?,
    serverAddress: String?,
    onOpenServers: () -> Unit,
) {
    val colors = LocalCinefinColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier =
                    Modifier.size(40.dp).clip(CinefinShapes.Xs).background(Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_logo),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(Modifier.width(CinefinSpacing.Space3))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(CoreR.string.app_name),
                    style = CinefinType.TitleMedium,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text =
                        listOfNotNull(userName, serverName)
                            .takeIf { it.isNotEmpty() }
                            ?.joinToString(" · ") ?: stringResource(CoreR.string.drawer_no_server),
                    style = CinefinType.BodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(CinefinSpacing.Space2))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier.fillMaxWidth()
                    .clip(CinefinShapes.Xs)
                    .background(Color.Transparent)
                    .clickable(onClick = onOpenServers)
                    .height(30.dp)
                    .padding(horizontal = CinefinSpacing.Space2),
        ) {
            Icon(
                painter = painterResource(CoreR.drawable.ic_server),
                contentDescription = null,
                tint = colors.onSurfaceFaint,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(CinefinSpacing.Space2))
            Text(
                text = serverName ?: stringResource(CoreR.string.drawer_no_server),
                style = CinefinType.BodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (serverAddress != null) {
                Text(
                    text = serverAddress,
                    style = CinefinType.BodySmall,
                    color = colors.onSurfaceFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 导航图标着色（W3-T2 K1）：选中 [navSelectedContent]（深色主题 = `aurora1`、浅色主题 = 加深值，与文字同色）、 未选中
 * `textSecondary`（`OnSurfaceVariant`），与容器选中 pill 保持同一套离散语言。
 */
@Composable
internal fun navIconTint(selected: Boolean): Color {
    val colors = LocalCinefinColors.current
    return if (selected) navSelectedContent(colors) else colors.onSurfaceVariant
}

/** 导航条目图标槽：统一 24dp 资源 + 选中态着色。 */
internal fun navIcon(@DrawableRes res: Int): @Composable (Boolean) -> Unit = { selected ->
    Icon(
        painter = painterResource(res),
        contentDescription = null,
        tint = navIconTint(selected),
    )
}

/** 库类型 → 图标：每一类库都有自己的图标，一眼能分清电影、动漫、音乐、图书。 */
internal fun libraryIconRes(type: CollectionType): Int =
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
