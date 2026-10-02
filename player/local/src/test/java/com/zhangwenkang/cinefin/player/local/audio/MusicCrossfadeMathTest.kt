package com.zhangwenkang.cinefin.player.local.audio

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** W30-MUSIC-FX：淡入淡出增益曲线（纯函数）单测。 */
class MusicCrossfadeMathTest {

    private val fadeMs = 4_000L

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
    fun `crossfade volume stays one far from edges`() {
        assertEquals(1f, MusicCrossfadeMath.crossfadeVolume(60_000L, 180_000L, fadeMs), 0.001f)
        assertEquals(1f, MusicCrossfadeMath.crossfadeVolume(10_000L, 180_000L, 0L), 0.001f)
        assertEquals(1f, MusicCrossfadeMath.crossfadeVolume(10_000L, 0L, fadeMs), 0.001f)
    }

    @Test
    fun `crossfade volume fades at start and end`() {
        // 距开头 1s / 距结尾 1s（4s 窗口的 1/4 处），等于 sin(π/8)。
        val quarter = sin(PI / 8.0).toFloat()
        assertEquals(quarter, MusicCrossfadeMath.crossfadeVolume(1_000L, 180_000L, fadeMs), 0.001f)
        assertEquals(
            quarter,
            MusicCrossfadeMath.crossfadeVolume(179_000L, 180_000L, fadeMs),
            0.001f,
        )
        assertEquals(0f, MusicCrossfadeMath.crossfadeVolume(180_000L, 180_000L, fadeMs), 0.001f)
    }

    @Test
    fun `crossfade volume is monotonic in fade out window`() {
        var previous = 1f
        for (remaining in fadeMs downTo 0 step 250) {
            val gain = MusicCrossfadeMath.crossfadeVolume(180_000L - remaining, 180_000L, fadeMs)
            assertTrue("淡出窗口内应单调不增：$gain > $previous", gain <= previous + 0.0001f)
            previous = gain
        }
    }

    @Test
    fun `short track overlapping windows take the smaller gain`() {
        // 曲长 3s < 淡入 4s + 淡出 4s：中间点两者都 < 1，取较小值。
        val gain = MusicCrossfadeMath.crossfadeVolume(1_500L, 3_000L, fadeMs)
        assertTrue(gain < 1f)
        assertTrue(gain > 0f)
    }

    @Test
    fun `crossfade options sanitize unknown values to off`() {
        assertEquals(0, MusicCrossfadeMath.sanitizeCrossfadeSeconds(5))
        assertEquals(2, MusicCrossfadeMath.sanitizeCrossfadeSeconds(2))
        assertEquals(6, MusicCrossfadeMath.sanitizeCrossfadeSeconds(6))
        assertEquals(0, MusicCrossfadeMath.sanitizeCrossfadeSeconds(-1))
    }
}
