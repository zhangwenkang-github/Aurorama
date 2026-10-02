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

    @Test
    fun `无歌词时回落歌名与歌手`() {
        val empty = LyricsOverlayLines(current = null, next = null)
        assertEquals(
            LyricsOverlayDisplay(first = "夜に駆ける", second = "YOASOBI"),
            overlayDisplayLines(empty, title = "夜に駆ける", artist = "YOASOBI"),
        )
        // 有歌名无歌手：第二行给占位，不出现空白双行
        assertEquals(
            LyricsOverlayDisplay(first = "夜に駆ける", second = "暂无歌词"),
            overlayDisplayLines(empty, title = "夜に駆ける", artist = null),
        )
        assertEquals(
            LyricsOverlayDisplay(first = "暂无歌词", second = null),
            overlayDisplayLines(empty, title = null, artist = null),
        )
        // 有歌词时歌词优先于歌名 / 歌手
        assertEquals(
            LyricsOverlayDisplay(first = "第一句", second = "第二句"),
            overlayDisplayLines(
                LyricsOverlayLines(current = "第一句", next = "第二句"),
                title = "歌名",
                artist = "歌手",
            ),
        )
    }

    @Test
    fun `锁定与交互决定背景显示`() {
        assertTrue(overlayChromeVisible(locked = false, interacting = true))
        assertFalse(overlayChromeVisible(locked = false, interacting = false))
        // 锁定后无论是否交互都保持隐藏（只留歌词文字）
        assertFalse(overlayChromeVisible(locked = true, interacting = true))
        assertFalse(overlayChromeVisible(locked = true, interacting = false))
    }

    @Test
    fun `悬浮窗位置记录与夹取`() {
        assertNull(storedOverlayPosition(-1, -1))
        assertNull(storedOverlayPosition(24, -1))
        assertEquals(LyricsOverlayPosition(24, 300), storedOverlayPosition(24, 300))

        assertEquals(
            LyricsOverlayPosition(x = 200, y = 0),
            clampOverlayPosition(
                LyricsOverlayPosition(500, -20),
                windowWidth = 200,
                windowHeight = 100,
                screenWidth = 400,
                screenHeight = 800,
            ),
        )
        // 窗口比屏幕还大时收敛到 (0,0)，不出现负坐标
        assertEquals(
            LyricsOverlayPosition(0, 0),
            clampOverlayPosition(
                LyricsOverlayPosition(10, 10),
                windowWidth = 600,
                windowHeight = 900,
                screenWidth = 400,
                screenHeight = 800,
            ),
        )
    }
}
