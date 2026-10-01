package com.zhangwenkang.cinefin.player.local.domain

/**
 * 画面比例档位（播放器面板的三个选项）。
 *
 * 取值与 media3 `AspectRatioFrameLayout.RESIZE_MODE_*` 一致——`player:local` 不依赖 media3-ui，
 * 这里只固化同一份数字契约，Activity 两边直接透传，不做二次映射。
 */
object PlayerResizeModes {
    /** 适应屏幕：保留比例，四周留黑边（letterbox） */
    const val FIT = 0

    /** 拉伸填满：拉满画面区，比例会变形 */
    const val FILL = 3

    /** 裁剪填满：保留比例铺满，裁掉溢出画面 */
    const val ZOOM = 4
}

/** mpv 侧的画面比例表达：`keepaspect` + `panscan`。 */
data class MpvResizeProperties(val keepAspect: Boolean, val panscan: Boolean)

/**
 * 把比例档位翻译成 mpv 的原生属性（纯函数）：
 * - 适应：[MpvResizeProperties.keepAspect] = true、panscan = false → 黑边 letterbox；
 * - 裁剪填满：keepAspect = true、panscan = true → 等比铺满并裁掉溢出；
 * - 拉伸填满：keepAspect = false → 直接拉满画面区（允许变形）。
 *
 * 未知档位按「适应」处理：避免从旧偏好 / 旧内核带回 panscan 残留把画面一直放大。
 */
fun mpvResizeProperties(resizeMode: Int): MpvResizeProperties =
    when (resizeMode) {
        PlayerResizeModes.ZOOM -> MpvResizeProperties(keepAspect = true, panscan = true)
        PlayerResizeModes.FILL -> MpvResizeProperties(keepAspect = false, panscan = false)
        else -> MpvResizeProperties(keepAspect = true, panscan = false)
    }
