package com.zhangwenkang.cinefin.repository

import com.zhangwenkang.cinefin.models.toFindroidSeason
import io.mockk.mockk
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * W8-T3' 契约：季列表卡片评分——`BaseItemDto.CommunityRating` 映射进
 * [com.zhangwenkang.cinefin.models.FindroidSeason]。
 */
class SeasonCommunityRatingMappingTest {
    private val repository = mockk<JellyfinRepository>(relaxed = true)

    @Test
    fun `季映射携带社区评分`() = runBlocking {
        val season = seasonDto(rating = 6.9f).toFindroidSeason(repository)

        assertEquals(6.9f, season.communityRating)
    }

    @Test
    fun `无评分的季为 null`() = runBlocking {
        assertNull(seasonDto(rating = null).toFindroidSeason(repository).communityRating)
    }

    private fun seasonDto(rating: Float?) =
        BaseItemDto(
            id = SEASON_ID,
            name = "Season 1",
            type = BaseItemKind.SEASON,
            seriesId = SERIES_ID,
            indexNumber = 1,
            communityRating = rating,
        )

    private companion object {
        val SEASON_ID: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val SERIES_ID: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
    }
}
