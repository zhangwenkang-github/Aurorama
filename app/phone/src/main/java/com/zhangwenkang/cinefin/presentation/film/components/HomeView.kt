package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.film.R as FilmR
import com.zhangwenkang.cinefin.film.presentation.home.HomeAction
import com.zhangwenkang.cinefin.models.FindroidCollection
import com.zhangwenkang.cinefin.models.FindroidImages
import com.zhangwenkang.cinefin.models.HomeItem
import com.zhangwenkang.cinefin.presentation.utils.rememberGridGutter

/** 媒体库走廊：显示某个库的最新几部，标题右侧的"全部"进入该库。 用文字操作代替圆形箭头按钮，页面因此没有多余的控件形状。 */
@Composable
fun HomeView(
    view: HomeItem.ViewItem,
    itemsPadding: PaddingValues,
    onAction: (HomeAction) -> Unit,
    modifier: Modifier = Modifier,
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
                LandscapeItemCard(
                    item = item,
                    onClick = { onAction(HomeAction.OnItemClick(item)) },
                    index = index,
                )
            }
        }
    }
}
