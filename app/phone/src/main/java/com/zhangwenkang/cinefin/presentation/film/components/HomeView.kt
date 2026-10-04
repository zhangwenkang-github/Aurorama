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
import androidx.compose.ui.res.stringResource
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.film.R as FilmR
import com.zhangwenkang.cinefin.film.presentation.home.HomeAction
import com.zhangwenkang.cinefin.models.FindroidCollection
import com.zhangwenkang.cinefin.models.FindroidImages
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.HomeItem
import com.zhangwenkang.cinefin.presentation.utils.rememberGridGutter
import java.util.UUID

/** 媒体库走廊：显示某个库的最新几部，标题右侧的"全部"进入该库。 用文字操作代替圆形箭头按钮，页面因此没有多余的控件形状。 */
@Composable
fun HomeView(
    view: HomeItem.ViewItem,
    itemsPadding: PaddingValues,
    onAction: (HomeAction) -> Unit,
    modifier: Modifier = Modifier,
    /** W60b：下载状态角标（itemId → 角标）；默认空表 = 无角标，既有调用零改动。 */
    downloadBadges: Map<UUID, DownloadBadgeInfo> = emptyMap(),
    /** W69c：书籍 / 音乐卡本地封面（服务器图缺失时覆盖）；默认 null = 走服务器图。 */
    imageOverrideFor: (FindroidItem) -> String? = { null },
    /** W69c：无图时的类型占位图标（书籍 = `ic_book` / 音乐 = `ic_music`）；null = 保持原有空色底。 */
    @DrawableRes placeholderIconResFor: (FindroidItem) -> Int? = { null },
    /** W69c：条目组合（可见）时回调——书籍卡用它触发懒生成封面。 */
    onItemVisible: (FindroidItem) -> Unit = {},
    /** W69c：服务器图加载失败回调——书籍卡据此触发本地生成回落。 */
    onServerImageFailed: (FindroidItem) -> Unit = {},
) {
    val gutter = rememberGridGutter()
    Column(modifier = modifier) {
        SectionHeader(
            title = stringResource(FilmR.string.latest_library, view.view.name),
            actionText = stringResource(FilmR.string.view_all),
            onActionClick = {
                onAction(
                    HomeAction.OnLibraryClick(
                        FindroidCollection(
                            id = view.view.id,
                            name = view.view.name,
                            images = FindroidImages(),
                            type = view.view.type,
                        )
                    )
                )
            },
            modifier = Modifier.padding(itemsPadding).padding(bottom = CinefinSpacing.Space4),
        )
        LazyRow(
            contentPadding = itemsPadding,
            horizontalArrangement = Arrangement.spacedBy(gutter),
        ) {
            itemsIndexed(view.view.items, key = { _, item -> item.id }) { index, item ->
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
