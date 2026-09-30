package com.zhangwenkang.cinefin.player.local.domain

import com.zhangwenkang.cinefin.player.core.domain.models.MusicQueue
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerItem
import com.zhangwenkang.cinefin.player.core.domain.models.QueueSource
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** 队列纯函数（拖拽排序 / 下一首播放）单测（W2 R2，MU-3）。 */
class MusicQueueTest {

    private fun item(name: String) =
        PlayerItem(
            name = name,
            itemId = UUID.nameUUIDFromBytes(name.toByteArray()),
            mediaSourceId = "source-$name",
            playbackPosition = 0L,
        )

    private fun queueOf(vararg names: String, currentIndex: Int = 0) =
        MusicQueue(
            items = names.map(::item),
            currentIndex = currentIndex,
            source = QueueSource.ALBUM,
            sourceId = "album",
        )

    @Test
    fun `拖拽排序保持当前曲目不变`() {
        val queue = queueOf("A", "B", "C", "D", currentIndex = 2)

        val moved = queue.move(0, 3)

        assertEquals(listOf("B", "C", "D", "A"), moved.items.map { it.name })
        // 当前曲目 C 从索引 2 挪到 1，播放内容不变
        assertEquals("C", moved.currentItem?.name)
        assertEquals(1, moved.currentIndex)
    }

    @Test
    fun `拖拽当前曲目时索引跟随`() {
        val queue = queueOf("A", "B", "C", currentIndex = 1)

        val moved = queue.move(1, 2)

        assertEquals(listOf("A", "C", "B"), moved.items.map { it.name })
        assertEquals(2, moved.currentIndex)
        assertEquals("B", moved.currentItem?.name)
    }

    @Test
    fun `越界或原地拖拽返回自身`() {
        val queue = queueOf("A", "B")

        assertSame(queue, queue.move(0, 0))
        assertSame(queue, queue.move(-1, 1))
        assertSame(queue, queue.move(0, 5))
    }

    @Test
    fun `下一首播放插在当前曲目之后`() {
        val queue = queueOf("A", "B", "C", currentIndex = 0)

        val inserted = queue.insertNext(item("X"))

        assertEquals(listOf("A", "X", "B", "C"), inserted.items.map { it.name })
        assertEquals(0, inserted.currentIndex)
        // 续播位置随插入不变
        assertEquals("A", inserted.currentItem?.name)
    }
}
