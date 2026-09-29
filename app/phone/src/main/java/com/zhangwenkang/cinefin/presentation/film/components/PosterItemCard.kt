package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyEpisode
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyMovie
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.isDownloaded
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.presentation.theme.Motion
import com.zhangwenkang.cinefin.presentation.theme.spacings

/**
 * 海报墙的竖版卡：只有海报与片名。
 *
 * 没有描边、没有卡片投影——海报自己就是形状。观看进度压在海报底边上， 用一线朱砂表示"已经看到这里"，这也是整张卡上唯一的颜色。
 */
@Composable
fun PosterItemCard(
    item: FindroidItem,
    onClick: (FindroidItem) -> Unit,
    modifier: Modifier = Modifier,
    index: Int = 0,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by
        animateFloatAsState(
            targetValue = if (pressed) 0.97f else 1f,
            animationSpec = tween(durationMillis = Motion.durationFast, easing = Motion.standard),
            label = "posterPressScale",
        )

    val resumeFraction = item.resumeFraction()

    Column(
        modifier =
            modifier
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                }
                .clip(MaterialTheme.shapes.medium)
                .clickable(interactionSource = interactionSource, indication = null) {
                    onClick(item)
                }
    ) {
        Box(
            modifier =
                Modifier.fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            AsyncImage(
                model =
                    if (item is FindroidEpisode) item.images.showPrimary else item.images.primary,
                placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceContainerHigh),
                error = ColorPainter(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(MaterialTheme.spacings.small),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
            ) {
                if (item.isDownloaded()) DownloadedBadge()
                if (item.played) PlayedBadge()
                item.unplayedItemCount?.takeIf { it > 0 }?.let { ItemCountBadge(it) }
            }

            if (resumeFraction > 0f) {
                Box(
                    modifier =
                        Modifier.align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f))
                ) {
                    Box(
                        modifier =
                            Modifier.fillMaxWidth(resumeFraction)
                                .height(2.dp)
                                .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }

        Text(
            text = if (item is FindroidEpisode) item.seriesName else item.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier.padding(
                    start = 2.dp,
                    end = 2.dp,
                    top = MaterialTheme.spacings.small,
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
