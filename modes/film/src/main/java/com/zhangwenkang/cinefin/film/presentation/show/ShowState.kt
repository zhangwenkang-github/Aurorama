package com.zhangwenkang.cinefin.film.presentation.show

import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidItemPerson
import com.zhangwenkang.cinefin.models.FindroidSeason
import com.zhangwenkang.cinefin.models.FindroidShow

data class ShowState(
    val show: FindroidShow? = null,
    val nextUp: FindroidEpisode? = null,
    val seasons: List<FindroidSeason> = emptyList(),
    val actors: List<FindroidItemPerson> = emptyList(),
    val director: FindroidItemPerson? = null,
    val writers: List<FindroidItemPerson> = emptyList(),
    /** W51：整剧下载目标（按季 / 集顺序展开的剧集）；null = 尚未按需加载。 */
    val downloadTargets: List<FindroidEpisode>? = null,
    val downloadTargetsLoading: Boolean = false,
    val downloadTargetsError: Exception? = null,
    val error: Exception? = null,
)
