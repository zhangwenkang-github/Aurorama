package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.zhangwenkang.cinefin.film.R as FilmR
import com.zhangwenkang.cinefin.film.presentation.home.HomeAction
import com.zhangwenkang.cinefin.models.HomeItem
import com.zhangwenkang.cinefin.presentation.theme.spacings
import com.zhangwenkang.cinefin.ui.components.Direction
import com.zhangwenkang.cinefin.ui.components.ItemCard

@Composable
fun HomeView(
    view: HomeItem.ViewItem,
    itemsPadding: PaddingValues,
    onAction: (HomeAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = stringResource(id = FilmR.string.latest_library, view.view.name),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(itemsPadding),
        )
        Spacer(modifier = Modifier.height(MaterialTheme.spacings.medium))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.default),
            contentPadding = itemsPadding,
        ) {
            items(view.view.items, key = { it.id }) { item ->
                ItemCard(
                    item = item,
                    direction = Direction.VERTICAL,
                    onClick = { onAction(HomeAction.OnItemClick(it)) },
                )
            }
        }
    }
}
