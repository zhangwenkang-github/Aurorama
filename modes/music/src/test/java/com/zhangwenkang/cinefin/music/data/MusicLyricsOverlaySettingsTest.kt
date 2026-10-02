package com.zhangwenkang.cinefin.music.data

import com.zhangwenkang.cinefin.music.data.lyrics.LyricsRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 桌面歌词悬浮窗的设置映射与"当前句 + 下一句"取值（W23-MUSIC · D 组）。 */
class MusicLyricsOverlaySettingsTest {

    private val rows =
        listOf(
            LyricsRow(startMs = 0L, mainText = "第一句"),
            LyricsRow(startMs = 5_000L, mainText = "第二句"),
            LyricsRow(startMs = 9_000L, mainText = "第三句"),
        )

    @Test
    fun `颜色与字号档位循环切换`() {
        assertEquals(LyricsOverlayTint.TURQUOISE, LyricsOverlayTint.MOON_WHITE.next())
        assertEquals(
            LyricsOverlayTint.MOON_WHITE,
            LyricsOverlayTint.entries.last().next(),
        )
        assertEquals(5, LyricsOverlayTint.entries.size)

        assertEquals(LyricsOverlaySize.MEDIUM, LyricsOverlaySize.SMALL.next())
        assertEquals(LyricsOverlaySize.SMALL, LyricsOverlaySize.entries.last().next())
        assertTrue(LyricsOverlaySize.EXTRA_LARGE.currentSp > LyricsOverlaySize.SMALL.currentSp)
        assertTrue(LyricsOverlaySize.EXTRA_LARGE.nextSp < LyricsOverlaySize.EXTRA_LARGE.currentSp)
    }

    @Test
    fun `偏好键值解析与回落`() {
        assertEquals(LyricsOverlayTint.AMBER, LyricsOverlayTint.fromKey("amber"))
        assertEquals(LyricsOverlayTint.MOON_WHITE, LyricsOverlayTint.fromKey("unknown"))
        assertEquals(LyricsOverlayTint.MOON_WHITE, LyricsOverlayTint.fromKey(null))

        assertEquals(LyricsOverlaySize.EXTRA_LARGE, LyricsOverlaySize.fromKey("xlarge"))
        assertEquals(LyricsOverlaySize.MEDIUM, LyricsOverlaySize.fromKey(""))
        assertEquals(LyricsOverlaySize.MEDIUM, LyricsOverlaySize.fromKey(null))
    }

    @Test
    fun `双行取当前句与下一句`() {
        assertEquals(
            LyricsOverlayLines(current = "第一句", next = "第二句"),
            overlayLyricsLines(rows, activeIndex = 0),
        )
        assertEquals(
            LyricsOverlayLines(current = "第二句", next = "第三句"),
            overlayLyricsLines(rows, activeIndex = 1),
        )
        // 最后一句没有下一句
        assertEquals(
            LyricsOverlayLines(current = "第三句", next = null),
            overlayLyricsLines(rows, activeIndex = 2),
        )
        // 越界索引安全收敛
        assertEquals(
            LyricsOverlayLines(current = "第三句", next = null),
            overlayLyricsLines(rows, activeIndex = 99),
        )
        assertEquals(
            LyricsOverlayLines(current = "第一句", next = "第二句"),
            overlayLyricsLines(rows, activeIndex = -3),
        )
    }

    @Test
    fun `空歌词返回两行空值`() {
        val lines = overlayLyricsLines(emptyList(), activeIndex = 0)
        assertNull(lines.current)
        assertNull(lines.next)
        assertFalse(lines.hasContent)
        assertTrue(overlayLyricsLines(rows, 0).hasContent)
    }
}
