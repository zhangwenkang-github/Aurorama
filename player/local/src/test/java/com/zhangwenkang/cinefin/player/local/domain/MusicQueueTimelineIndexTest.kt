package com.zhangwenkang.cinefin.player.local.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * W5-T12（MUSIC-14）：主队列下标 → 播放器时间线下标的映射。
 *
 * Auto DJ 追加批次后要以「追加批次第一首」起播；有「接下来播放」条目时主队列当前曲之后的条目整体后移，
 * 映射错了就会播到别的曲目上。断言只覆盖该起播路径用到的分支（追加下标恒大于当前曲下标）。
 */
class MusicQueueTimelineIndexTest {

    @Test
    fun `没有接下来播放时下标一致`() {
        // 单曲专辑播完（currentIndex=0）+ 追加 10 首：新批次首曲 = 主队列 10 / 时间线 10
        assertEquals(10, musicQueueTimelineIndex(currentIndex = 0, upNextCount = 0, mainIndex = 10))
        assertEquals(0, musicQueueTimelineIndex(currentIndex = 0, upNextCount = 0, mainIndex = 0))
    }

    @Test
    fun `有接下来播放时 当前曲之后整体后移`() {
        // 当前曲 2、接下来播放 3 条（时间线 = 0,1,2,[3,4,5],主队列3..）
        // 当前曲之后的第一首（主队列 3）落到时间线 6
        assertEquals(6, musicQueueTimelineIndex(currentIndex = 2, upNextCount = 3, mainIndex = 3))
        // Auto DJ 追加批次首曲（主队列 10）落到时间线 13
        assertEquals(13, musicQueueTimelineIndex(currentIndex = 2, upNextCount = 3, mainIndex = 10))
        // 当前曲及之前保持原下标（与既有 move / removeAt 的用法一致）
        assertEquals(2, musicQueueTimelineIndex(currentIndex = 2, upNextCount = 3, mainIndex = 2))
        assertEquals(0, musicQueueTimelineIndex(currentIndex = 2, upNextCount = 3, mainIndex = 0))
    }
}
