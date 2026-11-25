package com.zhangwenkang.cinefin.music.presentation

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

/** W3-T17：点单曲 → 队列计划（追加队尾并起播 / 已在队列则跳位）。 */
class MusicPlaySongQueueRulesTest {

    private val a = UUID.randomUUID()
    private val b = UUID.randomUUID()
    private val c = UUID.randomUUID()
    private val d = UUID.randomUUID()

    @Test
    fun `clicked song already queued jumps without duplicating`() {
        val plan =
            musicPlaySongPlan(
                existingItemIds = listOf(a, b, c),
                clickedItemId = b,
                tailItemIds = listOf(b),
            )

        assertEquals(MusicPlaySongPlan.JumpToExisting(1), plan)
    }

    @Test
    fun `empty queue appends clicked song and starts at index zero`() {
        val plan =
            musicPlaySongPlan(
                existingItemIds = emptyList(),
                clickedItemId = a,
                tailItemIds = listOf(a),
            )

        assertEquals(
            MusicPlaySongPlan.AppendAndPlay(enqueueItemIds = listOf(a), targetIndex = 0),
            plan,
        )
    }

    @Test
    fun `new song appends to queue tail and starts at the appended index`() {
        val plan =
            musicPlaySongPlan(
                existingItemIds = listOf(a, b),
                clickedItemId = d,
                tailItemIds = listOf(d),
            )

        assertEquals(
            MusicPlaySongPlan.AppendAndPlay(enqueueItemIds = listOf(d), targetIndex = 2),
            plan,
        )
    }

    @Test
    fun `detail tail enqueues list order from clicked song and starts at first appended item`() {
        val plan =
            musicPlaySongPlan(
                existingItemIds = listOf(a),
                clickedItemId = c,
                tailItemIds = listOf(c, d),
            )

        assertEquals(
            MusicPlaySongPlan.AppendAndPlay(enqueueItemIds = listOf(c, d), targetIndex = 1),
            plan,
        )
    }

    @Test
    fun `tail without clicked song gets clicked prepended`() {
        val plan =
            musicPlaySongPlan(
                existingItemIds = listOf(a),
                clickedItemId = b,
                tailItemIds = listOf(c, d),
            )

        assertEquals(
            MusicPlaySongPlan.AppendAndPlay(enqueueItemIds = listOf(b, c, d), targetIndex = 1),
            plan,
        )
    }

    @Test
    fun `clicked song in tail uses its own position for the start index`() {
        // 详情层理论上总把被点曲放在尾段首位；若调用方传入错位尾段，仍按被点曲在批次中的真实位置起播。
        val plan =
            musicPlaySongPlan(
                existingItemIds = listOf(a),
                clickedItemId = b,
                tailItemIds = listOf(a, b, c),
            )

        assertEquals(
            MusicPlaySongPlan.AppendAndPlay(enqueueItemIds = listOf(a, b, c), targetIndex = 2),
            plan,
        )
    }
}
