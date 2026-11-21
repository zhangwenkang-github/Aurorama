package com.zhangwenkang.cinefin.book.presentation.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** W6 T2：进度条纯逻辑（刻度计算 / 页码换算 / 浮层文案 / 书签点与跳转节流）。 */
class ReaderProgressBarTest {

    @Test
    fun `positions 按资源聚合章节刻度`() {
        val positions =
            listOf(
                ReaderPosition("ch1.xhtml", 0.0),
                ReaderPosition("ch1.xhtml", 0.02),
                ReaderPosition("ch2.xhtml", 0.5),
                ReaderPosition("ch2.xhtml", 0.55),
                ReaderPosition("ch3.xhtml", 0.25),
            )
        // 每资源取最小 progression；0 起点与轨道起点重合不画；结果升序去重。
        assertEquals(listOf(0.25f, 0.5f), readerChapterTicks(positions))
    }

    @Test
    fun `章节刻度忽略 fragment 与非法值`() {
        val positions =
            listOf(
                ReaderPosition("ch2.xhtml#a", 0.4),
                ReaderPosition("ch2.xhtml#b", 0.3),
                ReaderPosition("ch3.xhtml", Double.NaN),
                ReaderPosition("ch4.xhtml", 1.4),
            )
        assertEquals(listOf(0.3f), readerChapterTicks(positions))
    }

    @Test
    fun `空 positions 没有刻度`() {
        assertTrue(readerChapterTicks(emptyList()).isEmpty())
    }

    @Test
    fun `整书进度换算 positions 页码`() {
        assertEquals(0, readerPositionPage(0.5, 0))
        assertEquals(1, readerPositionPage(0.0, 300))
        assertEquals(1, readerPositionPage(-1.0, 300))
        assertEquals(300, readerPositionPage(1.0, 300))
        assertEquals(42, readerPositionPage(41.0 / 300.0, 300))
        assertEquals(1, readerPositionPage(Double.NaN, 300))
    }

    @Test
    fun `页码文案`() {
        assertEquals("第 7 页 / 共 42 页", readerPageLabel(7, 42))
    }

    @Test
    fun `拖动浮层 EPUB 显百分比与页码`() {
        val overlay = readerScrubOverlay(0.42, "第六章", pageCount = 100, percentVisible = true)
        assertEquals("第六章", overlay.title)
        // 42% 对应第 43 页的起始位置（页 42 覆盖 [0.41, 0.42)）。
        assertEquals("42.0% · 第 43 页 / 共 100 页", overlay.detail)
    }

    @Test
    fun `拖动浮层 positions 不可用时只显百分比`() {
        val overlay = readerScrubOverlay(0.1234, "第六章", pageCount = null, percentVisible = true)
        assertEquals("12.3%", overlay.detail)
    }

    @Test
    fun `拖动浮层 PDF 只显页码`() {
        val overlay = readerScrubOverlay(0.5, null, pageCount = 24, percentVisible = false)
        assertNull(overlay.title)
        assertEquals("第 13 页 / 共 24 页", overlay.detail)
    }

    @Test
    fun `拖动浮层空白章节名被丢弃`() {
        val overlay = readerScrubOverlay(0.1, "   ", pageCount = 10, percentVisible = true)
        assertNull(overlay.title)
    }

    @Test
    fun `书签点命中取最近且在容差内`() {
        val bookmarks = listOf(0.1f, 0.5f, 0.9f)
        assertEquals(1, readerBookmarkHit(bookmarks, 0.505f, tolerance = 0.02f))
        assertNull(readerBookmarkHit(bookmarks, 0.7f, tolerance = 0.02f))
        // 两个点都在容差内时取更近的
        assertEquals(0, readerBookmarkHit(listOf(0.10f, 0.12f), 0.105f, tolerance = 0.03f))
        assertNull(readerBookmarkHit(bookmarks, Float.NaN, tolerance = 0.02f))
    }

    @Test
    fun `松手才跳转且拖动优先于书签点`() {
        val bookmarks = listOf(0.5f)
        // 点按命中书签 → 跳书签（不重复跳位置）
        assertTrue(
            readerScrubRelease(0.5f, dragging = false, bookmarks, tolerance = 0.02f)
                is ReaderScrubRelease.Bookmark
        )
        // 拖动经过书签点、松手 → 只提交位置（一次）
        val release = readerScrubRelease(0.5f, dragging = true, bookmarks, tolerance = 0.02f)
        assertTrue(release is ReaderScrubRelease.JumpTo)
        assertEquals(0.5f, (release as ReaderScrubRelease.JumpTo).fraction, 0.0f)
    }

    @Test
    fun `点按轨道跳转到目标且夹取 0 到 1`() {
        val release = readerScrubRelease(1.4f, dragging = false, emptyList(), tolerance = 0.02f)
        assertEquals(1f, (release as ReaderScrubRelease.JumpTo).fraction, 0.0f)
        val negative = readerScrubRelease(-0.2f, dragging = true, emptyList(), tolerance = 0.02f)
        assertEquals(0f, (negative as ReaderScrubRelease.JumpTo).fraction, 0.0f)
    }

    @Test
    fun `双击书签按当前位置匹配`() {
        val progressions = listOf(0.1, 0.5000000001, 0.9)
        assertEquals(1, readerBookmarkIndexAt(progressions, 0.5))
        assertNull(readerBookmarkIndexAt(progressions, 0.3))
        assertNull(readerBookmarkIndexAt(progressions, Double.NaN))
    }
}
