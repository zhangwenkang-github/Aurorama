package com.zhangwenkang.cinefin.presentation.film.components

import android.app.DownloadManager
import android.os.Environment
import android.os.StatFs
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.CinefinIconButton
import com.zhangwenkang.cinefin.core.presentation.components.cinefinClickable
import com.zhangwenkang.cinefin.core.presentation.downloader.DownloaderState
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyEpisode
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors
import com.zhangwenkang.cinefin.film.presentation.detail.DetailDownloadState
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.models.FindroidShow
import com.zhangwenkang.cinefin.models.isDownloaded
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme

/**
 * 详情页动作区（W51 重绘）。
 *
 * 布局：第一行 = 播放 / 重播 / 预告（图标键）；第二行 = **下载 / 已播放 / 喜欢** 三枚带标签按钮（同排，Lumen 语义色）。
 *
 * 下载动作两套语义：
 * - [downloadState] 非空（Show / Season / Episode，W51）：三态（未下载 / 已在队列 / 已下载），点击统一回调
 *   [onDownloadClick]，由屏幕决定入队或弹 Snackbar；
 * - [downloadState] 为空（电影，既有行为）：未下载 → 下载（含存储选择）、已下载 → 删除确认。
 */
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
    downloadState: DetailDownloadState? = null,
    downloadBusy: Boolean = false,
    storageSelectionEnabled: Boolean = true,
    /** W51：覆盖「未下载」态的可点性（容器批量下载不看单条目 `canDownload`，由对话框按剧集过滤）。 */
    downloadEnabled: Boolean = true,
) {
    val context = LocalContext.current
    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
    val compact =
        !windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)

    val trailerUri =
        when (item) {
            is FindroidMovie -> item.trailer
            is FindroidShow -> item.trailer
            else -> null
        }

    var storageSelectionDialogOpen by remember { mutableStateOf(false) }
    var cancelDownloadDialogOpen by remember { mutableStateOf(false) }
    var deleteDownloadDialogOpen by remember { mutableStateOf(false) }

    var selectedStorageIndex by remember { mutableIntStateOf(0) }
    var storageLocations = remember { context.getExternalFilesDirs(null) }

    /** 单集下载：多存储位置时先选位置，否则直接入队（批量为 false，走确认对话框）。 */
    fun requestDownload() {
        if (!storageSelectionEnabled) {
            onDownloadClick(0)
            return
        }
        storageLocations = context.getExternalFilesDirs(null)
        if (storageLocations.size > 1) {
            storageSelectionDialogOpen = true
        } else {
            selectedStorageIndex = 0
            onDownloadClick(0)
        }
    }

    val legacyDownloaded = item.isDownloaded()
    val isLegacy = downloadState == null
    val showDownloadAction = if (isLegacy) downloaderState != null else true
    val downloadBusyNow = downloadBusy || (isLegacy && downloaderState?.isDownloading == true)
    val resolvedDownloadState = downloadState

    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayButton(
                    item = item,
                    onClick = { onPlayClick(false) },
                    modifier = if (compact) Modifier.weight(1f) else Modifier,
                    enabled = item.canPlay && canPlay,
                )
                if (item.playbackPositionTicks.div(600000000) > 0) {
                    DetailIconButton(
                        icon = CoreR.drawable.ic_rotate_ccw,
                        onClick = { onPlayClick(true) },
                    )
                }
                trailerUri?.let { uri ->
                    DetailIconButton(
                        icon = CoreR.drawable.ic_film,
                        onClick = { onTrailerClick(uri) },
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showDownloadAction) {
                    val label =
                        when {
                            isLegacy ->
                                if (legacyDownloaded) {
                                    stringResource(CoreR.string.detail_action_downloaded)
                                } else {
                                    stringResource(CoreR.string.detail_action_download)
                                }
                            resolvedDownloadState == DetailDownloadState.DOWNLOADED ->
                                stringResource(CoreR.string.detail_action_downloaded)
                            resolvedDownloadState == DetailDownloadState.IN_QUEUE ->
                                stringResource(CoreR.string.detail_action_queued)
                            else -> stringResource(CoreR.string.detail_action_download)
                        }
                    val selected =
                        if (isLegacy) legacyDownloaded
                        else resolvedDownloadState != DetailDownloadState.NOT_DOWNLOADED
                    val enabled =
                        when {
                            downloadBusyNow -> false
                            isLegacy -> item.canDownload || legacyDownloaded
                            resolvedDownloadState == DetailDownloadState.NOT_DOWNLOADED ->
                                downloadEnabled
                            else -> true
                        }
                    DetailLabeledButton(
                        label = label,
                        icon = CoreR.drawable.ic_download,
                        selected = selected,
                        enabled = enabled,
                        onClick = {
                            when {
                                downloadBusyNow -> Unit
                                isLegacy && legacyDownloaded -> deleteDownloadDialogOpen = true
                                isLegacy -> requestDownload()
                                resolvedDownloadState == DetailDownloadState.NOT_DOWNLOADED ->
                                    requestDownload()
                                else -> onDownloadClick(0)
                            }
                        },
                    )
                }
                DetailLabeledButton(
                    label = stringResource(CoreR.string.detail_action_played),
                    icon = CoreR.drawable.ic_check,
                    selected = item.played,
                    onClick = onMarkAsPlayedClick,
                )
                DetailLabeledButton(
                    label = stringResource(CoreR.string.detail_action_favorite),
                    icon =
                        if (item.favorite) CoreR.drawable.ic_bookmark_filled
                        else CoreR.drawable.ic_bookmark,
                    selected = item.favorite,
                    onClick = onMarkAsFavoriteClick,
                )
            }
            if (downloaderState != null) {
                AnimatedVisibility(downloaderState.isDownloading) {
                    DownloaderCard(
                        state = downloaderState,
                        onCancelClick = { cancelDownloadDialogOpen = true },
                        onRetryClick = { onDownloadClick(selectedStorageIndex) },
                    )
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
                    onDownloadClick(storageIndex)
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

/** 行内图标键（44dp 方圆形，§8.1 Icon 变形）；选中态由 [DetailLabeledButton] 承担。 */
@Composable
private fun DetailIconButton(@DrawableRes icon: Int, onClick: () -> Unit) {
    CinefinIconButton(
        onClick = onClick,
        modifier = Modifier.size(44.dp),
        icon = { tint ->
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
        },
    )
}

/**
 * 详情页带标签动作键（§8.1 Outlined 变形 + 选中态）：48dp 触控高、12dp 圆角、1dp 描边； 未选中 = 中性文字 / 透明底，选中 = 当前域容器 +
 * 强调色描边与内容（Lumen 下自动切极光青）。
 */
@Composable
private fun DetailLabeledButton(
    label: String,
    @DrawableRes icon: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val media = LocalMediaColors.current
    val colors = LocalCinefinColors.current
    val contentColor =
        when {
            !enabled -> colors.onSurfaceFaint.copy(alpha = colors.disabledAlpha)
            selected -> media.bright
            else -> colors.onSurfaceVariant
        }
    val containerColor = if (selected && enabled) media.container else Color.Transparent
    val borderColor = if (selected && enabled) media.outline else colors.outline
    Row(
        modifier =
            modifier
                .defaultMinSize(minHeight = 48.dp)
                .clip(CinefinShapes.Sm)
                .background(containerColor)
                .border(1.dp, borderColor, CinefinShapes.Sm)
                .cinefinClickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = CinefinSpacing.Space4, vertical = CinefinSpacing.Space3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            style = CinefinType.LabelLarge,
            color = contentColor,
            maxLines = 1,
        )
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
