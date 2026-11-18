package com.zhangwenkang.cinefin.core.presentation.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

/**
 * 三域媒体色（UI_DESIGN_SYSTEM v1.0 §2.2）。
 *
 * 同一组件只允许使用当前域的 1 组媒体色；未选中状态一律中性色。媒体色必须融入控件本体 （底 / 描边 / 文字 / 图标），禁止独立色块、色点、色条（§2.6 第 1 条）。
 *
 * base / bright / dim / onBase 是设计系统给定的固定色值；[container] / [containerPressed] / [outline]
 * 是按当前色板派生的呈现值（[mediaColorsFor] 用 [containerOver] 等函数在 `surfaceContainer` 上合成）， [MediaFilm] /
 * [MediaMusic] / [MediaBook] 保留深色主题的发布值供占位图等静态场景引用。
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

    /** 把发布值里的容器槽位换成在 [surface] 上派生的等价色。 */
    internal fun derivedOver(surface: Color): MediaColors =
        copy(
            container = containerOver(surface),
            containerPressed = containerPressedOver(surface),
            outline = outlineOver(surface),
        )
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

/** 当前域媒体色，容器槽位按 [colors] 的 `surfaceContainer` 派生。 */
fun mediaColorsFor(domain: ContentDomain, colors: CinefinColors): MediaColors =
    when (domain) {
        ContentDomain.Movie -> MediaFilm
        ContentDomain.Music -> MediaMusic
        ContentDomain.Book -> MediaBook
        ContentDomain.Neutral -> MediaFilm
    }.derivedOver(colors.surfaceContainer)

/** 首页 / 设置例外页允许并列展示另外两域颜色（§2.5 例外 2），容器同样按 [colors] 派生。 */
internal fun otherMediaColorsFor(
    domain: ContentDomain,
    colors: CinefinColors,
): Pair<MediaColors, MediaColors> {
    val (secondary, tertiary) =
        when (domain) {
            ContentDomain.Movie -> MediaMusic to MediaBook
            ContentDomain.Music -> MediaBook to MediaFilm
            ContentDomain.Book -> MediaFilm to MediaMusic
            ContentDomain.Neutral -> MediaMusic to MediaBook
        }
    return secondary.derivedOver(colors.surfaceContainer) to
        tertiary.derivedOver(colors.surfaceContainer)
}
