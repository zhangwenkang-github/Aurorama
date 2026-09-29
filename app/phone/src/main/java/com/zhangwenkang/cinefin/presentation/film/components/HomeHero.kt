package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.film.R as FilmR
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.models.FindroidShow
import com.zhangwenkang.cinefin.presentation.theme.spacings

/** 主视觉的高度上限：手机竖屏永远够不到，平板/横屏靠它兜底。 */
private val maxHeroHeight = 360.dp

/**
 * 首页主视觉：当前"待续"的那一部，直接铺满整个视口宽度。
 *
 * 全屏出血是刻意的——这是 App 里唯一一处让剧照压过界面的地方， 剩下的版面都退成安静的列表。文字全部压在底部的墨色渐变上， 保证任何亮度的剧照都能读清；朱砂只出现在"继续观看"这一处。
 */
@Composable
fun HomeHero(
    item: FindroidItem,
    onClick: (FindroidItem) -> Unit,
    modifier: Modifier = Modifier,
    contentPaddingHorizontal: androidx.compose.ui.unit.Dp = MaterialTheme.spacings.default,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                // 手机竖屏按 16:9；平板/横屏时封顶，避免主视觉吃掉整屏
                .height(
                    minOf(
                        (LocalConfiguration.current.screenWidthDp * 9 / 16).dp,
                        maxHeroHeight,
                    )
                )
                .clickable { onClick(item) }
    ) {
        AsyncImage(
            model = item.images.backdrop ?: item.images.primary,
            placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceContainer),
            error = ColorPainter(MaterialTheme.colorScheme.surfaceContainer),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        // 底部墨色渐变：文字托底，同时让主视觉与下方列表自然衔接
        Box(
            modifier =
                Modifier.fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.32f to Color.Transparent,
                            0.68f to MaterialTheme.colorScheme.background.copy(alpha = 0.72f),
                            1f to MaterialTheme.colorScheme.background,
                        )
                    )
        )

        Column(
            modifier =
                Modifier.align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(
                        start = contentPaddingHorizontal,
                        end = contentPaddingHorizontal,
                        bottom = MaterialTheme.spacings.medium,
                    ),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
        ) {
            Text(
                text = stringResource(FilmR.string.continue_watching),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = item.heroTitle(),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            item.heroSubtitle()?.let { subtitle ->
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(modifier = Modifier.height(MaterialTheme.spacings.extraSmall))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier.clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(
                                horizontal = MaterialTheme.spacings.medium,
                                vertical = MaterialTheme.spacings.small,
                            ),
                ) {
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_play),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(MaterialTheme.spacings.small))
                    Text(
                        text = stringResource(FilmR.string.hero_play),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                item.heroRemainingMinutes()?.let { remainingMinutes ->
                    Spacer(modifier = Modifier.width(MaterialTheme.spacings.medium))
                    Text(
                        text =
                            stringResource(FilmR.string.hero_remaining_minutes, remainingMinutes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun FindroidItem.heroTitle(): String =
    if (this is FindroidEpisode) this.seriesName else this.name

private fun FindroidItem.heroSubtitle(): String? {
    if (this is FindroidEpisode) return name

    val genres =
        when (this) {
            is FindroidMovie -> genres
            is FindroidShow -> genres
            else -> emptyList()
        }
    return genres.takeIf { it.isNotEmpty() }?.joinToString(" / ")
        ?: originalTitle?.takeIf { it.isNotBlank() && it != name }
}

/** 剩余时长：只对真正看了一半的内容有意义，因此没有进度时不显示。 Jellyfin 的时间单位是 100 纳秒（tick），除以 600_000_000 得到分钟。 */
private fun FindroidItem.heroRemainingMinutes(): Int? {
    if (runtimeTicks <= 0 || playbackPositionTicks <= 0) return null
    val remainingMinutes =
        ((runtimeTicks - playbackPositionTicks).coerceAtLeast(0) / 600_000_000L).toInt()
    return remainingMinutes.takeIf { it > 0 }
}
