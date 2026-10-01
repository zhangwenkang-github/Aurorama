package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.film.presentation.home.HomeAction
import com.zhangwenkang.cinefin.models.HomeSection
import com.zhangwenkang.cinefin.presentation.utils.rememberGridGutter

/** 一条横向内容走廊：标题 + 一排横版卡。卡片之间只有间隔，没有分隔线——横排本身就是分组。 */
@Composable
fun HomeSection(
    section: HomeSection,
    itemsPadding: PaddingValues,
    onAction: (HomeAction) -> Unit,
    modifier: Modifier = Modifier,
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
                LandscapeItemCard(
                    item = item,
                    onClick = { onAction(HomeAction.OnItemClick(item)) },
                    index = index,
                )
            }
        }
    }
}
