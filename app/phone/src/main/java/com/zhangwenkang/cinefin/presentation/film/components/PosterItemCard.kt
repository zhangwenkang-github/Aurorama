package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.zhangwenkang.cinefin.core.presentation.components.cinefinClickable
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyEpisode
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyMovie
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.isDownloaded
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme

/**
 * 海报墙竖版卡（§8.4 PosterCard · Lumen）：双层嵌套外壳 + 2:3 海报 + 标题（两行）+ 3dp 进度。
 *
 * 信息只留"片名 + 进行中"两件事：徽标压到最少（下载 / 未看集数），标题最多两行—— 海报墙靠"图说一切"，多一行元信息就会让整面墙变挤。
 */
@Composable
fun PosterItemCard(
    item: FindroidItem,
    onClick: (FindroidItem) -> Unit,
    modifier: Modifier = Modifier,
    index: Int = 0,
) {
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val emphasized = hovered || pressed

    val resumeFraction = item.resumeFraction()

    Column(
        modifier =
            modifier.lumenEntrance(index).cinefinClickable(interactionSource = interactionSource) {
                onClick(item)
            }
    ) {
        LumenCardFrame(
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
            emphasized = emphasized,
            container = colors.surfaceContainerHigh,
        ) {
            AsyncImage(
                model =
                    if (item is FindroidEpisode) item.images.showPrimary else item.images.primary,
                placeholder = ColorPainter(colors.surfaceContainerHigh),
                error = ColorPainter(colors.surfaceContainerHigh),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(CinefinSpacing.Space2),
                horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2),
            ) {
                if (item.isDownloaded()) DownloadedBadge()
                ItemStatusBadge(item)
            }

            if (resumeFraction > 0f) {
                Box(
                    modifier =
                        Modifier.align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(colors.progressTrackOnImage)
                ) {
                    Box(
                        modifier =
                            Modifier.fillMaxWidth(resumeFraction)
                                .height(3.dp)
                                .background(media.base)
                    )
                }
            }
        }

        Text(
            text = if (item is FindroidEpisode) item.seriesName else item.name,
            style = CinefinType.TitleSmall,
            color = colors.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier.padding(
                    start = 2.dp,
                    end = 2.dp,
                    top = CinefinSpacing.Space2,
                ),
        )
    }
}

/** 观看进度（0..1），没有时长信息时返回 0，卡片便不画进度线。 */
internal fun FindroidItem.resumeFraction(): Float =
    if (runtimeTicks > 0) {
        (playbackPositionTicks.toFloat() / runtimeTicks.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

@Preview(showBackground = true)
@Composable
private fun PosterItemCardPreview() {
    CinefinTheme {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PosterItemCard(item = dummyMovie, onClick = {}, modifier = Modifier.width(160.dp))
            PosterItemCard(item = dummyEpisode, onClick = {}, modifier = Modifier.width(160.dp))
        }
    }
}
