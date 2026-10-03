package com.zhangwenkang.cinefin.presentation.film

import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidImages
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.models.FindroidShow
import com.zhangwenkang.cinefin.models.SortBy
import com.zhangwenkang.cinefin.models.SortOrder
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

/** W60b 我的收藏页筛选 / 排序规则（纯函数）单测。 */
class FavoriteRulesTest {

    @Test
    fun `sort spec maps date added to created descending`() {
        assertEquals(
            FavoriteSortSpec(SortBy.DATE_ADDED, SortOrder.DESCENDING),
            favoriteSortSpec(FavoriteSort.DATE_ADDED),
        )
        assertEquals(
            FavoriteSortSpec(SortBy.NAME, SortOrder.ASCENDING),
            favoriteSortSpec(FavoriteSort.NAME),
        )
    }

    @Test
    fun `type filter matches only the requested kind`() {
        val movie = movie()
        val show = show()
        val episode = episode()

        assertEquals(true, favoriteTypeFilterMatches(movie, FavoriteTypeFilter.ALL))
        assertEquals(false, favoriteTypeFilterMatches(movie, FavoriteTypeFilter.SHOWS))
        assertEquals(true, favoriteTypeFilterMatches(show, FavoriteTypeFilter.SHOWS))
        assertEquals(false, favoriteTypeFilterMatches(show, FavoriteTypeFilter.EPISODES))
        assertEquals(true, favoriteTypeFilterMatches(episode, FavoriteTypeFilter.EPISODES))
        assertEquals(false, favoriteTypeFilterMatches(episode, FavoriteTypeFilter.MOVIES))
    }

    @Test
    fun `filter keeps server order inside the selected type`() {
        val firstMovie = movie(name = "第一部")
        val show = show()
        val secondMovie = movie(name = "第二部")

        assertEquals(
            listOf(firstMovie, show, secondMovie),
            filterFavoriteItems(listOf(firstMovie, show, secondMovie), FavoriteTypeFilter.ALL),
        )
        assertEquals(
            listOf(firstMovie, secondMovie),
            filterFavoriteItems(listOf(firstMovie, show, secondMovie), FavoriteTypeFilter.MOVIES),
        )
        assertEquals(
            emptyList<FindroidItem>(),
            filterFavoriteItems(listOf(firstMovie, secondMovie), FavoriteTypeFilter.EPISODES),
        )
    }

    private fun movie(name: String = "测试电影"): FindroidItem =
        FindroidMovie(
            id = UUID.randomUUID(),
            name = name,
            originalTitle = null,
            overview = "",
            sources = emptyList(),
            played = false,
            favorite = true,
            canPlay = true,
            canDownload = false,
            runtimeTicks = 0L,
            playbackPositionTicks = 0L,
            premiereDate = null,
            people = emptyList(),
            genres = emptyList(),
            communityRating = null,
            officialRating = null,
            status = "Ended",
            productionYear = null,
            endDate = null,
            trailer = null,
            images = FindroidImages(),
            chapters = emptyList(),
            trickplayInfo = null,
        )

    private fun show(): FindroidItem =
        FindroidShow(
            id = UUID.randomUUID(),
            name = "测试剧集",
            originalTitle = null,
            overview = "",
            sources = emptyList(),
            seasons = emptyList(),
            played = false,
            favorite = true,
            canPlay = true,
            canDownload = false,
            unplayedItemCount = 1,
            genres = emptyList(),
            people = emptyList(),
            runtimeTicks = 0L,
            communityRating = null,
            officialRating = null,
            status = "Ended",
            productionYear = null,
            endDate = null,
            trailer = null,
            images = FindroidImages(),
        )

    private fun episode(): FindroidItem =
        FindroidEpisode(
            id = UUID.randomUUID(),
            name = "第 1 集",
            originalTitle = null,
            overview = "",
            indexNumber = 1,
            indexNumberEnd = null,
            parentIndexNumber = 1,
            sources = emptyList(),
            played = false,
            favorite = true,
            canPlay = true,
            canDownload = false,
            runtimeTicks = 0L,
            playbackPositionTicks = 0L,
            premiereDate = null,
            seriesId = UUID.randomUUID(),
            seriesName = "测试剧集",
            seasonId = UUID.randomUUID(),
            seasonName = null,
            communityRating = null,
            people = emptyList(),
            images = FindroidImages(),
            chapters = emptyList(),
            trickplayInfo = null,
        )
}
