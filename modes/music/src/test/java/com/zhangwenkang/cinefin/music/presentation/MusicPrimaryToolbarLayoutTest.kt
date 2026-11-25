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
    fun `offline empty music omits toolbar measurable`() {
        val visibility =
            MusicPrimaryToolbarVisibility.forState(
                offline = true,
                songCount = 0,
                detailPresent = false,
                batchMode = false,
            )

        assertFalse(visibility.hasToolbar)
        assertFalse(visibility.showsSource)
        assertFalse(visibility.showsPlayAll)
    }

    @Test
    fun `offline loading to zero never measures tabs twice`() {
        val whileLoading =
            MusicPrimaryToolbarVisibility.forState(
                offline = true,
                songCount = 0,
                detailPresent = false,
                batchMode = false,
            )
        val loadedZero =
            MusicPrimaryToolbarVisibility.forState(
                offline = true,
                songCount = 0,
                detailPresent = false,
                batchMode = false,
            )

        assertFalse(whileLoading.hasToolbar)
        assertFalse(loadedZero.hasToolbar)
    }

    @Test
    fun `offline has songs measures play all only`() {
        val visibility =
            MusicPrimaryToolbarVisibility.forState(
                offline = true,
                songCount = 1,
                detailPresent = false,
                batchMode = false,
            )

        assertTrue(visibility.hasToolbar)
        assertFalse(visibility.showsSource)
        assertTrue(visibility.showsPlayAll)
    }

    @Test
    fun `online empty keeps source toolbar`() {
        val visibility =
            MusicPrimaryToolbarVisibility.forState(
                offline = false,
                songCount = 0,
                detailPresent = false,
                batchMode = false,
            )

        assertTrue(visibility.hasToolbar)
        assertTrue(visibility.showsSource)
        assertFalse(visibility.showsPlayAll)
    }

    @Test
    fun `batch mode has no toolbar measurable`() {
        val visibility =
            MusicPrimaryToolbarVisibility.forState(
                offline = false,
                songCount = 1,
                detailPresent = false,
                batchMode = true,
            )

        assertFalse(visibility.hasToolbar)
        assertFalse(visibility.showsSource)
        assertFalse(visibility.showsPlayAll)
    }
}
