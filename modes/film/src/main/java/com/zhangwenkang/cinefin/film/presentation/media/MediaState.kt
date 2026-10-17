package com.zhangwenkang.cinefin.film.presentation.media

import com.zhangwenkang.cinefin.models.FindroidCollection

data class MediaState(
    val libraries: List<FindroidCollection> = emptyList(),
    val isLoading: Boolean = false,
    /** 下拉刷新指示：已有内容时只显示 PTR，不清空库卡、不叠加骨架。 */
    val refreshing: Boolean = false,
    val error: Exception? = null,
)
