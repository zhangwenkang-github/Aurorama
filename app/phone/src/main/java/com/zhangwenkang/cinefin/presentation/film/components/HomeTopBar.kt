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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors
import com.zhangwenkang.cinefin.presentation.components.TopBarAction

/**
 * 首页顶栏：抽屉入口 + 品牌字标 + 搜索。
 *
 * 这里只用字标不用图形标志——首页的第一眼应该留给海报， 顶栏越安静，画面越突出。状态（加载中 / 出错）也用最小的图标提示， 不做彩色圆底按钮，避免与海报争夺注意力。
 */
@Composable
fun HomeTopBar(
    /** 抽屉入口；null = 当前形态没有抽屉（手机 Compact，W6-R6N），不显示 hamburger。 */
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
            TopBarAction(icon = CoreR.drawable.ic_menu, onClick = onOpenDrawer)

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
