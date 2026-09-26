package com.zhangwenkang.cinefin.presentation.film

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyEpisodes
import com.zhangwenkang.cinefin.core.presentation.dummy.dummySeason
import com.zhangwenkang.cinefin.film.presentation.season.SeasonAction
import com.zhangwenkang.cinefin.film.presentation.season.SeasonState
import com.zhangwenkang.cinefin.film.presentation.season.SeasonViewModel
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.presentation.theme.spacings
import com.zhangwenkang.cinefin.ui.components.EpisodeCard
import java.util.UUID

@Composable
fun SeasonScreen(
    seasonId: UUID,
    navigateToPlayer: (itemId: UUID) -> Unit,
    viewModel: SeasonViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(true) { viewModel.loadSeason(seasonId = seasonId) }

    SeasonScreenLayout(
        state = state,
        onAction = { action ->
            when (action) {
                is SeasonAction.NavigateToItem -> navigateToPlayer(action.item.id)
                else -> Unit
            }
        },
    )
}

@Composable
private fun SeasonScreenLayout(state: SeasonState, onAction: (SeasonAction) -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        state.season?.let { season ->
            Row(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier =
                        Modifier.weight(1f)
                            .padding(
                                start = MaterialTheme.spacings.extraLarge,
                                top = MaterialTheme.spacings.large,
                                end = MaterialTheme.spacings.large,
                            )
                ) {
                    Text(text = season.name, style = MaterialTheme.typography.displayMedium)
                    Text(text = season.seriesName, style = MaterialTheme.typography.headlineMedium)
                }
                LazyColumn(
                    contentPadding =
                        PaddingValues(
                            top = MaterialTheme.spacings.large,
                            bottom = MaterialTheme.spacings.large,
                        ),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
                    modifier = Modifier.weight(2f).padding(end = MaterialTheme.spacings.extraLarge),
                ) {
                    items(state.episodes) { episode ->
                        EpisodeCard(
                            episode = episode,
                            onClick = { onAction(SeasonAction.NavigateToItem(episode)) },
                        )
                    }
                }
            }
        } ?: run { CircularProgressIndicator(modifier = Modifier.align(Alignment.Center)) }
    }
}

@Preview(device = "id:tv_1080p")
@Composable
private fun SeasonScreenLayoutPreview() {
    CinefinTheme {
        SeasonScreenLayout(
            state = SeasonState(season = dummySeason, episodes = dummyEpisodes),
            onAction = {},
        )
    }
}
