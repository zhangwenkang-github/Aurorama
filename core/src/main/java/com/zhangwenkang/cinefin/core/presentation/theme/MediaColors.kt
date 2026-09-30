package com.zhangwenkang.cinefin.core.presentation.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

/**
 * 三域媒体色（UI_DESIGN_SYSTEM v1.0 §2.2）。
 *
 * 同一组件只允许使用当前域的 1 组媒体色；未选中状态一律中性色。媒体色必须融入控件本体 （底 / 描边 / 文字 / 图标），禁止独立色块、色点、色条（§2.6 第 1 条）。
 *
 * [container] / [containerPressed] / [outline] 是深色主题下叠加 `SurfaceContainer` 后的呈现值，
 * 与设计稿逐一对应；[containerOver] 等函数用于亮色主题或自定义表面的等价派生。
 */
@Immutable
data class MediaColors(
    val base: Color,
    val bright: Color,
    val dim: Color,
    val onBase: Color,
    val container: Color,
    val containerPressed: Color,
    val outline: Color,
) {
    fun containerOver(surface: Color): Color =
        base.copy(alpha = CinefinTokens.MediaContainerAlpha).compositeOver(surface)

    fun containerPressedOver(surface: Color): Color =
        base.copy(alpha = CinefinTokens.MediaContainerPressedAlpha).compositeOver(surface)

    fun outlineOver(surface: Color): Color =
        base.copy(alpha = CinefinTokens.MediaOutlineAlpha).compositeOver(surface)
}

/** 影视域：琥珀。 */
val MediaFilm =
    MediaColors(
        base = CinefinTokens.MediaFilmBase,
        bright = CinefinTokens.MediaFilmBright,
        dim = CinefinTokens.MediaFilmDim,
        onBase = CinefinTokens.MediaFilmOnBase,
        container = CinefinTokens.MediaFilmContainer,
        containerPressed = CinefinTokens.MediaFilmContainerPressed,
        outline = CinefinTokens.MediaFilmOutline,
    )

/** 音乐域：松石。 */
val MediaMusic =
    MediaColors(
        base = CinefinTokens.MediaMusicBase,
        bright = CinefinTokens.MediaMusicBright,
        dim = CinefinTokens.MediaMusicDim,
        onBase = CinefinTokens.MediaMusicOnBase,
        container = CinefinTokens.MediaMusicContainer,
        containerPressed = CinefinTokens.MediaMusicContainerPressed,
        outline = CinefinTokens.MediaMusicOutline,
    )

/** 阅读域：天青。 */
val MediaBook =
    MediaColors(
        base = CinefinTokens.MediaBookBase,
        bright = CinefinTokens.MediaBookBright,
        dim = CinefinTokens.MediaBookDim,
        onBase = CinefinTokens.MediaBookOnBase,
        container = CinefinTokens.MediaBookContainer,
        containerPressed = CinefinTokens.MediaBookContainerPressed,
        outline = CinefinTokens.MediaBookOutline,
    )
