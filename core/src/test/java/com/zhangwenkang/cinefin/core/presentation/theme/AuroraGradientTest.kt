package com.zhangwenkang.cinefin.core.presentation.theme

import androidx.compose.ui.geometry.Size
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 极光渐变几何单测（2.0.1 定稿 §2 批②）：钉住 CSS `linear-gradient(100deg)` 的渐变线算法。
 *
 * 该算法从 `CinefinNavigation` 的底栏指示条提到 theme 层后由 CTA / 进度 / 选中共用，角度一旦漂移会影响全 App 渐变方向，故单独钉死。
 */
class AuroraGradientTest {

    @Test
    fun `gradient line follows css 100deg`() {
        val size = Size(20f, 3f)
        val (start, end) = auroraGradientEnds(size)

        // 起止点以构件中心对称
        assertEquals(10f, (start.x + end.x) / 2f, 1e-3f)
        assertEquals(1.5f, (start.y + end.y) / 2f, 1e-3f)

        // 100° 的方向向量 = (sin 100°, -cos 100°) ≈ (0.985, +0.174)：屏幕坐标 y 向下，故「右略下」
        assertTrue("100° 渐变应自左上流向右下", end.x > start.x)
        assertTrue("100° 渐变应自左上流向右下", end.y > start.y)

        // 渐变线长度 = |w·sin θ| + |h·cos θ|
        val radians = Math.toRadians(100.0)
        val expected = (20.0 * abs(sin(radians)) + 3.0 * abs(cos(radians))).toFloat()
        val actual = hypot(end.x - start.x, end.y - start.y)
        assertEquals(expected, actual, 1e-3f)
    }

    @Test
    fun `gradient line covers a tall box along the 100deg direction`() {
        val size = Size(100f, 100f)
        val (start, end) = auroraGradientEnds(size)

        // 正方形：渐变线长度 = w·(|sin 100°| + |cos 100°|) ≈ 115.8，长于任一边长，保证渐变铺满整块
        val radians = Math.toRadians(100.0)
        val expected = (100.0 * (abs(sin(radians)) + abs(cos(radians)))).toFloat()
        val length = hypot(end.x - start.x, end.y - start.y)
        assertEquals(expected, length, 1e-3f)
        assertTrue("渐变线必须覆盖整个盒子，实际 $length", length > size.width)
    }
}
