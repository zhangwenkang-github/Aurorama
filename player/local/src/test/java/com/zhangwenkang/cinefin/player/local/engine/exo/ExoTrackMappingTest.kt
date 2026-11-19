package com.zhangwenkang.cinefin.player.local.engine.exo

import androidx.media3.common.C
import com.zhangwenkang.cinefin.player.core.playback.EngineTrackType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Exo 轨道 id 编解码与编码名归一（W4-T3a-1，纯 JVM）。 */
class ExoTrackMappingTest {

    @Test
    fun `轨道 id 编码后能原样解回`() {
        assertEquals(0 to 0, decodeExoTrackId(exoTrackId(0, 0)))
        assertEquals(12 to 3, decodeExoTrackId(exoTrackId(12, 3)))
        assertEquals("12:3", exoTrackId(12, 3))
    }

    @Test
    fun `非法 id 一律解析失败而不是抛异常`() {
        assertNull(decodeExoTrackId(""))
        assertNull(decodeExoTrackId("12"))
        assertNull(decodeExoTrackId("a:b"))
        assertNull(decodeExoTrackId("1:2:3"))
        assertNull(decodeExoTrackId("-1:0"))
        assertNull(decodeExoTrackId("1:-2"))
        assertNull(decodeExoTrackId("1:"))
    }

    @Test
    fun `轨道类型映射到 media3 常量`() {
        assertEquals(C.TRACK_TYPE_VIDEO, EngineTrackType.VIDEO.toMedia3TrackType())
        assertEquals(C.TRACK_TYPE_AUDIO, EngineTrackType.AUDIO.toMedia3TrackType())
        assertEquals(C.TRACK_TYPE_TEXT, EngineTrackType.SUBTITLE.toMedia3TrackType())
    }

    @Test
    fun `编码名取斜杠后的短名并小写`() {
        assertEquals("ass", exoCodecName("S_TEXT/ASS", "text/x-ssa"))
        assertEquals("mp4a", exoCodecName("mp4a.40.2", "audio/mp4a-latm"))
        assertEquals("hvc1", exoCodecName("hvc1.1.6.L93.B0", null))
        assertEquals("aac", exoCodecName("AAC", "audio/mp4a-latm"))
    }

    @Test
    fun `编码名缺失时退回 mime 子类型`() {
        assertEquals("ass", exoCodecName(null, "text/ass"))
        assertEquals("subrip", exoCodecName(" ", "text/subrip"))
    }

    @Test
    fun `两者都缺失时返回 null`() {
        assertNull(exoCodecName(null, null))
        assertNull(exoCodecName("", ""))
        assertNull(exoCodecName("/", null))
    }
}
