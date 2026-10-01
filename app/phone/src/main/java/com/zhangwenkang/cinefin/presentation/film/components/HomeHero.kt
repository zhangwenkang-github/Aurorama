package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import coil3.compose.AsyncImage
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButton
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonSize
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonVariant
import com.zhangwenkang.cinefin.core.presentation.components.cinefinClickable
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyEpisode
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyMovie
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors
import com.zhangwenkang.cinefin.film.R as FilmR
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.models.FindroidShow
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme

/** 主视觉高度上限：手机竖屏够不到，平板 / 横屏靠它兜底，避免头图吃掉整屏。 */
private val heroMaxHeight = 420.dp

/**
 * 首页主视觉（Lumen：内容即光源）。
 *
 * 全出血剧照 + 底部渐隐遮罩 + 内暗角；标题 / 元信息 / 主行动全部压在遮罩上， 任何亮度的剧照都能读清。媒体色只出现在三处：
 * 眉标圆点、进度线、"继续观看"填充按钮——都是"进行中"语义。
 *
 * 与旧稿的差别：头图从通栏方块改为同心圆角的大卡（22 / 28dp），元信息从长句改为 `·` 分隔的短标签， 行动区收敛为一个填充主行动（一屏一个 Filled，§8.1 规则 1）。
 */
@Composable
fun HomeHero(
    item: FindroidItem,
    onClick: (FindroidItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    val expanded =
        currentWindowAdaptiveInfo()
            .windowSizeClass
            .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    val shape = if (expanded) CinefinShapes.Lg else CinefinShapes.Xl
    val contentPadding = if (expanded) CinefinSpacing.Space8 else CinefinSpacing.Space6

    val resumeFraction = item.resumeFraction()

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // 平板按 21:9 出稿、手机按 16:9；两者都用高度上限兜底
        val ratio = if (expanded) 21f / 9f else 16f / 9f
        val heroHeight = minOf(maxWidth / ratio, heroMaxHeight)
        LumenCardFrame(
            modifier = Modifier.fillMaxWidth().height(heroHeight),
            shape = shape,
            container = colors.surfaceContainerLowest,
        ) {
            AsyncImage(
                model = item.images.backdrop ?: item.images.primary,
                placeholder = ColorPainter(colors.surfaceContainer),
                error = ColorPainter(colors.surfaceContainer),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            // 流光三层：顶部光晕（内容即光源）→ 底部渐隐（文字托底）→ 内暗角（画面向内收）
            Box(modifier = Modifier.fillMaxSize().background(lumenTopGlow))
            Box(modifier = Modifier.fillMaxSize().background(lumenBottomScrim(colors.surface)))
            Box(modifier = Modifier.fillMaxSize().background(lumenVignette))

            Column(
                modifier =
                    Modifier.align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(horizontal = contentPadding, vertical = contentPadding),
                verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(media.base))
                    Spacer(Modifier.width(CinefinSpacing.Space2))
                    Text(
                        text =
                            stringResource(
                                if (resumeFraction > 0f) FilmR.string.continue_watching
                                else CoreR.string.next_up
                            ),
                        style = CinefinType.LabelLarge,
                        color = media.bright,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    item.episodeCode()?.let { code ->
                        Text(
                            text = " · $code",
                            style = CinefinType.LabelMedium,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                }

                Text(
                    text = item.heroTitle(),
                    style = if (expanded) CinefinType.DisplaySmall else CinefinType.HeadlineMedium,
                    color = colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                item.metaLine()?.let { meta ->
                    Text(
                        text = meta,
                        style = CinefinType.LabelMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                item.heroSubtitle()?.let { subtitle ->
                    Text(
                        text = subtitle,
                        style = CinefinType.BodySmall,
                        color = colors.onSurfaceFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(Modifier.height(CinefinSpacing.Space2))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    CinefinButton(
                        text =
                            stringResource(
                                if (resumeFraction > 0f) FilmR.string.continue_watching
                                else FilmR.string.hero_play
                            ),
                        onClick = { onClick(item) },
                        variant = CinefinButtonVariant.Filled,
                        size = CinefinButtonSize.Medium,
                        icon = { tint: Color ->
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_play),
                                contentDescription = null,
                                tint = tint,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                    item.remainingMinutes()?.let { remainingMinutes ->
                        Spacer(Modifier.width(CinefinSpacing.Space4))
                        Text(
                            text =
                                stringResource(
                                    FilmR.string.hero_remaining_minutes,
                                    remainingMinutes,
                                ),
                            style = CinefinType.MonoData,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }

            // 内容图上的"进行中"标记：3dp 媒体色进度直接贴卡片下沿（§8.8 图上进度）
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

            // 整张卡可点：按钮先于该覆盖层命中，不会互相抢事件
            Box(
                modifier = Modifier.matchParentSize().clip(shape).cinefinClickable { onClick(item) }
            )
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

@Preview(showBackground = true, widthDp = 1280, heightDp = 520)
@Composable
private fun HomeHeroTabletPreview() {
    CinefinTheme { HomeHero(item = dummyMovie, onClick = {}, modifier = Modifier.padding(32.dp)) }
}

@Preview(showBackground = true, widthDp = 411, heightDp = 420)
@Composable
private fun HomeHeroPhonePreview() {
    CinefinTheme { HomeHero(item = dummyEpisode, onClick = {}, modifier = Modifier.padding(20.dp)) }
}
