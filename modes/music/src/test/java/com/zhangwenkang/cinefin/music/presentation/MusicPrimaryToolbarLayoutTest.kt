package com.zhangwenkang.cinefin.music.presentation

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicPrimaryToolbarLayoutTest {

    @Test
    fun `primary toolbar starts twelve dp below tab filter row`() {
        val layout =
            musicPrimaryToolbarGeometry(
                tabsHeightPx = 44,
                toolbarHeightPx = 48,
                gapPx = MusicPrimaryToolbarLayout.tabsToToolbarGap.roundToPxAtOneDensity(),
            )

        assertEquals(12.dp, MusicPrimaryToolbarLayout.tabsToToolbarGap)
        assertEquals(56, layout.toolbarTopPx)
        assertEquals(104, layout.totalHeightPx)
    }

    @Test
    fun `play all clickable height is at least forty eight dp`() {
        val minimumPx =
            MusicPrimaryToolbarLayout.playAllMinInteractiveHeight.roundToPxAtOneDensity()

        assertEquals(48.dp, MusicPrimaryToolbarLayout.playAllMinInteractiveHeight)
        assertEquals(48, musicPlayAllInteractiveHeight(contentHeightPx = 38, minimumPx))
        assertTrue(musicPlayAllInteractiveHeight(contentHeightPx = 52, minimumPx) >= minimumPx)
    }

    private fun androidx.compose.ui.unit.Dp.roundToPxAtOneDensity(): Int = value.toInt()
}
