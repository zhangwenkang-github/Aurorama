package com.zhangwenkang.cinefin.core.presentation.theme

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonTone
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonVariant
import com.zhangwenkang.cinefin.core.presentation.components.CinefinInteractionState
import com.zhangwenkang.cinefin.core.presentation.components.resolveButtonColors
import org.junit.Assert.assertEquals
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

    @Test
    fun `dark neutral tokens match design system`() {
        assertHex(0xFF151A21, CinefinTokens.SurfaceDark, "Surface")
        assertHex(0xFF10141A, CinefinTokens.SurfaceDimDark, "SurfaceDim")
        assertHex(0xFF1B212B, CinefinTokens.SurfaceContainerDark, "SurfaceContainer")
        assertHex(0xFF222A36, CinefinTokens.SurfaceContainerHighDark, "SurfaceContainerHigh")
        assertHex(0xFF2A3340, CinefinTokens.SurfaceContainerHighestDark, "SurfaceContainerHighest")
        assertHex(0xFFF4F1EA, CinefinTokens.OnSurfaceDark, "OnSurface")
        assertHex(0xFFA7B0BD, CinefinTokens.OnSurfaceVariantDark, "OnSurfaceVariant")
        assertHex(0xFF6E7887, CinefinTokens.OnSurfaceFaintDark, "OnSurfaceFaint")
        assertHex(0xFF2C3542, CinefinTokens.OutlineDark, "Outline")
        assertHex(0xFF232B36, CinefinTokens.OutlineVariantDark, "OutlineVariant")
        assertHex(0xFFF4F1EA, CinefinTokens.InverseSurfaceDark, "InverseSurface")
        assertHex(0xFF14181F, CinefinTokens.InverseOnSurfaceDark, "InverseOnSurface")
        assertHex(0xFFE87C6E, CinefinTokens.Error, "Error")
        assertHex(0xFF1A0F0C, CinefinTokens.OnError, "OnError")
    }

    @Test
    fun `light neutral tokens match design system`() {
        assertHex(0xFFF5F3EE, CinefinTokens.SurfaceLight, "Light Surface")
        assertHex(0xFFFFFFFF, CinefinTokens.SurfaceContainerLight, "Light SurfaceContainer")
        assertHex(0xFF1B212B, CinefinTokens.OnSurfaceLight, "Light OnSurface")
        assertHex(0xFF59616E, CinefinTokens.OnSurfaceVariantLight, "Light OnSurfaceVariant")
        assertHex(0xFFD8D3C8, CinefinTokens.OutlineLight, "Light Outline")
        assertHex(0xFFE7E2D8, CinefinTokens.OutlineVariantLight, "Light OutlineVariant")
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
    }

    @Test
    fun `media container composition matches published tokens`() {
        val surface = CinefinColorsDark.surfaceContainer
        listOf(MediaFilm, MediaMusic, MediaBook).forEach { media ->
            val composed = media.containerOver(surface)
            assertTrue(
                "container 合成值与 token 偏差过大",
                kotlin.math.abs(composed.red - media.container.red) <= 2f / 255f &&
                    kotlin.math.abs(composed.green - media.container.green) <= 2f / 255f &&
                    kotlin.math.abs(composed.blue - media.container.blue) <= 2f / 255f,
            )
        }
    }

    @Test
    fun `material color scheme maps current domain media color`() {
        val movieDark = cinefinColorScheme(ContentDomain.Movie, dark = true)
        assertEquals(MediaFilm.base, movieDark.primary)
        assertEquals(MediaFilm.onBase, movieDark.onPrimary)
        assertEquals(MediaFilm.container, movieDark.primaryContainer)
        assertEquals(MediaFilm.bright, movieDark.onPrimaryContainer)
        assertEquals(CinefinColorsDark.surface, movieDark.surface)
        assertEquals(CinefinColorsDark.surfaceContainer, movieDark.surfaceContainer)
        assertEquals(CinefinTokens.MediaMusicBase, movieDark.secondary)
        assertEquals(CinefinTokens.MediaBookBase, movieDark.tertiary)

        val bookLight = cinefinColorScheme(ContentDomain.Book, dark = false)
        assertEquals(MediaBook.base, bookLight.primary)
        assertEquals(MediaBook.onBase, bookLight.onPrimary)
        assertEquals(MediaFilm.base, bookLight.secondary)
        assertEquals(MediaMusic.base, bookLight.tertiary)
        assertEquals(CinefinColorsLight.surface, bookLight.surface)
    }

    @Test
    fun `shape tokens match 28 22 16 12 8 4`() {
        assertEquals(CornerSize(28.dp), CinefinShapes.Xl.topStart)
        assertEquals(CornerSize(22.dp), CinefinShapes.Lg.topStart)
        assertEquals(CornerSize(16.dp), CinefinShapes.Md.topStart)
        assertEquals(CornerSize(12.dp), CinefinShapes.Sm.topStart)
        assertEquals(CornerSize(8.dp), CinefinShapes.Xs.topStart)
        assertEquals(CornerSize(4.dp), CinefinShapes.TwoXs.topStart)
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
    fun `lumen tokens match s1 direction a palette`() {
        // 依据 docs/design/s1-direction-a/README.md §2 与 _src/direction-a.html 的 CSS 变量（用户 2026-10-01
        // 批准）
        assertHex(0xFF08090C, LumenTokens.Background, "Lumen Background")
        assertHex(0xFF111319, LumenTokens.Panel, "Lumen Panel")
        assertHex(0xFF171A21, LumenTokens.PanelElevated, "Lumen PanelElevated")
        assertHex(0xFFF2F5F9, LumenTokens.Text, "Lumen Text")
        assertHex(0xFF98A2B3, LumenTokens.TextSecondary, "Lumen TextSecondary")
        assertHex(0xFF6B7483, LumenTokens.TextFaint, "Lumen TextFaint")
        assertHex(0xFF5CE1D2, LumenTokens.Accent, "Lumen Accent")
        assertHex(0xFF7CC4FF, LumenTokens.AccentSecondary, "Lumen AccentSecondary")
        assertHex(0xFF0A0C11, LumenTokens.OnPrimary, "Lumen OnPrimary")
        assertEquals(0.085f, LumenTokens.LineAlpha, 0.001f)
        assertEquals(0.05f, LumenTokens.LineSoftAlpha, 0.001f)
        assertEquals(0.07f, LumenTokens.GhostAlpha, 0.001f)
        assertEquals(0.14f, LumenTokens.ProgressTrackAlpha, 0.001f)
    }

    @Test
    fun `lumen cover maps prism semantics to direction a values`() {
        assertHex(0xFF08090C, LumenCinefinColorsDark.surface, "Lumen surface")
        assertHex(0xFF111319, LumenCinefinColorsDark.surfaceContainer, "Lumen container")
        assertHex(
            0xFF171A21,
            LumenCinefinColorsDark.surfaceContainerHigh,
            "Lumen container high",
        )
        assertHex(0xFFF2F5F9, LumenCinefinColorsDark.onSurface, "Lumen onSurface")
        assertHex(0xFFF2F5F9, LumenColorsDark.primaryButton, "Lumen primary button")
        assertHex(0xFF0A0C11, LumenColorsDark.onPrimary, "Lumen onPrimary")
        // A 稿唯一强调色：极光青同时承担 base / bright（眉标、进度、焦点）
        assertHex(0xFF5CE1D2, LumenMediaColors.base, "Lumen media base")
        assertHex(0xFF5CE1D2, LumenMediaColors.bright, "Lumen media bright")
    }

    @Test
    fun `inverse button tone resolves to month white with dark content`() {
        val resolved =
            resolveButtonColors(
                variant = CinefinButtonVariant.Filled,
                state = CinefinInteractionState.Default,
                media = LumenMediaColors,
                colors = LumenCinefinColorsDark,
                tone = CinefinButtonTone.Inverse,
            )
        assertHex(0xFFF2F5F9, resolved.container, "Inverse container")
        assertHex(0xFF0A0C11, resolved.content, "Inverse content")
        assertEquals(0f, resolved.border.alpha, 0.001f)
    }
}
