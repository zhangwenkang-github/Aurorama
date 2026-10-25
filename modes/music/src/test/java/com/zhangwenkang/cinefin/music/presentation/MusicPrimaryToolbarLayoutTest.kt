package com.zhangwenkang.cinefin.music.presentation

import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicPrimaryToolbarLayoutTest {

    private val density = Density(density = 2f, fontScale = 1f)

    @Test
    fun `primary measure policy converts production gap at density`() {
        val tabs = MusicMeasuredBox(widthPx = 1200, heightPx = 88)
        val plan =
            MusicPrimaryToolbarLayout.measurePrimary(
                tabs = tabs,
                toolbar = MusicMeasuredBox(widthPx = 1200, heightPx = 96),
                density = density,
            )
        val gapDp = (plan.toolbarPlacement!!.yPx - tabs.heightPx).toFloat() / density.density

        assertEquals(1200, plan.widthPx)
        assertEquals(208, plan.heightPx)
        assertEquals(MusicPlacement(xPx = 0, yPx = 0), plan.tabsPlacement)
        assertEquals(MusicPlacement(xPx = 0, yPx = 112), plan.toolbarPlacement)
        assertEquals(12f, gapDp, 0f)
    }

    @Test
    fun `primary measure policy omits production gap when toolbar is absent`() {
        val plan =
            MusicPrimaryToolbarLayout.measurePrimary(
                tabs = MusicMeasuredBox(widthPx = 1200, heightPx = 88),
                toolbar = null,
                density = density,
            )

        assertEquals(88, plan.heightPx)
        assertNull(plan.toolbarPlacement)
    }

    @Test
    fun `play all measure policy converts production minimum at density`() {
        val plan =
            MusicPrimaryToolbarLayout.measurePlayAll(
                content = MusicMeasuredBox(widthPx = 200, heightPx = 76),
                density = density,
            )
        val interactiveHeightDp = plan.heightPx.toFloat() / density.density

        assertEquals(200, plan.widthPx)
        assertEquals(96, plan.heightPx)
        assertEquals(MusicPlacement(xPx = 0, yPx = 10), plan.contentPlacement)
        assertEquals(48f, interactiveHeightDp, 0f)
        assertEquals(MusicInteractionRole.BUTTON, plan.interaction.role)
        assertEquals("播放全部", plan.interaction.semanticsLabel)
    }

    @Test
    fun `play all measure policy preserves content taller than production minimum`() {
        val plan =
            MusicPrimaryToolbarLayout.measurePlayAll(
                content = MusicMeasuredBox(widthPx = 240, heightPx = 104),
                density = density,
            )

        assertEquals(104, plan.heightPx)
        assertEquals(MusicPlacement(xPx = 0, yPx = 0), plan.contentPlacement)
    }

    @Test
    fun `play all visual layer accepts no click callback`() {
        val owner = Class.forName("com.zhangwenkang.cinefin.music.presentation.MusicModeScreenKt")
        val visualMethods =
            owner.declaredMethods.filter { method -> method.name == "MusicPlayAllVisualButton" }

        assertTrue("visual button composable must exist", visualMethods.isNotEmpty())
        assertFalse(
            "visual layer must not own another click callback",
            visualMethods
                .flatMap { method -> method.parameterTypes.asList() }
                .any { type -> Function0::class.java.isAssignableFrom(type) },
        )
    }
}
