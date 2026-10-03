package com.zhangwenkang.cinefin.presentation.navigation

import com.zhangwenkang.cinefin.LibraryRoute
import com.zhangwenkang.cinefin.models.CollectionType
import com.zhangwenkang.cinefin.music.presentation.MusicLibraryRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** W53 Bug B：库入口路由的纯逻辑（音乐带具体库 id / 名称；其余类型进通用库内容页）。 */
class LibraryEntryRouteTest {
    @Test
    fun musicLibraryKeepsClickedLibraryId() {
        val route =
            libraryEntryRoute(
                libraryId = "lib-music-test",
                libraryName = "音乐测试",
                libraryType = CollectionType.Music,
            )

        val music = route as MusicLibraryRoute
        assertEquals("lib-music-test", music.libraryId)
        assertEquals("音乐测试", music.libraryName)
    }

    @Test
    fun booksLibraryKeepsClickedLibraryId() {
        val route =
            libraryEntryRoute(
                libraryId = "lib-books-3",
                libraryName = "书籍3",
                libraryType = CollectionType.Books,
            )

        val library = route as LibraryRoute
        assertEquals("lib-books-3", library.libraryId)
        assertEquals("书籍3", library.libraryName)
        assertEquals(CollectionType.Books, library.libraryType)
    }

    @Test
    fun moviesLibraryGoesToGenericLibraryPage() {
        val route =
            libraryEntryRoute(
                libraryId = "lib-movies",
                libraryName = "电影",
                libraryType = CollectionType.Movies,
            )

        assertTrue(route is LibraryRoute)
    }
}
