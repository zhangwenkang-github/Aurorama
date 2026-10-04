package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.presentation.utils.rememberPageGutter
import com.zhangwenkang.cinefin.presentation.utils.rememberSafePadding

/**
 * W66 视频详情页统一头图（用户 2026-10-04 拍板：统一到「节目页」版式）。
 *
 * 电影 / 节目 / 季 / 剧集四页共用同一套结构：
 * - 竖屏（<840dp）：海报（[detailHeroPosterWidthDp]，96–120dp 随宽度自适应）+ 眉标 / 标题 / 元信息 + 动作排，全部在头图内底排；
 * - 平板（≥840dp）：海报 216dp（与既有 Show / Movie expanded 一致），标题列内嵌动作排；
 * - 头图高度收敛为 [detailHeroMinHeightDp]（≥840dp 400dp / 竖屏 300dp，内容更高时由内容撑高）。
 */
@Composable
fun DetailHero(
    item: FindroidItem,
    scrollState: ScrollState,
    eyebrow: String?,
    title: String,
    originalTitle: String? = null,
    meta: String? = null,
    downloadBadge: DownloadBadgeInfo = DownloadBadgeInfo(),
    actions: @Composable ColumnScope.() -> Unit,
) {
    val expanded = rememberDetailHeroExpanded()
    ItemHeader(
        item = item,
        scrollState = scrollState,
        height = detailHeroMinHeight(expanded),
    ) {
        DetailHeroForeground(
            item = item,
            eyebrow = eyebrow,
            title = title,
            originalTitle = originalTitle,
            meta = meta,
            expanded = expanded,
            downloadBadge = downloadBadge,
            actions = actions,
        )
    }
}

/** [DetailHero] 的 LazyListState 版本（季详情页用 LazyColumn，滚动视差需要同一列表状态）。 */
@Composable
fun DetailHero(
    item: FindroidItem,
    lazyListState: LazyListState,
    eyebrow: String?,
    title: String,
    originalTitle: String? = null,
    meta: String? = null,
    downloadBadge: DownloadBadgeInfo = DownloadBadgeInfo(),
    actions: @Composable ColumnScope.() -> Unit,
) {
    val expanded = rememberDetailHeroExpanded()
    ItemHeader(
        item = item,
        lazyListState = lazyListState,
        height = detailHeroMinHeight(expanded),
    ) {
        DetailHeroForeground(
            item = item,
            eyebrow = eyebrow,
            title = title,
            originalTitle = originalTitle,
            meta = meta,
            expanded = expanded,
            downloadBadge = downloadBadge,
            actions = actions,
        )
    }
}

@Composable
private fun BoxScope.DetailHeroForeground(
    item: FindroidItem,
    eyebrow: String?,
    title: String,
    originalTitle: String?,
    meta: String?,
    expanded: Boolean,
    downloadBadge: DownloadBadgeInfo,
    actions: @Composable ColumnScope.() -> Unit,
) {
    val safePadding = rememberSafePadding()
    val gutter = rememberPageGutter()
    val paddingStart = safePadding.start + gutter
    val paddingEnd = safePadding.end + gutter
    val posterWidth =
        if (expanded) {
            DetailHeroPosterExpandedWidth
        } else {
            detailHeroPosterWidthDp(LocalConfiguration.current.screenWidthDp.toFloat()).dp
        }

    if (expanded) {
        Row(
            modifier =
                Modifier.align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(
                        start = paddingStart,
                        end = paddingEnd,
                        bottom = CinefinSpacing.Space6,
                    ),
            horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space6),
            verticalAlignment = Alignment.Bottom,
        ) {
            DetailPoster(item = item, width = posterWidth, downloadBadge = downloadBadge)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2),
            ) {
                DetailHeroTitleColumn(
                    eyebrow = eyebrow,
                    title = title,
                    originalTitle = originalTitle,
                    meta = meta,
                    expanded = true,
                )
                Spacer(Modifier.height(CinefinSpacing.Space2))
                actions()
            }
        }
    } else {
        Column(
            modifier =
                Modifier.align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(
                        start = paddingStart,
                        end = paddingEnd,
                        bottom = CinefinSpacing.Space5,
                    ),
            verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space4),
        ) {
            // 竖屏：海报 + 标题 / 眉标 / 元信息同行；动作排整宽底排（四个页面同参数）。
            Row(
                horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space4),
                verticalAlignment = Alignment.Bottom,
            ) {
                DetailPoster(item = item, width = posterWidth, downloadBadge = downloadBadge)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2),
                ) {
                    DetailHeroTitleColumn(
                        eyebrow = eyebrow,
                        title = title,
                        originalTitle = originalTitle,
                        meta = meta,
                        expanded = false,
                    )
                }
            }
            actions()
        }
    }
}

@Composable
private fun DetailHeroTitleColumn(
    eyebrow: String?,
    title: String,
    originalTitle: String?,
    meta: String?,
    expanded: Boolean,
) {
    val colors = LocalCinefinColors.current
    eyebrow
        ?.takeIf { it.isNotBlank() }
        ?.let { value ->
            Text(
                text = value,
                style = CinefinType.LabelLarge.lumenTextShadow(LumenTextShadow.Meta),
                color = LocalMediaColors.current.bright,
            )
        }
    Text(
        text = title,
        overflow = TextOverflow.Ellipsis,
        maxLines = 3,
        style =
            (if (expanded) CinefinType.DisplaySmall else CinefinType.HeadlineMedium)
                .lumenTextShadow(LumenTextShadow.Title),
        color = colors.onSurface,
    )
    originalTitle
        ?.takeIf { it.isNotBlank() && it != title }
        ?.let { value ->
            Text(
                text = value,
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
                style = CinefinType.BodyMedium.lumenTextShadow(LumenTextShadow.Meta),
                color = colors.onSurfaceVariant,
            )
        }
    meta
        ?.takeIf { it.isNotBlank() }
        ?.let { value ->
            Text(
                text = value,
                overflow = TextOverflow.Ellipsis,
                maxLines = 2,
                style = CinefinType.LabelMedium.lumenTextShadow(LumenTextShadow.Meta),
                color = colors.onSurfaceVariant,
            )
        }
}

/** 平板头图海报宽（与既有 Show / Movie expanded 一致）。 */
internal val DetailHeroPosterExpandedWidth = 216.dp

/**
 * 竖屏头图海报宽（纯函数，单测覆盖）：随屏宽自适应，夹取 96–120dp。
 *
 * K60 411dp ≈ 107dp（标题列仍保留 ~240dp 可读宽）；窄机 360dp → 下限 96dp。
 */
internal fun detailHeroPosterWidthDp(screenWidthDp: Float): Float =
    (screenWidthDp * 0.26f).coerceIn(96f, 120f)

/** 头图基准高度（纯函数）：平板 400dp / 竖屏 300dp；实际由 [ItemHeader] 的 `heightIn(min)` 保证不裁切。 */
internal fun detailHeroMinHeightDp(expanded: Boolean): Float = if (expanded) 400f else 300f

/** 与 [detailHeroMinHeightDp] 同源的 Dp 包装。 */
private fun detailHeroMinHeight(expanded: Boolean) = detailHeroMinHeightDp(expanded).dp

/** 平板断点 = 840dp（与四页既有 `WIDTH_DP_EXPANDED_LOWER_BOUND` 判定一致）。 */
@Composable
private fun rememberDetailHeroExpanded(): Boolean =
    currentWindowAdaptiveInfo()
        .windowSizeClass
        .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
