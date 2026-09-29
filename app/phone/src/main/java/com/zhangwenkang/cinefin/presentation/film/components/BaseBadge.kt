package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun BaseBadge(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier =
            modifier
                .defaultMinSize(minWidth = 22.dp, minHeight = 22.dp)
                .clip(RoundedCornerShape(6.dp))
                // 徽标压在海报上，用半透明墨底而不是品牌色，
                // 这样朱砂在整个界面里只保留"要播的内容"这一个含义。
                .background(Color.Black.copy(alpha = 0.62f))
    ) {
        content()
    }
}
