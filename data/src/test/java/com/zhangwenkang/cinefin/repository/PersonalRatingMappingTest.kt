package com.zhangwenkang.cinefin.repository

import com.zhangwenkang.cinefin.models.toFindroidEpisode
import com.zhangwenkang.cinefin.models.toFindroidMovie
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.UserItemDataDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** W8-T3 契约：`UserData.Rating` → `FindroidItem.personalRating`（0–10 分，含半星精度 7.5）。 */
class PersonalRatingMappingTest {
    private val repository = mockk<JellyfinRepository>(relaxed = true)

    @Test
    fun `电影评分保留半星精度`() = runBlocking {
        val movie = movieDto(rating = 7.5).toFindroidMovie(repository)

        assertEquals(7.5, movie.personalRating)
    }

    @Test
    fun `电影评分边界值 1 与 10`() = runBlocking {
        assertEquals(1.0, movieDto(rating = 1.0).toFindroidMovie(repository).personalRating)
        assertEquals(10.0, movieDto(rating = 10.0).toFindroidMovie(repository).personalRating)
    }

    @Test
    fun `未评分映射为 null`() = runBlocking {
        assertNull(movieDto(rating = null).toFindroidMovie(repository).personalRating)
        assertNull(episodeDto(rating = null).toFindroidEpisode(repository)?.personalRating)
    }

    @Test
    fun `单集评分保留半星精度`() = runBlocking {
        val episode = episodeDto(rating = 7.5).toFindroidEpisode(repository)

        assertEquals(7.5, episode?.personalRating)
    }

    private fun movieDto(rating: Double?) =
        BaseItemDto(
            id = ITEM_ID,
            name = "movie",
            type = BaseItemKind.MOVIE,
            userData = userData(rating),
        )

    private fun episodeDto(rating: Double?) =
        BaseItemDto(
            id = ITEM_ID,
            name = "episode",
            type = BaseItemKind.EPISODE,
            seriesId = SERIES_ID,
            seasonId = SEASON_ID,
            indexNumber = 1,
            parentIndexNumber = 1,
            userData = userData(rating),
        )

    private fun userData(rating: Double?): UserItemDataDto =
        mockk<UserItemDataDto>(relaxed = true).also { userData ->
            every { userData.rating } returns rating
        }

    private companion object {
        val ITEM_ID: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val SERIES_ID: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
        val SEASON_ID: UUID = UUID.fromString("33333333-3333-4333-8333-333333333333")
    }
}
