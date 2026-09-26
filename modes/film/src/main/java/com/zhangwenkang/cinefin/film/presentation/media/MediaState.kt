package com.zhangwenkang.cinefin.film.presentation.media

import com.zhangwenkang.cinefin.models.FindroidCollection

data class MediaState(
    val libraries: List<FindroidCollection> = emptyList(),
    val isLoading: Boolean = false,
    val error: Exception? = null,
)
