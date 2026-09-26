package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.presentation.theme.Motion
import com.zhangwenkang.cinefin.presentation.theme.spacings

/**
 * 首页顶部标签栏：胶囊式标签，选中态使用主色填充并带轻微放大动画。
 *
 * 与影视类应用的常见做法一致——标签用于在超长内容之间快速跳转，
 * 因此这里只负责呈现与回调，滚动定位由调用方处理。
 */
@Composable
fun HomeTabRow(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = MaterialTheme.spacings.default),
) {
    LazyRow(
        modifier = modifier,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
    ) {
        itemsIndexed(tabs) { index, title ->
            val selected = index == selectedIndex
            val containerColor by animateColorAsState(
                targetValue =
                    if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                animationSpec = tween(Motion.durationFast, easing = Motion.standard),
                label = "tabContainer",
            )
            val contentColor by animateColorAsState(
                targetValue =
                    if (selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                animationSpec = tween(Motion.durationFast, easing = Motion.standard),
                label = "tabContent",
            )
            val scale by animateFloatAsState(
                targetValue = if (selected) 1f else 0.96f,
                animationSpec = tween(Motion.durationFast, easing = Motion.standard),
                label = "tabScale",
            )

            Surface(
                onClick = { onSelect(index) },
                shape = CircleShape,
                color = containerColor,
                contentColor = contentColor,
                modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale },
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier =
                        Modifier.padding(
                            horizontal = MaterialTheme.spacings.medium,
                            vertical = 10.dp,
                        ),
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeTabRowPreview() {
    CinefinTheme {
        HomeTabRow(
            tabs = listOf("首页", "电影", "剧集", "动画", "纪录片"),
            selectedIndex = 0,
            onSelect = {},
        )
    }
}
