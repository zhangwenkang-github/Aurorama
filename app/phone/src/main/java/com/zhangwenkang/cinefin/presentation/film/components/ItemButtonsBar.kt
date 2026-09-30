package com.zhangwenkang.cinefin.presentation.film.components

import android.app.DownloadManager
import android.os.Environment
import android.os.StatFs
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.cinefinClickable
import com.zhangwenkang.cinefin.core.presentation.downloader.DownloaderState
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyEpisode
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.models.FindroidShow
import com.zhangwenkang.cinefin.models.isDownloaded
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme

@Composable
fun ItemButtonsBar(
    item: FindroidItem,
    onPlayClick: (startFromBeginning: Boolean) -> Unit,
    onMarkAsPlayedClick: () -> Unit,
    onMarkAsFavoriteClick: () -> Unit,
    onDownloadClick: (storageIndex: Int) -> Unit,
    onDownloadCancelClick: () -> Unit,
    onDownloadDeleteClick: () -> Unit,
    onTrailerClick: (uri: String) -> Unit,
    modifier: Modifier = Modifier,
    downloaderState: DownloaderState? = null,
    canPlay: Boolean = true,
) {
    val context = LocalContext.current
    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass

    val trailerUri =
        when (item) {
            is FindroidMovie -> {
                item.trailer
            }
            is FindroidShow -> {
                item.trailer
            }
            else -> null
        }

    var storageSelectionDialogOpen by remember { mutableStateOf(false) }
    var cancelDownloadDialogOpen by remember { mutableStateOf(false) }
    var deleteDownloadDialogOpen by remember { mutableStateOf(false) }

    var selectedStorageIndex by remember { mutableIntStateOf(0) }
    var storageLocations = remember { context.getExternalFilesDirs(null) }

    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2),
        ) {
            if (
                !windowSizeClass.isWidthAtLeastBreakpoint(
                    WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND
                )
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2)) {
                    PlayButton(
                        item = item,
                        onClick = { onPlayClick(false) },
                        modifier = Modifier.weight(weight = 1f, fill = true),
                        enabled = item.canPlay && canPlay,
                    )
                    if (item.playbackPositionTicks.div(600000000) > 0) {
                        DetailActionButton(
                            icon = CoreR.drawable.ic_rotate_ccw,
                            onClick = { onPlayClick(true) },
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2)) {
                if (
                    windowSizeClass.isWidthAtLeastBreakpoint(
                        WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND
                    )
                ) {
                    PlayButton(
                        item = item,
                        onClick = { onPlayClick(false) },
                        enabled = item.canPlay && canPlay,
                    )
                    if (item.playbackPositionTicks.div(600000000) > 0) {
                        DetailActionButton(
                            icon = CoreR.drawable.ic_rotate_ccw,
                            onClick = { onPlayClick(true) },
                        )
                    }
                }
                trailerUri?.let { uri ->
                    DetailActionButton(
                        icon = CoreR.drawable.ic_film,
                        onClick = { onTrailerClick(uri) },
                    )
                }
                DetailActionButton(
                    icon = CoreR.drawable.ic_check,
                    onClick = onMarkAsPlayedClick,
                    selected = item.played,
                )
                DetailActionButton(
                    icon =
                        if (item.favorite) CoreR.drawable.ic_heart_filled
                        else CoreR.drawable.ic_heart,
                    onClick = onMarkAsFavoriteClick,
                    selected = item.favorite,
                )
                if (downloaderState != null && !downloaderState.isDownloading) {
                    if (item.isDownloaded()) {
                        DetailActionButton(
                            icon = CoreR.drawable.ic_trash,
                            onClick = { deleteDownloadDialogOpen = true },
                        )
                    } else if (item.canDownload) {
                        DetailActionButton(
                            icon = CoreR.drawable.ic_download,
                            onClick = {
                                storageLocations = context.getExternalFilesDirs(null)
                                if (storageLocations.size > 1) {
                                    storageSelectionDialogOpen = true
                                } else {
                                    selectedStorageIndex = 0
                                    onDownloadClick(selectedStorageIndex)
                                }
                            },
                        )
                    }
                }
            }
            if (downloaderState != null) {
                AnimatedVisibility(downloaderState.isDownloading) {
                    Column {
                        DownloaderCard(
                            state = downloaderState,
                            onCancelClick = { cancelDownloadDialogOpen = true },
                            onRetryClick = { onDownloadClick(selectedStorageIndex) },
                        )
                        Spacer(Modifier.height(CinefinSpacing.Space2))
                    }
                }
            }
        }
        if (storageSelectionDialogOpen) {
            val locations = remember {
                storageLocations.map { dir ->
                    val locationStringRes =
                        if (Environment.isExternalStorageRemovable(dir)) CoreR.string.external
                        else CoreR.string.internal
                    val locationString = context.getString(locationStringRes)

                    val stat = StatFs(dir.path)
                    val availableMegaBytes = stat.availableBytes.div(1000000)
                    context.getString(CoreR.string.storage_name, locationString, availableMegaBytes)
                }
            }
            StorageSelectionDialog(
                storageLocations = locations,
                onSelect = { storageIndex ->
                    selectedStorageIndex = storageIndex
                    onDownloadClick(selectedStorageIndex)
                    storageSelectionDialogOpen = false
                },
                onDismiss = { storageSelectionDialogOpen = false },
            )
        }
        if (cancelDownloadDialogOpen) {
            CancelDownloadDialog(
                onCancel = {
                    onDownloadCancelClick()
                    cancelDownloadDialogOpen = false
                },
                onDismiss = { cancelDownloadDialogOpen = false },
            )
        }
        if (deleteDownloadDialogOpen) {
            DeleteDownloadDialog(
                onDelete = {
                    onDownloadDeleteClick()
                    deleteDownloadDialogOpen = false
                },
                onDismiss = { deleteDownloadDialogOpen = false },
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ItemButtonsBarPreview() {
    CinefinTheme {
        ItemButtonsBar(
            item = dummyEpisode,
            onPlayClick = {},
            onMarkAsPlayedClick = {},
            onMarkAsFavoriteClick = {},
            onDownloadClick = {},
            onDownloadCancelClick = {},
            onDownloadDeleteClick = {},
            onTrailerClick = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ItemButtonsBarDownloadingPreview() {
    CinefinTheme {
        ItemButtonsBar(
            item = dummyEpisode,
            downloaderState =
                DownloaderState(status = DownloadManager.STATUS_RUNNING, progress = 0.3f),
            onPlayClick = {},
            onMarkAsPlayedClick = {},
            onMarkAsFavoriteClick = {},
            onDownloadClick = {},
            onDownloadCancelClick = {},
            onDownloadDeleteClick = {},
            onTrailerClick = {},
        )
    }
}

/** 详情页行内操作键（§8.1 Icon 变形）：44dp 方圆形；选中态 `Media.Container` 底 + `Media.Base` 图标。 */
@Composable
private fun DetailActionButton(
    @DrawableRes icon: Int,
    onClick: () -> Unit,
    selected: Boolean = false,
) {
    val media = LocalMediaColors.current
    val colors = LocalCinefinColors.current
    Box(
        modifier =
            Modifier.size(44.dp)
                .clip(CinefinShapes.Sm)
                .background(if (selected) media.container else Color.Transparent)
                .cinefinClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = if (selected) media.base else colors.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}
