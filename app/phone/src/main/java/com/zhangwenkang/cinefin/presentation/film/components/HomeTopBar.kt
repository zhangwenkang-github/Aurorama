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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.R as CoreR

/**
 * 首页顶栏：抽屉入口 + 品牌字标 + 搜索。
 *
 * 这里只用字标不用图形标志——首页的第一眼应该留给海报， 顶栏越安静，画面越突出。状态（加载中 / 出错）也用最小的图标提示， 不做彩色圆底按钮，避免与海报争夺注意力。
 */
@Composable
fun HomeTopBar(
    onOpenDrawer: () -> Unit,
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
        IconButton(onClick = onOpenDrawer) {
            Icon(
                painter = painterResource(CoreR.drawable.ic_menu),
                contentDescription = null,
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = stringResource(CoreR.string.app_name),
            style = MaterialTheme.typography.titleLarge,
        )

        Spacer(modifier = Modifier.weight(1f))

        AnimatedVisibility(visible = isError, enter = fadeIn(), exit = fadeOut()) {
            IconButton(onClick = onErrorClick) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_alert_circle),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }

        AnimatedVisibility(visible = isLoading, enter = fadeIn(), exit = fadeOut()) {
            IconButton(onClick = onRetryClick) {
                Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                }
            }
        }

        IconButton(onClick = onSearchClick) {
            Icon(
                painter = painterResource(CoreR.drawable.ic_search),
                contentDescription = null,
            )
        }
    }
}
