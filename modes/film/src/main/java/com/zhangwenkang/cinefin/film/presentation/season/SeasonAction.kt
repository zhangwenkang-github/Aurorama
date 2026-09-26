package com.zhangwenkang.cinefin.film.presentation.season

import com.zhangwenkang.cinefin.models.FindroidItem
import java.util.UUID

sealed interface SeasonAction {
    data class Play(val startFromBeginning: Boolean = false) : SeasonAction

    data object MarkAsPlayed : SeasonAction

    data object UnmarkAsPlayed : SeasonAction

    data object MarkAsFavorite : SeasonAction

    data object UnmarkAsFavorite : SeasonAction

    data object OnBackClick : SeasonAction

    data object OnHomeClick : SeasonAction

    data class NavigateToItem(val item: FindroidItem) : SeasonAction

    data class NavigateToSeries(val seriesId: UUID) : SeasonAction
}
