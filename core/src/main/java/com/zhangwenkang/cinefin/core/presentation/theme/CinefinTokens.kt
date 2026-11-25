package com.zhangwenkang.cinefin.core.presentation.theme

import androidx.compose.ui.graphics.Color

/**
 * Cinefin 基础色板 token（依据 `docs/UI_DESIGN_SYSTEM.md` v1.0 §2–§7；App 主题色板见 `CinefinThemes` 注册表）。
 *
 * 本文件与历史 `Color.kt` 是项目中仅有的两处允许出现 `Color(0x…)` 字面量的文件；业务与组件代码 一律通过 [CinefinColors]（经
 * `CinefinThemes` 注册表）、[MediaFilm] / [MediaMusic] / [MediaBook] 以及 [CinefinType] / [CinefinShapes]
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

    // ---- 2.0.1 定稿 §2 批① 语义状态色（AUDIT-21）：告警 / 成功 / 信息一律走 token，禁手写 ----
    /** 告警：琥珀金（定稿 §2 批①给定值）。 */
    val Warning = Color(0xFFE0B84F)
    /** 告警上的内容色：与黄铜 onTertiary 同族深墨。 */
    val OnWarning = Color(0xFF2A2100)
    /** 成功：极光绿。 */
    val Success = Color(0xFF45D483)
    /** 成功上的内容色：与 onAurora 同族深墨。 */
    val OnSuccess = Color(0xFF04201A)
    /** 信息：定稿指定 Aurora2 兼任。 */
    val Info = Color(0xFF6FA0FF)
    /** 信息上的内容色：与阅读域 onBase 同族深墨。 */
    val OnInfo = Color(0xFF0A1220)
    val Scrim = Color(0xFF06080C)
    val NavSurfaceDark = Color(0xFF12171D) // §8.6 侧导航底色
    val NavSurfaceLight = Color(0xFFEDEAE3) // 派生

    // ---- §2.1 状态层（深色主题用白，浅色主题用黑）----
    const val StateHoverAlpha = 0.08f
    const val StateFocusAlpha = 0.12f
    const val StatePressedAlpha = 0.12f
    const val DisabledAlpha = 0.38f
    const val ScrimAlpha = 0.72f

    // ---- W42 侧栏 / 底栏（半透明石墨 + 未选中图标提亮）----
    /** 底栏底色透明度：用户取中值 82%（78–84% 区间），保留背景透出又不牺牲文字对比度。 */
    const val ChromeTranslucency = 0.82f
    /**
     * 侧轨 / 抽屉底色透明度（W46 用户确认：82% → 74%）。
     *
     * 只作用于侧栏（平板侧轨 + 手机抽屉）；底栏保持 [ChromeTranslucency] 不变。与 82% 同理，必须先铺页底衬底 再叠这一层，否则会被同色容器吃掉（踩坑
     * 57）。选中态容器用同一 alpha，避免选中行"吃掉"透明度。
     */
    const val RailTranslucency = 0.74f
    /** 侧栏未选中图标的提亮值（白 62%）；标签仍走次级灰，图标略亮一档便于扫读。 */
    const val NavUnselectedIconAlpha = 0.62f

    // ---- §2.2 媒体色合成系数 ----
    const val MediaContainerAlpha = 0.16f
    const val MediaContainerPressedAlpha = 0.24f
    const val MediaOutlineAlpha = 0.45f
    /** 评分徽标淡色底：aurora1 @ 12%（2.0.1 定稿 §2 批①：评分徽标改极光淡色 pill）。 */
    const val RatingBadgeAlpha = 0.12f
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

    // ---- 2.0.1 定稿 §2 批① 媒体色 · 中性（极光）：base = Aurora1、onBase = onAurora ----
    /** 中性域 bright：Aurora1 提亮档（定稿给定值）。 */
    val MediaAuroraBright = Color(0xFF8FF5DD)
    /** 中性域 dim：Aurora1 压暗档（定稿给定值）。 */
    val MediaAuroraDim = Color(0xFF2BA98E)

    // ---- Lumen 遗产色板（原 LumenColors.kt，仅注册表 `lumen` 条目引用；不在用户可选值域内）----
    /** `--bg` 曜石黑。 */
    val LumenBackground = Color(0xFF08090C)
    /** `--panel` 石墨。 */
    val LumenPanel = Color(0xFF111319)
    /** `--panel-2` 雾灰。 */
    val LumenPanelElevated = Color(0xFF171A21)
    /** `--text` 月白。 */
    val LumenText = Color(0xFFF2F5F9)
    /** `--text-2` 次级文字。 */
    val LumenTextSecondary = Color(0xFF98A2B3)
    /** `--text-3` 三级文字。 */
    val LumenTextFaint = Color(0xFF6B7483)
    /** `--accent` 极光青。 */
    val LumenAccent = Color(0xFF5CE1D2)
    /** `--accent-2` 辅光蓝。 */
    val LumenAccentSecondary = Color(0xFF7CC4FF)
    val LumenOnPrimary = Color(0xFF0A0C11)
    val LumenOnErrorContainer = Color(0xFFF4F1EA)
    /** `--line` 细线：白 8.5%。 */
    const val LumenLineAlpha = 0.085f
    /** `--line-soft` 弱分隔线：白 5%。 */
    const val LumenLineSoftAlpha = 0.05f
    /** hero 进度轨道：白 14%。 */
    const val LumenProgressTrackAlpha = 0.14f
    /** 侧缘渐隐基色（曜石黑同源）。 */
    val LumenScrim = Color(0xFF06070A)

    // ---- K1 Aurora Cinema（2026-11-18 设计定稿）----
    /** 玻璃面板底色透明度（aurora / lumen 同值）。 */
    const val GlassAlpha = 0.58f
    /** 普通表面上的进度轨道透明度：白 12%。 */
    const val ProgressTrackAlpha = 0.12f

    // ---- 播放页玻璃控制层（VIDEO-29，W12 用户验收值）----
    /** 覆盖层控件玻璃底：scrim 28%。 */
    const val PlayerGlassAlpha = 0.28f
    /** 覆盖层控件按下态玻璃底：scrim 44%。 */
    const val PlayerGlassPressedAlpha = 0.44f

    // aurora.dark 中性色
    val AuroraDarkSurface = Color(0xFF080D17)
    val AuroraDarkSurfaceDim = Color(0xFF04070D)
    val AuroraDarkSurfaceBright = Color(0xFF131D30)
    val AuroraDarkSurfaceContainerLowest = Color(0xFF04070D)
    val AuroraDarkSurfaceContainerLow = Color(0xFF080D17)
    val AuroraDarkSurfaceContainer = Color(0xFF0D1522)
    val AuroraDarkSurfaceContainerHigh = Color(0xFF131D30)
    val AuroraDarkSurfaceContainerHighest = Color(0xFF1C2638)
    val AuroraDarkOnSurface = Color(0xFFF5F7FB)
    val AuroraDarkOnSurfaceVariant = Color(0xFF9FAEC4)
    val AuroraDarkOnSurfaceFaint = Color(0xFF5D6C85)
    /** 描边：白 14%。 */
    const val AuroraDarkOutlineAlpha = 0.14f
    /** 弱描边：白 9%。 */
    const val AuroraDarkOutlineVariantAlpha = 0.09f
    val AuroraDarkInverseSurface = Color(0xFFF5F7FB)
    val AuroraDarkInverseOnSurface = Color(0xFF080D17)
    val AuroraDarkNavSurface = Color(0xFF0A101C)
    val AuroraDarkOnErrorContainer = Color(0xFFF5F7FB)
    /** 玻璃描边：白 9%。 */
    const val AuroraDarkGlassBorderAlpha = 0.09f
    val Aurora1Dark = Color(0xFF3DE8C2)
    val Aurora2Dark = Color(0xFF6FA0FF)
    val Aurora3Dark = Color(0xFFB07CFF)
    val OnAuroraDark = Color(0xFF04201A)

    // amoled.dark：只覆盖与 aurora.dark 不同的槽位（纯黑省电）
    val AmoledDarkSurface = Color(0xFF000000)
    val AmoledDarkSurfaceDim = Color(0xFF000000)
    val AmoledDarkSurfaceBright = Color(0xFF121A2A)
    val AmoledDarkSurfaceContainerLowest = Color(0xFF000000)
    val AmoledDarkSurfaceContainerLow = Color(0xFF000000)
    val AmoledDarkSurfaceContainer = Color(0xFF0A0F18)
    val AmoledDarkSurfaceContainerHigh = Color(0xFF121A2A)
    val AmoledDarkSurfaceContainerHighest = Color(0xFF1B2332)
    val AmoledDarkNavSurface = Color(0xFF000000)

    // light（aurora / amoled / lumen 共用的浅色变体）
    val AuroraLightSurface = Color(0xFFFAFAFB)
    val AuroraLightSurfaceDim = Color(0xFFE6E9F0)
    val AuroraLightSurfaceBright = Color(0xFFFFFFFF)
    val AuroraLightSurfaceContainerLowest = Color(0xFFFFFFFF)
    val AuroraLightSurfaceContainerLow = Color(0xFFFAFAFB)
    val AuroraLightSurfaceContainer = Color(0xFFF0F2F6)
    val AuroraLightSurfaceContainerHigh = Color(0xFFE6E9F0)
    val AuroraLightSurfaceContainerHighest = Color(0xFFDDE0E6)
    val AuroraLightOnSurface = Color(0xFF10151F)
    val AuroraLightOnSurfaceVariant = Color(0xFF4A5568)
    val AuroraLightOnSurfaceFaint = Color(0xFF8A94A6)
    /** 描边：#10151F @ 14%。 */
    const val AuroraLightOutlineAlpha = 0.14f
    /** 弱描边：#10151F @ 8%。 */
    const val AuroraLightOutlineVariantAlpha = 0.08f
    val AuroraLightInverseSurface = Color(0xFF10151F)
    val AuroraLightInverseOnSurface = Color(0xFFF5F7FB)
    val AuroraLightNavSurface = Color(0xFFFFFFFF)
    val AuroraLightErrorContainer = Color(0xFFF6D9D3)
    val AuroraLightOnErrorContainer = Color(0xFF3F120B)
    /** 玻璃底：白 72%。 */
    const val AuroraLightGlassAlpha = 0.72f
    /** 玻璃描边：#10151F @ 8%。 */
    const val AuroraLightGlassBorderAlpha = 0.08f
    val Aurora1Light = Color(0xFF0C9E82)
    val Aurora2Light = Color(0xFF3E6FE4)
    val Aurora3Light = Color(0xFF8B53E8)
    val OnAuroraLight = Color(0xFF000000)

    // ---- §8.14 阅读器纸色主题（C · Nocturne 皮肤）----
    val PaperSurface = Color(0xFFFBF6EC)
    val PaperAccent = Color(0xFFA8843C)

    // ---- §8.14 阅读器主题扩展（R1-W2，派生值，变更需回设计系统评审）----
    /** 阅读器排版面板深色底；§8.14 指定 `#191F28`。 */
    val ReaderPanelDark = Color(0xFF191F28)
    /** 护眼主题底色；设计系统未给值，按纸色向绿色相偏移派生（浅绿纸）。 */
    val ReaderEyeCareSurface = Color(0xFFE7EFE1)
    /** OLED 主题底色：纯黑，关闭像素。 */
    val ReaderOledSurface = Color(0xFF000000)

    // ---- §5.3 / §6.4 画面覆盖层（唯一允许 blur 的场景）----
    val OverlayGlass = Color(0x99000000) // 黑 60%
}
