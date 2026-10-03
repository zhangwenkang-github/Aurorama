package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.R
import com.zhangwenkang.cinefin.core.presentation.components.CinefinSelectIndicator
import com.zhangwenkang.cinefin.core.presentation.components.cinefinClickable
import com.zhangwenkang.cinefin.core.presentation.components.cinefinSelectable
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyEpisode
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyMovie
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.isDownloaded
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme

/**
 * 栅格条目卡：宽度由栅格列决定（不再固定 150 / 260 / 184dp），版式随方向切换。
 *
 * Lumen 双层嵌套外壳 + 图上进度（横版卡）；标题与元信息压在卡片下方的留白里，字号与间距统一走 Prism 字阶。
 *
 * [width] 只给**无界宽度的横排行**（LazyRow）用：那种场景下 `fillMaxWidth` 拿不到列宽， 卡片会按各自海报的固有尺寸排布，出现「一大一小」（W11
 * 反馈⑨季列表）。栅格 / 列表保持传 null 即可。
 */
@Composable
fun ItemCard(
    item: FindroidItem,
    direction: Direction,
    onClick: (FindroidItem) -> Unit,
    modifier: Modifier = Modifier,
    index: Int = 0,
    width: Dp? = null,
    /** W58b：多选态 = 左上角 20dp 勾选指示（§8.4「已选圆点」）。 */
    selectionMode: Boolean = false,
    selected: Boolean = false,
    /** W58b：长按进入多选；null = 该卡不参与多选（既有调用零改动）。 */
    onLongClick: (() -> Unit)? = null,
) {
    val colors = LocalCinefinColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val density = LocalDensity.current

    Column(
        modifier =
            modifier
                .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
                .lumenEntrance(index)
                .then(
                    if (onLongClick != null) {
                        Modifier.cinefinSelectable(
                            selected = selected,
                            interactionSource = interactionSource,
                            onClick = { onClick(item) },
                            onLongPress = onLongClick,
                        )
                    } else {
                        Modifier.cinefinClickable(interactionSource = interactionSource) {
                            onClick(item)
                        }
                    }
                )
    ) {
        LumenCardFrame(
            modifier = Modifier.fillMaxWidth(),
            emphasized = hovered || pressed,
            container = colors.surfaceContainerHigh,
        ) {
            ItemPoster(item = item, direction = direction, modifier = Modifier.fillMaxWidth())
            if (selectionMode) {
                CinefinSelectIndicator(
                    selected = selected,
                    modifier = Modifier.align(Alignment.TopStart).padding(CinefinSpacing.Space3),
                )
            }
            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(CinefinSpacing.Space3),
                horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2),
            ) {
                if (item.isDownloaded()) DownloadedBadge()
                ItemStatusBadge(item)
            }
            if (direction == Direction.HORIZONTAL) {
                ProgressBar(
                    item = item,
                    modifier =
                        Modifier.align(Alignment.BottomStart)
                            .padding(horizontal = CinefinSpacing.Space3)
                            .padding(bottom = CinefinSpacing.Space3),
                )
            }
        }
        Spacer(modifier = Modifier.height(CinefinSpacing.Space3))
        /*
         * 标题块固定预留两行（W11 反馈⑨）：季名一个一行、一个两行时，卡片总高会差一整行，
         * 一列季卡看着就不是同一个尺寸。预留两行后短标题也占满这块高度，卡片尺寸才真的统一。
         */
        Text(
            text = if (item is FindroidEpisode) item.seriesName else item.name,
            style = CinefinType.TitleSmall,
            color = colors.onSurface,
            maxLines = if (item is FindroidEpisode) 1 else 2,
            overflow = TextOverflow.Ellipsis,
            modifier =
                if (item is FindroidEpisode) {
                    Modifier
                } else {
                    Modifier.heightIn(
                        min = with(density) { (CinefinType.TitleSmall.lineHeight * 2f).toDp() }
                    )
                },
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
    }
}

@Preview(showBackground = true)
@Composable
private fun ItemCardPreviewMovie() {
    CinefinTheme {
        ItemCard(
            item = dummyMovie,
            direction = Direction.HORIZONTAL,
            onClick = {},
            modifier = Modifier.width(260.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ItemCardPreviewMovieVertical() {
    CinefinTheme {
        ItemCard(
            item = dummyMovie,
            direction = Direction.VERTICAL,
            onClick = {},
            modifier = Modifier.width(150.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ItemCardPreviewEpisode() {
    CinefinTheme {
        ItemCard(
            item = dummyEpisode,
            direction = Direction.HORIZONTAL,
            onClick = {},
            modifier = Modifier.width(260.dp),
        )
    }
}
