package com.zhangwenkang.cinefin.presentation.film

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import com.zhangwenkang.cinefin.PlayerActivity
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyShow
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors
import com.zhangwenkang.cinefin.film.presentation.show.ShowAction
import com.zhangwenkang.cinefin.film.presentation.show.ShowState
import com.zhangwenkang.cinefin.film.presentation.show.ShowViewModel
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidShow
import com.zhangwenkang.cinefin.presentation.film.components.ActorsRow
import com.zhangwenkang.cinefin.presentation.film.components.DetailPoster
import com.zhangwenkang.cinefin.presentation.film.components.Direction
import com.zhangwenkang.cinefin.presentation.film.components.InfoText
import com.zhangwenkang.cinefin.presentation.film.components.ItemButtonsBar
import com.zhangwenkang.cinefin.presentation.film.components.ItemCard
import com.zhangwenkang.cinefin.presentation.film.components.ItemHeader
import com.zhangwenkang.cinefin.presentation.film.components.ItemPoster
import com.zhangwenkang.cinefin.presentation.film.components.ItemTopBar
import com.zhangwenkang.cinefin.presentation.film.components.LumenInfoTable
import com.zhangwenkang.cinefin.presentation.film.components.OverviewText
import com.zhangwenkang.cinefin.presentation.film.components.SectionHeader
import com.zhangwenkang.cinefin.presentation.film.components.detailEyebrow
import com.zhangwenkang.cinefin.presentation.film.components.metaLine
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.presentation.utils.rememberPageGutter
import com.zhangwenkang.cinefin.presentation.utils.rememberSafePadding
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemKind

@Composable
fun ShowScreen(
    showId: UUID,
    navigateBack: () -> Unit,
    navigateHome: () -> Unit,
    navigateToItem: (item: FindroidItem) -> Unit,
    navigateToPerson: (personId: UUID) -> Unit,
    viewModel: ShowViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(true) { viewModel.loadShow(showId = showId) }

    ShowScreenLayout(
        state = state,
        onAction = { action ->
            when (action) {
                is ShowAction.Play -> {
                    val intent = Intent(context, PlayerActivity::class.java)
                    intent.putExtra("itemId", showId.toString())
                    intent.putExtra("itemKind", BaseItemKind.SERIES.serialName)
                    context.startActivity(intent)
                }
                is ShowAction.PlayTrailer -> {
                    try {
                        uriHandler.openUri(action.trailer)
                    } catch (e: IllegalArgumentException) {
                        Toast.makeText(context, e.localizedMessage, Toast.LENGTH_SHORT).show()
                    }
                }
                is ShowAction.OnBackClick -> navigateBack()
                is ShowAction.OnHomeClick -> navigateHome()
                is ShowAction.NavigateToItem -> navigateToItem(action.item)
                is ShowAction.NavigateToPerson -> navigateToPerson(action.personId)
                else -> Unit
            }
            viewModel.onAction(action)
        },
    )
}

/** 剧集详情（Lumen）：与电影详情同一套头图 / 标题 / 信息表语言，"接下来"与"季"作为两条走廊。 */
@Composable
private fun ShowScreenLayout(state: ShowState, onAction: (ShowAction) -> Unit) {
    val safePadding = rememberSafePadding()
    val gutter = rememberPageGutter()

    val paddingStart = safePadding.start + gutter
    val paddingEnd = safePadding.end + gutter
    val paddingBottom = safePadding.bottom + gutter

    val expanded =
        currentWindowAdaptiveInfo()
            .windowSizeClass
            .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)

    val scrollState = rememberScrollState()

    Box(modifier = Modifier.fillMaxSize()) {
        state.show?.let { show ->
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(scrollState)) {
                ItemHeader(
                    item = show,
                    scrollState = scrollState,
                    height = if (expanded) 400.dp else 300.dp,
                    content = {
                        Row(
                            modifier =
                                Modifier.align(Alignment.BottomStart)
                                    .fillMaxWidth()
                                    .padding(
                                        start = paddingStart,
                                        end = paddingEnd,
                                        bottom = CinefinSpacing.Space6,
                                    ),
                            horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space6),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            if (expanded) {
                                DetailPoster(item = show, width = 216.dp)
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2),
                            ) {
                                show.detailEyebrow(extra = null)?.let { eyebrow ->
                                    Text(
                                        text = eyebrow,
                                        style = CinefinType.LabelLarge,
                                        color = LocalMediaColors.current.bright,
                                    )
                                }
                                Text(
                                    text = show.name,
                                    overflow = TextOverflow.Ellipsis,
                                    maxLines = 3,
                                    style =
                                        if (expanded) CinefinType.DisplaySmall
                                        else CinefinType.HeadlineMedium,
                                    color = LocalCinefinColors.current.onSurface,
                                )
                                show.originalTitle
                                    ?.takeIf { it.isNotBlank() && it != show.name }
                                    ?.let { originalTitle ->
                                        Text(
                                            text = originalTitle,
                                            overflow = TextOverflow.Ellipsis,
                                            maxLines = 1,
                                            style = CinefinType.BodyMedium,
                                            color = LocalCinefinColors.current.onSurfaceVariant,
                                        )
                                    }
                                show.metaLine()?.let { meta ->
                                    Text(
                                        text = meta,
                                        style = CinefinType.LabelMedium,
                                        color = LocalCinefinColors.current.onSurfaceVariant,
                                    )
                                }
                                Spacer(Modifier.height(CinefinSpacing.Space2))
                                ItemButtonsBar(
                                    item = show,
                                    onPlayClick = { startFromBeginning ->
                                        onAction(
                                            ShowAction.Play(startFromBeginning = startFromBeginning)
                                        )
                                    },
                                    onMarkAsPlayedClick = {
                                        when (show.played) {
                                            true -> onAction(ShowAction.UnmarkAsPlayed)
                                            false -> onAction(ShowAction.MarkAsPlayed)
                                        }
                                    },
                                    onMarkAsFavoriteClick = {
                                        when (show.favorite) {
                                            true -> onAction(ShowAction.UnmarkAsFavorite)
                                            false -> onAction(ShowAction.MarkAsFavorite)
                                        }
                                    },
                                    onTrailerClick = { uri ->
                                        onAction(ShowAction.PlayTrailer(uri))
                                    },
                                    onDownloadClick = {},
                                    onDownloadCancelClick = {},
                                    onDownloadDeleteClick = {},
                                    modifier = Modifier.fillMaxWidth(),
                                    canPlay = state.seasons.isNotEmpty(),
                                )
                            }
                        }
                    },
                )

                Column(modifier = Modifier.padding(start = paddingStart, end = paddingEnd)) {
                    Spacer(Modifier.height(CinefinSpacing.Space8))
                    if (expanded) {
                        Row(horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space10)) {
                            Column(modifier = Modifier.weight(1f)) {
                                OverviewText(text = show.overview, maxCollapsedLines = 6)
                            }
                            LumenInfoTable(
                                rows = showInfoRows(show = show, state = state),
                                modifier = Modifier.width(360.dp),
                            )
                        }
                    } else {
                        OverviewText(text = show.overview, maxCollapsedLines = 4)
                        Spacer(Modifier.height(CinefinSpacing.Space6))
                        InfoText(
                            genres = show.genres,
                            director = state.director,
                            writers = state.writers,
                        )
                    }
                    Spacer(Modifier.height(CinefinSpacing.Space8))
                }

                state.nextUp?.let { nextUp ->
                    Column(modifier = Modifier.padding(start = paddingStart, end = paddingEnd)) {
                        SectionHeader(title = stringResource(CoreR.string.next_up))
                        Spacer(Modifier.height(CinefinSpacing.Space4))
                        Column(
                            modifier =
                                Modifier.widthIn(max = 420.dp).clip(CinefinShapes.Md).clickable {
                                    onAction(ShowAction.NavigateToItem(nextUp))
                                }
                        ) {
                            ItemPoster(
                                item = nextUp,
                                direction = Direction.HORIZONTAL,
                                modifier = Modifier.clip(CinefinShapes.Md),
                            )
                            Spacer(Modifier.height(CinefinSpacing.Space3))
                            Text(
                                text =
                                    stringResource(
                                        id = CoreR.string.episode_name_extended,
                                        nextUp.parentIndexNumber,
                                        nextUp.indexNumber,
                                        nextUp.name,
                                    ),
                                style = CinefinType.TitleSmall,
                                color = LocalCinefinColors.current.onSurface,
                            )
                        }
                        Spacer(Modifier.height(CinefinSpacing.Space8))
                    }
                }

                if (state.seasons.isNotEmpty()) {
                    Column(modifier = Modifier.padding(start = paddingStart, end = paddingEnd)) {
                        SectionHeader(title = stringResource(CoreR.string.seasons))
                    }
                    Spacer(Modifier.height(CinefinSpacing.Space4))
                    LazyRow(
                        contentPadding = PaddingValues(start = paddingStart, end = paddingEnd),
                        horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space6),
                    ) {
                        items(items = state.seasons, key = { item -> item.id }) { season ->
                            ItemCard(
                                item = season,
                                direction = Direction.VERTICAL,
                                onClick = { onAction(ShowAction.NavigateToItem(season)) },
                            )
                        }
                    }
                    Spacer(Modifier.height(CinefinSpacing.Space8))
                }

                if (state.actors.isNotEmpty()) {
                    ActorsRow(
                        actors = state.actors,
                        onActorClick = { personId ->
                            onAction(ShowAction.NavigateToPerson(personId))
                        },
                        contentPadding = PaddingValues(start = paddingStart, end = paddingEnd),
                    )
                }
                Spacer(Modifier.height(paddingBottom))
            }
        } ?: run { CircularProgressIndicator(modifier = Modifier.align(Alignment.Center)) }

        ItemTopBar(
            hasBackButton = true,
            hasHomeButton = true,
            onBackClick = { onAction(ShowAction.OnBackClick) },
            onHomeClick = { onAction(ShowAction.OnHomeClick) },
        )
    }
}

/** 剧集制作信息表：季数 / 类型 / 导演 / 编剧（年份与类型首项已经在头图眉标里出现过，这里不重复）。 */
@Composable
private fun showInfoRows(show: FindroidShow, state: ShowState): List<Pair<String, String>> {
    return buildList {
        if (state.seasons.isNotEmpty()) {
            add(stringResource(CoreR.string.seasons) to state.seasons.size.toString())
        }
        if (show.genres.isNotEmpty()) {
            add(stringResource(CoreR.string.genres) to show.genres.joinToString(" / "))
        }
        state.director?.let { add(stringResource(CoreR.string.director) to it.name) }
        if (state.writers.isNotEmpty()) {
            add(
                stringResource(CoreR.string.writers) to
                    state.writers.joinToString(" / ") { writer -> writer.name }
            )
        }
    }
}

@PreviewScreenSizes
@Composable
private fun ShowScreenLayoutPreview() {
    CinefinTheme { ShowScreenLayout(state = ShowState(show = dummyShow), onAction = {}) }
}
