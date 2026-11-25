package com.zhangwenkang.cinefin.core.presentation.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import timber.log.Timber

/**
 * Cinefin 语义颜色 token（UI_DESIGN_SYSTEM v1.0 §2.1–§2.4 + K1 Aurora Cinema 品牌槽位）。
 *
 * 组件层只允许引用本数据类或 [MediaColors]，禁止直接引用 [CinefinTokens] 的原始色值。 M3 槽位映射由 [cinefinColorScheme] 统一完成，保证
 * `MaterialTheme.colorScheme` 与设计系统同源。
 *
 * [aurora1] / [aurora2] / [aurora3] 是品牌极光三色（随主题走），[onAurora] 是在 [aurora1] 上阅读的内容色，[auroraInk] 是浅色档
 * 达 AA 的选中文字墨色（深色档即 [aurora1]）； [warning] / [success] / [info] 是语义状态色（2.0.1 定稿 §2 批①，告警 / 成功 /
 * 信息态一律走这三组槽位，禁手写色值）； [glass] / [glassBorder] 是玻璃面板的底与描边（底含 alpha）。
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
    val warning: Color,
    val onWarning: Color,
    val success: Color,
    val onSuccess: Color,
    val info: Color,
    val onInfo: Color,
    val navSurface: Color,
    val stateHover: Color,
    val stateFocus: Color,
    val statePressed: Color,
    val progressTrack: Color,
    val progressTrackOnImage: Color,
    val topHighlight: Color,
    val disabledAlpha: Float,
    val aurora1: Color,
    val aurora2: Color,
    val aurora3: Color,
    val onAurora: Color,
    /**
     * 选中 / 强调**文字**墨色（2.0.1 定稿 §2 批③ auroraInk）：浅色主题 = `#0A6B5A`，深色 / AMOLED / lumen = `aurora1`。
     *
     * 与 [aurora1] 的区别只在浅色主题：`aurora1` 在近白底上作正文只有 3.2:1，auroraInk 加深后约 5.2:1（达 AA）。填充容器仍用 [aurora1]
     * 低透明度，不随本 token 变化。
     */
    val auroraInk: Color,
    val glass: Color,
    val glassBorder: Color,
)

/** 一套主题的明 / 暗两个变体（浅色变体可以与其他主题共用同一份）。 */
@Immutable data class CinefinThemePalette(val dark: CinefinColors, val light: CinefinColors)

/**
 * 主题注册表：`themeId → CinefinThemePalette`。
 *
 * 组件层只消费 [LocalCinefinColors]；主题切换由 [CinefinTheme] 的 `themeId` 参数决定。 `dynamic`（Material
 * You）是运行时特例：色板仍用 [AURORA] 的变体，只是 M3 `ColorScheme` 改由系统动态取色生成，因此它也在这里登记为 aurora 的别名。
 */
object CinefinThemes {
    const val AURORA = "aurora"
    const val LIGHT = "light"
    const val AMOLED = "amoled"
    const val LUMEN = "lumen"
    const val DYNAMIC = "dynamic"

    /** 存量偏好值：跟随系统明暗（不是色板 id，故不登记在 [map] 里）。 */
    const val SYSTEM = "system"

    /** 默认主题。 */
    const val DEFAULT = AURORA

    private val auroraDark: CinefinColors =
        CinefinColors(
            surface = CinefinTokens.AuroraDarkSurface,
            surfaceDim = CinefinTokens.AuroraDarkSurfaceDim,
            surfaceBright = CinefinTokens.AuroraDarkSurfaceBright,
            surfaceContainerLowest = CinefinTokens.AuroraDarkSurfaceContainerLowest,
            surfaceContainerLow = CinefinTokens.AuroraDarkSurfaceContainerLow,
            surfaceContainer = CinefinTokens.AuroraDarkSurfaceContainer,
            surfaceContainerHigh = CinefinTokens.AuroraDarkSurfaceContainerHigh,
            surfaceContainerHighest = CinefinTokens.AuroraDarkSurfaceContainerHighest,
            onSurface = CinefinTokens.AuroraDarkOnSurface,
            onSurfaceVariant = CinefinTokens.AuroraDarkOnSurfaceVariant,
            onSurfaceFaint = CinefinTokens.AuroraDarkOnSurfaceFaint,
            outline = Color.White.copy(alpha = CinefinTokens.AuroraDarkOutlineAlpha),
            outlineVariant = Color.White.copy(alpha = CinefinTokens.AuroraDarkOutlineVariantAlpha),
            inverseSurface = CinefinTokens.AuroraDarkInverseSurface,
            inverseOnSurface = CinefinTokens.AuroraDarkInverseOnSurface,
            scrim = CinefinTokens.Scrim.copy(alpha = CinefinTokens.ScrimAlpha),
            error = CinefinTokens.Error,
            onError = CinefinTokens.OnError,
            errorContainer = CinefinTokens.ErrorContainerDark,
            onErrorContainer = CinefinTokens.AuroraDarkOnErrorContainer,
            warning = CinefinTokens.Warning,
            onWarning = CinefinTokens.OnWarning,
            success = CinefinTokens.Success,
            onSuccess = CinefinTokens.OnSuccess,
            info = CinefinTokens.Info,
            onInfo = CinefinTokens.OnInfo,
            navSurface = CinefinTokens.AuroraDarkNavSurface,
            stateHover = Color.White.copy(alpha = CinefinTokens.StateHoverAlpha),
            stateFocus = Color.White.copy(alpha = CinefinTokens.StateFocusAlpha),
            statePressed = Color.White.copy(alpha = CinefinTokens.StatePressedAlpha),
            progressTrack = Color.White.copy(alpha = CinefinTokens.ProgressTrackAlpha),
            progressTrackOnImage =
                Color.White.copy(alpha = CinefinTokens.ProgressTrackOnImageAlpha),
            topHighlight = Color.White.copy(alpha = CinefinTokens.TopHighlightAlpha),
            disabledAlpha = CinefinTokens.DisabledAlpha,
            aurora1 = CinefinTokens.Aurora1Dark,
            aurora2 = CinefinTokens.Aurora2Dark,
            aurora3 = CinefinTokens.Aurora3Dark,
            onAurora = CinefinTokens.OnAuroraDark,
            auroraInk = CinefinTokens.Aurora1Dark,
            glass = CinefinTokens.AuroraDarkNavSurface.copy(alpha = CinefinTokens.GlassAlpha),
            glassBorder = Color.White.copy(alpha = CinefinTokens.AuroraDarkGlassBorderAlpha),
        )

    /** AMOLED：与 aurora.dark 只差纯黑表面与更深的容器层级。 */
    private val amoledDark: CinefinColors =
        auroraDark.copy(
            surface = CinefinTokens.AmoledDarkSurface,
            surfaceDim = CinefinTokens.AmoledDarkSurfaceDim,
            surfaceBright = CinefinTokens.AmoledDarkSurfaceBright,
            surfaceContainerLowest = CinefinTokens.AmoledDarkSurfaceContainerLowest,
            surfaceContainerLow = CinefinTokens.AmoledDarkSurfaceContainerLow,
            surfaceContainer = CinefinTokens.AmoledDarkSurfaceContainer,
            surfaceContainerHigh = CinefinTokens.AmoledDarkSurfaceContainerHigh,
            surfaceContainerHighest = CinefinTokens.AmoledDarkSurfaceContainerHighest,
            navSurface = CinefinTokens.AmoledDarkNavSurface,
        )

    private val light: CinefinColors =
        CinefinColors(
            surface = CinefinTokens.AuroraLightSurface,
            surfaceDim = CinefinTokens.AuroraLightSurfaceDim,
            surfaceBright = CinefinTokens.AuroraLightSurfaceBright,
            surfaceContainerLowest = CinefinTokens.AuroraLightSurfaceContainerLowest,
            surfaceContainerLow = CinefinTokens.AuroraLightSurfaceContainerLow,
            surfaceContainer = CinefinTokens.AuroraLightSurfaceContainer,
            surfaceContainerHigh = CinefinTokens.AuroraLightSurfaceContainerHigh,
            surfaceContainerHighest = CinefinTokens.AuroraLightSurfaceContainerHighest,
            onSurface = CinefinTokens.AuroraLightOnSurface,
            onSurfaceVariant = CinefinTokens.AuroraLightOnSurfaceVariant,
            onSurfaceFaint = CinefinTokens.AuroraLightOnSurfaceFaint,
            outline =
                CinefinTokens.AuroraLightOnSurface.copy(
                    alpha = CinefinTokens.AuroraLightOutlineAlpha
                ),
            outlineVariant =
                CinefinTokens.AuroraLightOnSurface.copy(
                    alpha = CinefinTokens.AuroraLightOutlineVariantAlpha
                ),
            inverseSurface = CinefinTokens.AuroraLightInverseSurface,
            inverseOnSurface = CinefinTokens.AuroraLightInverseOnSurface,
            scrim = CinefinTokens.Scrim.copy(alpha = CinefinTokens.ScrimAlpha),
            error = CinefinTokens.Error,
            onError = CinefinTokens.OnError,
            errorContainer = CinefinTokens.AuroraLightErrorContainer,
            onErrorContainer = CinefinTokens.AuroraLightOnErrorContainer,
            warning = CinefinTokens.Warning,
            onWarning = CinefinTokens.OnWarning,
            success = CinefinTokens.Success,
            onSuccess = CinefinTokens.OnSuccess,
            info = CinefinTokens.Info,
            onInfo = CinefinTokens.OnInfo,
            navSurface = CinefinTokens.AuroraLightNavSurface,
            stateHover = Color.Black.copy(alpha = CinefinTokens.StateHoverAlpha),
            stateFocus = Color.Black.copy(alpha = CinefinTokens.StateFocusAlpha),
            statePressed = Color.Black.copy(alpha = CinefinTokens.StatePressedAlpha),
            progressTrack = Color.Black.copy(alpha = CinefinTokens.ProgressTrackAlpha),
            progressTrackOnImage =
                Color.White.copy(alpha = CinefinTokens.ProgressTrackOnImageAlpha),
            topHighlight = Color.White.copy(alpha = CinefinTokens.TopHighlightAlpha),
            disabledAlpha = CinefinTokens.DisabledAlpha,
            aurora1 = CinefinTokens.Aurora1Light,
            aurora2 = CinefinTokens.Aurora2Light,
            aurora3 = CinefinTokens.Aurora3Light,
            onAurora = CinefinTokens.OnAuroraLight,
            auroraInk = CinefinTokens.AuroraInkLight,
            glass = Color.White.copy(alpha = CinefinTokens.AuroraLightGlassAlpha),
            glassBorder =
                CinefinTokens.AuroraLightOnSurface.copy(
                    alpha = CinefinTokens.AuroraLightGlassBorderAlpha
                ),
        )

    /** Lumen 遗产条目（非用户可选）：曜石黑 A 稿色板，保留给存量主题 id。 */
    private val lumenDark: CinefinColors =
        CinefinColors(
            surface = CinefinTokens.LumenBackground,
            surfaceDim = CinefinTokens.LumenBackground,
            surfaceBright = CinefinTokens.LumenPanelElevated,
            surfaceContainerLowest = CinefinTokens.LumenBackground,
            surfaceContainerLow = CinefinTokens.LumenPanel,
            surfaceContainer = CinefinTokens.LumenPanel,
            surfaceContainerHigh = CinefinTokens.LumenPanelElevated,
            surfaceContainerHighest = CinefinTokens.LumenPanelElevated,
            onSurface = CinefinTokens.LumenText,
            onSurfaceVariant = CinefinTokens.LumenTextSecondary,
            onSurfaceFaint = CinefinTokens.LumenTextFaint,
            outline = Color.White.copy(alpha = CinefinTokens.LumenLineAlpha),
            outlineVariant = Color.White.copy(alpha = CinefinTokens.LumenLineSoftAlpha),
            inverseSurface = CinefinTokens.LumenText,
            inverseOnSurface = CinefinTokens.LumenOnPrimary,
            scrim = CinefinTokens.Scrim.copy(alpha = CinefinTokens.ScrimAlpha),
            error = CinefinTokens.Error,
            onError = CinefinTokens.OnError,
            errorContainer = CinefinTokens.ErrorContainerDark,
            onErrorContainer = CinefinTokens.LumenOnErrorContainer,
            warning = CinefinTokens.Warning,
            onWarning = CinefinTokens.OnWarning,
            success = CinefinTokens.Success,
            onSuccess = CinefinTokens.OnSuccess,
            info = CinefinTokens.Info,
            onInfo = CinefinTokens.OnInfo,
            navSurface = CinefinTokens.LumenBackground,
            stateHover = Color.White.copy(alpha = CinefinTokens.StateHoverAlpha),
            stateFocus = Color.White.copy(alpha = CinefinTokens.StateFocusAlpha),
            statePressed = Color.White.copy(alpha = CinefinTokens.StatePressedAlpha),
            progressTrack = Color.White.copy(alpha = CinefinTokens.LumenProgressTrackAlpha),
            progressTrackOnImage = Color.White.copy(alpha = CinefinTokens.LumenProgressTrackAlpha),
            topHighlight = Color.White.copy(alpha = CinefinTokens.TopHighlightAlpha),
            disabledAlpha = CinefinTokens.DisabledAlpha,
            aurora1 = CinefinTokens.LumenAccent,
            aurora2 = CinefinTokens.LumenAccentSecondary,
            aurora3 = CinefinTokens.LumenAccentSecondary,
            onAurora = CinefinTokens.LumenOnPrimary,
            auroraInk = CinefinTokens.LumenAccent,
            glass = CinefinTokens.LumenBackground.copy(alpha = CinefinTokens.GlassAlpha),
            glassBorder = Color.White.copy(alpha = CinefinTokens.LumenLineAlpha),
        )

    /** 主题 id → 明暗变体；`light` / `amoled` / `lumen` 的浅色变体统一指 [light]。 */
    val map: Map<String, CinefinThemePalette> =
        mapOf(
            AURORA to CinefinThemePalette(dark = auroraDark, light = light),
            LIGHT to CinefinThemePalette(dark = light, light = light),
            AMOLED to CinefinThemePalette(dark = amoledDark, light = light),
            LUMEN to CinefinThemePalette(dark = lumenDark, light = light),
            DYNAMIC to CinefinThemePalette(dark = auroraDark, light = light),
        )

    /** 取主题色板；未知 id 回落 [DEFAULT] 并告警。 */
    fun paletteFor(themeId: String): CinefinThemePalette =
        map[themeId]
            ?: run {
                Timber.w("Unknown theme id %s, falling back to %s", themeId, DEFAULT)
                map.getValue(DEFAULT)
            }

    /** 取主题的明 / 暗变体。 */
    fun colorsFor(themeId: String, dark: Boolean): CinefinColors =
        paletteFor(themeId).let { if (dark) it.dark else it.light }
}

/**
 * 把存量偏好值迁移成主题 id。
 *
 * 旧版值域是 `system/light/dark`：`dark` 与空值迁到 [CinefinThemes.AURORA]（旧的固定深色外观）， `system`
 * 保留给「跟随系统明暗」语义，其余新值原样透传，无法识别的值兜底 aurora。
 */
fun migrateThemeId(stored: String?): String =
    when (stored) {
        CinefinThemes.LIGHT -> CinefinThemes.LIGHT
        CinefinThemes.SYSTEM -> CinefinThemes.SYSTEM
        CinefinThemes.DYNAMIC -> CinefinThemes.DYNAMIC
        CinefinThemes.AURORA -> CinefinThemes.AURORA
        CinefinThemes.AMOLED -> CinefinThemes.AMOLED
        else -> CinefinThemes.AURORA
    }

/**
 * 主题选择器展示的主题 id 列表（W1-T5）：极光 → 浅色 → AMOLED →（Android S+）动态取色。
 *
 * [CinefinThemes.DYNAMIC] 依赖系统动态取色 API（Android 12 / API 31），低版本不展示该行。
 */
fun themeSelectorIds(sdkInt: Int): List<String> = buildList {
    add(CinefinThemes.AURORA)
    add(CinefinThemes.LIGHT)
    add(CinefinThemes.AMOLED)
    if (sdkInt >= 31) add(CinefinThemes.DYNAMIC)
}

/**
 * 主题选择器的选中态解析（W1-T5）：把存量偏好值映射到选择器里的一行。
 *
 * 「跟随系统明暗」（[CinefinThemes.SYSTEM]）没有独立卡片：动态取色开启时落 [CinefinThemes.DYNAMIC]，否则落
 * [CinefinThemes.AURORA]。其余值按 [migrateThemeId] 迁移（旧 `dark` / 空值 / 未知值 → 极光）。
 *
 * 选择器做出显式选择后 `pref_dynamic_colors` 即被清除（第 4 卡「跟随系统动态色」是动态取色的唯一入口），因此 `system` +
 * `dynamicColorsEnabled=true` 这一 legacy 组合只在用户尚未显式选择过之前存在。
 */
fun resolveThemeSelectorSelection(stored: String?, dynamicColorsEnabled: Boolean): String =
    when (val id = migrateThemeId(stored)) {
        CinefinThemes.SYSTEM ->
            if (dynamicColorsEnabled) CinefinThemes.DYNAMIC else CinefinThemes.AURORA
        else -> id
    }

/** 主题内可用的语义色（由 [CinefinTheme] 提供，随主题与亮 / 暗切换）。 */
val LocalCinefinColors = staticCompositionLocalOf {
    CinefinThemes.colorsFor(CinefinThemes.DEFAULT, dark = true)
}

/**
 * 依据 2.0.1 定稿 §2 批① 生成 M3 ColorScheme：M3 角色与域媒体色解耦。
 *
 * primary 系归极光——`primary` = aurora1、`onPrimary` = onAurora、`primaryContainer` = aurora1 16% 叠加
 * `surfaceContainer`、`inversePrimary` = aurora dim；secondary / tertiary 改中性系（次级文字档 +
 * 高表面容器），不再绑定另外两域媒体色。 域媒体色只经 [LocalMediaColors] 供域身份位使用，不再进入 M3 强调槽位。
 */
fun cinefinColorScheme(colors: CinefinColors, dark: Boolean): ColorScheme {
    val primaryContainer =
        colors.aurora1
            .copy(alpha = CinefinTokens.MediaContainerAlpha)
            .compositeOver(colors.surfaceContainer)
    val neutral = colors.onSurfaceVariant
    val neutralContainer = colors.surfaceContainerHigh
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
                primary = colors.aurora1,
                onPrimary = colors.onAurora,
                primaryContainer = primaryContainer,
                onPrimaryContainer = colors.onSurface,
                secondary = neutral,
                onSecondary = colors.surface,
                secondaryContainer = neutralContainer,
                onSecondaryContainer = colors.onSurface,
                tertiary = neutral,
                onTertiary = colors.surface,
                tertiaryContainer = neutralContainer,
                onTertiaryContainer = colors.onSurface,
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
                inversePrimary = CinefinTokens.MediaAuroraDim,
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
                primary = colors.aurora1,
                onPrimary = colors.onAurora,
                primaryContainer = primaryContainer,
                onPrimaryContainer = colors.onSurface,
                secondary = neutral,
                onSecondary = colors.surface,
                secondaryContainer = neutralContainer,
                onSecondaryContainer = colors.onSurface,
                tertiary = neutral,
                onTertiary = colors.surface,
                tertiaryContainer = neutralContainer,
                onTertiaryContainer = colors.onSurface,
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
                inversePrimary = CinefinTokens.MediaAuroraDim,
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
