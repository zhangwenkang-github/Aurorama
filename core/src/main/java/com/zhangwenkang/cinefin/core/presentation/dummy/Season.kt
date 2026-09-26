package com.zhangwenkang.cinefin.core.presentation.dummy

import com.zhangwenkang.cinefin.models.FindroidImages
import com.zhangwenkang.cinefin.models.FindroidSeason
import java.util.UUID

val dummySeason =
    FindroidSeason(
        id = UUID.randomUUID(),
        name = "Season 1",
        seriesId = UUID.randomUUID(),
        seriesName = "Attack on Titan",
        originalTitle = null,
        overview = "",
        sources = emptyList(),
        indexNumber = 0,
        episodes = emptyList(),
        played = false,
        favorite = false,
        canPlay = true,
        canDownload = false,
        unplayedItemCount = null,
        images = FindroidImages(),
    )
