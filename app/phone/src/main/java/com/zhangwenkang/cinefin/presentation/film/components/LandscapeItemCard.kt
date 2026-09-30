package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.zhangwenkang.cinefin.core.presentation.components.cinefinClickable
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyEpisode
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyMovie
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.isDownloaded
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme

/** 横版卡宽度：随屏幕尺寸自适应，平板更大、手机更紧凑。 */
@Composable
fun rememberLandscapeCardWidth(): Dp {
    val screenWidth = LocalConfiguration.current.screenWidthDp
    return when {
        screenWidth >= 1400 -> 296.dp
        screenWidth >= 1000 -> 264.dp
        screenWidth >= 700 -> 224.dp
        else -> 188.dp
    }
}

/**
 * 走廊里的横版卡（§8.4 WideCard）：16:9 剧照 + 底部 96dp 渐隐 + 片名（19sp）+ 3dp 进度。
 *
 * 悬停 / 按下只换描边色与容器色，不做位移与缩放（B 纪律）。
 */
@Composable
fun LandscapeItemCard(
    item: FindroidItem,
    onClick: (FindroidItem) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = rememberLandscapeCardWidth(),
    index: Int = 0,
) {
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val emphasized = hovered || pressed

    val resumeFraction = item.resumeFraction()

    Box(
        modifier =
            modifier
                .width(width)
                .aspectRatio(16f / 9f)
                .clip(CinefinShapes.Md)
                .background(colors.surfaceContainerHigh)
                .border(
                    width = 1.dp,
                    color = if (emphasized) media.outline else colors.outline,
                    shape = CinefinShapes.Md,
                )
                .cinefinClickable(interactionSource = interactionSource) { onClick(item) }
    ) {
        AsyncImage(
            model = item.images.backdrop ?: item.images.primary,
            placeholder = ColorPainter(colors.surfaceContainerHigh),
            error = ColorPainter(colors.surfaceContainerHigh),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        // 底部渐变：保证片名在任意剧照上都清晰可读
        Box(
            modifier =
                Modifier.fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.42f to Color.Transparent,
                            0.78f to Color.Black.copy(alpha = 0.5f),
                            1f to Color.Black.copy(alpha = 0.86f),
                        )
                    )
        )

        Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(CinefinSpacing.Space2),
            horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2),
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
                        start = CinefinSpacing.Space4,
                        end = CinefinSpacing.Space4,
                        bottom = CinefinSpacing.Space3,
                    ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = if (item is FindroidEpisode) item.seriesName else item.name,
                style = CinefinType.WideCardTitle,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitleFor(item)?.let { subtitle ->
                Text(
                    text = subtitle,
                    style = CinefinType.BodySmall,
                    color = colors.onSurfaceVariant,
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
                        .background(colors.progressTrackOnImage)
            ) {
                Box(
                    modifier =
                        Modifier.fillMaxWidth(resumeFraction).height(3.dp).background(media.base)
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
