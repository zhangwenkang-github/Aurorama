package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.delay

/**
 * 海报墙使用的竖版海报卡：2:3 海报 + 标题 + 观看进度。
 *
 * 与横版卡保持同一套动效语言（按下缩放、入场淡入上浮并错峰）。
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
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = tween(durationMillis = Motion.durationFast, easing = Motion.standard),
        label = "posterPressScale",
    )

    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay((index.coerceAtMost(8) * Motion.staggerStep).toLong())
        appeared = true
    }
    val appearProgress by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec =
            tween(durationMillis = Motion.durationSlow, easing = Motion.emphasizedDecelerate),
        label = "posterAppear",
    )

    val resumeFraction =
        if (item.runtimeTicks > 0) {
            (item.playbackPositionTicks.toFloat() / item.runtimeTicks.toFloat())
                .coerceIn(0f, 1f)
        } else {
            0f
        }

    Column(
        modifier =
            modifier
                .graphicsLayer {
                    alpha = appearProgress
                    translationY = (1f - appearProgress) * 28f
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
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        shape = MaterialTheme.shapes.medium,
                    )
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
                            .height(3.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                ) {
                    Box(
                        modifier =
                            Modifier.fillMaxWidth(resumeFraction)
                                .height(3.dp)
                                .background(MaterialTheme.colorScheme.tertiary)
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
