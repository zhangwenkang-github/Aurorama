package com.zhangwenkang.cinefin.settings

import com.zhangwenkang.cinefin.settings.domain.models.CatalogLibrary
import com.zhangwenkang.cinefin.settings.domain.models.LibraryCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 媒体库目录缓存编解码（设置页「使用哪个媒体库」的选项来源）。 */
class LibraryCatalogTest {
    @Test
    fun roundTripsLibrariesInServerOrder() {
        val libraries =
            listOf(
                CatalogLibrary(id = "1", name = "电影", type = "movies"),
                CatalogLibrary(id = "2", name = "动漫", type = "tvshows"),
                CatalogLibrary(id = "3", name = "书籍", type = "books"),
                CatalogLibrary(id = "4", name = "书籍", type = "books"),
            )

        val decoded = LibraryCatalog.decode(LibraryCatalog.encode(libraries))

        assertEquals(libraries, decoded)
    }

    @Test
    fun sanitizesSeparatorsInsideNames() {
        val libraries = listOf(CatalogLibrary(id = "1", name = "a\u001Fb\nc", type = "movies"))

        val decoded = LibraryCatalog.decode(LibraryCatalog.encode(libraries))

        assertEquals(1, decoded.size)
        assertEquals("1", decoded[0].id)
        assertTrue(decoded[0].name.isNotBlank())
        assertEquals("movies", decoded[0].type)
    }

    @Test
    fun dropsMalformedLinesAndKeepsValidOnes() {
        val raw = "10\u001F电影\u001Fmovies\nbroken-line\n12\u001Ftwo-fields"

        val decoded = LibraryCatalog.decode(raw)

        assertEquals(1, decoded.size)
        assertEquals(CatalogLibrary(id = "10", name = "电影", type = "movies"), decoded[0])
    }

    @Test
    fun decodesNullAndBlankAsEmpty() {
        assertTrue(LibraryCatalog.decode(null).isEmpty())
        assertTrue(LibraryCatalog.decode("").isEmpty())
        assertTrue(LibraryCatalog.decode("   ").isEmpty())
    }
}
