package com.zhangwenkang.cinefin.book.presentation.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors

/**
 * 页面叠加层（W29-READER）：在 `ContentScale.Fit` 画出的页面位图上画
 * ①搜索命中矩形（[searchRects]）②本地批注矩形（[annotations]）③批注模式下的实时框选。
 *
 * 交互口径：
 * - 非批注模式：单击命中批注框 → [onAnnotationTap]（打开备注编辑）；
 * - 批注模式：单指拖动框选（消费事件，单指翻页暂停），松手 → [onAnnotationRequested]；
 * - 双指缩放仍由外层 `ZoomableSlot` 处理（本层只在批注模式消费事件）。
 */
@Composable
internal fun PageOverlay(
    imageWidth: Float,
    imageHeight: Float,
    searchRects: List<PageRect>,
    annotations: List<ReaderAnnotation>,
    annotating: Boolean,
    onAnnotationRequested: (PageRect) -> Unit,
    onAnnotationTap: (ReaderAnnotation) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = LocalMediaColors.current.base
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var dragRect by remember { mutableStateOf<PageRect?>(null) }
    val fitted =
        remember(boxSize, imageWidth, imageHeight) {
            fittedImageRect(
                boxWidth = boxSize.width.toFloat(),
                boxHeight = boxSize.height.toFloat(),
                imageWidth = imageWidth,
                imageHeight = imageHeight,
            )
        }
    val dashEffect = remember { PathEffect.dashPathEffect(floatArrayOf(14f, 10f)) }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .onSizeChanged { boxSize = it }
                .pointerInput(annotating, fitted) {
                    if (!annotating) return@pointerInput
                    detectAnnotationDrag(
                        onDrag = { start, current ->
                            dragRect = pageRectBetween(start, current, fitted)
                        },
                        onFinish = { start, current ->
                            val rect = pageRectBetween(start, current, fitted)
                            dragRect = null
                            if (rect != null) onAnnotationRequested(rect)
                        },
                    )
                }
                .pointerInput(annotating, fitted, annotations) {
                    if (annotating) return@pointerInput
                    detectTapGestures(
                        onTap = { position ->
                            annotationAt(position, annotations, fitted)?.let(onAnnotationTap)
                        }
                    )
                }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (fitted.width <= 0f || fitted.height <= 0f) return@Canvas
            searchRects.forEach { rect ->
                drawPageRect(
                    rect = rect,
                    fitted = fitted,
                    fill = accent.copy(alpha = 0.16f),
                    stroke = accent.copy(alpha = 0.85f),
                    strokeWidth = 1.5.dp.toPx(),
                )
            }
            annotations.forEach { annotation ->
                drawPageRect(
                    rect = annotation.rect,
                    fitted = fitted,
                    fill = accent.copy(alpha = 0.12f),
                    stroke = accent.copy(alpha = 0.9f),
                    strokeWidth = 2.dp.toPx(),
                    pathEffect = if (annotation.note.isBlank()) null else dashEffect,
                )
            }
            dragRect?.let { rect ->
                drawPageRect(
                    rect = rect,
                    fitted = fitted,
                    fill = accent.copy(alpha = 0.18f),
                    stroke = accent.copy(alpha = 0.9f),
                    strokeWidth = 2.dp.toPx(),
                    pathEffect = dashEffect,
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPageRect(
    rect: PageRect,
    fitted: FittedImageRect,
    fill: Color,
    stroke: Color,
    strokeWidth: Float,
    pathEffect: PathEffect? = null,
) {
    val pixel = rect.toPixelRect(fitted)
    val size = Size(pixel.width, pixel.height)
    if (size.width <= 0f || size.height <= 0f) return
    drawRect(color = fill, topLeft = pixel.topLeft, size = size)
    drawRect(
        color = stroke,
        topLeft = pixel.topLeft,
        size = size,
        style = Stroke(width = strokeWidth, pathEffect = pathEffect),
    )
}

/** 框选：把「按下 → 当前」两个像素点换算为页面归一化矩形；太小（<1%）返回 null。 */
private fun pageRectBetween(
    start: Offset,
    current: Offset,
    fitted: FittedImageRect,
): PageRect? = pixelRectToPageRect(start.x, start.y, current.x, current.y, fitted)

/** 点中哪条批注：后画的优先（列表尾部在上）。 */
private fun annotationAt(
    position: Offset,
    annotations: List<ReaderAnnotation>,
    fitted: FittedImageRect,
): ReaderAnnotation? {
    if (fitted.width <= 0f || fitted.height <= 0f) return null
    val x = (position.x - fitted.left) / fitted.width
    val y = (position.y - fitted.top) / fitted.height
    if (x !in 0f..1f || y !in 0f..1f) return null
    return annotations.lastOrNull { it.rect.contains(x, y) }
}

/**
 * 批注框选手势：单指按下即消费事件（翻页不会被触发），拖动过程持续上报，松手回调最终矩形。
 *
 * 只在批注模式挂上；非批注模式的单击由 [detectTapGestures] 处理，不消费拖动 → 分页 / 滚动照常。
 */
private suspend fun PointerInputScope.detectAnnotationDrag(
    onDrag: (start: Offset, current: Offset) -> Unit,
    onFinish: (start: Offset, current: Offset) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        var current = down.position
        onDrag(down.position, current)
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
                change.consume()
                break
            }
            current = change.position
            change.consume()
            onDrag(down.position, current)
        }
        onFinish(down.position, current)
    }
}
