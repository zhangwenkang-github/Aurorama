package com.zhangwenkang.cinefin.core.presentation.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/*
 * 设计系统字族（UI_DESIGN_SYSTEM §3.1）的最终形态是打包 MiSans / Literata Italic / Cascadia Mono /
 * Noto Serif SC；授权与子集化未完成前先用平台同族字面兜底（sans-serif / serif / monospace），
 * 字号、行高、字重、字距已按 v1.0 全量落地，字体文件到位后只替换 FontFamily。
 */
private val CinefinSans = FontFamily.SansSerif
private val CinefinSerif = FontFamily.Serif
private val CinefinMono = FontFamily.Monospace

/**
 * Cinefin 字阶 token（§3.2 / §3.4）。
 *
 * `Display* → Label*` 与 M3 Typography 槽位一一对应；带 ★ 的扩展 token（DetailTitle / SectionTitle / Mono* /
 * Reader*）无 M3 对应，供页面与组件显式引用（新代码优先用本对象，不再从 MaterialTheme 取字号）。
 */
object CinefinType {
    val DisplayLarge =
        TextStyle(
            fontFamily = CinefinSans,
            fontWeight = FontWeight.Normal,
            fontSize = 57.sp,
            lineHeight = 64.sp,
            letterSpacing = (-0.25).sp,
        )
    val DisplayMedium =
        TextStyle(
            fontFamily = CinefinSans,
            fontWeight = FontWeight.Normal,
            fontSize = 45.sp,
            lineHeight = 52.sp,
            letterSpacing = 0.sp,
        )
    val DisplaySmall =
        TextStyle(
            fontFamily = CinefinSans,
            fontWeight = FontWeight.Normal,
            fontSize = 36.sp,
            lineHeight = 44.sp,
            letterSpacing = 0.sp,
        )
    val HeadlineLarge =
        TextStyle(
            fontFamily = CinefinSans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 40.sp,
            lineHeight = 48.sp,
            letterSpacing = (-0.2).sp,
        )
    val HeadlineMedium =
        TextStyle(
            fontFamily = CinefinSans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 32.sp,
            lineHeight = 40.sp,
            letterSpacing = 0.sp,
        )
    val HeadlineSmall =
        TextStyle(
            fontFamily = CinefinSans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 24.sp,
            lineHeight = 32.sp,
            letterSpacing = 0.sp,
        )
    val TitleLarge =
        TextStyle(
            fontFamily = CinefinSans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 22.sp,
            lineHeight = 28.sp,
            letterSpacing = 0.sp,
        )
    val TitleMedium =
        TextStyle(
            fontFamily = CinefinSans,
            fontWeight = FontWeight.Medium,
            fontSize = 17.sp,
            lineHeight = 24.sp,
            letterSpacing = 0.15.sp,
        )
    val TitleSmall =
        TextStyle(
            fontFamily = CinefinSans,
            fontWeight = FontWeight.Medium,
            fontSize = 15.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.1.sp,
        )
    val BodyLarge =
        TextStyle(
            fontFamily = CinefinSans,
            fontWeight = FontWeight.Normal,
            fontSize = 17.sp,
            lineHeight = 33.sp,
            letterSpacing = 0.2.sp,
        )
    val BodyMedium =
        TextStyle(
            fontFamily = CinefinSans,
            fontWeight = FontWeight.Normal,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            letterSpacing = 0.25.sp,
        )
    val BodySmall =
        TextStyle(
            fontFamily = CinefinSans,
            fontWeight = FontWeight.Normal,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.3.sp,
        )
    val LabelLarge =
        TextStyle(
            fontFamily = CinefinSans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.1.sp,
        )
    val LabelMedium =
        TextStyle(
            fontFamily = CinefinSans,
            fontWeight = FontWeight.Medium,
            fontSize = 15.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.3.sp,
        )
    val LabelSmall =
        TextStyle(
            fontFamily = CinefinSans,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.4.sp,
        )

    // ---- ★ Cinefin 扩展字阶 ----

    /** 侧导航 / 抽屉条目文字（§8.6：16sp）。 */
    val NavLabel =
        TextStyle(
            fontFamily = CinefinSans,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
            lineHeight = 22.sp,
            letterSpacing = 0.1.sp,
        )

    /** 详情页主标题（平板专用，B 稿 52px）。 */
    val DetailTitle =
        TextStyle(
            fontFamily = CinefinSans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 52.sp,
            lineHeight = 58.sp,
            letterSpacing = (-0.4).sp,
        )

    /** 区块标题（B 稿 23px）。 */
    val SectionTitle =
        TextStyle(
            fontFamily = CinefinSans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 23.sp,
            lineHeight = 30.sp,
            letterSpacing = 0.sp,
        )

    /** 横版卡标题（§8.4：19sp/600）。 */
    val WideCardTitle =
        TextStyle(
            fontFamily = CinefinSans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 19.sp,
            lineHeight = 26.sp,
            letterSpacing = 0.sp,
        )

    /** 时间码、集数、页码、文件信息（tabular-nums 由字体族保证）。 */
    val MonoData =
        TextStyle(
            fontFamily = CinefinMono,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.sp,
        )

    /** 队列编号、进度百分比。 */
    val MonoDataSmall =
        TextStyle(
            fontFamily = CinefinMono,
            fontWeight = FontWeight.Normal,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            letterSpacing = 0.sp,
        )

    /** 阅读章节题（Literata Italic / 渲染稿 Georgia）。 */
    val ReaderChapter =
        TextStyle(
            fontFamily = CinefinSerif,
            fontWeight = FontWeight.Normal,
            fontStyle = FontStyle.Italic,
            fontSize = 24.sp,
            lineHeight = 34.sp,
            letterSpacing = 0.2.sp,
        )

    /** 阅读正文 · 深色主题（行高 ≈1.98）。 */
    val ReaderBody =
        TextStyle(
            fontFamily = CinefinSerif,
            fontWeight = FontWeight.Normal,
            fontSize = 21.sp,
            lineHeight = 42.sp,
            letterSpacing = 0.2.sp,
        )

    /** 阅读正文 · 纸色主题（宋体，行高 ≈2.08）。 */
    val ReaderBodyPaper =
        TextStyle(
            fontFamily = CinefinSerif,
            fontWeight = FontWeight.Normal,
            fontSize = 22.sp,
            lineHeight = 46.sp,
            letterSpacing = 0.2.sp,
        )
}

/** 设计系统 M3 Typography（§3.4）。 */
val CinefinTypography =
    Typography(
        displayLarge = CinefinType.DisplayLarge,
        displayMedium = CinefinType.DisplayMedium,
        displaySmall = CinefinType.DisplaySmall,
        headlineLarge = CinefinType.HeadlineLarge,
        headlineMedium = CinefinType.HeadlineMedium,
        headlineSmall = CinefinType.HeadlineSmall,
        titleLarge = CinefinType.TitleLarge,
        titleMedium = CinefinType.TitleMedium,
        titleSmall = CinefinType.TitleSmall,
        bodyLarge = CinefinType.BodyLarge,
        bodyMedium = CinefinType.BodyMedium,
        bodySmall = CinefinType.BodySmall,
        labelLarge = CinefinType.LabelLarge,
        labelMedium = CinefinType.LabelMedium,
        labelSmall = CinefinType.LabelSmall,
    )

/**
 * 旧「影阁」排版（迁移前 `app:phone` 的 `Typography.kt` 原值）。
 *
 * W1 桥接：`app:phone` 的入口包装暂时把它交给 MaterialTheme，保证存量页面（145 处 `MaterialTheme.typography`
 * 引用）排版零回归；W3/W4 页面按 Prism 字阶改造后删除本桥接。
 */
val LegacyTypography =
    Typography(
        displayLarge =
            TextStyle(
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                fontSize = 50.sp,
                lineHeight = 60.sp,
                letterSpacing = (-0.5).sp,
            ),
        displayMedium =
            TextStyle(
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                fontSize = 40.sp,
                lineHeight = 50.sp,
                letterSpacing = (-0.4).sp,
            ),
        displaySmall =
            TextStyle(
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 32.sp,
                lineHeight = 42.sp,
                letterSpacing = (-0.3).sp,
            ),
        headlineLarge =
            TextStyle(
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 28.sp,
                lineHeight = 38.sp,
                letterSpacing = (-0.2).sp,
            ),
        headlineMedium =
            TextStyle(
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 24.sp,
                lineHeight = 34.sp,
                letterSpacing = (-0.1).sp,
            ),
        headlineSmall =
            TextStyle(
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 21.sp,
                lineHeight = 30.sp,
                letterSpacing = 0.sp,
            ),
        titleLarge =
            TextStyle(
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 19.sp,
                lineHeight = 28.sp,
                letterSpacing = 0.sp,
            ),
        titleMedium =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                letterSpacing = 0.1.sp,
            ),
        titleSmall =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 21.sp,
                letterSpacing = 0.1.sp,
            ),
        bodyLarge =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                lineHeight = 26.sp,
                letterSpacing = 0.1.sp,
            ),
        bodyMedium =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                lineHeight = 22.sp,
                letterSpacing = 0.1.sp,
            ),
        bodySmall =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Normal,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                letterSpacing = 0.2.sp,
            ),
        labelLarge =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.1.sp,
            ),
        labelMedium =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.2.sp,
            ),
        labelSmall =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.3.sp,
            ),
    )
