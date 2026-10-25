package com.zhangwenkang.cinefin.music.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicPrimaryToolbarLayoutTest {

    @Test
    fun `primary measure policy places toolbar twelve dp below measured tabs`() {
        val plan =
            MusicPrimaryToolbarLayout.measurePrimary(
                tabs = MusicMeasuredBox(widthPx = 600, heightPx = 44),
                toolbar = MusicMeasuredBox(widthPx = 600, heightPx = 48),
                gapPx = 12,
            )

        assertEquals(600, plan.widthPx)
        assertEquals(104, plan.heightPx)
        assertEquals(MusicPlacement(xPx = 0, yPx = 0), plan.tabsPlacement)
        assertEquals(MusicPlacement(xPx = 0, yPx = 56), plan.toolbarPlacement)
    }

    @Test
    fun `primary measure policy omits gap when toolbar is absent`() {
        val plan =
            MusicPrimaryToolbarLayout.measurePrimary(
                tabs = MusicMeasuredBox(widthPx = 600, heightPx = 44),
                toolbar = null,
                gapPx = 12,
            )

        assertEquals(44, plan.heightPx)
        assertNull(plan.toolbarPlacement)
    }

    @Test
    fun `play all measure policy provides forty eight dp interactive bounds`() {
        val plan =
            MusicPrimaryToolbarLayout.measurePlayAll(
                content = MusicMeasuredBox(widthPx = 100, heightPx = 38),
                minimumPx = 48,
            )

        assertEquals(100, plan.widthPx)
        assertEquals(48, plan.heightPx)
        assertEquals(MusicPlacement(xPx = 0, yPx = 5), plan.contentPlacement)
        assertEquals(MusicInteractionRole.BUTTON, plan.interaction.role)
        assertEquals("播放全部", plan.interaction.semanticsLabel)
        assertTrue(plan.interaction.singleSemanticsNode)
    }

    @Test
    fun `play all measure policy preserves content taller than minimum`() {
        val plan =
            MusicPrimaryToolbarLayout.measurePlayAll(
                content = MusicMeasuredBox(widthPx = 120, heightPx = 52),
                minimumPx = 48,
            )

        assertEquals(52, plan.heightPx)
        assertEquals(MusicPlacement(xPx = 0, yPx = 0), plan.contentPlacement)
    }
}
