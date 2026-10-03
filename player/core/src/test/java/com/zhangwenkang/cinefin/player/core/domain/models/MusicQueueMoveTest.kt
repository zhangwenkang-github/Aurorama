package com.zhangwenkang.cinefin.player.core.domain.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** W58：队列追加到队尾（批量入队）与既有拖拽排序语义。 */
class MusicQueueMoveTest {

    private fun queue(size: Int, currentIndex: Int = 0): MusicQueue =
        MusicQueue(
            items = (0 until size).map { index -> item("$index") },
            currentIndex = currentIndex,
            source = QueueSource.MANUAL,
        )

    private fun item(id: String) =
        PlayerItem(
            name = id,
            itemId = java.util.UUID.nameUUIDFromBytes(id.toByteArray()),
            mediaSourceId = id,
            playbackPosition = 0L,
            mediaSourceUri = "file:///$id",
            thumbnailUri = null,
        )

    @Test
    fun `move to items size appends to the end`() {
        val moved = queue(3).move(fromIndex = 1, toIndex = 3)
        assertEquals(listOf("0", "2", "1"), moved.items.map { it.name })
        // 当前曲目仍是第 0 首
        assertEquals(0, moved.currentIndex)
    }

    @Test
    fun `move to items size keeps the current track identity`() {
        val moved = queue(3, currentIndex = 1).move(fromIndex = 2, toIndex = 3)
        assertEquals(listOf("0", "1", "2"), moved.items.map { it.name })
        assertEquals("1", moved.currentItem?.name)
    }

    @Test
    fun `moving the current track updates the index to the end`() {
        val moved = queue(3, currentIndex = 0).move(fromIndex = 0, toIndex = 3)
        assertEquals(listOf("1", "2", "0"), moved.items.map { it.name })
        assertEquals(2, moved.currentIndex)
    }

    @Test
    fun `out of range targets are ignored`() {
        val original = queue(3)
        assertSame(original, original.move(fromIndex = 0, toIndex = 4))
        assertSame(original, original.move(fromIndex = 0, toIndex = -1))
        assertSame(original, original.move(fromIndex = 3, toIndex = 1))
        assertSame(original, original.move(fromIndex = 1, toIndex = 1))
    }

    @Test
    fun `regular reorder still shifts forward correctly`() {
        val moved = queue(4).move(fromIndex = 0, toIndex = 2)
        assertEquals(listOf("1", "2", "0", "3"), moved.items.map { it.name })
    }
}
