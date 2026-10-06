package com.zhangwenkang.cinefin.presentation.film.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** W75 #12：评分展示口径（电影 / 剧集详情信息行与单集页头图元信息共用）的回归测试。 */
class CommunityRatingTest {
    @Test
    fun communityRatingText_formatsOneDecimalWithStar() {
        assertEquals("★ 8.5", communityRatingText(8.5f))
        assertEquals("★ 9.0", communityRatingText(9f))
        assertEquals("★ 10.0", communityRatingText(10f))
    }

    @Test
    fun communityRatingText_roundsToOneDecimal() {
        assertEquals("★ 7.2", communityRatingText(7.24f))
    }

    @Test
    fun communityRatingText_isNullWhenMissing() {
        assertNull(communityRatingText(null))
    }
}
