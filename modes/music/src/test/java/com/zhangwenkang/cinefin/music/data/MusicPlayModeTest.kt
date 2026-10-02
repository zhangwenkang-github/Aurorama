package com.zhangwenkang.cinefin.music.data

import com.zhangwenkang.cinefin.player.core.domain.models.RepeatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 播放模式状态机（W23-MUSIC · C 组）：四种模式 ↔ 内核 repeatMode / shuffleEnabled 的映射与循环。 */
class MusicPlayModeTest {

    @Test
    fun `四种模式映射到内核开关`() {
        assertEquals(RepeatMode.OFF, MusicPlayMode.SEQUENTIAL.toRepeatMode())
        assertFalse(MusicPlayMode.SEQUENTIAL.toShuffleEnabled())

        assertEquals(RepeatMode.ALL, MusicPlayMode.LIST_LOOP.toRepeatMode())
        assertFalse(MusicPlayMode.LIST_LOOP.toShuffleEnabled())

        assertEquals(RepeatMode.ONE, MusicPlayMode.SINGLE_LOOP.toRepeatMode())
        assertFalse(MusicPlayMode.SINGLE_LOOP.toShuffleEnabled())

        // 随机播放：一轮播完继续下一轮（ALL），顺序由内核 shuffle 打乱
        assertEquals(RepeatMode.ALL, MusicPlayMode.SHUFFLE.toRepeatMode())
        assertTrue(MusicPlayMode.SHUFFLE.toShuffleEnabled())
    }

    @Test
    fun `内核状态反推模式`() {
        assertEquals(
            MusicPlayMode.SEQUENTIAL,
            musicPlayModeOf(RepeatMode.OFF, shuffleEnabled = false),
        )
        assertEquals(
            MusicPlayMode.LIST_LOOP,
            musicPlayModeOf(RepeatMode.ALL, shuffleEnabled = false),
        )
        assertEquals(
            MusicPlayMode.SINGLE_LOOP,
            musicPlayModeOf(RepeatMode.ONE, shuffleEnabled = false),
        )
        // shuffle 打开时优先读随机（单曲循环 + 随机不是合法组合，按随机处理）
        assertEquals(
            MusicPlayMode.SHUFFLE,
            musicPlayModeOf(RepeatMode.ONE, shuffleEnabled = true),
        )
        assertEquals(
            MusicPlayMode.SHUFFLE,
            musicPlayModeOf(RepeatMode.ALL, shuffleEnabled = true),
        )
    }

    @Test
    fun `图标循环切换一圈回到原模式`() {
        assertEquals(MusicPlayMode.LIST_LOOP, MusicPlayMode.SEQUENTIAL.next())
        assertEquals(MusicPlayMode.SINGLE_LOOP, MusicPlayMode.LIST_LOOP.next())
        assertEquals(MusicPlayMode.SHUFFLE, MusicPlayMode.SINGLE_LOOP.next())
        assertEquals(MusicPlayMode.SEQUENTIAL, MusicPlayMode.SHUFFLE.next())

        var mode = MusicPlayMode.SEQUENTIAL
        repeat(4) { mode = mode.next() }
        assertEquals(MusicPlayMode.SEQUENTIAL, mode)
    }

    @Test
    fun `映射与反推互为逆运算`() {
        MusicPlayMode.entries.forEach { mode ->
            assertEquals(
                mode,
                musicPlayModeOf(mode.toRepeatMode(), mode.toShuffleEnabled()),
            )
        }
    }
}
