package com.zhangwenkang.cinefin.player.local.mpv

import com.zhangwenkang.cinefin.player.core.playback.EngineTrackType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** mpv `track-list` 记录到引擎轨道的映射（W4-T3a-1，纯 JVM）。 */
class MpvTrackMappingTest {

    private fun entry(
        id: Int = 2,
        type: EngineTrackType = EngineTrackType.SUBTITLE,
        language: String? = "zh",
        title: String? = "简体中文",
        codec: String? = "ASS",
        isExternal: Boolean = false,
        isDefault: Boolean = false,
        isForced: Boolean = false,
    ) =
        MpvTrackEntry(
            id = id,
            type = type,
            language = language,
            title = title,
            codec = codec,
            isExternal = isExternal,
            isDefault = isDefault,
            isForced = isForced,
        )

    @Test
    fun `id 用 mpv 轨道 id 的十进制字符串`() {
        assertEquals("2", entry(id = 2).toEngineTrack().id)
        assertEquals("15", entry(id = 15).toEngineTrack().id)
    }

    @Test
    fun `字段原样透传且编码名统一小写`() {
        val track = entry(isExternal = true, isDefault = false, isForced = true).toEngineTrack()

        assertEquals(EngineTrackType.SUBTITLE, track.type)
        assertEquals("zh", track.language)
        assertEquals("简体中文", track.title)
        assertEquals("ass", track.codec)
        assertTrue(track.isExternal)
        assertFalse(track.isDefault)
        assertTrue(track.isForced)
    }

    @Test
    fun `空编码名归一为 null`() {
        assertNull(entry(codec = " ").toEngineTrack().codec)
        assertNull(entry(codec = null).toEngineTrack().codec)
    }

    @Test
    fun `空语言与空标题归一为 null`() {
        val track = entry(language = "", title = "").toEngineTrack()

        assertNull(track.language)
        assertNull(track.title)
    }

    @Test
    fun `轨道类型映射双向一致`() {
        assertEquals(EngineTrackType.VIDEO, MPVTrackType.VIDEO.toEngineTrackType())
        assertEquals(EngineTrackType.AUDIO, MPVTrackType.AUDIO.toEngineTrackType())
        assertEquals(EngineTrackType.SUBTITLE, MPVTrackType.SUBTITLE.toEngineTrackType())

        assertEquals(MPVTrackType.VIDEO, EngineTrackType.VIDEO.toMpvTrackType())
        assertEquals(MPVTrackType.AUDIO, EngineTrackType.AUDIO.toMpvTrackType())
        assertEquals(MPVTrackType.SUBTITLE, EngineTrackType.SUBTITLE.toMpvTrackType())
    }

    @Test
    fun `空 track-list 解析为空表`() {
        assertEquals(emptyList<MpvTrackEntry>(), parseMpvTrackList(null))
        assertEquals(emptyList<MpvTrackEntry>(), parseMpvTrackList(""))
        assertEquals(emptyList<MpvTrackEntry>(), parseMpvTrackList("[]"))
    }
}
