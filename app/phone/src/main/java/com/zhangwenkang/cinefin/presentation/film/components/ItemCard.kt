package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.R
import com.zhangwenkang.cinefin.core.presentation.components.cinefinClickable
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyEpisode
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyMovie
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.isDownloaded
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme

@Composable
fun ItemCard(
    item: FindroidItem,
    direction: Direction,
    onClick: (FindroidItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCinefinColors.current
    val width =
        when (direction) {
            Direction.HORIZONTAL -> 260
            Direction.VERTICAL -> 150
            Direction.SQUARE -> 184
        }
    Column(
        modifier =
            modifier.width(width.dp).clip(CinefinShapes.Md).cinefinClickable { onClick(item) }
    ) {
        Box(
            modifier =
                Modifier.clip(CinefinShapes.Md).border(1.dp, colors.outline, CinefinShapes.Md)
        ) {
            ItemPoster(item = item, direction = direction)
            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(CinefinSpacing.Space2),
                horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2),
            ) {
                if (item.isDownloaded()) DownloadedBadge()
                if (item.played) PlayedBadge()
                item.unplayedItemCount?.takeIf { it > 0 }?.let { ItemCountBadge(it) }
            }
            if (direction == Direction.HORIZONTAL) {
                ProgressBar(
                    item = item,
                    width = width,
                    modifier = Modifier.align(Alignment.BottomStart).padding(CinefinSpacing.Space2),
                )
            }
        }
        Spacer(modifier = Modifier.height(CinefinSpacing.Space1))
        Text(
            text = if (item is FindroidEpisode) item.seriesName else item.name,
            style = CinefinType.TitleSmall,
            color = colors.onSurface,
            maxLines = if (item is FindroidEpisode) 1 else 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (item is FindroidEpisode) {
            Text(
                text =
                    stringResource(
                        id = R.string.episode_name_extended,
                        item.parentIndexNumber,
                        item.indexNumber,
                        item.name,
                    ),
                style = CinefinType.BodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(2.dp))
    }
}

@Preview(showBackground = true)
@Composable
private fun ItemCardPreviewMovie() {
    CinefinTheme { ItemCard(item = dummyMovie, direction = Direction.HORIZONTAL, onClick = {}) }
}

@Preview(showBackground = true)
@Composable
private fun ItemCardPreviewMovieVertical() {
    CinefinTheme { ItemCard(item = dummyMovie, direction = Direction.VERTICAL, onClick = {}) }
}

@Preview(showBackground = true)
@Composable
private fun ItemCardPreviewEpisode() {
    CinefinTheme { ItemCard(item = dummyEpisode, direction = Direction.HORIZONTAL, onClick = {}) }
}

@Preview(showBackground = true)
@Composable
private fun ItemCardPreviewEpisodeVertical() {
    CinefinTheme { ItemCard(item = dummyEpisode, direction = Direction.VERTICAL, onClick = {}) }
}
