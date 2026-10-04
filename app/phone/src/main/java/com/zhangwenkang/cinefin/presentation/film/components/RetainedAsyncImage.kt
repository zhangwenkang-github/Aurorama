package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter

/**
 * W69：海报 / 剧照的「旧图保留 + 交叉淡入」异步图片。
 *
 * 与裸 `AsyncImage` 的差别（任务书 §三.3："图片 URL 变化时保持旧图显示直到新图加载完成，不得先把海报置空 / 黑底"）：
 * - 模型变化（服务器元数据刷新 / 换图 / 服务器图失败回落本地封面）时，新请求加载期间**继续画上一张已成功加载的图**；
 * - 新图就绪后交叉淡入替换（[crossfadeMillis]）；
 * - 模型为空或从未加载成功时才画 [placeholderPainter] / [errorPainter]。
 *
 * [retainKey] 是"同一张卡"的稳定身份（一般是 `item.id`）：列表复用槽位换条目时重置上一张图， 只有同一张卡的图片地址变化才保留旧图。
 */
@Composable
fun RetainedAsyncImage(
    model: Any?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
    retainKey: Any? = null,
    /** 首次加载（还没有旧图）时的画法；null = 透明（由调用方的背景决定底色）。 */
    placeholderPainter: Painter? = null,
    /** 加载失败且没有旧图时的画法；默认沿用 [placeholderPainter]。 */
    errorPainter: Painter? = placeholderPainter,
    /** 失败回调（每次进入错误态一次；书籍卡据此回落本地封面 / 占位）。 */
    onError: (() -> Unit)? = null,
    crossfadeMillis: Int = 160,
) {
    val painter = rememberAsyncImagePainter(model = model)
    val state by painter.state.collectAsStateWithLifecycle()
    var retainedPainter by remember(retainKey) { mutableStateOf<Painter?>(null) }

    LaunchedEffect(state) {
        when (val current = state) {
            is AsyncImagePainter.State.Success -> retainedPainter = current.painter
            is AsyncImagePainter.State.Error -> onError?.invoke()
            else -> Unit
        }
    }

    val drawPainter: Painter? =
        when (val current = state) {
            is AsyncImagePainter.State.Success -> current.painter
            is AsyncImagePainter.State.Error -> retainedPainter ?: errorPainter
            else ->
                when {
                    // 新图加载中：旧图继续显示，避免刷新时海报先变黑底。
                    retainedPainter != null -> retainedPainter
                    model != null -> placeholderPainter
                    else -> errorPainter
                }
        }

    if (drawPainter == null) {
        // 无图可画：保持透明，底色交由调用方的 modifier / 背景。
        Box(modifier = modifier)
    } else {
        Crossfade(
            modifier = modifier,
            targetState = drawPainter,
            animationSpec = tween(durationMillis = crossfadeMillis),
            label = "retained-image-crossfade",
        ) { active ->
            Image(
                painter = active,
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
