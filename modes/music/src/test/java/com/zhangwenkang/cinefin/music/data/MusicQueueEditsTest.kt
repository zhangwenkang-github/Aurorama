package com.zhangwenkang.cinefin.music.data

import com.zhangwenkang.cinefin.player.core.domain.models.MusicQueue
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerItem
import com.zhangwenkang.cinefin.player.core.domain.models.QueueSource
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** 恢复态队列本地编辑（W21-R2：未装载播放器前也能拖拽 / 移除）。 */
class MusicQueueEditsTest {

    private fun item(index: Int) =
        PlayerItem(
            name = "曲目$index",
            itemId = UUID.nameUUIDFromBytes("song-$index".toByteArray()),
            mediaSourceId = "source-$index",
            playbackPosition = 0L,
            mediaSourceUri = "file:///music/$index.mp3",
        )

    private fun queue(size: Int = 3, currentIndex: Int = 1) =
        MusicQueue(
            items = (0 until size).map(::item),
            currentIndex = currentIndex,
            source = QueueSource.MANUAL,
        )

    @Test
    fun `删除当前曲目时索引顺延到同位置`() {
        val updated = musicQueueRemoveAt(queue(), 1)!!
        assertEquals(listOf(item(0).itemId, item(2).itemId), updated.items.map { it.itemId })
        assertEquals(1, updated.currentIndex)
        assertEquals(item(2).itemId, updated.currentItem!!.itemId)
    }

    @Test
    fun `删除末尾当前曲目时索引前移`() {
        val updated = musicQueueRemoveAt(queue(currentIndex = 2), 2)!!
        assertEquals(1, updated.currentIndex)
        assertEquals(item(1).itemId, updated.currentItem!!.itemId)
    }

    @Test
    fun `删除当前曲目之前时索引左移`() {
        val updated = musicQueueRemoveAt(queue(currentIndex = 2), 0)!!
        assertEquals(1, updated.currentIndex)
        assertEquals(item(2).itemId, updated.currentItem!!.itemId)
    }

    @Test
    fun `删除唯一曲目返回 null`() {
        assertNull(musicQueueRemoveAt(queue(size = 1, currentIndex = 0), 0))
    }

    @Test
    fun `越界删除返回原队列`() {
        val original = queue()
        assertSame(original, musicQueueRemoveAt(original, 5))
    }
}
