package com.zhangwenkang.cinefin.presentation.film.components

import com.zhangwenkang.cinefin.core.presentation.dummy.dummyCollections
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyEpisode
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyMovie
import com.zhangwenkang.cinefin.core.presentation.dummy.dummySeason
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyShow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * W8-T3'：卡片评分徽标口径——复用 [communityRatingText]（「★ x.x」一位小数）， 类型范围 Movie / Episode / Show / Season，null
 * 不渲染，其他类型不显示。
 */
class CommunityRatingBadgeTest {
    @Test
    fun `四类条目有评分时显示一位小数`() {
        assertEquals("★ 7.8", communityRatingBadgeText(dummyMovie.copy(communityRating = 7.8f)))
        assertEquals(
            "★ 8.5",
            communityRatingBadgeText(dummyEpisode.copy(communityRating = 8.5f)),
        )
        assertEquals("★ 9.0", communityRatingBadgeText(dummyShow.copy(communityRating = 9.0f)))
        assertEquals("★ 6.9", communityRatingBadgeText(dummySeason.copy(communityRating = 6.9f)))
    }

    @Test
    fun `无评分条目返回 null 不渲染`() {
        assertNull(communityRatingBadgeText(dummyMovie.copy(communityRating = null)))
        assertNull(communityRatingBadgeText(dummyEpisode.copy(communityRating = null)))
        assertNull(communityRatingBadgeText(dummyShow.copy(communityRating = null)))
        assertNull(communityRatingBadgeText(dummySeason.copy(communityRating = null)))
    }

    @Test
    fun `类型范围外的条目不显示评分`() {
        assertNull(communityRatingBadgeText(dummyCollections.first()))
    }

    /** 2.0.3-C：首页视频卡传 `showRating = false`，即使是电影也不渲染评分徽标。 */
    @Test
    fun `首页关闭评分时不渲染徽标`() {
        val movie = dummyMovie.copy(communityRating = 8.0f)
        assertEquals("★ 8.0", communityRatingBadgeText(movie, showRating = true))
        assertNull(communityRatingBadgeText(movie, showRating = false))
        assertNull(
            communityRatingBadgeText(dummyShow.copy(communityRating = 9.0f), showRating = false)
        )
    }
}
