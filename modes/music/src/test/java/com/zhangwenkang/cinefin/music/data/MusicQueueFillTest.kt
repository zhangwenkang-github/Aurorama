package com.zhangwenkang.cinefin.music.data

import com.zhangwenkang.cinefin.player.core.domain.models.MusicQueue
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerItem
import com.zhangwenkang.cinefin.player.core.domain.models.QueueSource
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 点歌起播后的补队列次序（W3-R3b：先解析被点曲目起播，其余曲目随后补入）。 */
class MusicQueueFillTest {

    private fun item(index: Int) =
        PlayerItem(
            name = "曲目$index",
            itemId = UUID.nameUUIDFromBytes("song-$index".toByteArray()),
            mediaSourceId = "source-$index",
            playbackPosition = 0L,
            mediaSourceUri = "file:///music/$index.mp3",
        )

    @Test
    fun `补入次序先当前之后再当前之前`() {
        assertEquals(listOf(3, 4, 1, 0), musicQueueFillOrder(playedIndex = 2, size = 5))
        // 点第一首：只补当前之后
        assertEquals(listOf(1, 2), musicQueueFillOrder(playedIndex = 0, size = 3))
        // 点最后一首：只补当前之前（倒序前移到队首）
        assertEquals(listOf(1, 0), musicQueueFillOrder(playedIndex = 2, size = 3))
        assertTrue(musicQueueFillOrder(playedIndex = 0, size = 1).isEmpty())
        assertTrue(musicQueueFillOrder(playedIndex = 5, size = 3).isEmpty())
        assertTrue(musicQueueFillOrder(playedIndex = -1, size = 3).isEmpty())
    }

    @Test
    fun `按补入次序补队列后顺序与浏览列表一致且当前曲目不变`() {
        val items = (0 until 5).map(::item)
        val playedIndex = 2

        // 模拟 ViewModel：起播时队列只有被点曲目（currentIndex = 0），随后按补入次序逐首补
        var queue = MusicQueue(items = listOf(items[playedIndex]), source = QueueSource.MANUAL)
        for (index in musicQueueFillOrder(playedIndex, items.size)) {
            val insertedAt = queue.currentIndex + 1
            queue = queue.insertNext(items[index])
            if (index < playedIndex) {
                queue = queue.move(insertedAt, 0)
            } else {
                queue = queue.move(insertedAt, queue.items.size - 1)
            }
        }

        assertEquals(items, queue.items)
        assertEquals(playedIndex, queue.currentIndex)
        assertEquals(items[playedIndex], queue.currentItem)
    }
}
