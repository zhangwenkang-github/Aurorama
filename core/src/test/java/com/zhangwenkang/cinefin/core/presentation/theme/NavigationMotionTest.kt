package com.zhangwenkang.cinefin.core.presentation.theme

import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.TweenSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W3 导航动效契约：对应 `docs/V2_W3_TASKS.md`「W3 导航决策定稿」的 NAV-07~10 数值。
 *
 * 定稿值漂移（例如把抽屉关回 256ms 的 M3 默认）时这里先红，避免只靠真机手感兜底。
 */
class NavigationMotionTest {
    @Test
    fun `navigation motion matches the W3 nav spec`() {
        assertEquals(120, CinefinNavigationMotion.PageTransitionMillis)
        assertEquals(8f, CinefinNavigationMotion.PageTransitionOffset.value)
        assertEquals(200, CinefinNavigationMotion.DrawerOpenMillis)
        assertEquals(160, CinefinNavigationMotion.DrawerCloseMillis)
        assertEquals(50, CinefinNavigationMotion.ReducedMotionMillis)
    }

    @Test
    fun `drawer specs use emphasized decelerate and snap under reduce motion`() {
        val open = CinefinNavigationMotion.drawerOpenSpec(reduceMotion = false) as TweenSpec
        val close = CinefinNavigationMotion.drawerCloseSpec(reduceMotion = false) as TweenSpec
        val settle = CinefinNavigationMotion.drawerSettleSpec(reduceMotion = false) as TweenSpec

        assertEquals(200, open.durationMillis)
        assertEquals(160, close.durationMillis)
        assertEquals(200, settle.durationMillis)
        assertEquals(CinefinNavigationMotion.EmphasizedDecelerate, open.easing)
        assertEquals(CinefinNavigationMotion.EmphasizedDecelerate, close.easing)

        assertTrue(CinefinNavigationMotion.drawerOpenSpec(reduceMotion = true) is SnapSpec)
        assertTrue(CinefinNavigationMotion.drawerCloseSpec(reduceMotion = true) is SnapSpec)
        assertTrue(CinefinNavigationMotion.drawerSettleSpec(reduceMotion = true) is SnapSpec)
    }

    @Test
    fun `emphasized decelerate is monotonic without overshoot`() {
        var previous = 0f
        for (step in 0..20) {
            val value = CinefinNavigationMotion.EmphasizedDecelerate.transform(step / 20f)
            assertTrue("value $value out of range at step $step", value in 0f..1f)
            assertTrue("curve regressed at step $step", value >= previous)
            previous = value
        }
    }
}
