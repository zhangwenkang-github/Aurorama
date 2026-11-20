package com.zhangwenkang.cinefin.player.core.domain.models

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * W5 T5r：AUTO 连播时「播放器时间线 ↔ 队列」的对齐契约。
 *
 * 真机缺陷：主队列曲目 → 消费「接下来播放」→ AUTO 连播 2~3 首后，`queue.currentIndex` 钉在旧曲目不动 （ExoPlayer
 * 时间线被多删了一项，此后每次同步都因条数不等提前返回）。
 *
 * 这里的 [TimelineHarness] 复刻 controller 的同步口径：先按模型算对齐结果，再按旧下标降序从时间线摘除； 条数不等时和 controller
 * 一样放弃同步（这正是漂移会被"锁死"的原因）。
 */
class MusicQueueTimelineSyncTest {

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
        mainIds: List<String> = listOf("A", "B", "C", "D", "E"),
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

    /** 模拟 ExoPlayer：时间线条目 + 当前下标；同步口径与 `syncQueueWithPlayer` 一致。 */
    private class TimelineHarness(queue: MusicQueue) {
        var queue: MusicQueue = queue
            private set

        val timeline: MutableList<String> = queue.playbackItems.map { it.name }.toMutableList()
        var playerIndex: Int = queue.playbackIndex
            private set

        /** 一次切歌（手动 / AUTO）：与 controller 一样，条数不等就只让播放器前进、模型不动。 */
        fun transitionTo(index: Int) {
            playerIndex = index
            if (timeline.size != queue.playbackItems.size) return
            val sync = queue.reconcileWithTimeline(index) ?: return
            for (slot in sync.removalsDescending) timeline.removeAt(slot)
            playerIndex = index - sync.removalsDescending.count { it < index }
            queue = sync.queue
        }

        fun autoNext() = transitionTo(playerIndex + 1)

        fun assertInSync() {
            assertEquals(queue.playbackItems.map { it.name }, timeline)
            assertEquals(queue.playbackIndex, playerIndex)
            assertEquals(queue.currentItem?.name, timeline[playerIndex])
        }
    }

    @Test
    fun `消费接下来播放后 AUTO 连播 currentIndex 不漂移`() {
        val h = TimelineHarness(queue().insertNext(item("X")))
        assertEquals(listOf("A", "X", "B", "C", "D", "E"), h.timeline)

        // 手动「下一首」消费 up-next：X 正在播放，主队列当前曲 A 必须留在时间线上（回上一首要用）
        h.transitionTo(1)
        h.assertInSync()
        assertEquals("X", h.queue.currentItem?.name)

        // AUTO 连播 2 首：X 播完出列 → B，B 播完 → C
        h.autoNext()
        h.assertInSync()
        assertEquals("B", h.queue.currentItem?.name)
        assertEquals(1, h.queue.currentIndex)

        h.autoNext()
        h.assertInSync()
        assertEquals("C", h.queue.currentItem?.name)
        assertEquals(2, h.queue.currentIndex)
        assertEquals(listOf("A", "B", "C", "D", "E"), h.timeline)
    }

    @Test
    fun `多首接下来播放逐首播完即出 时间线始终同构`() {
        val h = TimelineHarness(queue(upNext = listOf("X", "Y")))

        h.transitionTo(1)
        h.assertInSync()
        assertEquals("X", h.queue.currentItem?.name)

        h.transitionTo(2)
        h.assertInSync()
        assertEquals("Y", h.queue.currentItem?.name)
        assertEquals(listOf("A", "Y", "B", "C", "D", "E"), h.timeline)

        h.autoNext()
        h.assertInSync()
        assertEquals("B", h.queue.currentItem?.name)
        assertEquals(1, h.queue.currentIndex)
        assertEquals(listOf("A", "B", "C", "D", "E"), h.timeline)
    }

    @Test
    fun `上一首回到主队列时正在播放的接下来播放条目出列`() {
        val h = TimelineHarness(queue(upNext = listOf("X", "Y")))
        h.transitionTo(1)

        h.transitionTo(0)
        h.assertInSync()
        assertEquals("A", h.queue.currentItem?.name)
        assertEquals(0, h.queue.currentIndex)
        assertEquals(listOf("A", "Y", "B", "C", "D", "E"), h.timeline)
    }

    @Test
    fun `跳过待播条目时被跨过的条目一并出列`() {
        val h = TimelineHarness(queue(upNext = listOf("X", "Y", "Z")))

        h.transitionTo(3)
        h.assertInSync()
        assertEquals("Z", h.queue.currentItem?.name)
        assertEquals(listOf("A", "Z", "B", "C", "D", "E"), h.timeline)
    }

    @Test
    fun `直接跳到主队列后段时待播条目不残留`() {
        val h = TimelineHarness(queue(upNext = listOf("X")))

        h.transitionTo(2)
        h.assertInSync()
        assertEquals("B", h.queue.currentItem?.name)
        assertEquals(1, h.queue.currentIndex)
        assertEquals(listOf("A", "B", "C", "D", "E"), h.timeline)
    }

    @Test
    fun `没有接下来播放时 AUTO 切歌只推进主队列索引`() {
        val h = TimelineHarness(queue())

        h.autoNext()
        h.assertInSync()
        assertEquals(1, h.queue.currentIndex)
        assertEquals("B", h.queue.currentItem?.name)
    }

    @Test
    fun `仍在播当前条目时无需同步`() {
        val q = queue(upNext = listOf("X")).withTimelineIndex(1)

        assertNull(q.reconcileWithTimeline(q.playbackIndex))
        assertNotNull(q.reconcileWithTimeline(q.playbackIndex + 1))
    }
}
