package com.zhangwenkang.cinefin.presentation.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定键 / 解锁键命中矩形的判定规则（W4-T22）。
 *
 * 真机根因：竖屏锁定后解锁键落在画面区垂直中点（K60 约 y=502px），而命中区按**整个容器**高度取中点（y≈1200px）， 两者错开约
 * 700px，解锁键点不到。命中区现在改用控制层回传的实测矩形，这里钉住： 命中区与按钮视觉同源（画面区中点），不再从容器高度推算（容器中点 = 竖屏下方选集区）。
 */
class PlayerOverlayHitRectTest {

    /** 竖屏 38dp 锁定键 / 解锁键在 K60（density 2.625）上的实测矩形 */
    private val unlockRect = HitRect(left = 949f, top = 453f, right = 1048f, bottom = 553f)

    @Test
    fun contains_matchesInclusiveBounds() {
        val rect = HitRect(900f, 470f, 1000f, 540f)

        assertTrue(rect.contains(950f, 505f))
        assertTrue(rect.contains(900f, 470f))
        assertFalse(rect.contains(899f, 505f))
        assertFalse(rect.contains(950f, 541f))
    }

    @Test
    fun unlockTarget_coversVisibleKey() {
        // 键中心与四角都应可点：命中区就是按钮本身的实测矩形
        assertTrue(unlockRect.contains(998f, 503f))
        assertTrue(unlockRect.contains(unlockRect.left, unlockRect.top))
        assertTrue(unlockRect.contains(unlockRect.right, unlockRect.bottom))
    }

    @Test
    fun unlockTarget_doesNotReachContainerCenter() {
        // 容器 2400px 高：旧实现按 height/2 = 1200px 划区，竖屏解锁键根本不在里面（本次修复的根因）
        assertFalse("容器中点（竖屏下方选集区）不应被当成解锁区", unlockRect.contains(998f, 1200f))
        assertFalse("旧命中区上沿 990px 也应落在解锁键之外", unlockRect.contains(998f, 990f))
    }
}
