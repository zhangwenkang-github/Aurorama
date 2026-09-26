package com.zhangwenkang.cinefin.film.presentation.season

import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidSeason

data class SeasonState(
    val season: FindroidSeason? = null,
    val episodes: List<FindroidEpisode> = emptyList(),
    val error: Exception? = null,
)
