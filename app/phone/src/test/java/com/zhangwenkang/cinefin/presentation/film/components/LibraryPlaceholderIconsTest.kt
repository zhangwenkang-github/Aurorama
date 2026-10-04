package com.zhangwenkang.cinefin.presentation.film.components

import com.zhangwenkang.cinefin.core.R
import com.zhangwenkang.cinefin.models.CollectionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** W69c：首页「最新 · <库名>」走廊的无图占位图标映射（书籍 / 音乐 / 其余）。 */
class LibraryPlaceholderIconsTest {

    @Test
    fun `书籍库用书图标 音乐库用音符 其余库不加占位`() {
        assertEquals(R.drawable.ic_book, libraryPlaceholderIconRes(CollectionType.Books))
        assertEquals(R.drawable.ic_music, libraryPlaceholderIconRes(CollectionType.Music))
        assertNull(libraryPlaceholderIconRes(CollectionType.Movies))
        assertNull(libraryPlaceholderIconRes(CollectionType.TvShows))
        assertNull(libraryPlaceholderIconRes(CollectionType.HomeVideos))
    }
}
