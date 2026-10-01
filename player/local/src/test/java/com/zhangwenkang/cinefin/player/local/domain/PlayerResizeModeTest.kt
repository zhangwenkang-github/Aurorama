package com.zhangwenkang.cinefin.player.local.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerResizeModeTest {

    @Test
    fun `fit keeps aspect without panscan`() {
        val props = mpvResizeProperties(PlayerResizeModes.FIT)

        assertTrue(props.keepAspect)
        assertFalse(props.panscan)
    }

    @Test
    fun `zoom keeps aspect and panscans to fill`() {
        val props = mpvResizeProperties(PlayerResizeModes.ZOOM)

        assertTrue(props.keepAspect)
        assertTrue(props.panscan)
    }

    @Test
    fun `fill drops aspect ratio to stretch`() {
        val props = mpvResizeProperties(PlayerResizeModes.FILL)

        assertFalse(props.keepAspect)
        assertFalse(props.panscan)
    }

    @Test
    fun `unknown mode falls back to fit without residue`() {
        val props = mpvResizeProperties(7)

        assertEquals(MpvResizeProperties(keepAspect = true, panscan = false), props)
    }

    @Test
    fun `resize mode values match media3 contract`() {
        // media3 AspectRatioFrameLayout：FIT = 0 / FILL = 3 / ZOOM = 4，改了这里就要同步 Activity
        assertEquals(0, PlayerResizeModes.FIT)
        assertEquals(3, PlayerResizeModes.FILL)
        assertEquals(4, PlayerResizeModes.ZOOM)
    }
}
