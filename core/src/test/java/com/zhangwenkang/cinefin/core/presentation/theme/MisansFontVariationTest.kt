package com.zhangwenkang.cinefin.core.presentation.theme

import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Test

/** MiSans 可变字体轴字符串（`Typeface.Builder.setFontVariationSettings` 入参）格式回归。 */
class MisansFontVariationTest {
    private val density = Density(1f, 1f)

    @Test
    fun `四档字重映射为 wght 轴字符串`() {
        val strings =
            listOf(400, 500, 600, 700).map { weight ->
                FontVariation.Settings(FontVariation.weight(weight))
                    .toAndroidVariationString(density, weightAdjustment = 0f)
            }

        assertEquals(
            listOf("'wght' 400.0", "'wght' 500.0", "'wght' 600.0", "'wght' 700.0"),
            strings,
        )
    }

    @Test
    fun `系统字体粗细调整叠加到 wght 轴并夹到上限`() {
        val bold = FontVariation.Settings(FontVariation.weight(700))

        assertEquals("'wght' 750.0", bold.toAndroidVariationString(density, weightAdjustment = 50f))
        assertEquals(
            "'wght' 1000.0",
            bold.toAndroidVariationString(density, weightAdjustment = 400f),
        )
    }
}
