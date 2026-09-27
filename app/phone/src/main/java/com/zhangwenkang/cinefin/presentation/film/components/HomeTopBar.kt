package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.R as CoreR

/**
 * 首页顶栏：只保留最必要的元素——抽屉入口、品牌标识、搜索与状态指示。
 *
 * 服务器信息已移入抽屉，避免首页顶部出现与浏览无关的信息。
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

        Spacer(modifier = Modifier.size(4.dp))

        Icon(
            painter = painterResource(CoreR.drawable.ic_logo),
            contentDescription = null,
            tint = Color.Unspecified,
            modifier = Modifier.size(34.dp),
        )

        Spacer(modifier = Modifier.weight(1f))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AnimatedVisibility(visible = isError, enter = fadeIn(), exit = fadeOut()) {
                Surface(
                    onClick = onErrorClick,
                    modifier = Modifier.fillMaxHeight().aspectRatio(1f),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Box {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_alert_circle),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }
            }

            AnimatedVisibility(visible = isLoading, enter = fadeIn(), exit = fadeOut()) {
                Surface(
                    onClick = onRetryClick,
                    modifier = Modifier.fillMaxHeight().aspectRatio(1f),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Box(modifier = Modifier.size(40.dp)) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp).align(Alignment.Center),
                            strokeWidth = 2.dp,
                        )
                    }
                }
            }

            Surface(
                onClick = onSearchClick,
                modifier = Modifier.fillMaxHeight().aspectRatio(1f),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Box {
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_search),
                        contentDescription = null,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        }
    }
}
