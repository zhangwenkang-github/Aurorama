package com.zhangwenkang.cinefin.presentation.film

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyHomeSection
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyHomeSuggestions
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyHomeView
import com.zhangwenkang.cinefin.film.presentation.home.HomeAction
import com.zhangwenkang.cinefin.film.presentation.home.HomeState
import com.zhangwenkang.cinefin.film.presentation.home.HomeViewModel
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.models.FindroidShow
import com.zhangwenkang.cinefin.presentation.film.components.HomeCarousel
import com.zhangwenkang.cinefin.presentation.film.components.HomeSection
import com.zhangwenkang.cinefin.presentation.film.components.HomeView
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.presentation.theme.spacings
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemKind

@Composable
fun HomeScreen(
    navigateToMovie: (itemId: UUID) -> Unit,
    navigateToShow: (itemId: UUID) -> Unit,
    navigateToPlayer: (itemId: UUID, itemKind: BaseItemKind) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
    isLoading: (Boolean) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(true) { viewModel.loadData() }

    LaunchedEffect(state.isLoading) { isLoading(state.isLoading) }

    HomeScreenLayout(
        state = state,
        onAction = { action ->
            when (action) {
                is HomeAction.OnItemClick -> {
                    when (action.item) {
                        is FindroidMovie -> navigateToMovie(action.item.id)
                        is FindroidShow -> navigateToShow(action.item.id)
                        is FindroidEpisode -> {
                            navigateToPlayer(action.item.id, BaseItemKind.EPISODE)
                        }
                    }
                }
                else -> Unit
            }
            viewModel.onAction(action)
        },
    )
}

@Composable
private fun HomeScreenLayout(state: HomeState, onAction: (HomeAction) -> Unit) {
    val itemsPadding = PaddingValues(horizontal = MaterialTheme.spacings.large)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding =
            PaddingValues(
                top = MaterialTheme.spacings.extraSmall,
                bottom = MaterialTheme.spacings.large,
            ),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.large),
    ) {
        state.suggestionsSection?.let { section ->
            item(key = section.id) {
                HomeCarousel(
                    items = section.items,
                    onAction = onAction,
                    modifier = Modifier.animateItem().padding(itemsPadding),
                )
            }
        }
        state.resumeSection?.let { section ->
            item(key = section.id) {
                HomeSection(
                    section = section.homeSection,
                    itemsPadding = itemsPadding,
                    onAction = onAction,
                    modifier = Modifier.animateItem(),
                )
            }
        }
        state.nextUpSection?.let { section ->
            item(key = section.id) {
                HomeSection(
                    section = section.homeSection,
                    itemsPadding = itemsPadding,
                    onAction = onAction,
                    modifier = Modifier.animateItem(),
                )
            }
        }
        items(state.views, key = { it.id }) { view ->
            HomeView(
                view = view,
                itemsPadding = itemsPadding,
                onAction = onAction,
                modifier = Modifier.animateItem(),
            )
        }
    }
}

@Preview(device = "id:tv_1080p")
@Composable
private fun HomeScreenLayoutPreview() {
    CinefinTheme {
        HomeScreenLayout(
            state =
                HomeState(
                    suggestionsSection = dummyHomeSuggestions,
                    resumeSection = dummyHomeSection,
                    views = listOf(dummyHomeView),
                ),
            onAction = {},
        )
    }
}
