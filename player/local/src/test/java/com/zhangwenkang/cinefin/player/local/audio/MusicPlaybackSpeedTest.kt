package com.zhangwenkang.cinefin.player.local.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/** W5-T6 · MUSIC-32：音乐倍速档位（0.5–2.0×，默认 1.0，不落偏好）。 */
class MusicPlaybackSpeedTest {

    @Test
    fun `speed presets match design`() {
        assertEquals(listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f), MUSIC_PLAYBACK_SPEEDS)
        assertEquals(1.0f, MUSIC_DEFAULT_PLAYBACK_SPEED, 0.0001f)
    }

    @Test
    fun `sanitize only accepts preset speeds`() {
        MUSIC_PLAYBACK_SPEEDS.forEach { speed ->
            assertEquals(speed, sanitizeMusicPlaybackSpeed(speed), 0.0001f)
        }
        assertEquals(1.0f, sanitizeMusicPlaybackSpeed(0.3f), 0.0001f)
        assertEquals(1.0f, sanitizeMusicPlaybackSpeed(4f), 0.0001f)
        assertEquals(1.0f, sanitizeMusicPlaybackSpeed(Float.NaN), 0.0001f)
    }
}
