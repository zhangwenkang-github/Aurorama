package com.zhangwenkang.cinefin.player.core.domain.models

import java.util.UUID
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W5 T5（MUSIC-09/11/12）：双队列模型 —— 消费顺序、播完即出、清空/移除，以及随机双子模式的纯函数顺序。
 *
 * 「当前曲 → 接下来播放（FIFO）→ 主队列剩余」是这里的唯一口径；任何一步之后，已经播过的接下来播放条目都不得残留在 `items` / `upNext` 里。
 */
class MusicQueueUpNextTest {

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
        mainIds: List<String> = listOf("A", "B", "C", "D"),
        currentIndex: Int = 0,
        upNext: List<String> = emptyList(),
        upNextPlaying: String? = null,
    ) =
        MusicQueue(
            items = mainIds.map(::item),
            currentIndex = currentIndex,
            source = QueueSource.MANUAL,
            upNext = upNext.map(::item),
            upNextPlaying = upNextPlaying?.let(::item),
        )

    private fun MusicQueue.names(): List<String> = playbackItems.map { it.name }

    @Test
    fun `没有接下来播放时时间线就是主队列`() {
        val q = queue()
        assertEquals(listOf("A", "B", "C", "D"), q.names())
        assertEquals(0, q.playbackIndex)
        assertEquals("A", q.currentItem?.name)
    }

    @Test
    fun `消费顺序 当前曲 接下来播放 FIFO 主队列剩余`() {
        val q = queue(upNext = listOf("X", "Y"))
        assertEquals(listOf("A", "X", "Y", "B", "C", "D"), q.names())

        // 播到 X：X 成为「正在播放的接下来播放条目」，Y 仍待播
        val playingX = q.withTimelineIndex(1)
        assertEquals("X", playingX.currentItem?.name)
        assertEquals(listOf("Y"), playingX.upNext.map { it.name })
        assertEquals(listOf("A", "X", "Y", "B", "C", "D"), playingX.names())

        // 播到 Y：X 已出列（播完即出），Y 正在播放
        val playingY = playingX.withTimelineIndex(2)
        assertEquals("Y", playingY.currentItem?.name)
        assertTrue(playingY.upNext.isEmpty())
        assertEquals(0, playingY.currentIndex)
        assertEquals(listOf("A", "Y", "B", "C", "D"), playingY.names())

        // Y 播完回到主队列剩余：当前索引推进到 B，时间线不再有 X / Y
        val playingB = playingY.withTimelineIndex(2)
        assertNull(playingB.upNextPlaying)
        assertEquals(1, playingB.currentIndex)
        assertEquals("B", playingB.currentItem?.name)
        assertEquals(listOf("A", "B", "C", "D"), playingB.names())
        val leftovers = playingB.items.map { it.name } + playingB.upNext.map { it.name }
        assertTrue("播完的接下来播放条目不得残留", "X" !in leftovers && "Y" !in leftovers)
    }

    @Test
    fun `播完不残留 多首接下来播放逐首出列`() {
        var q = queue(upNext = listOf("X", "Y", "Z"))
        // 依次播 X → Y → Z → B：每次都用模型自己的时间线「下一项」（controller 每次切歌后会同步摘掉已播条目）
        q = q.withTimelineIndex(q.playbackIndex + 1)
        assertEquals("X", q.currentItem?.name)
        q = q.withTimelineIndex(q.playbackIndex + 1)
        assertEquals(listOf("Z"), q.upNext.map { it.name })
        q = q.withTimelineIndex(q.playbackIndex + 1)
        assertTrue(q.upNext.isEmpty())
        assertEquals("Z", q.currentItem?.name)
        q = q.withTimelineIndex(q.playbackIndex + 1)
        assertEquals("B", q.currentItem?.name)
        assertEquals(1, q.currentIndex)
        assertEquals(listOf("A", "B", "C", "D"), q.names())
    }

    @Test
    fun `播完即出 正在播放的条目也算出列 只在 upNextPlaying 里`() {
        val q = queue(upNext = listOf("X", "Y")).withTimelineIndex(1)
        assertEquals("X", q.upNextPlaying?.name)
        assertEquals(listOf("Y"), q.upNext.map { it.name })
        assertEquals(2, q.upNextCount)
    }

    @Test
    fun `播放下一首插队首 加队尾排队尾`() {
        val q = queue(upNext = listOf("X"))
        val withNext = q.insertNext(item("N1"))
        assertEquals(listOf("N1", "X"), withNext.upNext.map { it.name })

        val withTail = withNext.appendUpNext(item("T1"))
        assertEquals(listOf("N1", "X", "T1"), withTail.upNext.map { it.name })

        // 连续「播放下一首」：后点的那首更靠前
        val twice = withTail.insertNext(item("N2"))
        assertEquals(listOf("N2", "N1", "X", "T1"), twice.upNext.map { it.name })
        // 主队列完全不受影响
        assertEquals(listOf("A", "B", "C", "D"), twice.items.map { it.name })
        assertEquals(
            listOf("A", "N2", "N1", "X", "T1", "B", "C", "D"),
            twice.names(),
        )
    }

    @Test
    fun `移除与清空接下来播放不影响主队列`() {
        val q = queue(upNext = listOf("X", "Y", "Z"))
        val removed = q.removeUpNextAt(1)
        assertEquals(listOf("X", "Z"), removed.upNext.map { it.name })
        assertEquals(q.items, removed.items)
        assertEquals(0, removed.currentIndex)

        val cleared = q.clearUpNext()
        assertTrue(cleared.upNext.isEmpty())
        assertEquals(listOf("A", "B", "C", "D"), cleared.items.map { it.name })
        assertEquals(0, cleared.currentIndex)
        assertTrue(q.clearUpNext().upNext.isEmpty())
        assertSame(q, q.removeUpNextAt(9))
        assertSame(q, q.removeUpNextAt(-1))
    }

    @Test
    fun `清空只清待播 不打断正在播放的接下来播放条目`() {
        val playing = queue(upNext = listOf("X", "Y")).withTimelineIndex(1)
        val cleared = playing.clearUpNext()
        assertTrue(cleared.upNext.isEmpty())
        assertEquals("X", cleared.currentItem?.name)
        assertEquals(listOf("A", "X", "B", "C", "D"), cleared.names())
    }

    @Test
    fun `跳回主队列会把正在播放的接下来播放条目出列`() {
        val playing = queue(upNext = listOf("X", "Y")).withTimelineIndex(1)
        val back = playing.withTimelineIndex(0)
        assertNull(back.upNextPlaying)
        assertEquals("A", back.currentItem?.name)
        assertEquals(listOf("Y"), back.upNext.map { it.name })
        assertEquals(listOf("A", "Y", "B", "C", "D"), back.names())
    }

    @Test
    fun `越界时间线下标原样返回`() {
        val q = queue(upNext = listOf("X"))
        assertSame(q, q.withTimelineIndex(-1))
        assertSame(q, q.withTimelineIndex(99))
    }

    @Test
    fun `随机整个队列是完整置换且不固定当前曲目`() {
        val order = fullShuffleOrder(itemCount = 6, random = Random(7))
        assertEquals(6, order.size)
        assertEquals((0 until 6).toSet(), order.toSet())
        // 同 seed 可复现（顺序即唯一真值，单测钉死）
        assertEquals(order, fullShuffleOrder(itemCount = 6, random = Random(7)))
        assertEquals(listOf(1, 0, 3, 5, 4, 2), order)
    }

    @Test
    fun `随机并保持当前曲目居首时当前曲目固定在首位`() {
        val order = currentFirstShuffleOrder(itemCount = 5, currentIndex = 2, random = Random(11))
        assertEquals(5, order.size)
        assertEquals((0 until 5).toSet(), order.toSet())
        assertEquals(2, order.first())
        assertEquals(order, currentFirstShuffleOrder(5, 2, Random(11)))
        assertEquals(listOf(2, 3, 0, 4, 1), order)
    }

    @Test
    fun `手动插入项在随机序里保持队首优先级`() {
        // 时间线 = [A, X, Y, B]，当前 0，接下来播放 2 条：顺序必须是 0,1,2 开头
        val order =
            priorityShuffleOrder(
                itemCount = 4,
                currentIndex = 0,
                priorityCount = 2,
                random = Random(3),
            )
        assertEquals(4, order.size)
        assertEquals((0 until 4).toSet(), order.toSet())
        assertEquals(listOf(0, 1, 2), order.take(3))
        assertEquals(3, order.last())
    }

    @Test
    fun `随机模式与开关字段保持一致`() {
        assertEquals(ShuffleMode.OFF, queue().shuffleMode)
        assertEquals(ShuffleMode.ALL, queue().copy(shuffleEnabled = true).shuffleMode)
        assertEquals(
            ShuffleMode.KEEP_CURRENT_FIRST,
            queue().copy(shuffleEnabled = true, shuffleKeepCurrentFirst = true).shuffleMode,
        )
    }

    @Test
    fun `构造批量入队与拖拽排序不破坏接下来播放`() {
        val q = queue(mainIds = listOf("A", "B", "C"), upNext = listOf("X"))
        val moved = q.move(fromIndex = 2, toIndex = 0)
        assertEquals(listOf("C", "A", "B"), moved.items.map { it.name })
        assertEquals(listOf("X"), moved.upNext.map { it.name })
        // 当前曲目仍是 A（索引随排序右移）
        assertEquals("A", moved.currentItem?.name)
        assertEquals(1, moved.currentIndex)
    }
}
