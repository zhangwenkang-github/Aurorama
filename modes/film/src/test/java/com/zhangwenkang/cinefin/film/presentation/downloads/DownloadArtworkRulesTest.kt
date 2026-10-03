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
    fun `W59 视频层级每级只认自己的图`() {
        assertEquals(
            "show-poster",
            DownloadArtworkRules.videoArtwork(
                DownloadArtworkRules.Level.SHOW,
                "show-poster",
                "https://host/show.jpg",
            ),
        )
        assertEquals(
            "season-poster",
            DownloadArtworkRules.videoArtwork(
                DownloadArtworkRules.Level.SEASON,
                "season-poster",
                null,
            ),
        )
        assertEquals(
            "https://host/episode.jpg",
            DownloadArtworkRules.videoArtwork(
                DownloadArtworkRules.Level.EPISODE,
                null,
                "https://host/episode.jpg",
            ),
        )
    }

    @Test
    fun `W59 本级缺图不跨级回退直接类型占位`() {
        // 剧集：即使季 / 节目海报存在，也不得采纳（防串图）。
        assertNull(
            DownloadArtworkRules.videoArtwork(
                DownloadArtworkRules.Level.EPISODE,
                ownLocal = null,
                ownRemote = null,
                seasonImage = "season-poster",
                showImage = "show-poster",
            )
        )
        // 季：即使节目海报存在，也不得采纳。
        assertNull(
            DownloadArtworkRules.videoArtwork(
                DownloadArtworkRules.Level.SEASON,
                ownLocal = null,
                ownRemote = null,
                showImage = "show-poster",
            )
        )
        // 节目：即使季海报存在，也不得采纳。
        assertNull(
            DownloadArtworkRules.videoArtwork(
                DownloadArtworkRules.Level.SHOW,
                ownLocal = null,
                ownRemote = null,
                seasonImage = "season-poster",
            )
        )
        // 本级本地 / 远程都缺 → null（视图层回退类型图标）。
        assertNull(
            DownloadArtworkRules.videoArtwork(
                DownloadArtworkRules.Level.MOVIE,
                null,
                null,
            )
        )
    }

    @Test
    fun `W59 本级本地图优先于本级远程图`() {
        assertEquals(
            "/files/images/episode/primary",
            DownloadArtworkRules.videoArtwork(
                DownloadArtworkRules.Level.EPISODE,
                "/files/images/episode/primary",
                "https://host/episode.jpg",
            ),
        )
    }
}
