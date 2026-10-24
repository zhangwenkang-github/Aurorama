package com.zhangwenkang.cinefin.player.local.domain

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ShuffleOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@UnstableApi
class MusicShuffleOrderTest {

    @Test
    fun `reads the exact default shuffle order`() {
        val order = ShuffleOrder.DefaultShuffleOrder(intArrayOf(2, 0, 1), 17L)

        assertEquals(listOf(2, 0, 1), readShuffleOrder(order, itemCount = 3))
    }

    @Test
    fun `rejects a player order whose length does not match the queue`() {
        val order = ShuffleOrder.DefaultShuffleOrder(intArrayOf(1, 0), 17L)

        assertNull(readShuffleOrder(order, itemCount = 3))
    }

    @Test
    fun `only a complete zero based permutation is valid`() {
        assertTrue(isValidShuffleOrder(listOf(2, 0, 1), itemCount = 3))
        assertFalse(isValidShuffleOrder(null, itemCount = 3))
        assertFalse(isValidShuffleOrder(emptyList(), itemCount = 3))
        assertFalse(isValidShuffleOrder(listOf(0, 1, 1), itemCount = 3))
        assertFalse(isValidShuffleOrder(listOf(0, 1, 3), itemCount = 3))
        assertFalse(isValidShuffleOrder(listOf(0, 1), itemCount = 3))
    }
}
