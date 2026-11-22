package com.zhangwenkang.cinefin.film.presentation.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** W8-T3：评分星条规则（5 星 / 半星 / 10 档，API 1–10 分）。 */
class RatingRulesTest {
    @Test
    fun `半星档位映射为整颗与半颗`() {
        assertEquals(RatingStarFill.FULL, ratingStarFill(7.0, 0))
        assertEquals(RatingStarFill.FULL, ratingStarFill(7.0, 2))
        assertEquals(RatingStarFill.HALF, ratingStarFill(7.0, 3))
        assertEquals(RatingStarFill.EMPTY, ratingStarFill(7.0, 4))
    }

    @Test
    fun `非整档分值 7点5 按半星显示`() {
        assertEquals(RatingStarFill.FULL, ratingStarFill(7.5, 2))
        assertEquals(RatingStarFill.HALF, ratingStarFill(7.5, 3))
        assertEquals(RatingStarFill.EMPTY, ratingStarFill(7.5, 4))
    }

    @Test
    fun `边界 1 分只有半颗星_10 分五颗全亮`() {
        assertEquals(RatingStarFill.HALF, ratingStarFill(1.0, 0))
        assertEquals(RatingStarFill.EMPTY, ratingStarFill(1.0, 1))
        repeat(5) { index -> assertEquals(RatingStarFill.FULL, ratingStarFill(10.0, index)) }
    }

    @Test
    fun `未评分与 0 分星条全空`() {
        repeat(5) { index ->
            assertEquals(RatingStarFill.EMPTY, ratingStarFill(null, index))
            assertEquals(RatingStarFill.EMPTY, ratingStarFill(0.0, index))
        }
    }

    @Test
    fun `标签显示分值并在未评分时为 null`() {
        assertEquals("7.5", ratingLabel(7.5))
        assertEquals("8", ratingLabel(8.0))
        assertEquals("1", ratingLabel(1.0))
        assertNull(ratingLabel(null))
        assertNull(ratingLabel(0.0))
    }

    @Test
    fun `点左右半星写入 1 到 10 分`() {
        assertEquals(1.0, ratingValueForHalfStar(starIndex = 0, rightHalf = false), 0.0)
        assertEquals(2.0, ratingValueForHalfStar(starIndex = 0, rightHalf = true), 0.0)
        assertEquals(9.0, ratingValueForHalfStar(starIndex = 4, rightHalf = false), 0.0)
        assertEquals(10.0, ratingValueForHalfStar(starIndex = 4, rightHalf = true), 0.0)
    }
}
