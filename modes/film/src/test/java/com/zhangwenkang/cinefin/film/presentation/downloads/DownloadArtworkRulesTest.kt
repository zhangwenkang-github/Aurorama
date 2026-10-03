package com.zhangwenkang.cinefin.film.presentation.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** W57 下载页缩略图回退优先级单测（本地优先 / 断链回退 / 类型图标占位）。 */
class DownloadArtworkRulesTest {

    @Test
    fun `本地图优先于远程 URL`() {
        assertEquals(
            "/data/files/images/a/primary",
            DownloadArtworkRules.resolve(
                "/data/files/images/a/primary",
                "https://host/a.jpg",
            ),
        )
    }

    @Test
    fun `本地缺失时回退远程 URL`() {
        assertEquals(
            "https://host/a.jpg",
            DownloadArtworkRules.resolve(null, "https://host/a.jpg"),
        )
    }

    @Test
    fun `本地空白字符串视为缺失`() {
        assertEquals(
            "https://host/a.jpg",
            DownloadArtworkRules.resolve("   ", "https://host/a.jpg"),
        )
    }

    @Test
    fun `两者都缺失时返回 null 由类型图标占位`() {
        assertNull(DownloadArtworkRules.resolve(null, null))
        assertNull(DownloadArtworkRules.resolve("", ""))
    }

    @Test
    fun `剧集回退链按条目季节目远程顺序`() {
        assertEquals(
            "item",
            DownloadArtworkRules.videoFallback("item", "season", "series", "remote"),
        )
        assertEquals(
            "season",
            DownloadArtworkRules.videoFallback(null, "season", "series", "remote"),
        )
        assertEquals(
            "series",
            DownloadArtworkRules.videoFallback(null, null, "series", "remote"),
        )
        assertEquals("remote", DownloadArtworkRules.videoFallback(null, null, null, "remote"))
        assertNull(DownloadArtworkRules.videoFallback(null, null, null, null))
    }
}
