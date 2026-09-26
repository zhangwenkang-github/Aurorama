package com.zhangwenkang.cinefin.presentation.film

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyMovies
import com.zhangwenkang.cinefin.film.presentation.collection.CollectionAction
import com.zhangwenkang.cinefin.film.presentation.collection.CollectionState
import com.zhangwenkang.cinefin.film.presentation.favorites.FavoritesViewModel
import com.zhangwenkang.cinefin.models.CollectionSection
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.UiText
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme

@Composable
fun FavoritesScreen(
    onItemClick: (item: FindroidItem) -> Unit,
    navigateBack: () -> Unit,
    viewModel: FavoritesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(true) { viewModel.loadItems() }

    CollectionScreenLayout(
        collectionName = stringResource(CoreR.string.title_favorite),
        state = state,
        onAction = { action ->
            when (action) {
                is CollectionAction.OnItemClick -> onItemClick(action.item)
                is CollectionAction.OnBackClick -> navigateBack()
            }
        },
    )
}

@PreviewScreenSizes
@Composable
private fun CollectionScreenLayoutPreview() {
    CinefinTheme {
        CollectionScreenLayout(
            collectionName = "Favorites",
            state =
                CollectionState(
                    sections =
                        listOf(
                            CollectionSection(
                                id = 0,
                                name = UiText.StringResource(CoreR.string.title_favorite),
                                items = dummyMovies,
                            )
                        )
                ),
            onAction = {},
        )
    }
}
