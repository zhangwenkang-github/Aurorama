package com.zhangwenkang.cinefin.player.local.domain

/**
 * 画面几何变换（§1.6）：旋转 / 镜像 / 裁剪 / 去黑边。
 *
 * 两个内核的实现方式不同，但**面板与偏好只有这一份状态**：
 * - ExoPlayer：对 `PlayerView` 做视图变换（旋转 / 缩放 / 镜像）+ 调整容器宽高比实现裁剪；
 * - mpv：写 `video-rotate` / `video-scale-x|y` / `video-zoom` 原生属性。
 */
data class PlayerVideoTransform(
    /** 旋转角度：0 / 90 / 180 / 270 */
    val rotationDegrees: Int = 0,
    /** 镜像：[VideoMirrorMode.OFF] / [VideoMirrorMode.HORIZONTAL] / [VideoMirrorMode.VERTICAL] */
    val mirror: Int = VideoMirrorMode.OFF,
    /** 四边各裁掉的百分比：0 / 5 / 10 / 15 / 20 */
    val cropPercent: Int = 0,
    /** 去黑边：自动放大画面填满画面区（与视频比例不一致留下的黑边） */
    val letterboxCrop: Boolean = false,
) {
    /** 是否做过任何调整（控制层用它决定「画面」键是否显示激活态） */
    val hasAdjustments: Boolean
        get() =
            rotationDegrees % 360 != 0 ||
                mirror != VideoMirrorMode.OFF ||
                cropPercent > 0 ||
                letterboxCrop
}

/**
 * 旋转后为了铺满画面区需要的等比缩放（纯函数）。
 *
 * 90° / 270° 时画面长宽互换：固定大小的画面区里要让旋转后的画面铺满，必须按 `max(w/h, h/w)` 放大（多出来的部分被裁掉）；0° / 180° 不需要额外缩放。
 */
fun rotationFillScale(
    rotationDegrees: Int,
    viewWidthPx: Float,
    viewHeightPx: Float,
): Float {
    val rotation = ((rotationDegrees % 360) + 360) % 360
    if (rotation != 90 && rotation != 270) return 1f
    if (viewWidthPx <= 0f || viewHeightPx <= 0f) return 1f
    return maxOf(viewWidthPx / viewHeightPx, viewHeightPx / viewWidthPx)
}

/** 裁剪百分比 → 视图缩放（纯函数）：四边各裁 p ⇒ 放大 `1 / (1 - 2p)`。 */
fun cropScale(cropPercent: Int): Float {
    val p = cropPercent.coerceIn(0, 20) / 100.0
    if (p <= 0.0) return 1f
    return (1.0 / (1.0 - 2.0 * p)).toFloat()
}

/**
 * 「去黑边」需要的填满倍数（纯函数）：画面区与视频比例不一致时，按比例差放大到刚好填满。
 *
 * 返回 ≥ 1 的等比系数；信息不足时返回 1（不做任何放大）。
 */
fun letterboxFillScale(
    viewWidthPx: Float,
    viewHeightPx: Float,
    videoWidth: Int,
    videoHeight: Int,
): Float {
    if (viewWidthPx <= 0f || viewHeightPx <= 0f || videoWidth <= 0 || videoHeight <= 0) return 1f
    val viewAspect = viewWidthPx / viewHeightPx
    val videoAspect = videoWidth.toFloat() / videoHeight
    if (videoAspect <= 0f) return 1f
    val scale = maxOf(viewAspect / videoAspect, videoAspect / viewAspect)
    return if (scale > 1f) scale else 1f
}
