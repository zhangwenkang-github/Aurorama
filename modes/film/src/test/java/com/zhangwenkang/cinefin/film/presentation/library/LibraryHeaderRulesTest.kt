package com.zhangwenkang.cinefin.film.presentation.library

import com.zhangwenkang.cinefin.film.R as FilmR
import com.zhangwenkang.cinefin.models.CollectionType
import com.zhangwenkang.cinefin.models.SortBy
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** W54-B：库内容页头部纯规则（tab 出现规则 / 工具行 / 筛选映射 / 计数文案 / 排序映射）。 */
class LibraryHeaderRulesTest {

    @Test
    fun `library tab is always first`() {
        CollectionType.entries.forEach { type ->
            assertEquals(
                "库名 tab 必须是第一项：$type",
                LibraryTab.Library,
                libraryTabs(type).first(),
            )
        }
    }

    @Test
    fun `tv shows library carries upcoming and episodes tabs`() {
        val tabs = libraryTabs(CollectionType.TvShows)
        assertTrue(tabs.contains(LibraryTab.Upcoming))
        assertTrue(tabs.contains(LibraryTab.Episodes))
        assertTrue(tabs.contains(LibraryTab.Studios))
        assertEquals(
            listOf(
                LibraryTab.Library,
                LibraryTab.Suggestions,
                LibraryTab.Upcoming,
                LibraryTab.Genres,
                LibraryTab.Studios,
                LibraryTab.Episodes,
            ),
            tabs,
        )
    }

    @Test
    fun `movie library has no upcoming or episodes tab`() {
        val tabs = libraryTabs(CollectionType.Movies)
        assertFalse(tabs.contains(LibraryTab.Upcoming))
        assertFalse(tabs.contains(LibraryTab.Episodes))
        assertEquals(
            listOf(
                LibraryTab.Library,
                LibraryTab.Suggestions,
                LibraryTab.Genres,
                LibraryTab.Studios,
            ),
            tabs,
        )
    }

    @Test
    fun `books and home videos drop the studios tab`() {
        listOf(CollectionType.Books, CollectionType.HomeVideos, CollectionType.Music).forEach { type
            ->
            assertFalse("$type 不该出现制片发行商 tab", libraryTabs(type).contains(LibraryTab.Studios))
        }
    }

    @Test
    fun `playlist library only shows its own content tab`() {
        assertEquals(listOf(LibraryTab.Library), libraryTabs(CollectionType.Playlists))
    }

    @Test
    fun `toolbar spec follows the selected tab`() {
        val library = libraryToolbarSpec(LibraryTab.Library)
        assertTrue(library.showViewMode)
        assertTrue(library.showSort)
        assertTrue(library.showFilter)

        val suggestions = libraryToolbarSpec(LibraryTab.Suggestions)
        assertTrue(suggestions.showViewMode)
        assertFalse(suggestions.showSort)
        assertFalse(suggestions.showFilter)

        val genres = libraryToolbarSpec(LibraryTab.Genres)
        assertFalse(genres.showViewMode)
        assertFalse(genres.showSort)
        assertFalse(genres.showFilter)
    }

    @Test
    fun `common filters map onto jellyfin item filters`() {
        assertEquals(
            listOf(ItemFilter.IS_UNPLAYED),
            libraryFilterItemFilters(LibraryFilter.Unplayed),
        )
        assertEquals(listOf(ItemFilter.IS_PLAYED), libraryFilterItemFilters(LibraryFilter.Played))
        assertEquals(
            listOf(ItemFilter.IS_FAVORITE),
            libraryFilterItemFilters(LibraryFilter.Favorite),
        )
        assertNull(libraryFilterItemFilters(null))
    }

    @Test
    fun `playlist library offers no quick filters`() {
        assertTrue(libraryFilters(CollectionType.Playlists).isEmpty())
        assertEquals(
            listOf(LibraryFilter.Unplayed, LibraryFilter.Played, LibraryFilter.Favorite),
            libraryFilters(CollectionType.Movies),
        )
    }

    @Test
    fun `books read wording differs from video wording`() {
        assertEquals(
            FilmR.string.library_filter_unread,
            libraryFilterLabelRes(CollectionType.Books, LibraryFilter.Unplayed),
        )
        assertEquals(
            FilmR.string.library_filter_read,
            libraryFilterLabelRes(CollectionType.Books, LibraryFilter.Played),
        )
        assertEquals(
            FilmR.string.library_filter_unplayed,
            libraryFilterLabelRes(CollectionType.Movies, LibraryFilter.Unplayed),
        )
        assertEquals(
            FilmR.string.library_filter_played,
            libraryFilterLabelRes(CollectionType.TvShows, LibraryFilter.Played),
        )
        assertEquals(
            FilmR.string.library_filter_favorite,
            libraryFilterLabelRes(CollectionType.Books, LibraryFilter.Favorite),
        )
    }

    @Test
    fun `library item types and recursion mirror the jellyfin library type`() {
        assertEquals(listOf(BaseItemKind.MOVIE), libraryItemTypes(CollectionType.Movies))
        assertEquals(listOf(BaseItemKind.SERIES), libraryItemTypes(CollectionType.TvShows))
        assertEquals(listOf(BaseItemKind.BOOK), libraryItemTypes(CollectionType.Books))
        assertEquals(listOf(BaseItemKind.VIDEO), libraryItemTypes(CollectionType.HomeVideos))
        assertTrue(libraryItemTypes(CollectionType.Mixed)!!.contains(BaseItemKind.FOLDER))
        assertNull(libraryItemTypes(CollectionType.Unknown))

        assertTrue(libraryRecursive(libraryItemTypes(CollectionType.Movies)))
        assertFalse(libraryRecursive(libraryItemTypes(CollectionType.Mixed)))
        assertTrue(libraryRecursive(null))
    }

    @Test
    fun `series library rewrites played sorting`() {
        assertEquals(
            SortBy.SERIES_DATE_PLAYED,
            librarySortByFor(CollectionType.TvShows, SortBy.DATE_PLAYED),
        )
        assertEquals(
            SortBy.DATE_PLAYED,
            librarySortByFor(CollectionType.Movies, SortBy.DATE_PLAYED),
        )
        assertEquals(SortBy.NAME, librarySortByFor(CollectionType.TvShows, SortBy.NAME))
    }

    @Test
    fun `count text shows the loaded range over the server total`() {
        assertEquals("1-94 / 94", libraryCountText(loadedCount = 94, totalCount = 94))
        assertEquals("1-30 / 370", libraryCountText(loadedCount = 30, totalCount = 370))
        assertEquals("1-370 / 370", libraryCountText(loadedCount = 400, totalCount = 370))
        assertEquals("0 / 12", libraryCountText(loadedCount = 0, totalCount = 12))
    }

    @Test
    fun `count text degrades when the total is unknown or empty`() {
        assertEquals("30", libraryCountText(loadedCount = 30, totalCount = null))
        assertEquals("", libraryCountText(loadedCount = 0, totalCount = null))
        assertEquals("", libraryCountText(loadedCount = 0, totalCount = 0))
        assertEquals("5", libraryCountText(loadedCount = 5, totalCount = -1))
    }
}
