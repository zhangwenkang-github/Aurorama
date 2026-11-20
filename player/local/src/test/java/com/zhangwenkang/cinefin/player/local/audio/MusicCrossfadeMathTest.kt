package com.zhangwenkang.cinefin.player.local.audio

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** W30-MUSIC-FX / W5-T7：淡入淡出增益曲线与触发口径（纯函数）单测。 */
class MusicCrossfadeMathTest {

    private val fadeMs = 4_000L
    private val durationMs = 180_000L
    private val quarter = sin(PI / 8.0).toFloat()

    @Test
    fun `fade in endpoints and midpoint follow equal power curve`() {
        assertEquals(0f, MusicCrossfadeMath.fadeInGain(0L, fadeMs), 0.001f)
        assertEquals(1f, MusicCrossfadeMath.fadeInGain(fadeMs, fadeMs), 0.001f)
        assertEquals(1f, MusicCrossfadeMath.fadeInGain(fadeMs * 2, fadeMs), 0.001f)
        val midpoint = MusicCrossfadeMath.fadeInGain(fadeMs / 2, fadeMs)
        assertEquals(sin(PI / 4.0).toFloat(), midpoint, 0.001f)
    }

    @Test
    fun `fade out mirrors fade in`() {
        assertEquals(1f, MusicCrossfadeMath.fadeOutGain(fadeMs, fadeMs), 0.001f)
        assertEquals(0f, MusicCrossfadeMath.fadeOutGain(0L, fadeMs), 0.001f)
        assertEquals(
            MusicCrossfadeMath.fadeInGain(fadeMs / 4, fadeMs),
            MusicCrossfadeMath.fadeOutGain(fadeMs / 4, fadeMs),
            0.001f,
        )
    }

    @Test
    fun `eligible track fades at start middle and end`() {
        assertEquals(
            0f,
            MusicCrossfadeMath.crossfadeVolume(0L, durationMs, fadeMs, eligible = true),
            0.001f,
        )
        assertEquals(
            quarter,
            MusicCrossfadeMath.crossfadeVolume(1_000L, durationMs, fadeMs, eligible = true),
            0.001f,
        )
        assertEquals(
            1f,
            MusicCrossfadeMath.crossfadeVolume(60_000L, durationMs, fadeMs, eligible = true),
            0.001f,
        )
        assertEquals(
            quarter,
            MusicCrossfadeMath.crossfadeVolume(179_000L, durationMs, fadeMs, eligible = true),
            0.001f,
        )
        assertEquals(
            0f,
            MusicCrossfadeMath.crossfadeVolume(180_000L, durationMs, fadeMs, eligible = true),
            0.001f,
        )
    }

    @Test
    fun `ineligible track stays at full volume everywhere`() {
        listOf(0L, 1_000L, 60_000L, 179_000L, 180_000L).forEach { position ->
            assertEquals(
                1f,
                MusicCrossfadeMath.crossfadeVolume(position, durationMs, fadeMs, eligible = false),
                0.001f,
            )
        }
    }

    @Test
    fun `crossfade volume stays one when disabled or duration unknown`() {
        assertEquals(1f, MusicCrossfadeMath.crossfadeVolume(10_000L, durationMs, 0L, true), 0.001f)
        assertEquals(1f, MusicCrossfadeMath.crossfadeVolume(10_000L, 0L, fadeMs, true), 0.001f)
    }

    @Test
    fun `crossfade volume is monotonic in fade out window`() {
        var previous = 1f
        for (remaining in fadeMs downTo 0 step 250) {
            val gain =
                MusicCrossfadeMath.crossfadeVolume(
                    durationMs - remaining,
                    durationMs,
                    fadeMs,
                    eligible = true,
                )
            assertTrue("淡出窗口内应单调不增：$gain > $previous", gain <= previous + 0.0001f)
            previous = gain
        }
    }

    @Test
    fun `short track threshold follows max of twenty seconds and three fade windows`() {
        // fade=6s → 3×=18s < 20s，阈值取 20s
        assertFalse(MusicCrossfadeMath.isFadeableTrack(19_999L, 6_000L))
        assertTrue(MusicCrossfadeMath.isFadeableTrack(20_000L, 6_000L))
        // fade=12s → 3×=36s > 20s，阈值取 36s
        assertFalse(MusicCrossfadeMath.isFadeableTrack(35_999L, 12_000L))
        assertTrue(MusicCrossfadeMath.isFadeableTrack(36_000L, 12_000L))
        // 关闭时不淡化
        assertFalse(MusicCrossfadeMath.isFadeableTrack(180_000L, 0L))
    }

    @Test
    fun `short track stays at full volume even when eligible`() {
        // fade=6s：恰等阈值 20s 仍淡化；差 1ms 整曲恒 1
        assertTrue(MusicCrossfadeMath.crossfadeVolume(0L, 20_000L, 6_000L, true) < 1f)
        assertEquals(1f, MusicCrossfadeMath.crossfadeVolume(0L, 19_999L, 6_000L, true), 0.001f)
        assertEquals(1f, MusicCrossfadeMath.crossfadeVolume(10_000L, 19_999L, 6_000L, true), 0.001f)
        // fade=12s：36s 起淡化，35_999ms 恒 1
        assertTrue(MusicCrossfadeMath.crossfadeVolume(0L, 36_000L, 12_000L, true) < 1f)
        assertEquals(1f, MusicCrossfadeMath.crossfadeVolume(0L, 35_999L, 12_000L, true), 0.001f)
    }

    @Test
    fun `crossfade seconds clamp to zero through twelve`() {
        assertEquals(0, MusicCrossfadeMath.sanitizeCrossfadeSeconds(-1))
        assertEquals(0, MusicCrossfadeMath.sanitizeCrossfadeSeconds(0))
        assertEquals(2, MusicCrossfadeMath.sanitizeCrossfadeSeconds(2))
        assertEquals(6, MusicCrossfadeMath.sanitizeCrossfadeSeconds(6))
        assertEquals(7, MusicCrossfadeMath.sanitizeCrossfadeSeconds(7))
        assertEquals(12, MusicCrossfadeMath.sanitizeCrossfadeSeconds(12))
        assertEquals(12, MusicCrossfadeMath.sanitizeCrossfadeSeconds(13))
    }
}
