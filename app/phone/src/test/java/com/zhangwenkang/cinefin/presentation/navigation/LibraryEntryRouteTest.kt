package com.zhangwenkang.cinefin.presentation.navigation

import com.zhangwenkang.cinefin.LibraryRoute
import com.zhangwenkang.cinefin.TemporaryLibraryRoute
import com.zhangwenkang.cinefin.models.CollectionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** W53 追加：侧栏库子项的落点（模式页 = 临时库视图；其余类型 = 通用库内容页）。 */
class LibraryEntryRouteTest {
    @Test
    fun musicLibraryKeepsClickedLibraryId() {
        val route =
            libraryEntryRoute(
                libraryId = "lib-music-test",
                libraryName = "音乐测试",
                libraryType = CollectionType.Music,
            )

        val music = route as TemporaryLibraryRoute
        assertEquals("lib-music-test", music.libraryId)
        assertEquals("音乐测试", music.libraryName)
        assertEquals(TemporaryLibraryKind.Music, music.kind)
    }

    @Test
    fun booksLibraryKeepsClickedLibraryId() {
        val route =
            libraryEntryRoute(
                libraryId = "lib-books-3",
                libraryName = "书籍3",
                libraryType = CollectionType.Books,
            )

        val library = route as TemporaryLibraryRoute
        assertEquals("lib-books-3", library.libraryId)
        assertEquals("书籍3", library.libraryName)
        assertEquals(TemporaryLibraryKind.Books, library.kind)
        assertEquals(CollectionType.Books.type, library.libraryType)
    }

    @Test
    fun videoLibrariesIncludingMixedAndHomeVideosOpenVideoMode() {
        listOf(
                CollectionType.Movies,
                CollectionType.TvShows,
                CollectionType.HomeVideos,
                // 「其他」= 混合库：用户口径归视频页。
                CollectionType.Mixed,
            )
            .forEach { type ->
                val route = libraryEntryRoute("lib-x", "某库", type)
                assertTrue("$type 应进视频临时库视图", route is TemporaryLibraryRoute)
                assertEquals(TemporaryLibraryKind.Video, (route as TemporaryLibraryRoute).kind)
            }
    }

    @Test
    fun playlistsStayOnGenericLibraryPage() {
        val route =
            libraryEntryRoute(
                libraryId = "lib-playlists",
                libraryName = "Playlists",
                libraryType = CollectionType.Playlists,
            )

        assertTrue(route is LibraryRoute)
    }

    @Test
    fun temporaryKindMappingCoversAllTypes() {
        assertEquals(TemporaryLibraryKind.Video, temporaryLibraryKindOf(CollectionType.Movies))
        assertEquals(TemporaryLibraryKind.Video, temporaryLibraryKindOf(CollectionType.TvShows))
        assertEquals(
            TemporaryLibraryKind.Video,
            temporaryLibraryKindOf(CollectionType.HomeVideos),
        )
        assertEquals(TemporaryLibraryKind.Video, temporaryLibraryKindOf(CollectionType.Mixed))
        assertEquals(TemporaryLibraryKind.Music, temporaryLibraryKindOf(CollectionType.Music))
        assertEquals(TemporaryLibraryKind.Books, temporaryLibraryKindOf(CollectionType.Books))
        assertEquals(null, temporaryLibraryKindOf(CollectionType.Playlists))
        assertEquals(null, temporaryLibraryKindOf(CollectionType.BoxSets))
    }
}
