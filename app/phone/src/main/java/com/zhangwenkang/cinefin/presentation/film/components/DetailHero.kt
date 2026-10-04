package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.text.style.TextAlign
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
    /** W66b：竖屏眉标（只留类型，去掉与元信息重复的年份）；默认与 [eyebrow] 相同。 */
    heroEyebrow: String? = eyebrow,
    downloadBadge: DownloadBadgeInfo = DownloadBadgeInfo(),
    /** W66b：`heroLayout = true` 时页面把动作排换成 hero 键布局（`ItemButtonsBar(heroLayout = ...)`）。 */
    actions: @Composable ColumnScope.(heroLayout: Boolean) -> Unit,
) {
    val expanded = rememberDetailHeroExpanded()
    if (expanded) {
        ItemHeader(
            item = item,
            scrollState = scrollState,
            height = detailHeroMinHeight(expanded = true),
        ) {
            DetailHeroExpandedForeground(
                item = item,
                eyebrow = eyebrow,
                title = title,
                originalTitle = originalTitle,
                meta = meta,
                downloadBadge = downloadBadge,
                actions = actions,
            )
        }
    } else {
        DetailHeroCompactForeground(
            item = item,
            scrollState = scrollState,
            heroEyebrow = heroEyebrow,
            title = title,
            originalTitle = originalTitle,
            meta = meta,
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
    /** W66b：竖屏眉标（只留类型，去掉与元信息重复的年份）；默认与 [eyebrow] 相同。 */
    heroEyebrow: String? = eyebrow,
    downloadBadge: DownloadBadgeInfo = DownloadBadgeInfo(),
    /** W66b：`heroLayout = true` 时页面把动作排换成 hero 键布局（`ItemButtonsBar(heroLayout = ...)`）。 */
    actions: @Composable ColumnScope.(heroLayout: Boolean) -> Unit,
) {
    val expanded = rememberDetailHeroExpanded()
    if (expanded) {
        ItemHeader(
            item = item,
            lazyListState = lazyListState,
            height = detailHeroMinHeight(expanded = true),
        ) {
            DetailHeroExpandedForeground(
                item = item,
                eyebrow = eyebrow,
                title = title,
                originalTitle = originalTitle,
                meta = meta,
                downloadBadge = downloadBadge,
                actions = actions,
            )
        }
    } else {
        DetailHeroCompactForeground(
            item = item,
            lazyListState = lazyListState,
            heroEyebrow = heroEyebrow,
            title = title,
            originalTitle = originalTitle,
            meta = meta,
            downloadBadge = downloadBadge,
            actions = actions,
        )
    }
}

/** W66b：平板（≥840dp）头图——海报 216dp + 标题列内嵌动作排（与既有 Show / Movie expanded 一致）。 */
@Composable
private fun BoxScope.DetailHeroExpandedForeground(
    item: FindroidItem,
    eyebrow: String?,
    title: String,
    originalTitle: String?,
    meta: String?,
    downloadBadge: DownloadBadgeInfo,
    actions: @Composable ColumnScope.(heroLayout: Boolean) -> Unit,
) {
    val safePadding = rememberSafePadding()
    val gutter = rememberPageGutter()
    val paddingStart = safePadding.start + gutter
    val paddingEnd = safePadding.end + gutter

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
        DetailPoster(
            item = item,
            width = DetailHeroPosterExpandedWidth,
            downloadBadge = downloadBadge,
        )
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
            actions(false)
        }
    }
}

/**
 * W66b 竖屏 hero（<840dp，用户 2026-10-04 复验拍板）： backdrop 整宽
 * [DetailHeroCompactBackdropHeight]（底部渐变到页面底色）→ 海报居中、半压 backdrop 下缘 → 居中标题块（类型眉标 → 标题 → 原题 → 元信息）→
 * hero 动作区（`heroLayout = true`）。
 */
@Composable
private fun DetailHeroCompactForeground(
    item: FindroidItem,
    scrollState: ScrollState? = null,
    lazyListState: LazyListState? = null,
    heroEyebrow: String?,
    title: String,
    originalTitle: String?,
    meta: String?,
    downloadBadge: DownloadBadgeInfo,
    actions: @Composable ColumnScope.(heroLayout: Boolean) -> Unit,
) {
    val safePadding = rememberSafePadding()
    val gutter = rememberPageGutter()
    val paddingStart = safePadding.start + gutter
    val paddingEnd = safePadding.end + gutter
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    val posterWidth = detailHeroPosterWidthDp(LocalConfiguration.current.screenWidthDp.toFloat()).dp
    // 海报中心落在 backdrop 底缘（半压）：列表顶部留白 = backdrop 高 − 海报高一半。
    val contentTopPadding = DetailHeroCompactBackdropHeight - posterWidth * 1.5f / 2f

    Box(modifier = Modifier.fillMaxWidth()) {
        when {
            scrollState != null ->
                HeroBackdropLayer(
                    item = item,
                    scrollState = scrollState,
                    height = DetailHeroCompactBackdropHeight,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            lazyListState != null ->
                HeroBackdropLayer(
                    item = item,
                    lazyListState = lazyListState,
                    height = DetailHeroCompactBackdropHeight,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
        }
        Column(
            modifier =
                Modifier.align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(top = contentTopPadding, start = paddingStart, end = paddingEnd),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space3),
        ) {
            DetailPoster(item = item, width = posterWidth, downloadBadge = downloadBadge)
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2),
            ) {
                heroEyebrow
                    ?.takeIf { it.isNotBlank() }
                    ?.let { value ->
                        Text(
                            text = value,
                            style = CinefinType.LabelLarge.lumenTextShadow(LumenTextShadow.Meta),
                            color = media.bright,
                            textAlign = TextAlign.Center,
                        )
                    }
                Text(
                    text = title,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 2,
                    textAlign = TextAlign.Center,
                    style = CinefinType.HeadlineMedium.lumenTextShadow(LumenTextShadow.Title),
                    color = colors.onSurface,
                )
                originalTitle
                    ?.takeIf { it.isNotBlank() && it != title }
                    ?.let { value ->
                        Text(
                            text = value,
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                            textAlign = TextAlign.Center,
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
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            style = CinefinType.LabelMedium.lumenTextShadow(LumenTextShadow.Meta),
                            color = colors.onSurfaceVariant,
                        )
                    }
            }
            Spacer(Modifier.height(CinefinSpacing.Space1))
            actions(true)
            Spacer(Modifier.height(CinefinSpacing.Space5))
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

/** W66b：竖屏 hero 的 backdrop 高度（用户口径 240–280dp，取 260dp）。 */
internal val DetailHeroCompactBackdropHeight = 260.dp

/**
 * 竖屏 hero 海报宽（纯函数，单测覆盖）：随屏宽自适应，夹取 120–140dp（约 130dp）。
 *
 * K60 411dp ≈ 131.5dp（居中、半压 backdrop 下缘）；窄机 360dp → 下限 120dp。
 */
internal fun detailHeroPosterWidthDp(screenWidthDp: Float): Float =
    (screenWidthDp * 0.32f).coerceIn(120f, 140f)

/** W66b：hero 动作区降级判定（纯函数，单测覆盖）——屏宽 <360dp 时「一行四键」降级为 「播放整行 + 三键一行」，保证窄机不挤压文字。 */
internal fun detailHeroActionsDegraded(screenWidthDp: Float): Boolean = screenWidthDp < 360f

/** 平板头图基准高度（纯函数）：400dp；实际由 [ItemHeader] 的 `heightIn(min)` 保证不裁切。 */
internal fun detailHeroMinHeightDp(expanded: Boolean): Float = if (expanded) 400f else 300f

/** 与 [detailHeroMinHeightDp] 同源的 Dp 包装。 */
private fun detailHeroMinHeight(expanded: Boolean) = detailHeroMinHeightDp(expanded).dp

/** 平板断点 = 840dp（与四页既有 `WIDTH_DP_EXPANDED_LOWER_BOUND` 判定一致）。 */
@Composable
private fun rememberDetailHeroExpanded(): Boolean =
    currentWindowAdaptiveInfo()
        .windowSizeClass
        .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
