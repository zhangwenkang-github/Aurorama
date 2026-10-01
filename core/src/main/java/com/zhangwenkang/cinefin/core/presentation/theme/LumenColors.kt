package com.zhangwenkang.cinefin.core.presentation.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

/**
 * S1「A · Lumen 流光」色板（依据 `docs/design/s1-direction-a/README.md` §2 与渲染源
 * `docs/design/_src/direction-a.html` 的 CSS 变量；用户 2026-10-01 批准）。
 *
 * 定位（UI_PLAN 决策 D20）：Prism 仍是全 App 的骨架与三域媒体色体系；Lumen 是**影视区局部的皮肤**， 只用于首页与视频相关页面（电影 / 剧集 / 季 /
 * 集详情）。音乐与阅读皮肤保持不变，不得引用本文件。
 *
 * 与 §2.6 媒体色纪律的关系：Lumen 不是"第 4 个媒体色"，而是影视域在 Lumen 区域的**局部覆盖**——
 * 底色从石板蓝黑换成曜石黑、强调色从琥珀换成极光青、主行动从媒体色填充换成月白填充； 一旦离开 Lumen 区域（媒体库 / 搜索 / 设置 / 音乐 / 阅读），一切回到 Prism。
 *
 * 本文件与 [CinefinTokens] 是项目中仅有的两处允许出现 `Color(0x…)` 字面量的文件；组件层只允许引用 [LumenColors] /
 * [LocalLumenColors] 的语义字段。
 */
object LumenTokens {
    /** `--bg` 曜石黑：Lumen 区域页面底（OLED 友好）。 */
    val Background = Color(0xFF08090C)

    /** `--panel` 石墨：卡片 / 面板底。 */
    val Panel = Color(0xFF111319)

    /** `--panel-2` 雾灰：次级面板、悬浮层、卡片高亮底。 */
    val PanelElevated = Color(0xFF171A21)

    /** `--text` 月白：主文字、主按钮底。 */
    val Text = Color(0xFFF2F5F9)

    /** `--text-2` 次级文字：说明、元信息。 */
    val TextSecondary = Color(0xFF98A2B3)

    /** `--text-3` 三级文字：区块操作（"全部 ›"）、占位符。 */
    val TextFaint = Color(0xFF6B7483)

    /** `--accent` 极光青：唯一强调色（眉标、进度、焦点环、激活态）。 */
    val Accent = Color(0xFF5CE1D2)

    /** `--accent-2` 辅光蓝：仅渐变辅助（<10% 用量，如头图进度条）。 */
    val AccentSecondary = Color(0xFF7CC4FF)

    /** 月白主按钮上的深色内容（A 稿 `.btn.primary` 的 `color:#0A0C11`）。 */
    val OnPrimary = Color(0xFF0A0C11)

    /** `--line` 细线：白 8.5%（卡片描边 / 结构线）。 */
    const val LineAlpha = 0.085f

    /** `--line-soft` 弱分隔线：白 5%。 */
    const val LineSoftAlpha = 0.05f

    /** `.btn.ghost` 底：白 7%。 */
    const val GhostAlpha = 0.07f

    /** `.hero .resume` 进度轨道：白 14%。 */
    const val ProgressTrackAlpha = 0.14f

    /** A 稿 hero 左右渐隐的基色（`rgba(6,7,10,…)` 家族，取曜石黑同源）。 */
    val Scrim = Color(0xFF06070A)
}

/**
 * Lumen 语义色（S1 A 稿）。组件层只允许引用本数据类的字段，禁止直接引用 [LumenTokens]。
 *
 * [primaryButton] / [onPrimary] 对应 A 稿 `.btn.primary`（月白填充 + 深色内容）； [ghost] 对应 `.btn.ghost`（白 7%
 * 底，配 [line] 描边）。
 */
@Immutable
data class LumenColors(
    val background: Color,
    val panel: Color,
    val panelElevated: Color,
    val line: Color,
    val lineSoft: Color,
    val text: Color,
    val textSecondary: Color,
    val textFaint: Color,
    val accent: Color,
    val accentSecondary: Color,
    val primaryButton: Color,
    val onPrimary: Color,
    val ghost: Color,
    val scrim: Color,
    val progressTrack: Color,
)

/** Lumen 深色色板（A 稿只有深色；App 固定深色外观，浅色主题下 Lumen 区域也维持该色板）。 */
val LumenColorsDark =
    LumenColors(
        background = LumenTokens.Background,
        panel = LumenTokens.Panel,
        panelElevated = LumenTokens.PanelElevated,
        line = Color.White.copy(alpha = LumenTokens.LineAlpha),
        lineSoft = Color.White.copy(alpha = LumenTokens.LineSoftAlpha),
        text = LumenTokens.Text,
        textSecondary = LumenTokens.TextSecondary,
        textFaint = LumenTokens.TextFaint,
        accent = LumenTokens.Accent,
        accentSecondary = LumenTokens.AccentSecondary,
        primaryButton = LumenTokens.Text,
        onPrimary = LumenTokens.OnPrimary,
        ghost = Color.White.copy(alpha = LumenTokens.GhostAlpha),
        scrim = LumenTokens.Scrim,
        progressTrack = Color.White.copy(alpha = LumenTokens.ProgressTrackAlpha),
    )

/**
 * 当前是否处于 Lumen 区域（`null` = Prism 区域，默认）。
 *
 * 由 [ProvideLumen] 置为非空；组件据此选择 Lumen 专用画法（渐变描边、文字阴影、青→蓝进度）， 未处于 Lumen 区域时一律回落到 Prism 语义。
 */
val LocalLumenColors = staticCompositionLocalOf<LumenColors?> { null }

/** Lumen 区域内的 Prism 语义色覆盖：走 [LocalCinefinColors] 的既有组件自动切到 A 色板。 */
val LumenCinefinColorsDark: CinefinColors =
    CinefinColorsDark.copy(
        surface = LumenTokens.Background,
        surfaceDim = LumenTokens.Background,
        surfaceBright = LumenTokens.PanelElevated,
        surfaceContainerLowest = LumenTokens.Background,
        surfaceContainerLow = LumenTokens.Panel,
        surfaceContainer = LumenTokens.Panel,
        surfaceContainerHigh = LumenTokens.PanelElevated,
        surfaceContainerHighest = LumenTokens.PanelElevated,
        onSurface = LumenTokens.Text,
        onSurfaceVariant = LumenTokens.TextSecondary,
        onSurfaceFaint = LumenTokens.TextFaint,
        outline = Color.White.copy(alpha = LumenTokens.LineAlpha),
        outlineVariant = Color.White.copy(alpha = LumenTokens.LineSoftAlpha),
        inverseSurface = LumenTokens.Text,
        inverseOnSurface = LumenTokens.OnPrimary,
        navSurface = LumenTokens.Background,
        progressTrack = Color.White.copy(alpha = LumenTokens.ProgressTrackAlpha),
        progressTrackOnImage = Color.White.copy(alpha = LumenTokens.ProgressTrackAlpha),
    )

/** Lumen 区域内的媒体色覆盖：影视域在 Lumen 区域改用极光青（唯一强调色）。 */
val LumenMediaColors: MediaColors =
    MediaColors(
        base = LumenTokens.Accent,
        bright = LumenTokens.Accent,
        dim = LumenTokens.Accent.copy(alpha = 0.86f).compositeOver(LumenTokens.Panel),
        onBase = LumenTokens.OnPrimary,
        container =
            LumenTokens.Accent.copy(alpha = CinefinTokens.MediaContainerAlpha)
                .compositeOver(LumenTokens.Panel),
        containerPressed =
            LumenTokens.Accent.copy(alpha = CinefinTokens.MediaContainerPressedAlpha)
                .compositeOver(LumenTokens.Panel),
        outline =
            LumenTokens.Accent.copy(alpha = CinefinTokens.MediaOutlineAlpha)
                .compositeOver(LumenTokens.Panel),
    )

/**
 * 把子树切到 S1 A 稿皮肤（首页 / 视频详情的唯一入口）。
 *
 * 三件事：① [LocalLumenColors] 置为 [LumenColorsDark]；② [LocalCinefinColors] / [LocalMediaColors]
 * 同步覆盖，使既有 Prism 组件零改动切换到 A 色板；③ 铺一层曜石黑页底（A 稿 `--bg`）。 离开本子树即恢复 Prism；音乐 / 阅读页面各自有独立的
 * [CinefinTheme]，不受影响。
 */
@Composable
fun ProvideLumen(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalLumenColors provides LumenColorsDark,
        LocalCinefinColors provides LumenCinefinColorsDark,
        LocalMediaColors provides LumenMediaColors,
    ) {
        Box(modifier = modifier.fillMaxSize().background(LumenColorsDark.background)) { content() }
    }
}
