package com.zhangwenkang.cinefin.player.local.engine

import com.zhangwenkang.cinefin.player.core.playback.EngineTrack
import com.zhangwenkang.cinefin.player.core.playback.EngineTrackType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 跨内核轨道映射表（W4-T3a-2，纯 JVM）：三级命中的顺序与边界。 */
class EngineTrackMatcherTest {

    private fun track(
        id: String,
        type: EngineTrackType = EngineTrackType.AUDIO,
        language: String? = "chi",
        title: String? = null,
        codec: String? = "aac",
        isExternal: Boolean = false,
        isDefault: Boolean = false,
    ) =
        EngineTrack(
            id = id,
            type = type,
            language = language,
            title = title,
            codec = codec,
            isExternal = isExternal,
            isDefault = isDefault,
            isForced = false,
        )

    @Test
    fun `精确命中优先于宽松与兜底`() {
        val snapshot = track("old", title = "主音轨", codec = "aac")
        val candidates =
            listOf(
                track("loose", title = "另一条", codec = "eac3", isDefault = true),
                track("exact", title = "主音轨", codec = "aac"),
            )

        assertEquals("exact", EngineTrackMatcher.match(snapshot, candidates)?.id)
    }

    @Test
    fun `精确未命中时按语言与外挂标记宽松命中`() {
        val snapshot = track("old", title = "旧标题", codec = "aac")
        val candidates =
            listOf(
                track("other-lang", language = "eng", codec = "aac", isDefault = true),
                track("loose", language = "chi", title = "新标题", codec = "eac3"),
            )

        assertEquals("loose", EngineTrackMatcher.match(snapshot, candidates)?.id)
    }

    @Test
    fun `外挂标记不一致时不进宽松档`() {
        val snapshot = track("old", isExternal = true, codec = "ass", title = "外挂字幕")
        val candidates =
            listOf(
                track("embedded", isExternal = false, isDefault = true),
                track("external", isExternal = true, codec = "srt", title = "别的外挂"),
            )

        assertEquals("external", EngineTrackMatcher.match(snapshot, candidates)?.id)
    }

    @Test
    fun `两级都未命中时兜底同类型默认轨`() {
        val snapshot = track("old", language = "chi", codec = "aac")
        val candidates =
            listOf(
                track("eng", language = "eng", codec = "eac3", isDefault = true),
                track("jpn", language = "jpn", codec = "aac", isDefault = false),
            )

        assertEquals("eng", EngineTrackMatcher.match(snapshot, candidates)?.id)
    }

    @Test
    fun `三级全未命中返回 null，调用方保持新内核自己的选择`() {
        val snapshot = track("old", language = "chi", codec = "aac")
        val candidates = listOf(track("eng", language = "eng", codec = "eac3"))

        assertNull(EngineTrackMatcher.match(snapshot, candidates))
    }

    @Test
    fun `候选集为空返回 null`() {
        assertNull(EngineTrackMatcher.match(track("old"), emptyList()))
    }

    @Test
    fun `类型隔离：同字段的别的类型不会被认领`() {
        val snapshot = track("old", type = EngineTrackType.SUBTITLE, codec = "ass")
        val candidates =
            listOf(track("audio", type = EngineTrackType.AUDIO, codec = "ass", isDefault = true))

        assertNull(EngineTrackMatcher.match(snapshot, candidates))
    }

    @Test
    fun `字段比较忽略大小写与首尾空白`() {
        val snapshot = track("old", language = " CHI ", title = "Simplified", codec = "AAC")
        val candidates = listOf(track("new", language = "chi", title = "simplified", codec = "aac"))

        assertEquals("new", EngineTrackMatcher.match(snapshot, candidates)?.id)
    }

    @Test
    fun `未知语言两边都为 null 时按语言相等处理`() {
        val snapshot = track("old", language = null, codec = "aac")
        val candidates =
            listOf(
                track("null-lang", language = null, codec = "eac3"),
                track("chi", language = "chi", codec = "aac"),
            )

        assertEquals("null-lang", EngineTrackMatcher.match(snapshot, candidates)?.id)
    }
}
