package com.zhangwenkang.cinefin.core.presentation.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

/**
 * Cinefin 语义颜色 token（UI_DESIGN_SYSTEM v1.0 §2.1–§2.4）。
 *
 * 组件层只允许引用本数据类或 [MediaColors]，禁止直接引用 [CinefinTokens] 的原始色值。 M3 槽位映射由 [cinefinColorScheme] 统一完成，保证
 * `MaterialTheme.colorScheme` 与设计系统同源。
 */
@Immutable
data class CinefinColors(
    val surface: Color,
    val surfaceDim: Color,
    val surfaceBright: Color,
    val surfaceContainerLowest: Color,
    val surfaceContainerLow: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,
    val surfaceContainerHighest: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val onSurfaceFaint: Color,
    val outline: Color,
    val outlineVariant: Color,
    val inverseSurface: Color,
    val inverseOnSurface: Color,
    val scrim: Color,
    val error: Color,
    val onError: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
    val navSurface: Color,
    val stateHover: Color,
    val stateFocus: Color,
    val statePressed: Color,
    val progressTrack: Color,
    val progressTrackOnImage: Color,
    val topHighlight: Color,
    val disabledAlpha: Float,
)

/** 深色主题（默认）：石板蓝黑 + 奶白。 */
val CinefinColorsDark =
    CinefinColors(
        surface = CinefinTokens.SurfaceDark,
        surfaceDim = CinefinTokens.SurfaceDimDark,
        surfaceBright = CinefinTokens.SurfaceBrightDark,
        surfaceContainerLowest = CinefinTokens.SurfaceContainerLowestDark,
        surfaceContainerLow = CinefinTokens.SurfaceContainerLowDark,
        surfaceContainer = CinefinTokens.SurfaceContainerDark,
        surfaceContainerHigh = CinefinTokens.SurfaceContainerHighDark,
        surfaceContainerHighest = CinefinTokens.SurfaceContainerHighestDark,
        onSurface = CinefinTokens.OnSurfaceDark,
        onSurfaceVariant = CinefinTokens.OnSurfaceVariantDark,
        onSurfaceFaint = CinefinTokens.OnSurfaceFaintDark,
        outline = CinefinTokens.OutlineDark,
        outlineVariant = CinefinTokens.OutlineVariantDark,
        inverseSurface = CinefinTokens.InverseSurfaceDark,
        inverseOnSurface = CinefinTokens.InverseOnSurfaceDark,
        scrim = CinefinTokens.Scrim.copy(alpha = CinefinTokens.ScrimAlpha),
        error = CinefinTokens.Error,
        onError = CinefinTokens.OnError,
        errorContainer = CinefinTokens.ErrorContainerDark,
        onErrorContainer = CinefinTokens.OnErrorContainerDark,
        navSurface = CinefinTokens.NavSurfaceDark,
        stateHover = Color.White.copy(alpha = CinefinTokens.StateHoverAlpha),
        stateFocus = Color.White.copy(alpha = CinefinTokens.StateFocusAlpha),
        statePressed = Color.White.copy(alpha = CinefinTokens.StatePressedAlpha),
        progressTrack = Color.White.copy(alpha = 0.12f),
        progressTrackOnImage = Color.White.copy(alpha = CinefinTokens.ProgressTrackOnImageAlpha),
        topHighlight = Color.White.copy(alpha = CinefinTokens.TopHighlightAlpha),
        disabledAlpha = CinefinTokens.DisabledAlpha,
    )

/** 浅色主题：宣纸白底，媒体色沿用同一组 base（§2.4）。 */
val CinefinColorsLight =
    CinefinColors(
        surface = CinefinTokens.SurfaceLight,
        surfaceDim = CinefinTokens.SurfaceDimLight,
        surfaceBright = CinefinTokens.SurfaceBrightLight,
        surfaceContainerLowest = CinefinTokens.SurfaceContainerLowestLight,
        surfaceContainerLow = CinefinTokens.SurfaceContainerLowLight,
        surfaceContainer = CinefinTokens.SurfaceContainerLight,
        surfaceContainerHigh = CinefinTokens.SurfaceContainerHighLight,
        surfaceContainerHighest = CinefinTokens.SurfaceContainerHighestLight,
        onSurface = CinefinTokens.OnSurfaceLight,
        onSurfaceVariant = CinefinTokens.OnSurfaceVariantLight,
        onSurfaceFaint = CinefinTokens.OnSurfaceFaintLight,
        outline = CinefinTokens.OutlineLight,
        outlineVariant = CinefinTokens.OutlineVariantLight,
        inverseSurface = CinefinTokens.InverseSurfaceLight,
        inverseOnSurface = CinefinTokens.InverseOnSurfaceLight,
        scrim = CinefinTokens.Scrim.copy(alpha = CinefinTokens.ScrimAlpha),
        error = CinefinTokens.Error,
        onError = CinefinTokens.OnError,
        errorContainer = CinefinTokens.ErrorContainerLight,
        onErrorContainer = CinefinTokens.OnErrorContainerLight,
        navSurface = CinefinTokens.NavSurfaceLight,
        stateHover = Color.Black.copy(alpha = CinefinTokens.StateHoverAlpha),
        stateFocus = Color.Black.copy(alpha = CinefinTokens.StateFocusAlpha),
        statePressed = Color.Black.copy(alpha = CinefinTokens.StatePressedAlpha),
        progressTrack = Color.Black.copy(alpha = 0.12f),
        progressTrackOnImage = Color.White.copy(alpha = CinefinTokens.ProgressTrackOnImageAlpha),
        topHighlight = Color.White.copy(alpha = CinefinTokens.TopHighlightAlpha),
        disabledAlpha = CinefinTokens.DisabledAlpha,
    )

/** 主题内可用的语义色（由 [CinefinTheme] 提供，随亮 / 暗切换）。 */
val LocalCinefinColors = staticCompositionLocalOf { CinefinColorsDark }

/** 当前域媒体色（由 [CinefinTheme] 提供；组件默认取当前域，禁止手工跨域取色）。 */
fun mediaColorsFor(domain: ContentDomain): MediaColors =
    when (domain) {
        ContentDomain.Movie -> MediaFilm
        ContentDomain.Music -> MediaMusic
        ContentDomain.Book -> MediaBook
        ContentDomain.Neutral -> MediaFilm
    }

/** 首页 / 设置例外页允许并列展示另外两域颜色（§2.5 例外 2）。 */
internal fun otherMediaColorsFor(domain: ContentDomain): Pair<MediaColors, MediaColors> =
    when (domain) {
        ContentDomain.Movie -> MediaMusic to MediaBook
        ContentDomain.Music -> MediaBook to MediaFilm
        ContentDomain.Book -> MediaFilm to MediaMusic
        ContentDomain.Neutral -> MediaMusic to MediaBook
    }

/**
 * 依据 UI_DESIGN_SYSTEM §2.4 生成 M3 ColorScheme。
 *
 * 深色：primary / onPrimary / primaryContainer / onPrimaryContainer 取当前域媒体色（容器为文档精确值）。 浅色：媒体色 base
 * 不变，容器改用 base 16% 叠加浅色 surface 的派生值，容器文字用更深的 dim 保证对比度。
 */
fun cinefinColorScheme(domain: ContentDomain, dark: Boolean): ColorScheme {
    val colors = if (dark) CinefinColorsDark else CinefinColorsLight
    val media = mediaColorsFor(domain)
    val (secondary, tertiary) = otherMediaColorsFor(domain)

    val primaryContainer =
        if (dark) media.container else media.containerOver(colors.surfaceContainer)
    val onPrimaryContainer = if (dark) media.bright else media.dim
    val secondaryContainer =
        if (dark) secondary.container else secondary.containerOver(colors.surfaceContainer)
    val onSecondaryContainer = if (dark) secondary.bright else secondary.dim
    val tertiaryContainer =
        if (dark) tertiary.container else tertiary.containerOver(colors.surfaceContainer)
    val onTertiaryContainer = if (dark) tertiary.bright else tertiary.dim
    val surfaceVariant = colors.surfaceContainer
    val errorContainer =
        if (dark) colors.errorContainer
        else
            colors.error
                .copy(alpha = CinefinTokens.MediaContainerAlpha)
                .compositeOver(colors.surfaceContainer)

    val scheme =
        if (dark) {
            darkColorScheme(
                primary = media.base,
                onPrimary = media.onBase,
                primaryContainer = primaryContainer,
                onPrimaryContainer = onPrimaryContainer,
                secondary = secondary.base,
                onSecondary = secondary.onBase,
                secondaryContainer = secondaryContainer,
                onSecondaryContainer = onSecondaryContainer,
                tertiary = tertiary.base,
                onTertiary = tertiary.onBase,
                tertiaryContainer = tertiaryContainer,
                onTertiaryContainer = onTertiaryContainer,
                error = colors.error,
                onError = colors.onError,
                errorContainer = errorContainer,
                onErrorContainer = colors.onErrorContainer,
                background = colors.surface,
                onBackground = colors.onSurface,
                surface = colors.surface,
                onSurface = colors.onSurface,
                surfaceVariant = surfaceVariant,
                onSurfaceVariant = colors.onSurfaceVariant,
                outline = colors.outline,
                outlineVariant = colors.outlineVariant,
                scrim = colors.scrim,
                inverseSurface = colors.inverseSurface,
                inverseOnSurface = colors.inverseOnSurface,
                inversePrimary = media.dim,
                surfaceDim = colors.surfaceDim,
                surfaceBright = colors.surfaceBright,
                surfaceContainerLowest = colors.surfaceContainerLowest,
                surfaceContainerLow = colors.surfaceContainerLow,
                surfaceContainer = colors.surfaceContainer,
                surfaceContainerHigh = colors.surfaceContainerHigh,
                surfaceContainerHighest = colors.surfaceContainerHighest,
            )
        } else {
            lightColorScheme(
                primary = media.base,
                onPrimary = media.onBase,
                primaryContainer = primaryContainer,
                onPrimaryContainer = onPrimaryContainer,
                secondary = secondary.base,
                onSecondary = secondary.onBase,
                secondaryContainer = secondaryContainer,
                onSecondaryContainer = onSecondaryContainer,
                tertiary = tertiary.base,
                onTertiary = tertiary.onBase,
                tertiaryContainer = tertiaryContainer,
                onTertiaryContainer = onTertiaryContainer,
                error = colors.error,
                onError = colors.onError,
                errorContainer = errorContainer,
                onErrorContainer = colors.onErrorContainer,
                background = colors.surface,
                onBackground = colors.onSurface,
                surface = colors.surface,
                onSurface = colors.onSurface,
                surfaceVariant = surfaceVariant,
                onSurfaceVariant = colors.onSurfaceVariant,
                outline = colors.outline,
                outlineVariant = colors.outlineVariant,
                scrim = colors.scrim,
                inverseSurface = colors.inverseSurface,
                inverseOnSurface = colors.inverseOnSurface,
                inversePrimary = media.base,
                surfaceDim = colors.surfaceDim,
                surfaceBright = colors.surfaceBright,
                surfaceContainerLowest = colors.surfaceContainerLowest,
                surfaceContainerLow = colors.surfaceContainerLow,
                surfaceContainer = colors.surfaceContainer,
                surfaceContainerHigh = colors.surfaceContainerHigh,
                surfaceContainerHighest = colors.surfaceContainerHighest,
            )
        }
    return scheme
}
