package com.zhangwenkang.cinefin.book.presentation.reader

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import com.zhangwenkang.cinefin.book.presentation.reader.tts.ReaderTtsDefaultRate
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinTokens
import com.zhangwenkang.cinefin.core.presentation.theme.MediaBook
import com.zhangwenkang.cinefin.core.presentation.theme.MediaColors
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.settings.domain.ReaderThemeCustomColors
import com.zhangwenkang.cinefin.settings.domain.ReaderThemeCustomJson
import com.zhangwenkang.cinefin.settings.domain.models.Preference
import dagger.hilt.android.EntryPointAccessors
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Color as ReadiumColor
import org.readium.r2.navigator.preferences.ColumnCount
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.TextAlign
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.ExperimentalReadiumApi
import timber.log.Timber

/**
 * 阅读器排版设置模型（R1-W2，需求 EB-5 / EB-6 / EB-7）。
 *
 * 这是 UI 与持久化层使用的纯数据；提交给 Readium 前通过 [toEpubPreferences] 映射。 滑块范围取官方
 * `EpubPreferencesEditor.supportedRange` 的实用子区间：字号 0.1–5.0、行距 1.0–2.0、 边距 0.0–4.0。
 */
val ReaderFontSizeRange = 0.7f..2.5f
val ReaderLineHeightRange = 1.0f..2.0f
val ReaderPageMarginsRange = 0.0f..2.0f

/** 段间距滑块区间（W6 T5，定稿四 §2：0–2em）。 */
val ReaderParagraphSpacingRange = 0.0f..2.0f

/** 首行缩进开启时的缩进量（em）。 */
const val ReaderParagraphIndentEm = 2.0

/** 阅读正文墨色（浅色家族）：阅读域自持，不随 App 主题色板变化。 */
val ReaderInkLight = Color(0xFF1B212B)

/** 阅读正文墨色（深色 / OLED）：同上，阅读域自持的暖白。 */
val ReaderInkDark = Color(0xFFF4F1EA)

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
        if (isDark(systemDark)) ReaderInkDark else ReaderInkLight

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

/**
 * 正文字重三档（W6 T5 / READER-16，定稿四 §2）：400 / 500 / 700。
 *
 * Readium `EpubPreferences.fontWeight` 是 400 的倍数（0–2.5，400 → 1.0），故提交前换算为 [readiumScale]。
 */
enum class ReaderFontWeight(val storageValue: Int, val label: String) {
    Normal(400, "常规"),
    Medium(500, "中粗"),
    Bold(700, "加粗");

    /** 提交给 Readium 的倍数：400 → 1.0、500 → 1.25、700 → 1.75。 */
    val readiumScale: Double
        get() = storageValue / 400.0

    companion object {
        fun fromStorage(value: Int?): ReaderFontWeight =
            entries.firstOrNull { it.storageValue == value } ?: Normal
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
    /** 漫画右起翻页（RTL，EB-4）：只作用于 PDF / CBZ 的分页 / 双栏，滚动模式不受影响。 */
    val rtl: Boolean = false,
    /** W6 T1：「反转左右」——交换单击左 / 右 1/3 的翻页方向（全格式统一）。 */
    val invertTapZones: Boolean = false,
    /** W6 T1：「点按翻页」——关闭后单击任意处 = 控制层开关。 */
    val tapPagingEnabled: Boolean = true,
    /** W6 T2：「目录显示已读」——目录抽屉里给已读完的章节打 accent ✓。 */
    val tocShowRead: Boolean = true,
    /** W6 T5：正文字重（400 / 500 / 700）。 */
    val fontWeight: ReaderFontWeight = ReaderFontWeight.Normal,
    /** W6 T5：段间距（em，0–2，映射 Readium `paragraphSpacing`）。 */
    val paragraphSpacing: Float = 0f,
    /** W6 T5：首行缩进（开 = [ReaderParagraphIndentEm] em）。 */
    val paragraphIndent: Boolean = false,
    /** W6 T5：连字符（默认关）。 */
    val hyphens: Boolean = false,
    /** W6 T5：各套具体主题的自定义色（跟随主题按解析后的具体主题取用，不单独存）。 */
    val themeCustom: Map<ReaderTheme, ReaderThemeCustomColors> = emptyMap(),
) {
    /** 把外部（SharedPreferences）可能越界 / 非有限的值收进可用区间。 */
    fun sanitized(): ReaderSettings =
        copy(
            fontSize = fontSize.coerceFiniteIn(ReaderFontSizeRange, 1.0f),
            lineHeight = lineHeight.coerceFiniteIn(ReaderLineHeightRange, 1.2f),
            pageMargins = pageMargins.coerceFiniteIn(ReaderPageMarginsRange, 1.0f),
            paragraphSpacing = paragraphSpacing.coerceFiniteIn(ReaderParagraphSpacingRange, 0f),
        )

    /** 当前设置 + 控制层可见性 → 三分区单击策略（纯函数，视图层只做分发）。 */
    internal fun tapPolicy(controlsVisible: Boolean): ReaderTapPolicy =
        ReaderTapPolicy(
            controlsVisible = controlsVisible,
            invertZones = invertTapZones,
            tapPagingEnabled = tapPagingEnabled,
        )

    fun isDark(systemDark: Boolean): Boolean = theme.isDark(systemDark)

    /** W6 T5：当前主题（跟随已解析）的自定义色；未自定义时为 null。 */
    fun customColors(systemDark: Boolean): ReaderThemeCustomColors? =
        themeCustom[theme.resolve(systemDark)]?.takeUnless { it.isDefault }

    fun surfaceColor(systemDark: Boolean): Color =
        customColors(systemDark)?.background?.let { Color(it) } ?: theme.surfaceColor(systemDark)

    fun contentColor(systemDark: Boolean): Color =
        customColors(systemDark)?.content?.let { Color(it) } ?: theme.contentColor(systemDark)

    /** 深色家族仍是 §8.14 的阅读面板底色；浅色家族（含自定义背景）与内容同底。 */
    fun chromeColor(systemDark: Boolean): Color =
        if (theme.isDark(systemDark)) CinefinTokens.ReaderPanelDark else surfaceColor(systemDark)

    fun accentColor(systemDark: Boolean): Color = theme.accentColor(systemDark)

    /** 强调色填充上的前景色（纸色 / 护眼用深墨，深色 / OLED 用阅读域 OnBase）。 */
    fun onAccentColor(systemDark: Boolean): Color =
        if (theme.isDark(systemDark)) MediaBook.onBase else ReaderInkLight

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
            onBase = ReaderInkLight,
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
            backgroundColor = value.surfaceColor(systemDark),
            textColor = value.contentColor(systemDark),
            font = value.font,
            fontSize = value.fontSize.toDouble(),
            lineHeight = value.lineHeight.toDouble(),
            pageMargins = value.pageMargins.toDouble(),
            textAlign = value.textAlign,
            fontWeight = value.fontWeight.readiumScale,
            paragraphSpacing = value.paragraphSpacing.toDouble(),
            paragraphIndent = if (value.paragraphIndent) ReaderParagraphIndentEm else 0.0,
            hyphens = value.hyphens,
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
            fontWeight = spec.fontWeight,
            hyphens = spec.hyphens,
            lineHeight = spec.lineHeight,
            pageMargins = spec.pageMargins,
            paragraphIndent = spec.paragraphIndent,
            paragraphSpacing = spec.paragraphSpacing,
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
    val fontWeight: Double,
    val paragraphSpacing: Double,
    val paragraphIndent: Double,
    val hyphens: Boolean,
)

private fun Float.coerceFiniteIn(range: ClosedFloatingPointRange<Float>, fallback: Float): Float =
    if (isFinite()) coerceIn(range) else fallback

/**
 * 阅读器三分区手势偏好（W6 T1）：沿用 `AppPreferences` 的具名 Preference + `pref_reader_*` 命名空间。
 *
 * 键声明留在 `modes/book`（本卡文件边界不含 `settings` 模块）；后续如需并入 `AppPreferences` 统一管理，迁移只需挪声明。
 */
internal object ReaderGesturePreferences {
    /** 「反转左右」：交换单击左 / 右 1/3 的翻页方向。 */
    val invertTapZones = Preference("pref_reader_invert_tap_zones", false)

    /** 「点按翻页」：关闭后单击任意处 = 控制层开关。 */
    val tapPagingEnabled = Preference("pref_reader_tap_paging", true)

    /** W6 T2：「目录显示已读」：目录抽屉里给已读完的章节打 ✓（默认开）。 */
    val tocShowRead = Preference("pref_reader_toc_show_read", true)
}

/**
 * 翻页动画档位（W6 T3 / READER-08，定稿三 §2）：滑动（默认）/ 覆盖 / 仿真。
 *
 * 覆盖 / 仿真只由自研页引擎（PDF / CBZ）实现；EPUB 走 Readium，只有「滑动 / 无动画」两档 （状态块预案 1）。`None` 是 EPUB 专用档，落在 PDF /
 * CBZ 上时按滑动处理。
 */
enum class ReaderPageAnimation(val storageValue: String, val label: String) {
    Slide("slide", "滑动"),
    Cover("cover", "覆盖"),
    Curl("curl", "仿真"),
    None("none", "无动画");

    companion object {
        fun fromStorage(value: String?): ReaderPageAnimation =
            entries.firstOrNull { it.storageValue == value } ?: Slide
    }
}

/** 自研页引擎（PDF / CBZ）可选档；默认第一项 = 滑动。 */
val ReaderSimplePageAnimations: List<ReaderPageAnimation> =
    listOf(ReaderPageAnimation.Slide, ReaderPageAnimation.Cover, ReaderPageAnimation.Curl)

/** EPUB 可选档：覆盖 / 仿真仅 PDF / CBZ。 */
val ReaderEpubPageAnimations: List<ReaderPageAnimation> =
    listOf(ReaderPageAnimation.Slide, ReaderPageAnimation.None)

/**
 * 翻页动画偏好（W6 T3）：沿用 `pref_reader_*` 命名空间，键声明自持在 `modes/book`。
 *
 * 读写走 [ReaderPageAnimationSetting]（本卡文件边界不含 `ReaderViewModel`，W6-T2 并行中）。
 */
internal object ReaderPageAnimationPreferences {
    /** 存 [ReaderPageAnimation.storageValue]；默认 `slide`。 */
    val pageAnimation = Preference("pref_reader_page_animation", "slide")
}

/**
 * 方向锁定三档（W6 T4 / READER-06，定稿三 §3）。
 *
 * `跟随系统` 交给系统旋转 / 用户旋转锁；锁定档在 `ReaderActivity` 里转成 `requestedOrientation`。
 */
enum class ReaderOrientation(val storageValue: String, val label: String) {
    FollowSystem("follow", "跟随系统"),
    Portrait("portrait", "锁定竖屏"),
    Landscape("landscape", "锁定横屏");

    companion object {
        fun fromStorage(value: String?): ReaderOrientation =
            entries.firstOrNull { it.storageValue == value } ?: FollowSystem
    }
}

/** 阅读输入偏好（W6 T4）：音量键翻页开关 + 方向锁定，沿用 `pref_reader_*` 命名空间。 */
internal object ReaderInputPreferences {
    /** 「音量键翻页」：默认关，只在阅读页 Activity 内生效。 */
    val volumeKeysPaging = Preference("pref_reader_volume_keys_paging", false)

    /** 方向锁定：存 [ReaderOrientation.storageValue]，默认 `follow`。 */
    val orientation =
        Preference("pref_reader_orientation", ReaderOrientation.FollowSystem.storageValue)
}

/** 可各自存自定义色的四套具体主题（「跟随」按解析后的具体主题取用，不单独存）。 */
val ReaderCustomizableThemes: List<ReaderTheme> =
    listOf(ReaderTheme.Paper, ReaderTheme.EyeCare, ReaderTheme.Dark, ReaderTheme.Oled)

/**
 * 主题自定义背景色板（W6 T5，定稿四 §3）：6 档全部由该主题的底色 / 墨色 / accent 派生。
 *
 * 第一档 = 主题默认底色；其余 = 在底色上叠加 accent / ink 两轴（固定透明度），不引入任何新色值。
 */
internal fun readerThemeBackgroundSwatches(theme: ReaderTheme, systemDark: Boolean): List<Color> {
    val resolved = theme.resolve(systemDark)
    val surface = resolved.surfaceColor(systemDark)
    val ink = resolved.contentColor(systemDark)
    val accent = resolved.accentColor(systemDark)
    return listOf(
        surface,
        accent.copy(alpha = 0.08f).compositeOver(surface),
        accent.copy(alpha = 0.16f).compositeOver(surface),
        ink.copy(alpha = 0.06f).compositeOver(surface),
        ink.copy(alpha = 0.12f).compositeOver(surface),
        ink.copy(alpha = 0.08f).compositeOver(accent.copy(alpha = 0.08f).compositeOver(surface)),
    )
}

/** 主题自定义正文色板（W6 T5）：墨色 / 两档淡化 / accent / accent 淡化 / 墨色微偏 accent，与背景色板同源。 */
internal fun readerThemeContentSwatches(theme: ReaderTheme, systemDark: Boolean): List<Color> {
    val resolved = theme.resolve(systemDark)
    val surface = resolved.surfaceColor(systemDark)
    val ink = resolved.contentColor(systemDark)
    val accent = resolved.accentColor(systemDark)
    return listOf(
        ink,
        ink.copy(alpha = 0.86f).compositeOver(surface),
        ink.copy(alpha = 0.72f).compositeOver(surface),
        accent,
        accent.copy(alpha = 0.86f).compositeOver(surface),
        ink.copy(alpha = 0.9f).compositeOver(accent.copy(alpha = 0.12f).compositeOver(surface)),
    )
}

/**
 * 排版扩展 + 主题自定义色的单一真相源（W6 T5）。
 *
 * 与 T3 / T4 的翻页动画 / 输入偏好同一模式：键声明在 `AppPreferences`（`pref_reader_*`），读写直接落盘；
 * 面板写、阅读视图读，`ReaderViewModel` 不参与（T6 并行中，共享文件只做追加）。
 */
internal object ReaderTypographySetting {
    private var preferences: AppPreferences? = null

    /** 正文字重；默认 400（常规）。 */
    var fontWeight by mutableStateOf(ReaderFontWeight.Normal)
        private set

    /** 段间距（em）；默认 0。 */
    var paragraphSpacing by mutableStateOf(0f)
        private set

    /** 首行缩进；默认关。 */
    var paragraphIndent by mutableStateOf(false)
        private set

    /** 连字符；默认关。 */
    var hyphens by mutableStateOf(false)
        private set

    /** 四套具体主题的自定义色（跟随主题按解析结果取用）。 */
    var themeCustom by mutableStateOf<Map<ReaderTheme, ReaderThemeCustomColors>>(emptyMap())
        private set

    /** Compose 侧绑定（面板 / 阅读视图首次组合时调用）；重复调用无副作用。 */
    fun bind(context: Context) {
        if (preferences != null) return
        val resolved =
            runCatching {
                EntryPointAccessors.fromApplication(
                        context.applicationContext,
                        ReaderPreferencesEntryPoint::class.java,
                    )
                    .appPreferences()
            }
                .onFailure { Timber.w(it, "阅读排版偏好不可用，沿用默认值") }
                .getOrNull() ?: return
        bind(resolved)
    }

    /** Activity 侧绑定（已由 Hilt 注入的实例）。 */
    fun bind(appPreferences: AppPreferences) {
        if (preferences === appPreferences) return
        preferences = appPreferences
        fontWeight =
            ReaderFontWeight.fromStorage(appPreferences.getValue(appPreferences.readerFontWeight))
        paragraphSpacing =
            appPreferences
                .getValue(appPreferences.readerParagraphSpacing)
                .coerceFiniteIn(ReaderParagraphSpacingRange, 0f)
        paragraphIndent = appPreferences.getValue(appPreferences.readerParagraphIndent)
        hyphens = appPreferences.getValue(appPreferences.readerHyphens)
        themeCustom = ReaderCustomizableThemes.associateWith { theme ->
            ReaderThemeCustomJson.decode(
                appPreferences.getValue(customPreference(theme, appPreferences))
            )
        }
    }

    fun updateFontWeight(next: ReaderFontWeight) {
        fontWeight = next
        write { it.setValue(it.readerFontWeight, next.storageValue) }
    }

    fun updateParagraphSpacing(next: Float) {
        paragraphSpacing = next.coerceFiniteIn(ReaderParagraphSpacingRange, 0f)
        write { it.setValue(it.readerParagraphSpacing, paragraphSpacing) }
    }

    fun updateParagraphIndent(next: Boolean) {
        paragraphIndent = next
        write { it.setValue(it.readerParagraphIndent, next) }
    }

    fun updateHyphens(next: Boolean) {
        hyphens = next
        write { it.setValue(it.readerHyphens, next) }
    }

    /** 写一套具体主题的自定义色；「跟随」按解析后的具体主题编辑，不接受 System。 */
    fun updateCustomColors(theme: ReaderTheme, colors: ReaderThemeCustomColors) {
        if (theme == ReaderTheme.System) return
        themeCustom = themeCustom + (theme to colors)
        write { it.setValue(customPreference(theme, it), ReaderThemeCustomJson.encode(colors)) }
    }

    /** 还原一套主题的自定义色（背景 / 正文两项都回到主题默认）。 */
    fun resetCustomColors(theme: ReaderTheme) = updateCustomColors(theme, ReaderThemeCustomColors())

    private fun write(block: (AppPreferences) -> Unit) {
        preferences?.let(block)
    }

    private fun customPreference(theme: ReaderTheme, prefs: AppPreferences): Preference<String> =
        when (theme) {
            ReaderTheme.Paper -> prefs.readerThemeCustomPaper
            ReaderTheme.EyeCare -> prefs.readerThemeCustomEyeCare
            ReaderTheme.Dark -> prefs.readerThemeCustomDark
            ReaderTheme.Oled -> prefs.readerThemeCustomOled
            ReaderTheme.System -> error("跟随主题不单独存自定义色")
        }
}

/** 排版扩展快照（字段即 [ReaderSettings] 里的同名字段）。 */
internal data class ReaderTypographyState(
    val fontWeight: ReaderFontWeight,
    val paragraphSpacing: Float,
    val paragraphIndent: Boolean,
    val hyphens: Boolean,
    val themeCustom: Map<ReaderTheme, ReaderThemeCustomColors>,
)

/** 首次组合时从偏好恢复；阅读页把它合并进 [ReaderSettings] 后再提交导航器。 */
@Composable
internal fun rememberReaderTypographyState(): ReaderTypographyState {
    val context = LocalContext.current
    LaunchedEffect(context) { ReaderTypographySetting.bind(context) }
    return ReaderTypographyState(
        fontWeight = ReaderTypographySetting.fontWeight,
        paragraphSpacing = ReaderTypographySetting.paragraphSpacing,
        paragraphIndent = ReaderTypographySetting.paragraphIndent,
        hyphens = ReaderTypographySetting.hyphens,
        themeCustom = ReaderTypographySetting.themeCustom,
    )
}

/** 把偏好快照合并进设置模型（阅读页组合期调用；T6 并行期间不改 `ReaderViewModel`）。 */
internal fun ReaderSettings.withTypography(state: ReaderTypographyState): ReaderSettings =
    copy(
            fontWeight = state.fontWeight,
            paragraphSpacing = state.paragraphSpacing,
            paragraphIndent = state.paragraphIndent,
            hyphens = state.hyphens,
            themeCustom = state.themeCustom,
        )
        .sanitized()

/**
 * 自动阅读参数（W6 T6 / READER-12）：沿用 `pref_reader_auto_*` 命名空间，键声明自持在 `modes/book`。
 *
 * 只持久化「翻页间隔 / 滚动速度」两个参数；开关本身默认关、离开阅读页即停（会话态，不落盘）。
 */
internal object ReaderAutoPreferences {
    /** 分页档自动翻页间隔（秒，5–120；默认 30）。 */
    val pageIntervalSeconds =
        Preference("pref_reader_auto_page_interval_seconds", AutoAdvanceDefaultIntervalSeconds)

    /** 滚动档自动滚动速度（px/s，10–300；默认 60）。 */
    val scrollSpeedPxPerSecond =
        Preference("pref_reader_auto_scroll_speed_px", AutoAdvanceDefaultSpeedPxPerSecond)
}

/**
 * 听书偏好（W6 T9 / READER-32/34）：沿用 `pref_reader_tts_*` 命名空间，键声明自持在 `modes/book`。
 *
 * 「点按正文跳转朗读位置」默认开、「点按正文时暂停朗读」默认关（保持播放）——按定稿六 §3 口径。
 */
internal object ReaderTtsPreferences {
    /** 语速（0.5–2.0×，默认 1.0）。 */
    val rate = Preference("pref_reader_tts_rate", ReaderTtsDefaultRate)

    /** 系统音色名（`voice.name`）；空串 = 让引擎按语言挑默认音色。 */
    val voice = Preference("pref_reader_tts_voice", "")

    /** 「点按正文跳转朗读位置」：默认开。 */
    val tapFollowsReading = Preference("pref_reader_tts_tap_follow", true)

    /** 「点按正文时暂停朗读」：默认关 = 保持播放。 */
    val pauseOnTap = Preference("pref_reader_tts_tap_pause", false)
}
