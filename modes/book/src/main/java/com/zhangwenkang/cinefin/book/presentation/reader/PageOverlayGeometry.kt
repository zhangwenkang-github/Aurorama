package com.zhangwenkang.cinefin.book.presentation.reader

import androidx.compose.ui.geometry.Rect

/**
 * 页面叠加层几何（搜索高亮 / 批注框）： 页面位图用 `ContentScale.Fit` 画在页槽里，叠加层必须用**同一套 fit 数学**把页面归一化坐标 ([PageRect])
 * 换算成像素坐标，否则高亮会随页槽长宽比偏移（纯函数，单测锁定）。
 */
internal data class FittedImageRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    val right: Float
        get() = left + width

    val bottom: Float
        get() = top + height
}

/** `ContentScale.Fit` 的落位：等比放大到贴住页槽，居中留边。 */
internal fun fittedImageRect(
    boxWidth: Float,
    boxHeight: Float,
    imageWidth: Float,
    imageHeight: Float,
): FittedImageRect {
    if (boxWidth <= 0f || boxHeight <= 0f || imageWidth <= 0f || imageHeight <= 0f) {
        return FittedImageRect(0f, 0f, 0f, 0f)
    }
    val scale = minOf(boxWidth / imageWidth, boxHeight / imageHeight)
    val width = imageWidth * scale
    val height = imageHeight * scale
    return FittedImageRect(
        left = (boxWidth - width) / 2f,
        top = (boxHeight - height) / 2f,
        width = width,
        height = height,
    )
}

/** 页面归一化矩形 → 叠加层像素矩形。 */
internal fun PageRect.toPixelRect(fitted: FittedImageRect): Rect =
    Rect(
        left = fitted.left + left * fitted.width,
        top = fitted.top + top * fitted.height,
        right = fitted.left + right * fitted.width,
        bottom = fitted.top + bottom * fitted.height,
    )

/** 叠加层像素坐标 → 页面归一化矩形（框选批注用），越界自动夹取。 */
internal fun pixelRectToPageRect(
    startX: Float,
    startY: Float,
    endX: Float,
    endY: Float,
    fitted: FittedImageRect,
): PageRect? {
    if (fitted.width <= 0f || fitted.height <= 0f) return null
    return PageRect(
            left = (startX - fitted.left) / fitted.width,
            top = (startY - fitted.top) / fitted.height,
            right = (endX - fitted.left) / fitted.width,
            bottom = (endY - fitted.top) / fitted.height,
        )
        .normalized()
        .takeIf { it.width > 0.01f && it.height > 0.01f }
}
