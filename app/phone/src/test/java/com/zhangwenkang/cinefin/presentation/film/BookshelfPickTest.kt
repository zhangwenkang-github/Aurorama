package com.zhangwenkang.cinefin.presentation.film

import com.zhangwenkang.cinefin.models.CollectionType
import com.zhangwenkang.cinefin.models.FindroidCollection
import com.zhangwenkang.cinefin.models.FindroidImages
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 书架入口的选择规则：优先第一个非空 books 库；全空退第一个 books 库；没有 books 库返回 null（空态）。 */
class BookshelfPickTest {
    @Test
    fun picksFirstNonEmptyBooksLibrary() {
        val empty = booksLibrary("书籍3-空壳")
        val nonEmpty = booksLibrary("书籍")
        val libraries = listOf(movieLibrary(), empty, nonEmpty)

        val picked = pickBooksLibrary(libraries, nonEmptyLibraryIds = setOf(nonEmpty.id))

        assertEquals(nonEmpty.id, picked?.id)
    }

    @Test
    fun fallsBackToFirstBooksLibraryWhenAllEmpty() {
        val first = booksLibrary("书籍")
        val second = booksLibrary("书籍3")
        val libraries = listOf(movieLibrary(), first, second)

        val picked = pickBooksLibrary(libraries, nonEmptyLibraryIds = emptySet())

        assertEquals(first.id, picked?.id)
    }

    @Test
    fun returnsNullWhenServerHasNoBooksLibrary() {
        val libraries = listOf(movieLibrary(), musicLibrary())

        assertNull(pickBooksLibrary(libraries, nonEmptyLibraryIds = emptySet()))
    }

    @Test
    fun prefersConfiguredLibraryEvenWhenItIsEmpty() {
        val empty = booksLibrary("书籍3-空壳")
        val nonEmpty = booksLibrary("书籍")
        val libraries = listOf(empty, nonEmpty)

        val picked =
            pickBooksLibrary(
                libraries = libraries,
                nonEmptyLibraryIds = setOf(nonEmpty.id),
                preferredLibraryId = empty.id,
            )

        // 显式选定优先：哪怕这个库是空的，也不偷偷换到别的库（页面显示空态）。
        assertEquals(empty.id, picked?.id)
    }

    @Test
    fun ignoresConfiguredLibraryThatNoLongerExists() {
        val first = booksLibrary("书籍")
        val libraries = listOf(first)

        val picked =
            pickBooksLibrary(
                libraries = libraries,
                nonEmptyLibraryIds = emptySet(),
                preferredLibraryId = UUID.randomUUID(),
            )

        assertEquals(first.id, picked?.id)
    }

    private fun booksLibrary(name: String) =
        FindroidCollection(
            id = UUID.randomUUID(),
            name = name,
            type = CollectionType.Books,
            images = FindroidImages(),
        )

    private fun movieLibrary() =
        FindroidCollection(
            id = UUID.randomUUID(),
            name = "电影",
            type = CollectionType.Movies,
            images = FindroidImages(),
        )

    private fun musicLibrary() =
        FindroidCollection(
            id = UUID.randomUUID(),
            name = "音乐",
            type = CollectionType.Music,
            images = FindroidImages(),
        )
}
