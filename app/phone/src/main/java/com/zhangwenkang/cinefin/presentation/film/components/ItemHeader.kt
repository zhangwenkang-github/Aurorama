package com.zhangwenkang.cinefin.presentation.film.components

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalLumenColors
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidSeason
import com.zhangwenkang.cinefin.presentation.utils.parallaxLayoutModifier

@Composable
fun ItemHeader(
    item: FindroidItem,
    scrollState: ScrollState,
    showLogo: Boolean = false,
    height: Dp = 288.dp,
    content: @Composable (BoxScope.() -> Unit) = {},
) {
    val colors = LocalCinefinColors.current
    val context = LocalContext.current
    var backdropUri =
        when (item) {
            is FindroidEpisode -> item.images.primary
            // W73（#10）：季没有 backdrop / 海报时回落剧集图——服务器的「未知季」分组不建图，
            // 否则季详情页整块头图空白（用户 2026-10-06 截图 2）。
            is FindroidSeason ->
                item.images.backdrop ?: item.images.primary ?: item.images.showPrimary
            else -> item.images.backdrop
        }

    // Ugly workaround to append the files directory when loading local images
    if (backdropUri?.scheme == null) {
        backdropUri =
            Uri.Builder()
                .appendEncodedPath("${context.filesDir}")
                .appendEncodedPath(backdropUri?.path)
                .build()
    }

    ItemHeaderBase(
        item = item,
        showLogo = showLogo,
        height = height,
        backdropImage = {
            // W69：头图刷新时保留上一张图（不先置空）。
            RetainedAsyncImage(
                model = backdropUri,
                contentDescription = null,
                modifier =
                    Modifier.fillMaxSize()
                        .parallaxLayoutModifier(scrollState = scrollState, rate = 2),
                retainKey = item.id,
                placeholderPainter = ColorPainter(colors.surfaceContainer),
                contentScale = ContentScale.Crop,
            )
        },
        content = content,
    )
}

@Composable
fun ItemHeader(
    item: FindroidItem,
    lazyListState: LazyListState,
    showLogo: Boolean = false,
    height: Dp = 288.dp,
    content: @Composable (BoxScope.() -> Unit) = {},
) {
    val colors = LocalCinefinColors.current
    val context = LocalContext.current
    var backdropUri =
        when (item) {
            is FindroidEpisode -> item.images.primary
            is FindroidSeason -> item.images.showBackdrop
            else -> item.images.backdrop
        }

    // Ugly workaround to append the files directory when loading local images
    if (backdropUri?.scheme == null) {
        backdropUri =
            Uri.Builder()
                .appendEncodedPath("${context.filesDir}")
                .appendEncodedPath(backdropUri?.path)
                .build()
    }

    ItemHeaderBase(
        item = item,
        showLogo = showLogo,
        height = height,
        backdropImage = {
            // W69：头图刷新时保留上一张图（不先置空）。
            RetainedAsyncImage(
                model = backdropUri,
                contentDescription = null,
                modifier =
                    Modifier.fillMaxSize()
                        .parallaxLayoutModifier(lazyListState = lazyListState, rate = 2),
                retainKey = item.id,
                placeholderPainter = ColorPainter(colors.surfaceContainer),
                contentScale = ContentScale.Crop,
            )
        },
        content = content,
    )
}

@Composable
private fun ItemHeaderBase(
    item: FindroidItem,
    showLogo: Boolean = false,
    height: Dp = 288.dp,
    backdropImage: @Composable (() -> Unit),
    content: @Composable (BoxScope.() -> Unit) = {},
) {
    val colors = LocalCinefinColors.current

    val logoUri =
        when (item) {
            is FindroidEpisode -> item.images.showLogo
            else -> item.images.logo
        }

    // W66：由 `heightIn(min)` 替代固定高度——头图内容（统一 DetailHero 的海报 + 标题 + 动作排）
    // 高于基准高度时由内容撑高，不再被压缩测量裁切（踩坑 29 同类）。
    Box(modifier = Modifier.fillMaxWidth().heightIn(min = height).clipToBounds()) {
        Box(modifier = Modifier.matchParentSize()) { backdropImage() }
        LumenBackdropScrims()
        content()
        if (showLogo) {
            RetainedAsyncImage(
                model = logoUri,
                contentDescription = null,
                modifier =
                    Modifier.align(Alignment.BottomCenter)
                        .padding(CinefinSpacing.Space5)
                        .height(100.dp)
                        .fillMaxWidth(),
                contentScale = ContentScale.Fit,
                retainKey = item.id,
                placeholderPainter = null,
                errorPainter = null,
            )
        }
    }
}

/**
 * Lumen 背景全套 scrim（顶部光晕 + 侧向渐隐 + 暗角 + 底部渐隐到页面底色）。
 *
 * 左侧水平渐隐只在 Lumen 区域（电影 / 剧集详情）叠加，非 Lumen 详情页保持原观感；被 [ItemHeaderBase] 与 W66b 竖屏 hero 的独立 backdrop
 * 图层（[HeroBackdropLayer]）共用。
 */
@Composable
internal fun BoxScope.LumenBackdropScrims() {
    val backgroundColor = LocalCinefinColors.current.surface
    val lumenScrimColor = LocalLumenColors.current?.scrim
    Canvas(modifier = Modifier.matchParentSize()) {
        // Lumen：顶部光晕（内容即光源）+ 左侧水平渐隐（文字托底）+ 内暗角 + 底部渐隐，内容图向下溶进页面底色
        drawRect(brush = lumenTopGlow)
        lumenScrimColor?.let { drawRect(brush = lumenSideScrim(it)) }
        drawRect(brush = lumenVignette)
        drawRect(brush = lumenBottomScrim(backgroundColor))
    }
}

/**
 * W66b：竖屏 hero 的独立 backdrop 图层（整宽 + 自定高度 + 全套 Lumen scrim）——海报 / 标题块居中压在它上面，
 * 底部渐变到页面底色。`ScrollState` / `LazyListState` 双入口保持与页面滚动视差一致。
 */
@Composable
internal fun HeroBackdropLayer(
    item: FindroidItem,
    scrollState: ScrollState,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCinefinColors.current
    val context = LocalContext.current
    var backdropUri =
        when (item) {
            is FindroidEpisode -> item.images.primary
            else -> item.images.backdrop
        }
    if (backdropUri?.scheme == null) {
        backdropUri =
            Uri.Builder()
                .appendEncodedPath("${context.filesDir}")
                .appendEncodedPath(backdropUri?.path)
                .build()
    }
    Box(modifier = modifier.fillMaxWidth().height(height)) {
        // W69：竖屏 hero backdrop 刷新时保留上一张图（不先置空）。
        RetainedAsyncImage(
            model = backdropUri,
            contentDescription = null,
            modifier =
                Modifier.fillMaxSize().parallaxLayoutModifier(scrollState = scrollState, rate = 2),
            retainKey = item.id,
            placeholderPainter = ColorPainter(colors.surfaceContainer),
            contentScale = ContentScale.Crop,
        )
        LumenBackdropScrims()
    }
}

/** [HeroBackdropLayer] 的 LazyListState 版本（季详情页 LazyColumn）。 */
@Composable
internal fun HeroBackdropLayer(
    item: FindroidItem,
    lazyListState: LazyListState,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCinefinColors.current
    val context = LocalContext.current
    var backdropUri =
        when (item) {
            is FindroidEpisode -> item.images.primary
            is FindroidSeason -> item.images.showBackdrop
            else -> item.images.backdrop
        }
    if (backdropUri?.scheme == null) {
        backdropUri =
            Uri.Builder()
                .appendEncodedPath("${context.filesDir}")
                .appendEncodedPath(backdropUri?.path)
                .build()
    }
    Box(modifier = modifier.fillMaxWidth().height(height)) {
        // W69：竖屏 hero backdrop 刷新时保留上一张图（不先置空）。
        RetainedAsyncImage(
            model = backdropUri,
            contentDescription = null,
            modifier =
                Modifier.fillMaxSize()
                    .parallaxLayoutModifier(lazyListState = lazyListState, rate = 2),
            retainKey = item.id,
            placeholderPainter = ColorPainter(colors.surfaceContainer),
            contentScale = ContentScale.Crop,
        )
        LumenBackdropScrims()
    }
}
