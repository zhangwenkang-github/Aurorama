package com.zhangwenkang.cinefin.player.local.engine

import com.zhangwenkang.cinefin.player.core.playback.EngineCapabilities
import com.zhangwenkang.cinefin.player.local.engine.exo.ExoPlaybackEngine
import com.zhangwenkang.cinefin.player.local.engine.mpv.MpvPlaybackEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 两套内核的能力位取值断言（W2-T4；W4-T3a-1 增补轨道枚举 / 字幕延迟两位）。
 *
 * 取值是 2026-11-19 的现状快照；翻转任何一位都要同步 ADR-0010 的取值表。
 */
class EngineCapabilitiesTest {

    @Test
    fun `mpv 全能力为 true 唯独随机播放不支持`() {
        val capabilities = MpvPlaybackEngine.CAPABILITIES

        assertTrue(capabilities.canPlayHls)
        assertTrue(capabilities.supportsSoftwareDecode)
        assertTrue(capabilities.supportsSubtitleStyling)
        assertTrue(capabilities.supportsAudioDelay)
        assertTrue(capabilities.supportsSideloadedSubtitles)
        assertFalse(capabilities.supportsShuffleMode)
        assertTrue(capabilities.supportsTrackEnumeration)
        assertTrue(capabilities.supportsSubtitleDelay)
    }

    @Test
    fun `exoplayer 现状全部支持`() {
        val capabilities = ExoPlaybackEngine.CAPABILITIES

        assertTrue(capabilities.canPlayHls)
        assertTrue(capabilities.supportsSoftwareDecode)
        assertTrue(capabilities.supportsSubtitleStyling)
        assertTrue(capabilities.supportsAudioDelay)
        assertTrue(capabilities.supportsSideloadedSubtitles)
        assertTrue(capabilities.supportsShuffleMode)
        assertTrue(capabilities.supportsTrackEnumeration)
        assertTrue(capabilities.supportsSubtitleDelay)
    }

    @Test
    fun `两内核唯一差异是随机播放`() {
        val exo = ExoPlaybackEngine.CAPABILITIES
        val mpv = MpvPlaybackEngine.CAPABILITIES

        assertEquals(exo.copy(supportsShuffleMode = false), mpv)
        assertTrue(exo.supportsShuffleMode)
        assertFalse(mpv.supportsShuffleMode)
    }

    @Test
    fun `能力位是值对象`() {
        val copy: EngineCapabilities = MpvPlaybackEngine.CAPABILITIES.copy()

        assertEquals(MpvPlaybackEngine.CAPABILITIES, copy)
    }
}
