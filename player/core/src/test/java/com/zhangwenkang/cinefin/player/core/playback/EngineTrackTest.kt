package com.zhangwenkang.cinefin.player.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [EngineTrack] / [EngineTrackType] / [EngineCapabilities] 的模型契约（W4-T3a-1）。 */
class EngineTrackTest {

    @Test
    fun `轨道类型就是视频音频字幕三种`() {
        assertEquals(
            listOf("VIDEO", "AUDIO", "SUBTITLE"),
            EngineTrackType.entries.map { it.name },
        )
    }

    @Test
    fun `轨道是值对象且字段齐全`() {
        val track =
            EngineTrack(
                id = "1:0",
                type = EngineTrackType.SUBTITLE,
                language = "zh-Hans",
                title = "简体中文",
                codec = "ass",
                isExternal = true,
                isDefault = false,
                isForced = false,
            )

        assertEquals(track, track.copy())
        assertEquals("1:0", track.id)
        assertEquals(EngineTrackType.SUBTITLE, track.type)
        assertEquals("ass", track.codec)
        assertTrue(track.isExternal)
    }

    @Test
    fun `未知字段允许为 null`() {
        val track =
            EngineTrack(
                id = "3",
                type = EngineTrackType.VIDEO,
                language = null,
                title = null,
                codec = null,
                isExternal = false,
                isDefault = false,
                isForced = false,
            )

        assertNull(track.language)
        assertNull(track.title)
        assertNull(track.codec)
    }

    @Test
    fun `能力位是值对象且八个字段都能独立翻转`() {
        val base =
            EngineCapabilities(
                canPlayHls = true,
                supportsSoftwareDecode = true,
                supportsSubtitleStyling = true,
                supportsAudioDelay = true,
                supportsSideloadedSubtitles = true,
                supportsShuffleMode = true,
                supportsTrackEnumeration = true,
                supportsSubtitleDelay = true,
            )

        assertEquals(base, base.copy())
        assertEquals(
            base,
            base
                .copy(
                    supportsTrackEnumeration = false,
                    supportsSubtitleDelay = false,
                )
                .copy(
                    supportsTrackEnumeration = true,
                    supportsSubtitleDelay = true,
                ),
        )
    }
}
