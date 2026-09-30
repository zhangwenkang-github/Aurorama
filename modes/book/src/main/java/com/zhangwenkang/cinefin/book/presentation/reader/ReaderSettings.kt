package com.zhangwenkang.cinefin.book.presentation.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinTokens
import com.zhangwenkang.cinefin.core.presentation.theme.MediaBook
import com.zhangwenkang.cinefin.core.presentation.theme.MediaColors
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Color as ReadiumColor
import org.readium.r2.navigator.preferences.ColumnCount
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.TextAlign
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.ExperimentalReadiumApi

/**
 * 阅读器排版设置模型（R1-W2，需求 EB-5 / EB-6 / EB-7）。
 *
 * 这是 UI 与持久化层使用的纯数据；提交给 Readium 前通过 [toEpubPreferences] 映射。 滑块范围取官方
 * `EpubPreferencesEditor.supportedRange` 的实用子区间：字号 0.1–5.0、行距 1.0–2.0、 边距 0.0–4.0。
 */
val ReaderFontSizeRange = 0.7f..2.5f
val ReaderLineHeightRange = 1.0f..2.0f
val ReaderPageMarginsRange = 0.0f..2.0f

/** 阅读模式：滚动 / 横向分页（单栏）/ 横向分页（平板双栏）。 */
enum class ReaderMode(val storageValue: String, val label: String) {
    Scroll("scroll", "滚动"),
    Paged("paged", "分页"),
    TwoColumn("two_column", "双栏");

    /** 顶栏快捷按钮的循环顺序：滚动 → 分页 → 双栏 → 滚动。 */
    fun next(): ReaderMode = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromStorage(value: String?): ReaderMode =
            entries.firstOrNull { it.storageValue == value } ?: Scroll
    }
}

/** 阅读主题，独立于主 App 主题（EB-7）；跟随 = 系统深色取深色、浅色取纸色。 */
enum class ReaderTheme(val storageValue: String, val label: String) {
    Paper("paper", "纸色"),
    EyeCare("eyecare", "护眼"),
    Dark("dark", "深色"),
    Oled("oled", "OLED"),
    System("system", "跟随");

    /** 把「跟随」解析为实际主题。 */
    fun resolve(systemDark: Boolean): ReaderTheme =
        when (this) {
            System -> if (systemDark) Dark else Paper
            else -> this
        }

    /** 解析后是否为深色家族（深色 / OLED）。 */
    fun isDark(systemDark: Boolean): Boolean = resolve(systemDark).let { it == Dark || it == Oled }

    /** 阅读内容底色（纸色 / 护眼 / 深色 / OLED；跟随按系统解析）。 */
    fun surfaceColor(systemDark: Boolean): Color =
        when (resolve(systemDark)) {
            Paper -> CinefinTokens.PaperSurface
            EyeCare -> CinefinTokens.ReaderEyeCareSurface
            Dark -> CinefinTokens.SurfaceDark
            Oled -> CinefinTokens.ReaderOledSurface
            System -> error("跟随主题应已解析")
        }

    /** 阅读内容文字色。 */
    fun contentColor(systemDark: Boolean): Color =
        if (isDark(systemDark)) CinefinTokens.OnSurfaceDark else CinefinTokens.OnSurfaceLight

    /** 阅读器外壳（顶栏 / 面板）底色：深色家族用 §8.14 的 `#191F28`，浅色家族与内容同底。 */
    fun chromeColor(systemDark: Boolean): Color =
        if (isDark(systemDark)) CinefinTokens.ReaderPanelDark else surfaceColor(systemDark)

    /** 阅读器强调色：纸色 / 护眼用纸页棕，深色 / OLED 用阅读域天青（§8.14）。 */
    fun accentColor(systemDark: Boolean): Color =
        when (resolve(systemDark)) {
            Paper,
            EyeCare -> CinefinTokens.PaperAccent
            else -> MediaBook.base
        }

    companion object {
        fun fromStorage(value: String?): ReaderTheme =
            entries.firstOrNull { it.storageValue == value } ?: Dark
    }
}

/** 内置字体族；默认保留出版方 / 系统字体，不覆盖。 */
enum class ReaderFont(val storageValue: String, val label: String, val fontFamily: FontFamily?) {
    Publisher("publisher", "默认", null),
    Serif("serif", "衬线", FontFamily.SERIF),
    SansSerif("sans", "无衬线", FontFamily.SANS_SERIF),
    Monospace("monospace", "等宽", FontFamily.MONOSPACE);

    companion object {
        fun fromStorage(value: String?): ReaderFont =
            entries.firstOrNull { it.storageValue == value } ?: Publisher
    }
}

/** 正文对齐；三个实用档位映射 Readium 的 JUSTIFY / START / CENTER。 */
enum class ReaderTextAlign(
    val storageValue: String,
    val label: String,
    val readiumValue: TextAlign,
) {
    Justify("justify", "两端", TextAlign.JUSTIFY),
    Start("start", "左对齐", TextAlign.START),
    Center("center", "居中", TextAlign.CENTER);

    companion object {
        fun fromStorage(value: String?): ReaderTextAlign =
            entries.firstOrNull { it.storageValue == value } ?: Justify
    }
}

/** 阅读器排版 + 主题设置的完整快照。 */
data class ReaderSettings(
    val mode: ReaderMode = ReaderMode.Scroll,
    val fontSize: Float = 1.0f,
    val lineHeight: Float = 1.2f,
    val pageMargins: Float = 1.0f,
    val font: ReaderFont = ReaderFont.Publisher,
    val theme: ReaderTheme = ReaderTheme.Dark,
    val textAlign: ReaderTextAlign = ReaderTextAlign.Justify,
) {
    /** 把外部（SharedPreferences）可能越界 / 非有限的值收进可用区间。 */
    fun sanitized(): ReaderSettings =
        copy(
            fontSize = fontSize.coerceFiniteIn(ReaderFontSizeRange, 1.0f),
            lineHeight = lineHeight.coerceFiniteIn(ReaderLineHeightRange, 1.2f),
            pageMargins = pageMargins.coerceFiniteIn(ReaderPageMarginsRange, 1.0f),
        )

    fun isDark(systemDark: Boolean): Boolean = theme.isDark(systemDark)

    fun surfaceColor(systemDark: Boolean): Color = theme.surfaceColor(systemDark)

    fun contentColor(systemDark: Boolean): Color = theme.contentColor(systemDark)

    fun chromeColor(systemDark: Boolean): Color = theme.chromeColor(systemDark)

    fun accentColor(systemDark: Boolean): Color = theme.accentColor(systemDark)

    /** 强调色填充上的前景色（纸色 / 护眼用深墨，深色 / OLED 用阅读域 OnBase）。 */
    fun onAccentColor(systemDark: Boolean): Color =
        if (theme.isDark(systemDark)) MediaBook.onBase else CinefinTokens.OnSurfaceLight

    /**
     * 阅读器内的媒体色（§8.14 例外）：纸色 / 护眼主题把阅读域天青整体替换为纸页棕 `#A8843C`， 深色 / OLED 仍用阅读域天青。
     *
     * 返回同一份 [MediaColors] 结构，只是底 / 描边 / 文字 / 容器都换成纸页棕派生值， 这样顶栏按钮、分段控件、chip 与滑块在纸色主题内保持同一种强调色。
     */
    fun mediaColors(systemDark: Boolean): MediaColors {
        if (theme.isDark(systemDark)) return MediaBook
        val accent = CinefinTokens.PaperAccent
        val surface = surfaceColor(systemDark)
        return MediaColors(
            base = accent,
            bright = accent,
            dim = accent,
            onBase = CinefinTokens.OnSurfaceLight,
            container =
                accent.copy(alpha = CinefinTokens.MediaContainerAlpha).compositeOver(surface),
            containerPressed =
                accent
                    .copy(alpha = CinefinTokens.MediaContainerPressedAlpha)
                    .compositeOver(surface),
            outline = accent.copy(alpha = CinefinTokens.MediaOutlineAlpha).compositeOver(surface),
        )
    }

    /** 先映射为与 Readium 解耦的中间快照（纯 Kotlin，可在 JVM 单测中直接断言）； [toEpubPreferences] 只做最后一步薄适配。 */
    internal fun toPreferenceSpec(systemDark: Boolean): ReaderPreferenceSpec {
        val value = sanitized()
        return ReaderPreferenceSpec(
            scroll = value.mode == ReaderMode.Scroll,
            twoColumn = value.mode == ReaderMode.TwoColumn,
            darkTheme = value.theme.isDark(systemDark),
            backgroundColor = value.theme.surfaceColor(systemDark),
            textColor = value.theme.contentColor(systemDark),
            font = value.font,
            fontSize = value.fontSize.toDouble(),
            lineHeight = value.lineHeight.toDouble(),
            pageMargins = value.pageMargins.toDouble(),
            textAlign = value.textAlign,
        )
    }

    /** 映射为 Readium 偏好；每次设置变更提交一整套偏好，保证即时生效且无残留。 */
    @OptIn(ExperimentalReadiumApi::class)
    fun toEpubPreferences(systemDark: Boolean): EpubPreferences {
        val spec = toPreferenceSpec(systemDark)
        return EpubPreferences(
            backgroundColor = ReadiumColor(spec.backgroundColor.toArgb()),
            columnCount = if (spec.twoColumn) ColumnCount.TWO else ColumnCount.ONE,
            fontFamily = spec.font.fontFamily,
            fontSize = spec.fontSize,
            lineHeight = spec.lineHeight,
            pageMargins = spec.pageMargins,
            scroll = spec.scroll,
            textAlign = spec.textAlign.readiumValue,
            textColor = ReadiumColor(spec.textColor.toArgb()),
            theme = if (spec.darkTheme) Theme.DARK else Theme.LIGHT,
        )
    }
}

/** 与 Readium 类型解耦的偏好快照；单测断言本层，避免加载依赖 Android 色值的 Readium `Theme`。 */
internal data class ReaderPreferenceSpec(
    val scroll: Boolean,
    val twoColumn: Boolean,
    val darkTheme: Boolean,
    val backgroundColor: Color,
    val textColor: Color,
    val font: ReaderFont,
    val fontSize: Double,
    val lineHeight: Double,
    val pageMargins: Double,
    val textAlign: ReaderTextAlign,
)

private fun Float.coerceFiniteIn(range: ClosedFloatingPointRange<Float>, fallback: Float): Float =
    if (isFinite()) coerceIn(range) else fallback
