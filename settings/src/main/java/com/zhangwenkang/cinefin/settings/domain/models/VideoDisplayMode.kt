package com.zhangwenkang.cinefin.settings.domain.models

/**
 * 视频模式页的显示方式（W53，用户 2026-10-03 确认）。
 *
 * - [Cards]：库卡列表（默认）——按库分组展示 16:9 库卡，点进库内容页；
 * - [Aggregated]：聚合列表——把全部视频库的条目（电影 + 剧集）合并成一个网格。
 *
 * [value] 是落盘的稳定字符串（`pref_ui_video_display_mode`），改名时不要动既有取值。
 */
enum class VideoDisplayMode(val value: String) {
    Cards("cards"),
    Aggregated("aggregated");

    companion object {
        /** 默认显示方式 = 库卡列表。 */
        val defaultValue = Cards

        fun fromString(value: String?): VideoDisplayMode =
            entries.firstOrNull { it.value == value } ?: defaultValue
    }
}
