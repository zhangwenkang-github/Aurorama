package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme

/**
 * 收藏角标（W60b）：条目已收藏时压在封面角上的实心书签（`ic_bookmark_filled`，§8.9 中性徽标）。
 *
 * 收藏是单一数据源（仓库层广播）：详情页收藏 / 取消收藏后，库网格 / 首页走廊 / 搜索结果 / 我的收藏页的这枚角标随刷新即时一致。
 */
@Composable
fun FavoriteBadge(modifier: Modifier = Modifier) {
    BaseBadge(modifier = modifier) {
        Icon(
            painter = painterResource(CoreR.drawable.ic_bookmark_filled),
            contentDescription = stringResource(CoreR.string.favorite_badge_description),
            tint = Color.White,
            modifier = Modifier.size(16.dp).align(Alignment.Center),
        )
    }
}

@Composable
@Preview
private fun FavoriteBadgePreview() {
    CinefinTheme { FavoriteBadge() }
}
