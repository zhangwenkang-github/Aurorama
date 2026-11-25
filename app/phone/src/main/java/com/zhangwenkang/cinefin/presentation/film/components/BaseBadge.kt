package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes

/**
 * 压在海报上的中性状态徽标（§8.9）：黑 62% 覆盖底 + 白 12% 描边；不使用任何媒体色。
 *
 * [container] 供需要品牌淡色底的徽标覆盖默认中性底（2.0.1 定稿 §2 批①：评分徽标 = aurora1 12% 淡色 pill）。
 */
@Composable
fun BaseBadge(
    modifier: Modifier = Modifier,
    container: Color = Color.Black.copy(alpha = 0.62f),
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = CinefinShapes.Xs
    Box(
        modifier =
            modifier
                .defaultMinSize(minWidth = 24.dp, minHeight = 24.dp)
                .clip(shape)
                .background(container)
                .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
    ) {
        content()
    }
}
