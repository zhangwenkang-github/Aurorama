package com.zhangwenkang.cinefin.core.presentation.components

import androidx.compose.material3.DrawerValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** W2-T11（需求 #12）：抽屉呼出热区门控 / 方向仲裁 / 松手落点判定的纯函数单测。 */
class CinefinDrawerGestureGateTest {

    private val width = 1080f
    private val edgeStart = 63f // 系统返回手势保留区（≈24dp @ 2.625x）
    private val edge = 74f // ≈ 28dp

    private fun allowed(
        startX: Float,
        drawerVisible: Boolean = false,
        rtl: Boolean = false,
    ): Boolean =
        drawerDragAllowedAt(
            startX = startX,
            containerWidth = width,
            edgeStartPx = edgeStart,
            edgeWidthPx = edge,
            drawerVisible = drawerVisible,
            rtl = rtl,
        )

    @Test
    fun closedDrawerAllowsSwipeFromLeftEdgeBandOnly() {
        // 热区内（含两侧边界）
        assertTrue(allowed(edgeStart))
        assertTrue(allowed(edgeStart + edge / 2f))
        assertTrue(allowed(edgeStart + edge))
        // 系统保留区（被系统返回手势占住）不再算热区
        assertFalse(allowed(0f))
        assertFalse(allowed(8f))
        assertFalse(allowed(edgeStart - 1f))
        // 热区外：中段、右缘都不能呼出抽屉
        assertFalse(allowed(edgeStart + edge + 1f))
        assertFalse(allowed(540f))
        assertFalse(allowed(width - 1f))
    }

    @Test
    fun closedDrawerAllowsMirroredBandInRtl() {
        assertTrue(allowed(width - edgeStart, rtl = true))
        assertTrue(allowed(width - edgeStart - edge, rtl = true))
        assertFalse(allowed(width - 1f, rtl = true))
        assertFalse(allowed(width - edgeStart - edge - 1f, rtl = true))
        assertFalse(allowed(540f, rtl = true))
    }

    @Test
    fun openDrawerCanBeDraggedFromAnywhere() {
        assertTrue(allowed(540f, drawerVisible = true))
        assertTrue(allowed(0f, drawerVisible = true))
        assertTrue(allowed(width - 1f, drawerVisible = true, rtl = true))
    }

    @Test
    fun degenerateBoundsNeverGateIn() {
        assertFalse(
            drawerDragAllowedAt(
                startX = edgeStart,
                containerWidth = 0f,
                edgeStartPx = edgeStart,
                edgeWidthPx = edge,
                drawerVisible = false,
                rtl = false,
            )
        )
        assertFalse(
            drawerDragAllowedAt(
                startX = edgeStart,
                containerWidth = width,
                edgeStartPx = edgeStart,
                edgeWidthPx = 0f,
                drawerVisible = false,
                rtl = false,
            )
        )
        assertFalse(
            drawerDragAllowedAt(
                startX = Float.NaN,
                containerWidth = width,
                edgeStartPx = edgeStart,
                edgeWidthPx = edge,
                drawerVisible = false,
                rtl = false,
            )
        )
    }

    @Test
    fun horizontalDominanceClaimsVerticalDominanceYields() {
        val slop = 21f
        // 横向过 slop 且明显大于纵向 → 抽屉接管
        assertEquals(DrawerDragVerdict.Claim, drawerDragVerdict(60f, 4f, slop))
        // 纵向过 slop → 让给页面滚动（页面上下滑动不可能呼出抽屉）
        assertEquals(DrawerDragVerdict.Yield, drawerDragVerdict(3f, 55f, slop))
        // 斜向但仍然纵向占优 → 让给页面
        assertEquals(DrawerDragVerdict.Yield, drawerDragVerdict(40f, 70f, slop))
        // 位移未过 slop → 继续观察
        assertEquals(DrawerDragVerdict.Pending, drawerDragVerdict(10f, 12f, slop))
        assertEquals(DrawerDragVerdict.Pending, drawerDragVerdict(-10f, 5f, slop))
        // 反向横向（往右往左）同样按横向接管
        assertEquals(DrawerDragVerdict.Claim, drawerDragVerdict(-80f, 6f, slop))
    }

    @Test
    fun dragReleaseVelocityDecidesBeforePosition() {
        val closed = -600f
        val open = 0f
        assertEquals(
            DrawerValue.Open,
            target(offset = -500f, closed = closed, open = open, velocity = 2000f),
        )
        assertEquals(
            DrawerValue.Closed,
            target(offset = -100f, closed = closed, open = open, velocity = -2000f),
        )
    }

    @Test
    fun dragReleaseFallsBackToHalfwayPosition() {
        val closed = -600f
        val open = 0f
        assertEquals(
            DrawerValue.Open,
            target(offset = -300f, closed = closed, open = open, velocity = 0f),
        )
        assertEquals(
            DrawerValue.Closed,
            target(offset = -301f, closed = closed, open = open, velocity = 0f),
        )
        // 反向锚点（RTL 镜像）：朝开方向的速度同样打开
        assertEquals(
            DrawerValue.Open,
            target(offset = 100f, closed = 600f, open = 0f, velocity = -2000f),
        )
        // 锚点重合（未测量完成）不误开
        assertEquals(
            DrawerValue.Closed,
            target(offset = 0f, closed = 0f, open = 0f, velocity = 2000f),
        )
    }

    private fun target(
        offset: Float,
        closed: Float,
        open: Float,
        velocity: Float,
    ): DrawerValue =
        drawerTargetAfterDrag(
            offset = offset,
            closedOffset = closed,
            openOffset = open,
            velocity = velocity,
            velocityThresholdPx = 1100f,
        )
}
