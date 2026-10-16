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

    /**
     * W78：补队列的批量入队路径（先追加"当前之后"、再前插"当前之前"）必须与浏览器列表顺序一致， 且**与当前播到第几首无关**。
     *
     * 这是用户报的「队列只有 64 首 / 随机只播固定几首」的直接回归防线：旧实现用 `insertNext` + `move` 逐首补入，并且一旦"当前曲目"变了（第一首自然播完 →
     * 自动切歌）就整体放弃补齐。
     */
    @Test
    fun `批量追加与前插补出列表顺序且当前曲目不变`() {
        val names = (0 until 9).map { index -> "T$index" }
        val playedIndex = 3

        // 模拟 ViewModel：起播时队列只有被点曲目，随后按"先当前之后升序追加 / 再当前之前倒序前插"补齐
        var queue =
            MusicQueue(
                items = listOf(item(names[playedIndex])),
                currentIndex = 0,
                source = QueueSource.ALBUM,
                sourceId = "album",
            )
        queue =
            queue.appendItems(
                (playedIndex + 1 until names.size).map { index -> item(names[index]) }
            )
        // 批量前插的约定：**传入顺序即最终队首顺序**（调用方按升序逐批前插）
        queue = queue.prependItems((0 until playedIndex).map { index -> item(names[index]) })

        assertEquals(names, queue.items.map { it.name })
        assertEquals(names[playedIndex], queue.currentItem?.name)
        assertEquals(playedIndex, queue.currentIndex)
    }

    @Test
    fun `批量入队支持分批调用且空列表返回自身`() {
        val queue = queueOf("C", currentIndex = 0)

        // 分两批追加（每批一次 addMediaItems 的纯模型等价物）
        val appended = queue.appendItems(listOf(item("D"))).appendItems(listOf(item("E")))
        // 分两批前插：后一批前插后仍整体保持列表序
        val prepended = appended.prependItems(listOf(item("B"))).prependItems(listOf(item("A")))

        assertEquals(listOf("A", "B", "C", "D", "E"), prepended.items.map { it.name })
        assertEquals("C", prepended.currentItem?.name)
        assertSame(queue, queue.appendItems(emptyList()))
        assertSame(queue, queue.prependItems(emptyList()))
    }
}
