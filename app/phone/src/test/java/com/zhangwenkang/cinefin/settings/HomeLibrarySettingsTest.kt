package com.zhangwenkang.cinefin.settings

import com.zhangwenkang.cinefin.settings.domain.models.HomeLibrarySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** W54-D 首页库级偏好：顺序 / 每库开关映射 / 默认分页映射 / 编解码。 */
class HomeLibrarySettingsTest {
    private val serverOrder = listOf("movie-lib", "anime-lib", "book-lib", "music-lib")

    @Test
    fun applyOrderKeepsStoredOrderAndAppendsUnknownLibraries() {
        val ordered =
            HomeLibrarySettings.applyOrder(
                ids = serverOrder,
                storedOrder = listOf("music-lib", "movie-lib"),
            )

        // 已排过的库按存储顺序在前，未排过的库（服务器新增）按服务器顺序补到末尾。
        assertEquals(listOf("music-lib", "movie-lib", "anime-lib", "book-lib"), ordered)
    }

    @Test
    fun applyOrderWithoutStoredOrderKeepsServerOrder() {
        assertEquals(
            serverOrder,
            HomeLibrarySettings.applyOrder(serverOrder, storedOrder = emptyList()),
        )
    }

    @Test
    fun moveLibrarySwapsNeighboursAndStopsAtBounds() {
        assertEquals(
            listOf("anime-lib", "movie-lib", "book-lib", "music-lib"),
            HomeLibrarySettings.moveLibrary(serverOrder, "movie-lib", delta = 1),
        )
        assertEquals(
            listOf("movie-lib", "book-lib", "anime-lib", "music-lib"),
            HomeLibrarySettings.moveLibrary(serverOrder, "anime-lib", delta = 1),
        )
        // 首项上移 / 末项下移 / 未知 id 都是 no-op。
        assertEquals(serverOrder, HomeLibrarySettings.moveLibrary(serverOrder, "movie-lib", -1))
        assertEquals(serverOrder, HomeLibrarySettings.moveLibrary(serverOrder, "music-lib", 1))
        assertEquals(serverOrder, HomeLibrarySettings.moveLibrary(serverOrder, "missing", 1))
    }

    @Test
    fun libraryVisibilityDefaultsToAllVisible() {
        assertTrue(HomeLibrarySettings.isLibraryVisible("movie-lib", hiddenLibraryIds = emptySet()))

        val hidden = HomeLibrarySettings.setLibraryVisible(emptySet(), "movie-lib", visible = false)
        assertEquals(setOf("movie-lib"), hidden)
        assertFalse(HomeLibrarySettings.isLibraryVisible("movie-lib", hidden))
        assertTrue(HomeLibrarySettings.isLibraryVisible("anime-lib", hidden))

        val restored = HomeLibrarySettings.setLibraryVisible(hidden, "movie-lib", visible = true)
        assertTrue(HomeLibrarySettings.isLibraryVisible("movie-lib", restored))
    }

    @Test
    fun pageKeysFollowLibraryType() {
        assertEquals(
            listOf(
                HomeLibrarySettings.PAGE_LIBRARY,
                HomeLibrarySettings.PAGE_SUGGESTIONS,
                HomeLibrarySettings.PAGE_UPCOMING,
                HomeLibrarySettings.PAGE_GENRES,
                HomeLibrarySettings.PAGE_STUDIOS,
                HomeLibrarySettings.PAGE_EPISODES,
            ),
            HomeLibrarySettings.pageKeys("tvshows"),
        )
        assertEquals(
            listOf(
                HomeLibrarySettings.PAGE_LIBRARY,
                HomeLibrarySettings.PAGE_SUGGESTIONS,
                HomeLibrarySettings.PAGE_GENRES,
            ),
            HomeLibrarySettings.pageKeys("books"),
        )
        // 混合库（collectionType = null）/ 文件夹库保留制片发行商。
        assertTrue(HomeLibrarySettings.PAGE_STUDIOS in HomeLibrarySettings.pageKeys("null"))
        // 播放列表库只有库内容。
        assertEquals(
            listOf(HomeLibrarySettings.PAGE_LIBRARY),
            HomeLibrarySettings.pageKeys("playlists"),
        )
    }

    @Test
    fun resolvePageKeyFallsBackWhenStoredValueIsNotValidForType() {
        assertEquals(
            HomeLibrarySettings.PAGE_UPCOMING,
            HomeLibrarySettings.resolvePageKey("tvshows", "upcoming"),
        )
        // 剧集库存了「即将播出」，之后库类型变成电影 → 回落默认分页。
        assertEquals(
            HomeLibrarySettings.DEFAULT_PAGE_KEY,
            HomeLibrarySettings.resolvePageKey("movies", "upcoming"),
        )
        assertEquals(
            HomeLibrarySettings.DEFAULT_PAGE_KEY,
            HomeLibrarySettings.resolvePageKey("movies", null),
        )
    }

    @Test
    fun pageMapAndOrderRoundTrip() {
        val pages =
            mapOf(
                "movie-lib" to HomeLibrarySettings.PAGE_GENRES,
                "anime-lib" to HomeLibrarySettings.PAGE_EPISODES,
            )
        assertEquals(
            pages,
            HomeLibrarySettings.decodePageMap(HomeLibrarySettings.encodePageMap(pages)),
        )

        val order = listOf("music-lib", "book-lib", "movie-lib")
        assertEquals(
            order,
            HomeLibrarySettings.decodeIdList(HomeLibrarySettings.encodeIdList(order)),
        )
    }

    @Test
    fun malformedPageMapLinesAreDropped() {
        val raw = "movie-lib\u001Fgenres\nbroken\n\u001Fgenres\nanime-lib\u001F"
        assertEquals(
            mapOf("movie-lib" to HomeLibrarySettings.PAGE_GENRES),
            HomeLibrarySettings.decodePageMap(raw),
        )
    }
}
