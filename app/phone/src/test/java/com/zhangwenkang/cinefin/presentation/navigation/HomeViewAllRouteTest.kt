package com.zhangwenkang.cinefin.presentation.navigation

import com.zhangwenkang.cinefin.LibraryRoute
import com.zhangwenkang.cinefin.models.CollectionType
import com.zhangwenkang.cinefin.models.SortBy
import com.zhangwenkang.cinefin.models.SortOrder
import org.junit.Assert.assertEquals
import org.junit.Test

/** W54-D 修 bug ①：首页「最新 · <库名>」右侧「全部」的落点是纯函数。 */
class HomeViewAllRouteTest {
    @Test
    fun keepsClickedLibraryAndDefaultsToRecentlyAdded() {
        val route = homeViewAllRoute("lib-movies", "电影", CollectionType.Movies)

        assertEquals("lib-movies", route.libraryId)
        assertEquals("电影", route.libraryName)
        assertEquals(CollectionType.Movies, route.libraryType)
        // 「最近添加」= DateCreated 倒序（SortBy.DATE_ADDED）。
        assertEquals(SortBy.DATE_ADDED.name, route.sortBy)
        assertEquals(SortOrder.DESCENDING.name, route.sortOrder)
    }

    @Test
    fun everyLibraryTypeGoesToTheSameLibraryContentRoute() {
        CollectionType.supported.forEach { type ->
            val route = homeViewAllRoute("lib-${type.type}", "某库", type)
            assertEquals(LibraryRoute::class, route::class)
            assertEquals(type, route.libraryType)
        }
    }
}
