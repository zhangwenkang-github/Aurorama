package com.zhangwenkang.cinefin.presentation.film.components

import java.util.Locale

/**
 * W75 #12：社区评分的唯一展示口径（电影 / 剧集详情信息行与单集页头图元信息共用）。
 *
 * 有评分 → 「★ x.x」（一位小数，固定 `.` 作小数点）；无评分（null）→ 返回 null，调用方不渲染评分元素。
 */
fun communityRatingText(rating: Float?): String? = rating?.let {
    String.format(Locale.US, "★ %.1f", it)
}
