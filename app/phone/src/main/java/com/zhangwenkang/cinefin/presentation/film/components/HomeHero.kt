package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import coil3.compose.AsyncImage
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButton
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonSize
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonTone
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonVariant
import com.zhangwenkang.cinefin.core.presentation.components.cinefinClickable
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyEpisode
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyMovie
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalLumenColors
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
 * 全出血剧照 + 左侧水平渐隐（A 稿 `.hero .scrim`）+ 底部渐隐 + 内暗角；标题 / 元信息 / 主行动全部压在 遮罩上，并统一加 Lumen 文字阴影（S1 A 稿
 * `text-shadow`），任何亮度的剧照都能读清。
 *
 * 高度自适应：比例高度（平板 21:9 / 手机 16:9）是**下限**；内容（大字体、行动区换行）更高时卡片自动变高， 不再把行动区压扁（K60 竖屏曾因 16:9 高度 <
 * 内容高度导致"继续观看 / 剩余 N 分钟"被裁）。
 *
 * 行动区用 `FlowRow`：宽屏时按钮与剩余时间同一行（Pad 5 布局不回退）；窄屏放不下时剩余时间自动换到 下一行，两者都完整显示。
 */
@Composable
fun HomeHero(
    item: FindroidItem,
    onClick: (FindroidItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    val lumen = LocalLumenColors.current
    val expanded =
        currentWindowAdaptiveInfo()
            .windowSizeClass
            .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    val shape = if (expanded) CinefinShapes.Lg else CinefinShapes.Xl
    val contentPadding = if (expanded) CinefinSpacing.Space8 else CinefinSpacing.Space6

    val resumeFraction = item.resumeFraction()
    val sideScrimColor = lumen?.scrim ?: colors.surface

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // 平板按 21:9 出稿、手机按 16:9；高度上限兜底大屏，内容高度兜底小屏（见类注释）
        val ratio = if (expanded) 21f / 9f else 16f / 9f
        val minHeroHeight = minOf(maxWidth / ratio, heroMaxHeight)
        LumenCardFrame(
            modifier = Modifier.fillMaxWidth().heightIn(min = minHeroHeight),
            shape = shape,
            container = colors.surfaceContainerLowest,
        ) {
            AsyncImage(
                model = item.images.backdrop ?: item.images.primary,
                placeholder = ColorPainter(colors.surfaceContainer),
                error = ColorPainter(colors.surfaceContainer),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )

            // 流光四层：顶部光晕（内容即光源）→ 左侧水平渐隐（A 稿文字托底）→ 底部渐隐 → 内暗角
            Box(modifier = Modifier.matchParentSize().background(lumenTopGlow))
            Box(modifier = Modifier.matchParentSize().background(lumenSideScrim(sideScrimColor)))
            Box(modifier = Modifier.matchParentSize().background(lumenBottomScrim(colors.surface)))
            Box(modifier = Modifier.matchParentSize().background(lumenVignette))

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
                        style = CinefinType.LabelLarge.lumenTextShadow(LumenTextShadow.Meta),
                        color = media.bright,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    item.episodeCode()?.let { code ->
                        Text(
                            text = " · $code",
                            style = CinefinType.LabelMedium.lumenTextShadow(LumenTextShadow.Meta),
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                }

                Text(
                    text = item.heroTitle(),
                    style =
                        (if (expanded) CinefinType.DisplaySmall else CinefinType.HeadlineMedium)
                            .lumenTextShadow(LumenTextShadow.Title),
                    color = colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                item.metaLine()?.let { meta ->
                    Text(
                        text = meta,
                        style = CinefinType.LabelMedium.lumenTextShadow(LumenTextShadow.Meta),
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                item.heroSubtitle()?.let { subtitle ->
                    Text(
                        text = subtitle,
                        style = CinefinType.BodySmall.lumenTextShadow(LumenTextShadow.Meta),
                        color = colors.onSurfaceFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(Modifier.height(CinefinSpacing.Space2))

                // 行动区：放不下时自动换行（窄屏「继续观看」与「剩余 N 分钟」各占一行，均不截断）
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space4),
                    verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2),
                ) {
                    CinefinButton(
                        text =
                            stringResource(
                                if (resumeFraction > 0f) FilmR.string.continue_watching
                                else FilmR.string.hero_play
                            ),
                        onClick = { onClick(item) },
                        variant = CinefinButtonVariant.Filled,
                        size = CinefinButtonSize.Medium,
                        tone = CinefinButtonTone.Inverse,
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
                        Text(
                            text =
                                stringResource(
                                    FilmR.string.hero_remaining_minutes,
                                    remainingMinutes,
                                ),
                            style = CinefinType.MonoData.lumenTextShadow(LumenTextShadow.Meta),
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                            textAlign = TextAlign.Start,
                            modifier = Modifier.align(Alignment.CenterVertically),
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
                                .background(
                                    if (lumen != null) {
                                        Brush.horizontalGradient(
                                            listOf(lumen.accent, lumen.accentSecondary)
                                        )
                                    } else {
                                        SolidColor(media.base)
                                    }
                                )
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
