package com.zhangwenkang.cinefin.presentation.navigation

import com.zhangwenkang.cinefin.SearchEntry
import com.zhangwenkang.cinefin.SearchRoute
import com.zhangwenkang.cinefin.defaultSearchScope
import com.zhangwenkang.cinefin.models.CollectionType
import com.zhangwenkang.cinefin.repository.SearchScope
import com.zhangwenkang.cinefin.searchRoute
import com.zhangwenkang.cinefin.searchScopeForLibraryType
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchRouteScopeTest {

    @Test
    fun `各入口默认 scope 固定且无明确域时回全部`() {
        assertEquals(SearchScope.ALL, defaultSearchScope(SearchEntry.HOME))
        assertEquals(SearchScope.ALL, defaultSearchScope(SearchEntry.MEDIA))
        assertEquals(SearchScope.ALL, defaultSearchScope(SearchEntry.MIXED_LIBRARY))
        assertEquals(SearchScope.VIDEO, defaultSearchScope(SearchEntry.VIDEO))
        assertEquals(SearchScope.VIDEO, defaultSearchScope(SearchEntry.VIDEO_LIBRARY))
        assertEquals(SearchScope.MUSIC, defaultSearchScope(SearchEntry.MUSIC))
        assertEquals(SearchScope.MUSIC, defaultSearchScope(SearchEntry.MUSIC_LIBRARY))
        assertEquals(SearchScope.BOOK, defaultSearchScope(SearchEntry.BOOKS))
        assertEquals(SearchScope.BOOK, defaultSearchScope(SearchEntry.BOOK_LIBRARY))
    }

    @Test
    fun `库类型映射区分影视音乐书籍与混合域`() {
        assertEquals(SearchScope.VIDEO, searchScopeForLibraryType(CollectionType.Movies))
        assertEquals(SearchScope.VIDEO, searchScopeForLibraryType(CollectionType.TvShows))
        assertEquals(SearchScope.VIDEO, searchScopeForLibraryType(CollectionType.HomeVideos))
        assertEquals(SearchScope.MUSIC, searchScopeForLibraryType(CollectionType.Music))
        assertEquals(SearchScope.BOOK, searchScopeForLibraryType(CollectionType.Books))
        assertEquals(SearchScope.ALL, searchScopeForLibraryType(CollectionType.Mixed))
    }

    @Test
    fun `搜索路由持久化进入时 scope 而不改偏好`() {
        assertEquals(SearchRoute(initialScope = "MUSIC"), searchRoute(SearchScope.MUSIC))
    }
}
