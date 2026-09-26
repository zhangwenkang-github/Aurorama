package com.zhangwenkang.cinefin.core.presentation.dummy

import com.zhangwenkang.cinefin.models.CollectionType
import com.zhangwenkang.cinefin.models.HomeItem
import com.zhangwenkang.cinefin.models.HomeSection
import com.zhangwenkang.cinefin.models.UiText
import com.zhangwenkang.cinefin.models.View
import java.util.UUID

val dummyHomeSuggestions = HomeItem.Suggestions(id = UUID.randomUUID(), items = dummyMovies)

val dummyHomeSection =
    HomeItem.Section(
        HomeSection(
            id = UUID.randomUUID(),
            name = UiText.DynamicString("Continue watching"),
            items = dummyMovies + dummyEpisodes,
        )
    )

val dummyHomeView =
    HomeItem.ViewItem(
        View(
            id = UUID.randomUUID(),
            name = "Movies",
            items = dummyMovies,
            type = CollectionType.Movies,
        )
    )
