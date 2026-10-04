package com.zhangwenkang.cinefin.presentation.film.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.film.presentation.home.HomeAction
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.HomeSection
import com.zhangwenkang.cinefin.presentation.utils.rememberGridGutter
import java.util.UUID

/** 一条横向内容走廊：标题 + 一排横版卡。卡片之间只有间隔，没有分隔线——横排本身就是分组。 */
@Composable
fun HomeSection(
    section: HomeSection,
    itemsPadding: PaddingValues,
    onAction: (HomeAction) -> Unit,
    modifier: Modifier = Modifier,
    /** W60b：下载状态角标（itemId → 角标）；默认空表 = 无角标，既有调用零改动。 */
    downloadBadges: Map<UUID, DownloadBadgeInfo> = emptyMap(),
    /** W64：书籍卡本地封面（服务器图缺失时覆盖）；默认 null = 走服务器图，既有调用零改动。 */
    imageOverrideFor: (FindroidItem) -> String? = { null },
    /** W64：无图时的类型占位图标（书籍卡传 `ic_book`）。 */
    @DrawableRes placeholderIconResFor: (FindroidItem) -> Int? = { null },
    /** W64：条目组合（可见）时回调——书籍卡用它触发懒生成封面。 */
    onItemVisible: (FindroidItem) -> Unit = {},
    /** W69b：服务器图加载失败回调——书籍卡据此触发本地生成回落。 */
    onServerImageFailed: (FindroidItem) -> Unit = {},
) {
    val gutter = rememberGridGutter()
    Column(modifier = modifier) {
        SectionHeader(
            title = section.name.asString(),
            modifier = Modifier.padding(itemsPadding).padding(bottom = CinefinSpacing.Space4),
        )
        LazyRow(
            contentPadding = itemsPadding,
            horizontalArrangement = Arrangement.spacedBy(gutter),
        ) {
            itemsIndexed(section.items, key = { _, item -> item.id }) { index, item ->
                LaunchedEffect(item.id) { onItemVisible(item) }
                LandscapeItemCard(
                    item = item,
                    onClick = { onAction(HomeAction.OnItemClick(item)) },
                    index = index,
                    downloadBadge = downloadBadges[item.id] ?: DownloadBadgeInfo(),
                    imageOverride = imageOverrideFor(item),
                    placeholderIconRes = placeholderIconResFor(item),
                    onServerImageFailed = { onServerImageFailed(item) },
                )
            }
        }
    }
}
