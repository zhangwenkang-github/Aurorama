package com.zhangwenkang.cinefin.player.local.mpv

import com.zhangwenkang.cinefin.player.core.domain.models.PlayerSubtitleSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MPVServerSubtitleTest {

    private fun source(
        index: Int,
        codec: String = "ass",
        uri: String = "https://server/Subtitles/$index/Stream.ass",
        isExternal: Boolean = false,
        isGraphic: Boolean = false,
    ) =
        PlayerSubtitleSource(
            index = index,
            title = "字幕 $index",
            language = "zh-Hans",
            uri = uri,
            codec = codec,
            isGraphic = isGraphic,
            isExternal = isExternal,
        )

    @Test
    fun `内嵌文本字幕需要注入`() {
        val sources = listOf(source(3), source(4, codec = "srt"))

        val injectable = selectServerSubtitlesToInject(sources)

        assertEquals(listOf(3, 4), injectable.map { it.index })
    }

    @Test
    fun `服务器外挂字幕不重复注入`() {
        val sources = listOf(source(1, isExternal = true), source(2))

        val injectable = selectServerSubtitlesToInject(sources)

        assertEquals(listOf(2), injectable.map { it.index })
    }

    @Test
    fun `图形字幕与拿不到文件的字幕不注入`() {
        val sources =
            listOf(
                source(1, codec = "pgs", isGraphic = true),
                source(2, uri = ""),
                source(3, codec = "ass"),
            )

        val injectable = selectServerSubtitlesToInject(sources)

        assertEquals(listOf(3), injectable.map { it.index })
    }

    @Test
    fun `容器内嵌字幕轨识别`() {
        val tracks = listOf(MpvSubtitleTrackInfo(external = false))

        assertTrue(hasInternalSubtitleTrack(tracks))
    }

    @Test
    fun `外挂字幕轨不算容器内嵌`() {
        val tracks = listOf(MpvSubtitleTrackInfo(external = true))

        assertFalse(hasInternalSubtitleTrack(tracks))
    }

    @Test
    fun `没有字幕轨时不误判`() {
        assertFalse(hasInternalSubtitleTrack(emptyList()))
    }
}
