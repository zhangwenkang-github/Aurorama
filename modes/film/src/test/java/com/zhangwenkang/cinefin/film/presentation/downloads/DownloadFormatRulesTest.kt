package com.zhangwenkang.cinefin.film.presentation.downloads

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadFormatRulesTest {

    @Test
    fun `bytes scale by magnitude`() {
        assertEquals("0 B", DownloadFormatRules.formatBytes(0L))
        assertEquals("512 B", DownloadFormatRules.formatBytes(512L))
        assertEquals("1.00 KB", DownloadFormatRules.formatBytes(1024L))
        assertEquals("1.50 KB", DownloadFormatRules.formatBytes(1536L))
        assertEquals("1.00 MB", DownloadFormatRules.formatBytes(1024L * 1024L))
        assertEquals(
            "12.5 MB",
            DownloadFormatRules.formatBytes((12.5 * 1024 * 1024).toLong()),
        )
        assertEquals(
            "1.50 GB",
            DownloadFormatRules.formatBytes((1.5 * 1024 * 1024 * 1024).toLong()),
        )
        assertEquals(
            "120 GB",
            DownloadFormatRules.formatBytes((120.0 * 1024 * 1024 * 1024).toLong()),
        )
        assertEquals("0 B", DownloadFormatRules.formatBytes(-5L))
    }

    @Test
    fun `speed falls back to placeholder`() {
        assertEquals(DownloadFormatRules.PLACEHOLDER, DownloadFormatRules.formatSpeed(0L))
        assertEquals(DownloadFormatRules.PLACEHOLDER, DownloadFormatRules.formatSpeed(-1L))
        assertEquals("1.00 MB/s", DownloadFormatRules.formatSpeed(1024L * 1024L))
    }

    @Test
    fun `eta formats minutes and hours`() {
        assertEquals(DownloadFormatRules.PLACEHOLDER, DownloadFormatRules.formatEta(null))
        assertEquals(DownloadFormatRules.PLACEHOLDER, DownloadFormatRules.formatEta(-3L))
        assertEquals("0:45", DownloadFormatRules.formatEta(45L))
        assertEquals("3:05", DownloadFormatRules.formatEta(185L))
        assertEquals("1:02:03", DownloadFormatRules.formatEta(3723L))
    }

    @Test
    fun `count progress is completed over total`() {
        assertEquals("3/12", DownloadFormatRules.formatCountProgress(3, 12))
        assertEquals("0/0", DownloadFormatRules.formatCountProgress(0, 0))
        assertEquals("0/4", DownloadFormatRules.formatCountProgress(-2, 4))
    }

    @Test
    fun `percent clamps to range`() {
        assertEquals("18%", DownloadFormatRules.formatPercent(0.18f))
        assertEquals("100%", DownloadFormatRules.formatPercent(1.4f))
        assertEquals("0%", DownloadFormatRules.formatPercent(-0.5f))
    }

    @Test
    fun `size pair omits unknown total`() {
        assertEquals(
            "1.00 GB / 2.00 GB",
            DownloadFormatRules.formatSizePair(
                1024L * 1024L * 1024L,
                2L * 1024L * 1024L * 1024L,
            ),
        )
        assertEquals(
            "1.00 GB",
            DownloadFormatRules.formatSizePair(1024L * 1024L * 1024L, 0L),
        )
    }
}
