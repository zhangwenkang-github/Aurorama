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
import com.zhangwenkang.cinefin.core.presentation.components.cinefinClickable
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyEpisode
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyMovie
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors
import com.zhangwenkang.cinefin.film.R as FilmR
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.isDownloaded
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme

/** 横版卡宽度：随屏幕尺寸自适应。Lumen 版整体放大一档，走廊不再"挤成一条传送带"。 */
@Composable
fun rememberLandscapeCardWidth(): Dp {
    val screenWidth = LocalConfiguration.current.screenWidthDp
    return when {
        screenWidth >= 1400 -> 320.dp
        screenWidth >= 1000 -> 288.dp
        screenWidth >= 700 -> 248.dp
        else -> 208.dp
    }
}

/**
 * 走廊里的横版卡（§8.4 WideCard · Lumen）：16:9 剧照 + 强底部渐隐 + 片名 + 元信息 + 右下角片长 + 3dp 进度。
 *
 * 外壳是双层嵌套：1dp 描边 + 顶部 1px 内高光；悬停 / 按下只换描边色，不做位移与缩放（B 纪律）。
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

    val resumeFraction = item.cardResumeFraction()

    LumenCardFrame(
        modifier =
            modifier.width(width).aspectRatio(16f / 9f).lumenEntrance(index).cinefinClickable(
                interactionSource = interactionSource
            ) {
                onClick(item)
            },
        emphasized = emphasized,
        container = colors.surfaceContainerHigh,
    ) {
        AsyncImage(
            model = item.images.backdrop ?: item.images.primary,
            placeholder = ColorPainter(colors.surfaceContainerHigh),
            error = ColorPainter(colors.surfaceContainerHigh),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        Box(
            modifier =
                Modifier.fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.36f to Color.Transparent,
                            0.72f to Color.Black.copy(alpha = 0.42f),
                            1f to Color.Black.copy(alpha = 0.88f),
                        )
                    )
        )

        Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(CinefinSpacing.Space3),
            horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2),
        ) {
            if (item.favorite) FavoriteBadge()
            if (item.isDownloaded()) DownloadedBadge()
            ItemStatusBadge(item)
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.cardMetaLine(),
                    style = CinefinType.BodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                item.runtimeLabel()?.let { runtime ->
                    Text(
                        text = runtime,
                        style = CinefinType.MonoDataSmall,
                        color = colors.onSurfaceFaint,
                    )
                }
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

/**
 * 走廊卡片进度（0..1，W60）：
 * - 优先 `playbackPosition / runtime`（继续观看 / 收听有完整时长时精度最高）；
 * - 没有时长数据（书籍 `runtimeTicks = 0`）或位置为 0 时回退 `UserData.playedPercentage / 100`；
 * - 两者都没有返回 0，卡片不画进度线。
 */
internal fun FindroidItem.cardResumeFraction(): Float {
    if (runtimeTicks > 0) {
        val fromTicks = (playbackPositionTicks.toFloat() / runtimeTicks.toFloat()).coerceIn(0f, 1f)
        if (fromTicks > 0f) return fromTicks
    }
    val percentage = playedPercentage ?: return 0f
    return (percentage / 100.0).toFloat().coerceIn(0f, 1f)
}

/** 卡片元信息：剧集显示 `S1 · E1 · 剩余 24 分钟`，电影显示原名（无原名时退回片名）。 */
@Composable
internal fun FindroidItem.cardMetaLine(): String {
    if (this is FindroidEpisode) {
        val parts = buildList {
            add(seasonCode())
            add(indexCode())
            remainingMinutes()?.let { add(stringResource(FilmR.string.hero_remaining_minutes, it)) }
        }
        return parts.joinToString(" · ")
    }
    return originalTitle?.takeIf { it.isNotBlank() && it != name } ?: name
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
