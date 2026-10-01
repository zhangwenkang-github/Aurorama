package com.zhangwenkang.cinefin.presentation.film

import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import android.widget.Toast
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.zhangwenkang.cinefin.core.presentation.downloader.DownloaderAction
import com.zhangwenkang.cinefin.core.presentation.downloader.DownloaderEvent
import com.zhangwenkang.cinefin.core.presentation.downloader.DownloaderState
import com.zhangwenkang.cinefin.core.presentation.downloader.DownloaderViewModel
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyMovie
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyVideoMetadata
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors
import com.zhangwenkang.cinefin.core.presentation.theme.ProvideLumen
import com.zhangwenkang.cinefin.film.presentation.movie.MovieAction
import com.zhangwenkang.cinefin.film.presentation.movie.MovieState
import com.zhangwenkang.cinefin.film.presentation.movie.MovieViewModel
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.models.VideoMetadata
import com.zhangwenkang.cinefin.presentation.film.components.ActorsRow
import com.zhangwenkang.cinefin.presentation.film.components.DetailPoster
import com.zhangwenkang.cinefin.presentation.film.components.ExtraInfoText
import com.zhangwenkang.cinefin.presentation.film.components.InfoText
import com.zhangwenkang.cinefin.presentation.film.components.ItemButtonsBar
import com.zhangwenkang.cinefin.presentation.film.components.ItemHeader
import com.zhangwenkang.cinefin.presentation.film.components.ItemTopBar
import com.zhangwenkang.cinefin.presentation.film.components.LumenInfoTable
import com.zhangwenkang.cinefin.presentation.film.components.LumenTextShadow
import com.zhangwenkang.cinefin.presentation.film.components.OverviewText
import com.zhangwenkang.cinefin.presentation.film.components.VideoMetadataBar
import com.zhangwenkang.cinefin.presentation.film.components.detailEyebrow
import com.zhangwenkang.cinefin.presentation.film.components.lumenTextShadow
import com.zhangwenkang.cinefin.presentation.film.components.metaLine
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.presentation.utils.LocalOfflineMode
import com.zhangwenkang.cinefin.presentation.utils.rememberPageGutter
import com.zhangwenkang.cinefin.presentation.utils.rememberSafePadding
import com.zhangwenkang.cinefin.utils.ObserveAsEvents
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemKind

@Composable
fun MovieScreen(
    movieId: UUID,
    navigateBack: () -> Unit,
    navigateHome: () -> Unit,
    navigateToPerson: (personId: UUID) -> Unit,
    viewModel: MovieViewModel = hiltViewModel(),
    downloaderViewModel: DownloaderViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val isOfflineMode = LocalOfflineMode.current

    val state by viewModel.state.collectAsStateWithLifecycle()
    val downloaderState by downloaderViewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(true) { viewModel.loadMovie(movieId = movieId) }

    LaunchedEffect(state.movie) { state.movie?.let { movie -> downloaderViewModel.update(movie) } }

    ObserveAsEvents(downloaderViewModel.events) { event ->
        when (event) {
            is DownloaderEvent.Successful -> {
                viewModel.loadMovie(movieId = movieId)
            }
            is DownloaderEvent.Deleted -> {
                if (isOfflineMode) {
                    navigateBack()
                } else {
                    viewModel.loadMovie(movieId = movieId)
                }
            }
        }
    }

    MovieScreenLayout(
        state = state,
        downloaderState = downloaderState,
        onAction = { action ->
            when (action) {
                is MovieAction.Play -> {
                    val intent = Intent(context, PlayerActivity::class.java)
                    intent.putExtra("itemId", movieId.toString())
                    intent.putExtra("itemKind", BaseItemKind.MOVIE.serialName)
                    intent.putExtra("startFromBeginning", action.startFromBeginning)
                    context.startActivity(intent)
                }
                is MovieAction.PlayTrailer -> {
                    try {
                        uriHandler.openUri(action.trailer)
                    } catch (e: IllegalArgumentException) {
                        Toast.makeText(context, e.localizedMessage, Toast.LENGTH_SHORT).show()
                    }
                }
                is MovieAction.OnBackClick -> navigateBack()
                is MovieAction.OnHomeClick -> navigateHome()
                is MovieAction.NavigateToPerson -> navigateToPerson(action.personId)
                else -> Unit
            }
            viewModel.onAction(action)
        },
        onDownloaderAction = { action -> downloaderViewModel.onAction(action) },
    )
}

/**
 * 电影详情（Lumen）：沉浸头图 + 海报 + 大标题 + 主行动全部压在图上，正文只留"剧情简介"。
 *
 * 平板（≥840dp）右侧再挂一张制作信息表，主栏不再堆"标签: 值"长句；手机把同样的信息留在正文里， 用间距与字阶分层（不画分隔线框死）。
 */
@Composable
private fun MovieScreenLayout(
    state: MovieState,
    downloaderState: DownloaderState,
    onAction: (MovieAction) -> Unit,
    onDownloaderAction: (DownloaderAction) -> Unit,
) {
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

    ProvideLumen {
        Box(modifier = Modifier.fillMaxSize()) {
            state.movie?.let { movie ->
                Column(modifier = Modifier.fillMaxWidth().verticalScroll(scrollState)) {
                    ItemHeader(
                        item = movie,
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
                                    DetailPoster(item = movie, width = 216.dp)
                                }
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement =
                                        Arrangement.spacedBy(CinefinSpacing.Space2),
                                ) {
                                    movie.detailEyebrow()?.let { eyebrow ->
                                        Text(
                                            text = eyebrow,
                                            style =
                                                CinefinType.LabelLarge.lumenTextShadow(
                                                    LumenTextShadow.Meta
                                                ),
                                            color = LocalMediaColors.current.bright,
                                        )
                                    }
                                    Text(
                                        text = movie.name,
                                        overflow = TextOverflow.Ellipsis,
                                        maxLines = 3,
                                        style =
                                            (if (expanded) CinefinType.DisplaySmall
                                                else CinefinType.HeadlineMedium)
                                                .lumenTextShadow(LumenTextShadow.Title),
                                        color = LocalCinefinColors.current.onSurface,
                                    )
                                    movie.originalTitle
                                        ?.takeIf { it.isNotBlank() && it != movie.name }
                                        ?.let { originalTitle ->
                                            Text(
                                                text = originalTitle,
                                                overflow = TextOverflow.Ellipsis,
                                                maxLines = 1,
                                                style =
                                                    CinefinType.BodyMedium.lumenTextShadow(
                                                        LumenTextShadow.Meta
                                                    ),
                                                color = LocalCinefinColors.current.onSurfaceVariant,
                                            )
                                        }
                                    movie.metaLine()?.let { meta ->
                                        Text(
                                            text = meta,
                                            style =
                                                CinefinType.LabelMedium.lumenTextShadow(
                                                    LumenTextShadow.Meta
                                                ),
                                            color = LocalCinefinColors.current.onSurfaceVariant,
                                        )
                                    }
                                    Spacer(Modifier.height(CinefinSpacing.Space2))
                                    ItemButtonsBar(
                                        item = movie,
                                        downloaderState = downloaderState,
                                        onPlayClick = { startFromBeginning ->
                                            onAction(
                                                MovieAction.Play(
                                                    startFromBeginning = startFromBeginning
                                                )
                                            )
                                        },
                                        onMarkAsPlayedClick = {
                                            when (movie.played) {
                                                true -> onAction(MovieAction.UnmarkAsPlayed)
                                                false -> onAction(MovieAction.MarkAsPlayed)
                                            }
                                        },
                                        onMarkAsFavoriteClick = {
                                            when (movie.favorite) {
                                                true -> onAction(MovieAction.UnmarkAsFavorite)
                                                false -> onAction(MovieAction.MarkAsFavorite)
                                            }
                                        },
                                        onTrailerClick = { uri ->
                                            onAction(MovieAction.PlayTrailer(uri))
                                        },
                                        onDownloadClick = { storageIndex ->
                                            onDownloaderAction(
                                                DownloaderAction.Download(movie, storageIndex)
                                            )
                                        },
                                        onDownloadCancelClick = {
                                            onDownloaderAction(
                                                DownloaderAction.CancelDownload(movie)
                                            )
                                        },
                                        onDownloadDeleteClick = {
                                            onDownloaderAction(
                                                DownloaderAction.DeleteDownload(movie)
                                            )
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        },
                    )

                    Column(modifier = Modifier.padding(start = paddingStart, end = paddingEnd)) {
                        Spacer(Modifier.height(CinefinSpacing.Space8))
                        if (expanded) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space10)
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement =
                                        Arrangement.spacedBy(CinefinSpacing.Space4),
                                ) {
                                    OverviewText(text = movie.overview, maxCollapsedLines = 6)
                                    state.videoMetadata?.let { videoMetadata ->
                                        VideoMetadataBar(videoMetadata = videoMetadata)
                                    }
                                }
                                LumenInfoTable(
                                    rows = movieInfoRows(movie = movie, state = state),
                                    modifier = Modifier.width(360.dp),
                                )
                            }
                        } else {
                            OverviewText(text = movie.overview, maxCollapsedLines = 4)
                            Spacer(Modifier.height(CinefinSpacing.Space6))
                            InfoText(
                                genres = movie.genres,
                                director = state.director,
                                writers = state.writers,
                            )
                            state.videoMetadata?.let { videoMetadata ->
                                Spacer(Modifier.height(CinefinSpacing.Space4))
                                VideoMetadataBar(videoMetadata = videoMetadata)
                            }
                            if (state.displayExtraInfo && state.videoMetadata != null) {
                                Spacer(Modifier.height(CinefinSpacing.Space6))
                                ExtraInfoText(videoMetadata = state.videoMetadata!!)
                            }
                        }
                        Spacer(Modifier.height(CinefinSpacing.Space8))
                    }

                    if (state.actors.isNotEmpty()) {
                        ActorsRow(
                            actors = state.actors,
                            onActorClick = { personId ->
                                onAction(MovieAction.NavigateToPerson(personId))
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
                onBackClick = { onAction(MovieAction.OnBackClick) },
                onHomeClick = { onAction(MovieAction.OnHomeClick) },
            )
        }
    }
}

/** 制作信息表内容：导演 / 编剧 / 类型 + 文件层信息（有元数据时才出现）。 */
@Composable
private fun movieInfoRows(movie: FindroidMovie, state: MovieState): List<Pair<String, String>> {
    val context = LocalContext.current
    return buildList {
        state.director?.let { add(stringResource(CoreR.string.director) to it.name) }
        if (state.writers.isNotEmpty()) {
            add(
                stringResource(CoreR.string.writers) to
                    state.writers.joinToString(" / ") { writer -> writer.name }
            )
        }
        if (movie.genres.isNotEmpty()) {
            add(stringResource(CoreR.string.genres) to movie.genres.joinToString(" / "))
        }
        state.videoMetadata?.let { videoMetadata -> addAll(fileInfoRows(context, videoMetadata)) }
    }
}

/** 文件层信息：体积 / 视频 / 音轨 / 字幕。 */
@Composable
private fun fileInfoRows(
    context: Context,
    videoMetadata: VideoMetadata,
): List<Pair<String, String>> {
    return buildList {
        if (videoMetadata.size > 0) {
            add(
                stringResource(CoreR.string.size) to
                    Formatter.formatFileSize(context, videoMetadata.size)
            )
        }
        videoMetadata.videoTracks.firstOrNull()?.let {
            add(stringResource(CoreR.string.video) to it)
        }
        if (videoMetadata.audioTracks.isNotEmpty()) {
            add(stringResource(CoreR.string.audio) to videoMetadata.audioTracks.joinToString(" / "))
        }
        if (videoMetadata.subtitleTracks.isNotEmpty()) {
            add(
                stringResource(CoreR.string.subtitle) to
                    videoMetadata.subtitleTracks.joinToString(" / ")
            )
        }
    }
}

@PreviewScreenSizes
@Composable
private fun MovieScreenLayoutPreview() {
    CinefinTheme {
        MovieScreenLayout(
            state = MovieState(movie = dummyMovie, videoMetadata = dummyVideoMetadata),
            downloaderState = DownloaderState(),
            onAction = {},
            onDownloaderAction = {},
        )
    }
}
