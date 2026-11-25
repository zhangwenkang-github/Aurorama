package com.zhangwenkang.cinefin.core.presentation.theme

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonTone
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonVariant
import com.zhangwenkang.cinefin.core.presentation.components.CinefinInteractionState
import com.zhangwenkang.cinefin.core.presentation.components.resolveButtonColors
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设计 token 一致性单测：逐值核对 `docs/UI_DESIGN_SYSTEM.md` v1.0 §2–§7 的落地结果。
 *
 * 任何一方改值而另一方未同步都会在这里失败，防止「文档与代码漂移」。
 */
class DesignTokenConsistencyTest {
    private val tolerance = 0.5f / 255f

    private fun assertHex(expectedHex: Long, actual: Color, label: String) {
        val red = ((expectedHex shr 16) and 0xFF).toInt() / 255f
        val green = ((expectedHex shr 8) and 0xFF).toInt() / 255f
        val blue = (expectedHex and 0xFF).toInt() / 255f
        assertEquals("$label · red", red, actual.red, tolerance)
        assertEquals("$label · green", green, actual.green, tolerance)
        assertEquals("$label · blue", blue, actual.blue, tolerance)
        assertEquals("$label · alpha", 1f, actual.alpha, tolerance)
    }

    private fun assertWhiteOutline(expectedAlpha: Float, actual: Color, label: String) {
        assertEquals("$label · alpha", expectedAlpha, actual.alpha, tolerance)
        assertEquals("$label · red", 1f, actual.red, tolerance)
        assertEquals("$label · green", 1f, actual.green, tolerance)
        assertEquals("$label · blue", 1f, actual.blue, tolerance)
    }

    @Test
    fun `dark neutral tokens match K1 aurora palette`() {
        val colors = CinefinThemes.colorsFor(CinefinThemes.AURORA, dark = true)
        assertHex(0xFF080D17, colors.surface, "Surface")
        assertHex(0xFF04070D, colors.surfaceDim, "SurfaceDim")
        assertHex(0xFF131D30, colors.surfaceBright, "SurfaceBright")
        assertHex(0xFF04070D, colors.surfaceContainerLowest, "SurfaceContainerLowest")
        assertHex(0xFF0D1522, colors.surfaceContainer, "SurfaceContainer")
        assertHex(0xFF131D30, colors.surfaceContainerHigh, "SurfaceContainerHigh")
        assertHex(0xFF1C2638, colors.surfaceContainerHighest, "SurfaceContainerHighest")
        assertHex(0xFFF5F7FB, colors.onSurface, "OnSurface")
        assertHex(0xFF9FAEC4, colors.onSurfaceVariant, "OnSurfaceVariant")
        assertHex(0xFF5D6C85, colors.onSurfaceFaint, "OnSurfaceFaint")
        assertWhiteOutline(0.14f, colors.outline, "Outline")
        assertWhiteOutline(0.09f, colors.outlineVariant, "OutlineVariant")
        assertHex(0xFFF5F7FB, colors.inverseSurface, "InverseSurface")
        assertHex(0xFF080D17, colors.inverseOnSurface, "InverseOnSurface")
        assertHex(0xFFE87C6E, colors.error, "Error")
        assertHex(0xFF1A0F0C, colors.onError, "OnError")
        assertHex(0xFF3DE8C2, colors.aurora1, "Aurora1")
        assertHex(0xFF6FA0FF, colors.aurora2, "Aurora2")
        assertHex(0xFFB07CFF, colors.aurora3, "Aurora3")
        assertHex(0xFF04201A, colors.onAurora, "OnAurora")
    }

    @Test
    fun `light neutral tokens match K1 aurora palette`() {
        val colors = CinefinThemes.colorsFor(CinefinThemes.LIGHT, dark = false)
        assertHex(0xFFFAFAFB, colors.surface, "Light Surface")
        assertHex(0xFFF0F2F6, colors.surfaceContainer, "Light SurfaceContainer")
        assertHex(0xFFE6E9F0, colors.surfaceContainerHigh, "Light SurfaceContainerHigh")
        assertHex(0xFF10151F, colors.onSurface, "Light OnSurface")
        assertHex(0xFF4A5568, colors.onSurfaceVariant, "Light OnSurfaceVariant")
        assertEquals(0.14f, colors.outline.alpha, tolerance)
        assertEquals(0.08f, colors.outlineVariant.alpha, tolerance)
        assertHex(0xFF0C9E82, colors.aurora1, "Light Aurora1")
        assertHex(0xFF3E6FE4, colors.aurora2, "Light Aurora2")
        assertHex(0xFF8B53E8, colors.aurora3, "Light Aurora3")
        assertHex(0xFF000000, colors.onAurora, "Light OnAurora")
    }

    @Test
    fun `media tokens match design system`() {
        assertHex(0xFFE8A15C, MediaFilm.base, "Film base")
        assertHex(0xFFEDB680, MediaFilm.bright, "Film bright")
        assertHex(0xFFBE844B, MediaFilm.dim, "Film dim")
        assertHex(0xFF3C3533, MediaFilm.container, "Film container")
        assertHex(0xFF4C4037, MediaFilm.containerPressed, "Film containerPressed")
        assertHex(0xFF141009, MediaFilm.onBase, "Film onBase")
        assertHex(0xFF775B41, MediaFilm.outline, "Film outline")

        assertHex(0xFF3FC9A0, MediaMusic.base, "Music base")
        assertHex(0xFF69D5B5, MediaMusic.bright, "Music bright")
        assertHex(0xFF34A583, MediaMusic.dim, "Music dim")
        assertHex(0xFF213C3E, MediaMusic.container, "Music container")
        assertHex(0xFF244947, MediaMusic.containerPressed, "Music containerPressed")
        assertHex(0xFF06120D, MediaMusic.onBase, "Music onBase")
        assertHex(0xFF2B6D60, MediaMusic.outline, "Music outline")

        assertHex(0xFF6FA8FF, MediaBook.base, "Book base")
        assertHex(0xFF8FBBFF, MediaBook.bright, "Book bright")
        assertHex(0xFF5B8AD1, MediaBook.dim, "Book dim")
        assertHex(0xFF28374D, MediaBook.container, "Book container")
        assertHex(0xFF2F415E, MediaBook.containerPressed, "Book containerPressed")
        assertHex(0xFF0A1220, MediaBook.onBase, "Book onBase")
        assertHex(0xFF415E8A, MediaBook.outline, "Book outline")

        // 2.0.1 定稿 §2 批①：中性域改极光色板（base = Aurora1）。
        assertHex(0xFF3DE8C2, MediaAurora.base, "Neutral base")
        assertHex(0xFF8FF5DD, MediaAurora.bright, "Neutral bright")
        assertHex(0xFF2BA98E, MediaAurora.dim, "Neutral dim")
        assertHex(0xFF04201A, MediaAurora.onBase, "Neutral onBase")
    }

    @Test
    fun `semantic status tokens match design final`() {
        assertHex(0xFFE0B84F, CinefinTokens.Warning, "Warning")
        assertHex(0xFF2A2100, CinefinTokens.OnWarning, "OnWarning")
        assertHex(0xFF45D483, CinefinTokens.Success, "Success")
        assertHex(0xFF04201A, CinefinTokens.OnSuccess, "OnSuccess")
        assertHex(0xFF6FA0FF, CinefinTokens.Info, "Info")
        assertHex(0xFF0A1220, CinefinTokens.OnInfo, "OnInfo")
        assertEquals(0.12f, CinefinTokens.RatingBadgeAlpha, 0.001f)

        CinefinThemes.map.forEach { (id, palette) ->
            listOf("dark" to palette.dark, "light" to palette.light).forEach { (mode, colors) ->
                assertHex(0xFFE0B84F, colors.warning, "$id.$mode warning")
                assertHex(0xFF45D483, colors.success, "$id.$mode success")
                assertHex(0xFF6FA0FF, colors.info, "$id.$mode info")
            }
        }
    }

    @Test
    fun `neutral domain resolves to the aurora media palette`() {
        val darkColors = CinefinThemes.colorsFor(CinefinThemes.AURORA, dark = true)
        val lightColors = CinefinThemes.colorsFor(CinefinThemes.LIGHT, dark = false)

        val darkNeutral = mediaColorsFor(ContentDomain.Neutral, darkColors)
        assertHex(0xFF3DE8C2, darkNeutral.base, "Neutral dark base")
        assertNotEquals("中性域 base 不得再取影视琥珀", MediaFilm.base, darkNeutral.base)
        assertHex(0xFF04201A, darkNeutral.onBase, "Neutral dark onBase")
        assertEquals(MediaAurora.containerOver(darkColors.surfaceContainer), darkNeutral.container)

        val lightNeutral = mediaColorsFor(ContentDomain.Neutral, lightColors)
        assertEquals(darkNeutral.base, lightNeutral.base)
        assertHex(0xFF000000, lightNeutral.onBase, "Neutral light onBase")
        assertEquals(
            MediaAurora.containerOver(lightColors.surfaceContainer),
            lightNeutral.container,
        )
    }

    @Test
    fun `media containers are derived over the palette surface container`() {
        val colors = CinefinThemes.colorsFor(CinefinThemes.AURORA, dark = true)
        listOf(ContentDomain.Movie, ContentDomain.Music, ContentDomain.Book).forEach { domain ->
            val media = mediaColorsFor(domain, colors)
            val published =
                when (domain) {
                    ContentDomain.Movie -> MediaFilm
                    ContentDomain.Music -> MediaMusic
                    ContentDomain.Book -> MediaBook
                    ContentDomain.Neutral -> MediaAurora.copy(onBase = colors.onAurora)
                }
            assertEquals(published.containerOver(colors.surfaceContainer), media.container)
            assertEquals(
                published.containerPressedOver(colors.surfaceContainer),
                media.containerPressed,
            )
            assertEquals(published.outlineOver(colors.surfaceContainer), media.outline)
            assertEquals(published.base, media.base)
            assertEquals(published.bright, media.bright)
            assertEquals(published.dim, media.dim)
            assertEquals(published.onBase, media.onBase)
        }
    }

    @Test
    fun `material color scheme decouples brand roles from domain media colors`() {
        // 2.0.1 定稿 §2 批①：M3 primary 系归极光，secondary / tertiary 改中性系。
        val darkColors = CinefinThemes.colorsFor(CinefinThemes.AURORA, dark = true)
        val lightColors = CinefinThemes.colorsFor(CinefinThemes.LIGHT, dark = false)

        listOf(darkColors to true, lightColors to false).forEach { (colors, dark) ->
            val scheme = cinefinColorScheme(colors, dark = dark)
            val label = if (dark) "dark" else "light"
            val auroraContainer =
                colors.aurora1
                    .copy(alpha = CinefinTokens.MediaContainerAlpha)
                    .compositeOver(colors.surfaceContainer)

            assertEquals("$label primary", colors.aurora1, scheme.primary)
            assertEquals("$label onPrimary", colors.onAurora, scheme.onPrimary)
            assertEquals("$label primaryContainer", auroraContainer, scheme.primaryContainer)
            assertEquals("$label onPrimaryContainer", colors.onSurface, scheme.onPrimaryContainer)
            assertEquals("$label inversePrimary", MediaAurora.dim, scheme.inversePrimary)

            assertEquals("$label secondary", colors.onSurfaceVariant, scheme.secondary)
            assertEquals("$label onSecondary", colors.surface, scheme.onSecondary)
            assertEquals(
                "$label secondaryContainer",
                colors.surfaceContainerHigh,
                scheme.secondaryContainer,
            )
            assertEquals(
                "$label onSecondaryContainer",
                colors.onSurface,
                scheme.onSecondaryContainer,
            )
            assertEquals("$label tertiary", colors.onSurfaceVariant, scheme.tertiary)
            assertEquals(
                "$label tertiaryContainer",
                colors.surfaceContainerHigh,
                scheme.tertiaryContainer,
            )

            // 域媒体色不再进入 M3 强调槽位（影视琥珀 / 音乐松石 / 阅读天青）。
            assertNotEquals("$label primary 不得为琥珀", MediaFilm.base, scheme.primary)
            assertNotEquals("$label secondary 不得为松石", MediaMusic.base, scheme.secondary)
            assertNotEquals("$label tertiary 不得为天青", MediaBook.base, scheme.tertiary)

            assertEquals("$label surface", colors.surface, scheme.surface)
            assertEquals(
                "$label surfaceContainer",
                colors.surfaceContainer,
                scheme.surfaceContainer,
            )
        }
    }

    @Test
    fun `shape tokens match 32 28 20 16 14 8 4`() {
        assertEquals(CornerSize(32.dp), CinefinShapes.Glass.topStart)
        assertEquals(CornerSize(28.dp), CinefinShapes.Xl.topStart)
        assertEquals(CornerSize(20.dp), CinefinShapes.Card.topStart)
        assertEquals(CornerSize(16.dp), CinefinShapes.Button.topStart)
        assertEquals(CornerSize(14.dp), CinefinShapes.Poster.topStart)
        assertEquals(CornerSize(8.dp), CinefinShapes.Xs.topStart)
        assertEquals(CornerSize(4.dp), CinefinShapes.TwoXs.topStart)
    }

    @Test
    fun `material shapes map to cinefin slots`() {
        assertEquals(CinefinShapes.TwoXs, CinefinShapes.M3.extraSmall)
        assertEquals(CinefinShapes.Xs, CinefinShapes.M3.small)
        assertEquals(CinefinShapes.Button, CinefinShapes.M3.medium)
        assertEquals(CinefinShapes.Card, CinefinShapes.M3.large)
        assertEquals(CinefinShapes.Xl, CinefinShapes.M3.extraLarge)
    }

    @Test
    fun `motion tokens match design system`() {
        assertEquals(100, CinefinMotion.Instant)
        assertEquals(200, CinefinMotion.Fast)
        assertEquals(220, CinefinMotion.Reader)
        assertEquals(280, CinefinMotion.Player)
        assertEquals(420, CinefinMotion.Page)
        assertEquals(560, CinefinMotion.Enter)
        assertEquals(40, CinefinMotion.Stagger)
        assertEquals(700, CinefinMotion.Immersive)
        assertEquals(0f, CinefinMotion.Emphasized.transform(0f), 0.001f)
        assertEquals(1f, CinefinMotion.Emphasized.transform(1f), 0.001f)
        // B 稿主曲线「更快出、更缓收」：同一进度上应早于 M3 标准曲线。
        assertTrue(
            CinefinMotion.Emphasized.transform(0.25f) > CinefinMotion.Standard.transform(0.25f)
        )
    }

    @Test
    fun `player glass control layer tokens match K1 spec`() {
        // VIDEO-29：播放页玻璃控制层的 W12 验收值
        assertEquals(0.28f, CinefinTokens.PlayerGlassAlpha, 0.001f)
        assertEquals(0.44f, CinefinTokens.PlayerGlassPressedAlpha, 0.001f)
        // VIDEO-29/30/31：三个弹簧 token 的定稿参数
        assertEquals(0.86f, CinefinMotion.SpringControlIn.dampingRatio, 0.001f)
        assertEquals(400f, CinefinMotion.SpringControlIn.stiffness, 0.001f)
        assertEquals(0.62f, CinefinMotion.SpringOvershoot.dampingRatio, 0.001f)
        assertEquals(520f, CinefinMotion.SpringOvershoot.stiffness, 0.001f)
        assertEquals(0.75f, CinefinMotion.SpringSnap.dampingRatio, 0.001f)
        assertEquals(600f, CinefinMotion.SpringSnap.stiffness, 0.001f)
        // 位移版与 SpringControlIn 同源；退场位移是临界阻尼
        assertEquals(
            CinefinMotion.SpringControlIn.dampingRatio,
            CinefinMotion.SpringControlInOffset.dampingRatio,
            0.001f,
        )
        assertEquals(
            CinefinMotion.SpringControlIn.stiffness,
            CinefinMotion.SpringControlInOffset.stiffness,
            0.001f,
        )
        assertEquals(1f, CinefinMotion.SpringControlOutOffset.dampingRatio, 0.001f)
    }

    @Test
    fun `spacing tokens match 4 8 based scale`() {
        assertEquals(4f, CinefinSpacing.Space1.value)
        assertEquals(8f, CinefinSpacing.Space2.value)
        assertEquals(12f, CinefinSpacing.Space3.value)
        assertEquals(16f, CinefinSpacing.Space4.value)
        assertEquals(20f, CinefinSpacing.Space5.value)
        assertEquals(24f, CinefinSpacing.Space6.value)
        assertEquals(26f, CinefinSpacing.Space7.value)
        assertEquals(32f, CinefinSpacing.Space8.value)
        assertEquals(40f, CinefinSpacing.Space10.value)
        assertEquals(48f, CinefinSpacing.Space12.value)
        assertEquals(64f, CinefinSpacing.Space16.value)
    }

    @Test
    fun `type scale matches design system`() {
        assertEquals(57f, CinefinTypography.displayLarge.fontSize.value)
        assertEquals(64f, CinefinTypography.displayLarge.lineHeight.value)
        assertEquals(45f, CinefinTypography.displayMedium.fontSize.value)
        assertEquals(36f, CinefinTypography.displaySmall.fontSize.value)
        assertEquals(40f, CinefinTypography.headlineLarge.fontSize.value)
        assertEquals(32f, CinefinTypography.headlineMedium.fontSize.value)
        assertEquals(24f, CinefinTypography.headlineSmall.fontSize.value)
        assertEquals(22f, CinefinTypography.titleLarge.fontSize.value)
        assertEquals(17f, CinefinTypography.titleMedium.fontSize.value)
        assertEquals(15f, CinefinTypography.titleSmall.fontSize.value)
        assertEquals(17f, CinefinTypography.bodyLarge.fontSize.value)
        assertEquals(33f, CinefinTypography.bodyLarge.lineHeight.value)
        assertEquals(15f, CinefinTypography.bodyMedium.fontSize.value)
        assertEquals(13f, CinefinTypography.bodySmall.fontSize.value)
        assertEquals(16f, CinefinTypography.labelLarge.fontSize.value)
        assertEquals(15f, CinefinTypography.labelMedium.fontSize.value)
        assertEquals(13f, CinefinTypography.labelSmall.fontSize.value)
    }

    @Test
    fun `extended type tokens match design system`() {
        assertEquals(52f, CinefinType.DetailTitle.fontSize.value)
        assertEquals(58f, CinefinType.DetailTitle.lineHeight.value)
        assertEquals(23f, CinefinType.SectionTitle.fontSize.value)
        assertEquals(14f, CinefinType.MonoData.fontSize.value)
        assertEquals(13f, CinefinType.MonoDataSmall.fontSize.value)
        assertEquals(24f, CinefinType.ReaderChapter.fontSize.value)
        assertEquals(21f, CinefinType.ReaderBody.fontSize.value)
        assertEquals(42f, CinefinType.ReaderBody.lineHeight.value)
        assertEquals(22f, CinefinType.ReaderBodyPaper.fontSize.value)
        assertEquals(46f, CinefinType.ReaderBodyPaper.lineHeight.value)
    }

    @Test
    fun `legacy typography bridge keeps previous app phone values`() {
        assertEquals(50f, LegacyTypography.displayLarge.fontSize.value)
        assertEquals(60f, LegacyTypography.displayLarge.lineHeight.value)
        assertEquals(28f, LegacyTypography.headlineLarge.fontSize.value)
        assertEquals(19f, LegacyTypography.titleLarge.fontSize.value)
        assertEquals(16f, LegacyTypography.bodyLarge.fontSize.value)
        assertEquals(14f, LegacyTypography.labelLarge.fontSize.value)
        assertEquals(11f, LegacyTypography.labelSmall.fontSize.value)
    }

    @Test
    fun `state layer alphas match design system`() {
        assertEquals(0.08f, CinefinTokens.StateHoverAlpha, 0.001f)
        assertEquals(0.12f, CinefinTokens.StateFocusAlpha, 0.001f)
        assertEquals(0.12f, CinefinTokens.StatePressedAlpha, 0.001f)
        assertEquals(0.38f, CinefinTokens.DisabledAlpha, 0.001f)
        assertEquals(0.16f, CinefinTokens.MediaContainerAlpha, 0.001f)
        assertEquals(0.24f, CinefinTokens.MediaContainerPressedAlpha, 0.001f)
        assertEquals(0.45f, CinefinTokens.MediaOutlineAlpha, 0.001f)
        assertEquals(0.60f, CinefinTokens.FocusRingAlpha, 0.001f)
    }

    @Test
    fun `inverse button tone resolves to inverse surface with dark content`() {
        val colors = CinefinThemes.colorsFor(CinefinThemes.AURORA, dark = true)
        val resolved =
            resolveButtonColors(
                variant = CinefinButtonVariant.Filled,
                state = CinefinInteractionState.Default,
                media = mediaColorsFor(ContentDomain.Movie, colors),
                colors = colors,
                tone = CinefinButtonTone.Inverse,
            )
        assertEquals(colors.inverseSurface, resolved.container)
        assertEquals(colors.inverseOnSurface, resolved.content)
        assertEquals(0f, resolved.border.alpha, 0.001f)
    }

    private fun relativeLuminance(color: Color): Double {
        fun channel(value: Float): Double {
            val c = value.toDouble()
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) +
            0.7152 * channel(color.green) +
            0.0722 * channel(color.blue)
    }

    private fun contrast(foreground: Color, background: Color): Double {
        val a = relativeLuminance(foreground)
        val b = relativeLuminance(background)
        val (lighter, darker) = if (a >= b) a to b else b to a
        return (lighter + 0.05) / (darker + 0.05)
    }

    @Test
    fun `media content tokens match the T10 design values`() {
        // 2.0.1 定稿 §3：scrimBehindControl = 黑 32%；mediaContentShadow = 0 1px 4px rgba(0,0,0,.6)
        assertEquals(0.32f, CinefinTokens.ScrimBehindControl.alpha, 0.005f)
        assertEquals(0.6f, CinefinTokens.MediaContentShadowColor.alpha, 0.01f)
        assertEquals(1f, CinefinTokens.MediaContentShadowOffsetYPx, 0.0001f)
        assertEquals(4f, CinefinTokens.MediaContentShadowBlurRadiusPx, 0.0001f)

        // 两套明暗色板都接线到 CinefinColors（组件层只允许引用 CinefinColors）
        listOf(
                CinefinThemes.colorsFor(CinefinThemes.AURORA, dark = true),
                CinefinThemes.colorsFor(CinefinThemes.LIGHT, dark = false),
            )
            .forEach { colors ->
                assertEquals(CinefinTokens.ScrimBehindControl, colors.scrimBehindControl)
                assertEquals(CinefinTokens.MediaContentShadowColor, colors.mediaContentShadow)
            }
    }

    @Test
    fun `aurora ink keeps AA contrast on selected fills`() {
        // 浅色档（2.0.1 定稿 §2 批③ 专项裁定）：#0A6B5A 对 aurora1@16% 叠 surface，须 ≥4.5:1。
        val light = CinefinThemes.colorsFor(CinefinThemes.LIGHT, dark = false)
        assertEquals(CinefinTokens.AuroraInkLight, light.auroraInk)
        val lightPill = light.aurora1.copy(alpha = 0.16f).compositeOver(light.surface)
        val lightRatio = contrast(light.auroraInk, lightPill)
        assertTrue("浅色 auroraInk 对 16% 选中底仅 $lightRatio:1", lightRatio >= 4.5)

        // 评分徽标同源：aurora1 12% 底 + auroraInk 文字，同样须 ≥4.5:1。
        val lightBadge =
            light.aurora1.copy(alpha = CinefinTokens.RatingBadgeAlpha).compositeOver(light.surface)
        val badgeRatio = contrast(light.auroraInk, lightBadge)
        assertTrue("浅色评分徽标文字对比度仅 $badgeRatio:1", badgeRatio >= 4.5)

        // 深色档：auroraInk 即 aurora1，对 aurora1@12% 叠 surface 约 8.75:1。
        val dark = CinefinThemes.colorsFor(CinefinThemes.AURORA, dark = true)
        assertEquals(dark.aurora1, dark.auroraInk)
        val darkPill = dark.aurora1.copy(alpha = 0.12f).compositeOver(dark.surface)
        val darkRatio = contrast(dark.auroraInk, darkPill)
        assertTrue("深色 auroraInk 对 12% 选中底仅 $darkRatio:1", darkRatio >= 4.5)
    }

    @Test
    fun `aurora button tone resolves to aurora fill with onAurora content`() {
        val colors = CinefinThemes.colorsFor(CinefinThemes.AURORA, dark = true)
        val resolved =
            resolveButtonColors(
                variant = CinefinButtonVariant.Filled,
                state = CinefinInteractionState.Default,
                media = mediaColorsFor(ContentDomain.Movie, colors),
                colors = colors,
                tone = CinefinButtonTone.Aurora,
            )
        assertEquals(colors.aurora1, resolved.container)
        assertEquals(colors.onAurora, resolved.content)
        assertEquals(0f, resolved.border.alpha, 0.001f)
    }

    @Test
    fun `aurora button tone falls back to media behavior on non filled variants`() {
        val colors = CinefinThemes.colorsFor(CinefinThemes.AURORA, dark = true)
        val media = mediaColorsFor(ContentDomain.Movie, colors)
        val aurora =
            resolveButtonColors(
                variant = CinefinButtonVariant.Outlined,
                state = CinefinInteractionState.Default,
                media = media,
                colors = colors,
                tone = CinefinButtonTone.Aurora,
            )
        val fallback =
            resolveButtonColors(
                variant = CinefinButtonVariant.Outlined,
                state = CinefinInteractionState.Default,
                media = media,
                colors = colors,
                tone = CinefinButtonTone.Media,
            )
        assertEquals(fallback, aurora)
    }
}
