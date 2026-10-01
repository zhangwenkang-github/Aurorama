package com.zhangwenkang.cinefin.presentation.film

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhangwenkang.cinefin.PlayerActivity
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.downloader.DownloaderAction
import com.zhangwenkang.cinefin.core.presentation.downloader.DownloaderEvent
import com.zhangwenkang.cinefin.core.presentation.downloader.DownloaderState
import com.zhangwenkang.cinefin.core.presentation.downloader.DownloaderViewModel
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyEpisode
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyVideoMetadata
import com.zhangwenkang.cinefin.core.presentation.theme.ProvideLumen
import com.zhangwenkang.cinefin.film.presentation.episode.EpisodeAction
import com.zhangwenkang.cinefin.film.presentation.episode.EpisodeState
import com.zhangwenkang.cinefin.film.presentation.episode.EpisodeViewModel
import com.zhangwenkang.cinefin.presentation.components.DetailSkeleton
import com.zhangwenkang.cinefin.presentation.film.components.ActorsRow
import com.zhangwenkang.cinefin.presentation.film.components.ExtraInfoText
import com.zhangwenkang.cinefin.presentation.film.components.ItemButtonsBar
import com.zhangwenkang.cinefin.presentation.film.components.ItemHeader
import com.zhangwenkang.cinefin.presentation.film.components.ItemTopBar
import com.zhangwenkang.cinefin.presentation.film.components.LumenTextShadow
import com.zhangwenkang.cinefin.presentation.film.components.OverviewText
import com.zhangwenkang.cinefin.presentation.film.components.VideoMetadataBar
import com.zhangwenkang.cinefin.presentation.film.components.lumenTextShadow
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.presentation.theme.spacings
import com.zhangwenkang.cinefin.presentation.utils.LocalOfflineMode
import com.zhangwenkang.cinefin.presentation.utils.rememberSafePadding
import com.zhangwenkang.cinefin.utils.ObserveAsEvents
import com.zhangwenkang.cinefin.utils.format
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemKind

@Composable
fun EpisodeScreen(
    episodeId: UUID,
    navigateBack: () -> Unit,
    navigateHome: () -> Unit,
    navigateToPerson: (personId: UUID) -> Unit,
    navigateToSeason: (seasonId: UUID) -> Unit,
    viewModel: EpisodeViewModel = hiltViewModel(),
    downloaderViewModel: DownloaderViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val isOfflineMode = LocalOfflineMode.current

    val state by viewModel.state.collectAsStateWithLifecycle()
    val downloaderState by downloaderViewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(true) { viewModel.loadEpisode(episodeId = episodeId) }

    LaunchedEffect(state.episode) {
        state.episode?.let { episode -> downloaderViewModel.update(episode) }
    }

    ObserveAsEvents(downloaderViewModel.events) { event ->
        when (event) {
            is DownloaderEvent.Successful -> {
                viewModel.loadEpisode(episodeId = episodeId)
            }
            is DownloaderEvent.Deleted -> {
                if (isOfflineMode) {
                    navigateBack()
                } else {
                    viewModel.loadEpisode(episodeId = episodeId)
                }
            }
        }
    }

    EpisodeScreenLayout(
        state = state,
        downloaderState = downloaderState,
        onAction = { action ->
            when (action) {
                is EpisodeAction.Play -> {
                    val intent = Intent(context, PlayerActivity::class.java)
                    intent.putExtra("itemId", episodeId.toString())
                    intent.putExtra("itemKind", BaseItemKind.EPISODE.serialName)
                    intent.putExtra("startFromBeginning", action.startFromBeginning)
                    context.startActivity(intent)
                }
                is EpisodeAction.OnBackClick -> navigateBack()
                is EpisodeAction.OnHomeClick -> navigateHome()
                is EpisodeAction.NavigateToPerson -> navigateToPerson(action.personId)
                is EpisodeAction.NavigateToSeason -> navigateToSeason(action.seasonId)
                else -> Unit
            }
            viewModel.onAction(action)
        },
        onDownloaderAction = { action -> downloaderViewModel.onAction(action) },
    )
}

@Composable
private fun EpisodeScreenLayout(
    state: EpisodeState,
    downloaderState: DownloaderState,
    onAction: (EpisodeAction) -> Unit,
    onDownloaderAction: (DownloaderAction) -> Unit,
) {
    val safePadding = rememberSafePadding()

    val paddingStart = safePadding.start + MaterialTheme.spacings.default
    val paddingEnd = safePadding.end + MaterialTheme.spacings.default
    val paddingBottom = safePadding.bottom + MaterialTheme.spacings.default

    val scrollState = rememberScrollState()

    ProvideLumen {
        Box(modifier = Modifier.fillMaxSize()) {
            state.episode?.let { episode ->
                Column(modifier = Modifier.fillMaxWidth().verticalScroll(scrollState)) {
                    ItemHeader(
                        item = episode,
                        scrollState = scrollState,
                        content = {
                            Column(
                                modifier =
                                    Modifier.align(Alignment.BottomStart)
                                        .padding(start = paddingStart, end = paddingEnd)
                            ) {
                                val seasonName =
                                    episode.seasonName
                                        ?: run {
                                            stringResource(
                                                CoreR.string.season_number,
                                                episode.parentIndexNumber,
                                            )
                                        }
                                Text(
                                    text =
                                        "$seasonName - " +
                                            stringResource(
                                                id = CoreR.string.episode_number,
                                                episode.indexNumber,
                                            ),
                                    maxLines = 1,
                                    style =
                                        MaterialTheme.typography.labelLarge.lumenTextShadow(
                                            LumenTextShadow.Meta
                                        ),
                                )
                                Text(
                                    text = episode.name,
                                    overflow = TextOverflow.Ellipsis,
                                    maxLines = 3,
                                    style =
                                        MaterialTheme.typography.headlineMedium.lumenTextShadow(
                                            LumenTextShadow.Title
                                        ),
                                )
                            }
                        },
                    )
                    Column(modifier = Modifier.padding(start = paddingStart, end = paddingEnd)) {
                        Spacer(Modifier.height(MaterialTheme.spacings.small))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement =
                                Arrangement.spacedBy(MaterialTheme.spacings.small),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            episode.premiereDate?.let { premiereDate ->
                                Text(
                                    text = premiereDate.format(),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            Text(
                                text =
                                    stringResource(
                                        CoreR.string.runtime_minutes,
                                        episode.runtimeTicks.div(600000000),
                                    ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            episode.communityRating?.let { communityRating ->
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Icon(
                                        painter = painterResource(CoreR.drawable.ic_star),
                                        contentDescription = null,
                                        tint = Color("#F2C94C".toColorInt()),
                                    )
                                    Spacer(Modifier.width(MaterialTheme.spacings.extraSmall))
                                    Text(
                                        text = "%.1f".format(communityRating),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(MaterialTheme.spacings.small))
                        state.videoMetadata?.let { videoMetadata ->
                            VideoMetadataBar(videoMetadata)
                            Spacer(Modifier.height(MaterialTheme.spacings.small))
                        }
                        ItemButtonsBar(
                            item = episode,
                            downloaderState = downloaderState,
                            onPlayClick = { startFromBeginning ->
                                onAction(
                                    EpisodeAction.Play(startFromBeginning = startFromBeginning)
                                )
                            },
                            onMarkAsPlayedClick = {
                                when (episode.played) {
                                    true -> onAction(EpisodeAction.UnmarkAsPlayed)
                                    false -> onAction(EpisodeAction.MarkAsPlayed)
                                }
                            },
                            onMarkAsFavoriteClick = {
                                when (episode.favorite) {
                                    true -> onAction(EpisodeAction.UnmarkAsFavorite)
                                    false -> onAction(EpisodeAction.MarkAsFavorite)
                                }
                            },
                            onTrailerClick = {},
                            onDownloadClick = { storageIndex ->
                                onDownloaderAction(DownloaderAction.Download(episode, storageIndex))
                            },
                            onDownloadCancelClick = {
                                onDownloaderAction(DownloaderAction.CancelDownload(episode))
                            },
                            onDownloadDeleteClick = {
                                onDownloaderAction(DownloaderAction.DeleteDownload(episode))
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(MaterialTheme.spacings.small))
                        if (state.displayExtraInfo && state.videoMetadata != null) {
                            ExtraInfoText(videoMetadata = state.videoMetadata!!)
                            Spacer(Modifier.height(MaterialTheme.spacings.medium))
                        }
                        OverviewText(text = episode.overview)
                        Spacer(Modifier.height(MaterialTheme.spacings.medium))
                    }
                    if (state.actors.isNotEmpty()) {
                        ActorsRow(
                            actors = state.actors,
                            onActorClick = { personId ->
                                onAction(EpisodeAction.NavigateToPerson(personId))
                            },
                            contentPadding = PaddingValues(start = paddingStart, end = paddingEnd),
                        )
                    }
                    Spacer(Modifier.height(paddingBottom))
                }
            }
                ?: run {
                    DetailSkeleton(
                        gutterStart = paddingStart,
                        gutterEnd = paddingEnd,
                        modifier = Modifier.fillMaxSize().padding(top = 72.dp),
                    )
                }

            ItemTopBar(
                hasBackButton = true,
                hasHomeButton = true,
                onBackClick = { onAction(EpisodeAction.OnBackClick) },
                onHomeClick = { onAction(EpisodeAction.OnHomeClick) },
            ) {
                Spacer(modifier = Modifier.width(4.dp))
                state.episode?.let { episode ->
                    Button(
                        onClick = { onAction(EpisodeAction.NavigateToSeason(episode.seasonId)) },
                        modifier = Modifier.alpha(0.7f),
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = Color.Black,
                                contentColor = Color.White,
                            ),
                    ) {
                        episode.seasonName?.let { seasonName -> Text(seasonName) }
                            ?: run {
                                Text(
                                    stringResource(
                                        CoreR.string.season_number,
                                        episode.parentIndexNumber,
                                    )
                                )
                            }
                    }
                }
            }
        }
    }
}

@PreviewScreenSizes
@Composable
private fun EpisodeScreenLayoutPreview() {
    CinefinTheme {
        EpisodeScreenLayout(
            state = EpisodeState(episode = dummyEpisode, videoMetadata = dummyVideoMetadata),
            downloaderState = DownloaderState(),
            onAction = {},
            onDownloaderAction = {},
        )
    }
}
