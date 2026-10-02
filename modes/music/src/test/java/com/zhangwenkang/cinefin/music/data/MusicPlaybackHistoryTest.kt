package com.zhangwenkang.cinefin.music.data

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 随机播放的播放历史（W23-MUSIC · C 组）：上一曲回实际播放过的上一首。 */
class MusicPlaybackHistoryTest {

    private fun id(name: String) = UUID.nameUUIDFromBytes(name.toByteArray())

    private val a = id("a")
    private val b = id("b")
    private val c = id("c")
    private val d = id("d")
    private val queue = listOf(a, b, c, d)

    @Test
    fun `上一曲按历史倒序回退`() {
        val history = MusicPlaybackHistory()
        history.record(a)
        history.record(c)
        history.record(d)

        // 当前在 d：上一首是实际播放过的 c（不是随机顺序里的前一项 b）
        assertEquals(2, history.previous(queue, currentItemId = d))
        // 回到 c 之后（record 由播放器变化触发），再上一首是 a
        history.record(c)
        assertEquals(0, history.previous(queue, currentItemId = c))
        // 历史已走到头
        assertNull(history.previous(queue, currentItemId = a))
    }

    @Test
    fun `重复播放的曲目移到历史末尾`() {
        val history = MusicPlaybackHistory()
        history.record(a)
        history.record(b)
        history.record(a)

        assertEquals(listOf(b, a), history.snapshot)
        assertEquals(1, history.previous(queue, currentItemId = a))
    }

    @Test
    fun `历史里的曲目已不在队列时继续往前找`() {
        val history = MusicPlaybackHistory()
        history.record(a)
        history.record(b)
        history.record(d)

        assertEquals(0, history.previous(queueItemIds = listOf(a, d), currentItemId = d))
        assertNull(history.previous(queueItemIds = listOf(d), currentItemId = d))
    }

    @Test
    fun `空历史与超容量都会安全回收`() {
        val history = MusicPlaybackHistory(capacity = 3)
        assertNull(history.previous(queue, currentItemId = a))

        listOf(a, b, c, d).forEach(history::record)
        assertEquals(listOf(b, c, d), history.snapshot)

        history.clear()
        assertEquals(emptyList<UUID>(), history.snapshot)
    }
}
