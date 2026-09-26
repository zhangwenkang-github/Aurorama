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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.zhangwenkang.cinefin.core.R
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyEpisode
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyMovie
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.isDownloaded
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.presentation.theme.Motion
import com.zhangwenkang.cinefin.presentation.theme.spacings
import kotlinx.coroutines.delay

/** 横版海报卡的宽度：随屏幕尺寸自适应，平板更大、手机更紧凑。 */
@Composable
fun rememberLandscapeCardWidth(): Dp {
    val screenWidth = LocalConfiguration.current.screenWidthDp
    return when {
        screenWidth >= 1200 -> 420.dp
        screenWidth >= 900 -> 360.dp
        screenWidth >= 600 -> 300.dp
        else -> 236.dp
    }
}

/**
 * Netflix 风格的横版内容卡：16:9 剧照 + 底部渐变字幕层 + 暖金进度条。
 *
 * 交互细节：按下时轻微缩放、入场时淡入上浮并按 [index] 错开，形成成排出现的节奏感。
 */
@Composable
fun LandscapeItemCard(
    item: FindroidItem,
    onClick: (FindroidItem) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = rememberLandscapeCardWidth(),
    index: Int = 0,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = tween(durationMillis = Motion.durationFast, easing = Motion.standard),
        label = "cardPressScale",
    )

    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay((index.coerceAtMost(6) * Motion.staggerStep).toLong())
        appeared = true
    }
    val appearProgress by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec =
            tween(durationMillis = Motion.durationSlow, easing = Motion.emphasizedDecelerate),
        label = "cardAppear",
    )

    val resumeFraction =
        if (item.runtimeTicks > 0) {
            (item.playbackPositionTicks.toFloat() / item.runtimeTicks.toFloat())
                .coerceIn(0f, 1f)
        } else {
            0f
        }

    Box(
        modifier =
            modifier
                .width(width)
                .graphicsLayer {
                    alpha = appearProgress
                    translationY = (1f - appearProgress) * 36f
                    scaleX = pressScale
                    scaleY = pressScale
                }
                .aspectRatio(16f / 9f)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    shape = MaterialTheme.shapes.medium,
                )
                .clickable(interactionSource = interactionSource, indication = null) {
                    onClick(item)
                }
    ) {
        AsyncImage(
            model = item.images.backdrop ?: item.images.primary,
            placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceContainerHigh),
            error = ColorPainter(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        // 底部渐变，保证标题在任意剧照上都清晰可读
        Box(
            modifier =
                Modifier.fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.35f to Color.Transparent,
                            0.72f to Color.Black.copy(alpha = 0.55f),
                            1f to Color.Black.copy(alpha = 0.88f),
                        )
                    )
        )

        Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(MaterialTheme.spacings.small),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
        ) {
            if (item.isDownloaded()) DownloadedBadge()
            if (item.played) PlayedBadge()
            item.unplayedItemCount?.takeIf { it > 0 }?.let { ItemCountBadge(it) }
        }

        Column(
            modifier =
                Modifier.align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(
                        start = MaterialTheme.spacings.medium,
                        end = MaterialTheme.spacings.medium,
                        bottom = MaterialTheme.spacings.medium,
                    ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = if (item is FindroidEpisode) item.seriesName else item.name,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitleFor(item)?.let { subtitle ->
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (resumeFraction > 0f) {
            Box(
                modifier =
                    Modifier.align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.White.copy(alpha = 0.18f))
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
}

@Composable
private fun subtitleFor(item: FindroidItem): String? =
    when (item) {
        is FindroidEpisode ->
            stringResource(
                id = R.string.episode_name_extended,
                item.parentIndexNumber,
                item.indexNumber,
                item.name,
            )
        else -> item.originalTitle?.takeIf { it.isNotBlank() && it != item.name }
    }

@Preview(showBackground = true)
@Composable
private fun LandscapeItemCardPreview() {
    CinefinTheme {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LandscapeItemCard(item = dummyMovie, onClick = {}, width = 320.dp)
            LandscapeItemCard(item = dummyEpisode, onClick = {}, width = 320.dp)
        }
    }
}
