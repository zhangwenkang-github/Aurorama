package com.zhangwenkang.cinefin.film.presentation.detail

/** W8-T3：评分星条的单个星位状态（显示口径见 [ratingStarFill]）。 */
enum class RatingStarFill {
    EMPTY,
    HALF,
    FULL,
}

/**
 * 分数 → 第 [starIndex] 颗星（0 起）的填充状态（W8-T3 设计定稿：5 星、半星、10 档）。
 *
 * 服务器 `UserData.Rating` 单位是「分」（0–10，每颗星 2 分）。半星 = 1 分；非整档分值（如 7.5， 来自其他客户端）按「余数 ≥ 0.5
 * 星取半星」显示，数字标签仍显示原始分值。
 */
fun ratingStarFill(rating: Double?, starIndex: Int): RatingStarFill {
    if (rating == null || rating <= 0.0 || starIndex !in 0..4) return RatingStarFill.EMPTY
    val fullStars = (rating / 2.0).toInt()
    val hasHalf = rating - fullStars * 2.0 >= 1.0
    return when {
        starIndex < fullStars -> RatingStarFill.FULL
        starIndex == fullStars && hasHalf -> RatingStarFill.HALF
        else -> RatingStarFill.EMPTY
    }
}

/** 评分标签：已评分时显示服务器分值（`8` → "8"，`7.5` → "7.5"），未评分（null / 0）→ null， 由调用方回落到「评分」文案。 */
fun ratingLabel(rating: Double?): String? {
    if (rating == null || rating <= 0.0) return null
    val whole = rating.toInt()
    return if (rating == whole.toDouble()) whole.toString() else rating.toString()
}

/** 点击第 [starIndex] 颗星的左 / 右半 → 写入分值（1–10 分，半星 = 1 分）。 */
fun ratingValueForHalfStar(starIndex: Int, rightHalf: Boolean): Double =
    (starIndex * 2 + if (rightHalf) 2 else 1).toDouble()
