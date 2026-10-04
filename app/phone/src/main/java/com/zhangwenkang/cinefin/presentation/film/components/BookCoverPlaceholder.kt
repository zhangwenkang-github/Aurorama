package com.zhangwenkang.cinefin.presentation.film.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors

/**
 * W64：书籍卡风格化占位（用户 2026-10-04 第 12 条口径）——书籍类型图标 + 当前域媒体色底。
 *
 * 复用既有矢量（`ic_book` 等）与媒体色 token，不新增位图；与相邻真实书封并排时不显得是黑块。 封面口径：**服务器图优先 → 本地封面（已生成缓存 / 本地提取）→ 本占位**。
 */
@Composable
fun BookCoverPlaceholder(
    @DrawableRes iconRes: Int,
    modifier: Modifier = Modifier,
    iconSize: Dp = 32.dp,
) {
    val media = LocalMediaColors.current
    Box(
        modifier = modifier.background(media.container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = media.bright,
            modifier = Modifier.size(iconSize),
        )
    }
}
