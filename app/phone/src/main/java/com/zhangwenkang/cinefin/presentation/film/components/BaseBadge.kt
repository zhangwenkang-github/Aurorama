package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** 压在海报上的中性状态徽标（§8.9）：黑 62% 覆盖底 + 白 12% 描边；不使用任何媒体色。 */
@Composable
fun BaseBadge(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier =
            modifier
                .defaultMinSize(minWidth = 24.dp, minHeight = 24.dp)
                .clip(shape)
                .background(Color.Black.copy(alpha = 0.62f))
                .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
    ) {
        content()
    }
}
