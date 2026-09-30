package com.zhangwenkang.cinefin.core.presentation.theme

import androidx.compose.ui.graphics.Color

/**
 * Cinefin Prism 原始 token（依据 `docs/UI_DESIGN_SYSTEM.md` v1.0 §2–§7）。
 *
 * 本文件与历史 `Color.kt` 是项目中仅有的两处允许出现 `Color(0x…)` 字面量的文件；业务与组件代码 一律通过 [CinefinColorsDark] /
 * [CinefinColorsLight]、[MediaFilm] / [MediaMusic] / [MediaBook] 以及 [CinefinType] / [CinefinShapes]
 * / [CinefinMotion] / [CinefinSpacing] 引用语义 token。
 *
 * 标注「派生」的值是设计系统未逐字给出、按 §2.4 派生规则（叠加 / 邻近明度）补全的亮色与扩展槽位， 变更需回设计系统评审。
 */
object CinefinTokens {
    // ---- §2.1 中性色 · 深色主题（默认主题）----
    val SurfaceDark = Color(0xFF151A21)
    val SurfaceDimDark = Color(0xFF10141A)
    val SurfaceContainerDark = Color(0xFF1B212B)
    val SurfaceContainerHighDark = Color(0xFF222A36)
    val SurfaceContainerHighestDark = Color(0xFF2A3340)
    val SurfaceContainerLowestDark = Color(0xFF0E1217) // 派生
    val SurfaceContainerLowDark = Color(0xFF171D25) // 派生
    val SurfaceBrightDark = Color(0xFF323C4B) // 派生
    val OnSurfaceDark = Color(0xFFF4F1EA)
    val OnSurfaceVariantDark = Color(0xFFA7B0BD)
    val OnSurfaceFaintDark = Color(0xFF6E7887)
    val OutlineDark = Color(0xFF2C3542)
    val OutlineVariantDark = Color(0xFF232B36)
    val InverseSurfaceDark = Color(0xFFF4F1EA)
    val InverseOnSurfaceDark = Color(0xFF14181F)

    // ---- §2.4 中性色 · 浅色主题 ----
    val SurfaceLight = Color(0xFFF5F3EE)
    val SurfaceDimLight = Color(0xFFE9E5DC) // 派生
    val SurfaceContainerLight = Color(0xFFFFFFFF)
    val SurfaceContainerHighLight = Color(0xFFEFECE5) // 派生
    val SurfaceContainerHighestLight = Color(0xFFE6E2D9) // 派生
    val SurfaceContainerLowestLight = Color(0xFFFFFFFF) // 派生
    val SurfaceContainerLowLight = Color(0xFFFAF8F4) // 派生
    val SurfaceBrightLight = Color(0xFFFFFFFF) // 派生
    val OnSurfaceLight = Color(0xFF1B212B)
    val OnSurfaceVariantLight = Color(0xFF59616E)
    val OnSurfaceFaintLight = Color(0xFF8A9099) // 派生
    val OutlineLight = Color(0xFFD8D3C8)
    val OutlineVariantLight = Color(0xFFE7E2D8)
    val InverseSurfaceLight = Color(0xFF1B212B) // 派生
    val InverseOnSurfaceLight = Color(0xFFF4F1EA) // 派生

    // ---- §2.1 语义色 ----
    val Error = Color(0xFFE87C6E)
    val OnError = Color(0xFF1A0F0C)
    val ErrorContainerDark = Color(0xFF372A2D) // 派生：error 16% 叠加 surface
    val OnErrorContainerDark = Color(0xFFF4F1EA) // 派生
    val ErrorContainerLight = Color(0xFFF6D9D3) // 派生
    val OnErrorContainerLight = Color(0xFF3F120B) // 派生
    val Scrim = Color(0xFF06080C)
    val NavSurfaceDark = Color(0xFF12171D) // §8.6 侧导航底色
    val NavSurfaceLight = Color(0xFFEDEAE3) // 派生

    // ---- §2.1 状态层（深色主题用白，浅色主题用黑）----
    const val StateHoverAlpha = 0.08f
    const val StateFocusAlpha = 0.12f
    const val StatePressedAlpha = 0.12f
    const val DisabledAlpha = 0.38f
    const val ScrimAlpha = 0.72f

    // ---- §2.2 媒体色合成系数 ----
    const val MediaContainerAlpha = 0.16f
    const val MediaContainerPressedAlpha = 0.24f
    const val MediaOutlineAlpha = 0.45f
    const val FocusRingAlpha = 0.60f

    // ---- §5.2 内高光 / §8.8 图上进度轨道 ----
    const val TopHighlightAlpha = 0.05f
    const val ProgressTrackOnImageAlpha = 0.16f

    // ---- §2.2 媒体色 · 影视（琥珀）----
    val MediaFilmBase = Color(0xFFE8A15C)
    val MediaFilmBright = Color(0xFFEDB680)
    val MediaFilmDim = Color(0xFFBE844B)
    val MediaFilmContainer = Color(0xFF3C3533)
    val MediaFilmContainerPressed = Color(0xFF4C4037)
    val MediaFilmOnBase = Color(0xFF141009)
    val MediaFilmOutline = Color(0xFF775B41)

    // ---- §2.2 媒体色 · 音乐（松石）----
    val MediaMusicBase = Color(0xFF3FC9A0)
    val MediaMusicBright = Color(0xFF69D5B5)
    val MediaMusicDim = Color(0xFF34A583)
    val MediaMusicContainer = Color(0xFF213C3E)
    val MediaMusicContainerPressed = Color(0xFF244947)
    val MediaMusicOnBase = Color(0xFF06120D)
    val MediaMusicOutline = Color(0xFF2B6D60)

    // ---- §2.2 媒体色 · 阅读（天青）----
    val MediaBookBase = Color(0xFF6FA8FF)
    val MediaBookBright = Color(0xFF8FBBFF)
    val MediaBookDim = Color(0xFF5B8AD1)
    val MediaBookContainer = Color(0xFF28374D)
    val MediaBookContainerPressed = Color(0xFF2F415E)
    val MediaBookOnBase = Color(0xFF0A1220)
    val MediaBookOutline = Color(0xFF415E8A)

    // ---- §8.14 阅读器纸色主题（C · Nocturne 皮肤）----
    val PaperSurface = Color(0xFFFBF6EC)
    val PaperAccent = Color(0xFFA8843C)

    // ---- §5.3 / §6.4 画面覆盖层（唯一允许 blur 的场景）----
    val OverlayGlass = Color(0x99000000) // 黑 60%
}
