package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors
import com.zhangwenkang.cinefin.presentation.components.TopBarAction

/**
 * 首页顶栏：**app 图标（抽屉入口）** + 品牌字标 + 搜索。
 *
 * W7-R3（用户反馈 1）：手机 Compact 恢复抽屉后，入口从汉堡键换成品牌图标（S1 A 稿的"光圈棱镜"）；W46 起音乐 / 书架 / 媒体库三个一级页的顶栏入口也统一成同一枚
 * app 图标（24dp + 「打开侧栏」），二级页面保持原样。顶栏其余部分保持安静——首页的第一眼应该留给海报。
 */
@Composable
fun HomeTopBar(
    /** 抽屉入口；null = 不显示入口（控制台等不渲染侧柜的页面）。 */
    onOpenDrawer: (() -> Unit)?,
    onSearchClick: () -> Unit,
    isLoading: Boolean,
    isError: Boolean,
    onErrorClick: () -> Unit,
    onRetryClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val colors = LocalCinefinColors.current
        val media = LocalMediaColors.current

        if (onOpenDrawer != null) {
            TopBarAction(onClick = onOpenDrawer) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_logo),
                    contentDescription = stringResource(CoreR.string.nav_open_drawer),
                    tint = Color.Unspecified,
                    modifier = Modifier.size(24.dp),
                )
            }

            Spacer(modifier = Modifier.width(CinefinSpacing.Space2))
        }

        Text(
            text = stringResource(CoreR.string.app_name),
            style = CinefinType.TitleLarge,
            color = colors.onSurface,
        )

        Spacer(modifier = Modifier.weight(1f))

        AnimatedVisibility(visible = isError, enter = fadeIn(), exit = fadeOut()) {
            TopBarAction(
                icon = CoreR.drawable.ic_alert_circle,
                onClick = onErrorClick,
                tint = colors.error,
            )
        }

        AnimatedVisibility(visible = isLoading, enter = fadeIn(), exit = fadeOut()) {
            TopBarAction(onClick = onRetryClick) {
                Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(strokeWidth = 2.dp, color = media.base)
                }
            }
        }

        TopBarAction(icon = CoreR.drawable.ic_search, onClick = onSearchClick)
    }
}
