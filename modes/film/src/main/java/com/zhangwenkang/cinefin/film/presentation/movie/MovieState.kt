package com.zhangwenkang.cinefin.film.presentation.movie

import com.zhangwenkang.cinefin.models.FindroidItemPerson
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.models.VideoMetadata

data class MovieState(
    val movie: FindroidMovie? = null,
    val videoMetadata: VideoMetadata? = null,
    val actors: List<FindroidItemPerson> = emptyList(),
    val director: FindroidItemPerson? = null,
    val writers: List<FindroidItemPerson> = emptyList(),
    val displayExtraInfo: Boolean = false,
    val error: Exception? = null,
)
