package com.zhangwenkang.cinefin.player.core.domain.models

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * W5-fixpack：主队列移除条目时 `currentIndex` 必须同步换算。
 *
 * 回归缺陷：实时队列编辑（`MusicPlaybackControllerImpl.removeAt`）只重建 `items`，索引要等 500ms 一次的时间线同步才回写， 队列 UI
 * 会闪一帧旧索引。
 */
class MusicQueueRemoveTest {

    private fun item(id: String) =
        PlayerItem(
            name = id,
            itemId = UUID.nameUUIDFromBytes(id.toByteArray()),
            mediaSourceId = id,
            playbackPosition = 0L,
            mediaSourceUri = "file:///$id",
            thumbnailUri = null,
        )

    private fun queue(
        ids: List<String> = listOf("A", "B", "C", "D"),
        currentIndex: Int = 0,
        upNext: List<String> = emptyList(),
        upNextPlaying: String? = null,
    ) =
        MusicQueue(
            items = ids.map(::item),
            currentIndex = currentIndex,
            source = QueueSource.MANUAL,
            upNext = upNext.map(::item),
            upNextPlaying = upNextPlaying?.let(::item),
        )

    @Test
    fun `删除当前曲目之前的条目时索引同步左移且当前曲目不变`() {
        val removed = queue(currentIndex = 2).removeAt(0)

        assertEquals(listOf("B", "C", "D"), removed.items.map { it.name })
        assertEquals("C", removed.currentItem?.name)
        assertEquals(1, removed.currentIndex)
    }

    @Test
    fun `删除当前曲目之后的条目不改变索引与当前曲目`() {
        val removed = queue(currentIndex = 1).removeAt(3)

        assertEquals(listOf("A", "B", "C"), removed.items.map { it.name })
        assertEquals("B", removed.currentItem?.name)
        assertEquals(1, removed.currentIndex)
    }

    @Test
    fun `删除当前曲目时索引顺延到同位置`() {
        val removed = queue(currentIndex = 1).removeAt(1)

        assertEquals(listOf("A", "C", "D"), removed.items.map { it.name })
        assertEquals("C", removed.currentItem?.name)
        assertEquals(1, removed.currentIndex)
    }

    @Test
    fun `删除末尾的当前曲目时索引前移`() {
        val removed = queue(currentIndex = 3).removeAt(3)

        assertEquals(listOf("A", "B", "C"), removed.items.map { it.name })
        assertEquals("C", removed.currentItem?.name)
        assertEquals(2, removed.currentIndex)
    }

    @Test
    fun `删除唯一曲目后索引归零`() {
        val removed = queue(ids = listOf("A"), currentIndex = 0).removeAt(0)

        assertEquals(emptyList<String>(), removed.items.map { it.name })
        assertEquals(0, removed.currentIndex)
        assertNull(removed.currentItem)
    }

    @Test
    fun `越界删除返回自身`() {
        val original = queue()

        assertSame(original, original.removeAt(-1))
        assertSame(original, original.removeAt(4))
    }

    @Test
    fun `删除条目使旧实际随机序与种子失效`() {
        val original =
            queue()
                .copy(
                    shuffleOrder = listOf(2, 0, 3, 1),
                    shuffleSeed = 42L,
                )

        val removed = original.removeAt(0)

        assertNull(removed.shuffleOrder)
        assertNull(removed.shuffleSeed)
    }

    /** 播放器同步删项后，模型索引已经等于播放器当前下标：时间线 ticker 不应再二次换算。 */
    @Test
    fun `删除后索引即刻与播放器时间线一致不再等待 ticker`() {
        val removed = queue(currentIndex = 2).removeAt(0)

        // Exo removeMediaItem 同步左移当前下标：C 从 2 → 1
        assertEquals(1, removed.playbackIndex)
        assertNull(removed.reconcileWithTimeline(removed.playbackIndex))
    }

    /** 「接下来播放」正在播放时删除它前面的主队列条目：当前曲目仍是 P，锚点左移。 */
    @Test
    fun `删除当前曲目之前的条目时保持正在播放的接下来播放条目`() {
        val removed = queue(currentIndex = 2, upNext = listOf("X"), upNextPlaying = "P").removeAt(0)

        assertEquals("P", removed.currentItem?.name)
        assertEquals(1, removed.currentIndex)
        assertEquals(2, removed.playbackIndex)
    }
}
