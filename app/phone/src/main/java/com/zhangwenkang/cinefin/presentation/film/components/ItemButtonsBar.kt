package com.zhangwenkang.cinefin.presentation.film.components

import android.app.DownloadManager
import android.os.Environment
import android.os.StatFs
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.cinefinClickable
import com.zhangwenkang.cinefin.core.presentation.components.navSelectedContent
import com.zhangwenkang.cinefin.core.presentation.downloader.DownloadPrecheckDialog
import com.zhangwenkang.cinefin.core.presentation.downloader.DownloadPrecheckEnvironment
import com.zhangwenkang.cinefin.core.presentation.downloader.DownloadPrecheckRules
import com.zhangwenkang.cinefin.core.presentation.downloader.DownloaderState
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyEpisode
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinTokens
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.auroraGradient
import com.zhangwenkang.cinefin.film.R as FilmR
import com.zhangwenkang.cinefin.film.presentation.detail.DetailDownloadRules
import com.zhangwenkang.cinefin.film.presentation.detail.DetailDownloadState
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.models.FindroidShow
import com.zhangwenkang.cinefin.models.isDownloaded

/**
 * 详情页动作区（2.0.3-C 图标化重绘）。
 *
 * 单行圆形图标键（同一 FlowRow，含 ⋮）：播放 / 重播 / 预告 / 下载 / 已播放 / 喜欢 / ⋮（编辑元数据 / 删除）。 键内不再有文字标签，图标 24dp、1dp
 * `outline` 细描边；键宽随可用宽度自适应（竖屏 hero 优先 48dp、宽屏 44dp，下限 40dp），保证 ⋮ 永远与同排。 宽屏（头图动作排压在 backdrop 上）整排垫一层
 * `glass` + `glassBorder`，播放主键保留极光渐变 CTA；竖屏 hero 保持纯描边。 玩家靠图标辨识动作，无障碍文案由 contentDescription 保留。
 *
 * 下载动作两套语义：
 * - [downloadState] 非空（Show / Season / Episode，W51）：三态（未下载 / 已在队列 / 已下载），点击统一回调
 *   [onDownloadClick]，由屏幕决定入队或弹 Snackbar；
 * - [downloadState] 为空（电影，既有行为）：未下载 → 下载（含存储选择）、已下载 → 删除确认。
 */
@OptIn(ExperimentalLayoutApi::class)
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
    /** W66b：竖屏 hero 布局键位放大到 48dp（横屏 / 平板 44dp）；两种布局同为单行圆形图标键。 */
    heroLayout: Boolean = false,
) {
    val context = LocalContext.current
    val colors = LocalCinefinColors.current

    val trailerUri =
        when (item) {
            is FindroidMovie -> item.trailer
            is FindroidShow -> item.trailer
            else -> null
        }

    var storageSelectionDialogOpen by remember { mutableStateOf(false) }
    var cancelDownloadDialogOpen by remember { mutableStateOf(false) }
    var deleteDownloadDialogOpen by remember { mutableStateOf(false) }
    /** W7-T1：视频单集 / 电影的前置检查提示层（整剧 / 全季走 BatchDownloadDialog 内联预检）。 */
    var precheckOpen by remember { mutableStateOf(false) }

    var selectedStorageIndex by remember { mutableIntStateOf(0) }
    var storageLocations = remember { context.getExternalFilesDirs(null) }

    val precheckContent =
        remember(item) {
            if (item is FindroidEpisode || item is FindroidMovie) {
                DetailDownloadRules.videoPrecheckContent(item)
            } else {
                null
            }
        }

    /** 存储位置选择：多存储时弹选择，否则直接入队。 */
    fun proceedToStorage() {
        if (!storageSelectionEnabled) {
            onDownloadClick(selectedStorageIndex)
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

    /** 单集 / 电影下载：先过前置检查提示层，确认后再选存储位置并入队。 */
    fun requestDownload() {
        if (precheckContent != null) {
            precheckOpen = true
            return
        }
        proceedToStorage()
    }

    val legacyDownloaded = item.isDownloaded()
    val isLegacy = downloadState == null
    val showDownloadAction = if (isLegacy) downloaderState != null else true
    val downloadBusyNow = downloadBusy || (isLegacy && downloaderState?.isDownloading == true)
    val resolvedDownloadState = downloadState
    val downloadLabel: String? =
        if (showDownloadAction) {
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
        } else {
            null
        }
    val downloadSelected =
        if (isLegacy) legacyDownloaded
        else resolvedDownloadState != DetailDownloadState.NOT_DOWNLOADED
    val downloadEnabledNow =
        when {
            downloadBusyNow -> false
            isLegacy -> item.canDownload || legacyDownloaded
            resolvedDownloadState == DetailDownloadState.NOT_DOWNLOADED -> downloadEnabled
            else -> true
        }
    val onDownloadAction: () -> Unit = {
        when {
            downloadBusyNow -> Unit
            isLegacy && legacyDownloaded -> deleteDownloadDialogOpen = true
            isLegacy -> requestDownload()
            resolvedDownloadState == DetailDownloadState.NOT_DOWNLOADED -> requestDownload()
            else -> onDownloadClick(0)
        }
    }

    val playDescription = stringResource(CoreR.string.play_button_description)
    val restartDescription = stringResource(FilmR.string.detail_play_from_beginning)
    val trailerDescription = stringResource(CoreR.string.trailer_button_description)
    val playedDescription = stringResource(CoreR.string.detail_action_played)
    val favoriteDescription = stringResource(CoreR.string.detail_action_favorite)

    val showRestart = item.playbackPositionTicks.div(600000000) > 0
    val showTrailer = trailerUri != null
    val overflowVisible = rememberItemOverflowMenuVisible(item)
    val keyCount =
        1 +
            (if (showRestart) 1 else 0) +
            (if (showTrailer) 1 else 0) +
            (if (showDownloadAction) 1 else 0) +
            2 +
            (if (overflowVisible) 1 else 0)

    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2),
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                // 宽屏动作排垫一层 glass（压在 backdrop 上，纯描边圆在浅色剧照上看不清）；竖屏 hero 不垫。
                val plateInset = if (heroLayout) 0.dp else CinefinSpacing.Space2
                val plateBorder = if (heroLayout) 0.dp else 1.dp
                val availableDp = (maxWidth - plateInset * 2 - plateBorder * 2).value
                val keySize =
                    detailActionKeySizeDp(
                            availableWidthDp = availableDp,
                            keyCount = keyCount,
                            preferredDp = if (heroLayout) 48f else 44f,
                            minimumDp = 40f,
                            gapDp = CinefinSpacing.Space2.value,
                        )
                        .dp
                FlowRow(
                    modifier =
                        Modifier.fillMaxWidth()
                            .then(
                                if (heroLayout) {
                                    Modifier
                                } else {
                                    Modifier.clip(CinefinShapes.Card)
                                        .background(colors.glass)
                                        .border(1.dp, colors.glassBorder, CinefinShapes.Card)
                                        .padding(plateInset)
                                }
                            ),
                    horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2),
                    verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2),
                ) {
                    DetailActionKey(
                        icon = CoreR.drawable.ic_play,
                        contentDescription = playDescription,
                        onClick = { onPlayClick(false) },
                        enabled = item.canPlay && canPlay,
                        emphasized = true,
                        primary = !heroLayout,
                        size = keySize,
                    )
                    if (showRestart) {
                        DetailActionKey(
                            icon = CoreR.drawable.ic_rotate_ccw,
                            contentDescription = restartDescription,
                            onClick = { onPlayClick(true) },
                            size = keySize,
                        )
                    }
                    trailerUri
                        ?.takeIf { showTrailer }
                        ?.let { uri ->
                            DetailActionKey(
                                icon = CoreR.drawable.ic_film,
                                contentDescription = trailerDescription,
                                onClick = { onTrailerClick(uri) },
                                size = keySize,
                            )
                        }
                    if (showDownloadAction) {
                        DetailActionKey(
                            icon = CoreR.drawable.ic_download,
                            contentDescription = downloadLabel,
                            selected = downloadSelected,
                            enabled = downloadEnabledNow,
                            onClick = onDownloadAction,
                            size = keySize,
                        )
                    }
                    DetailActionKey(
                        icon = CoreR.drawable.ic_check,
                        contentDescription = playedDescription,
                        selected = item.played,
                        onClick = onMarkAsPlayedClick,
                        size = keySize,
                    )
                    DetailActionKey(
                        icon =
                            if (item.favorite) CoreR.drawable.ic_bookmark_filled
                            else CoreR.drawable.ic_bookmark,
                        contentDescription = favoriteDescription,
                        selected = item.favorite,
                        onClick = onMarkAsFavoriteClick,
                        size = keySize,
                    )
                    ItemOverflowMenu(
                        item = item,
                        size = keySize,
                        touchSize = keySize,
                        outlined = true,
                    )
                }
            }
            if (downloaderState != null) {
                AnimatedVisibility(
                    downloaderState.isDownloading ||
                        downloaderState.status == DownloadManager.STATUS_FAILED
                ) {
                    DownloaderCard(
                        state = downloaderState,
                        onCancelClick = { cancelDownloadDialogOpen = true },
                        onRetryClick = { onDownloadClick(selectedStorageIndex) },
                    )
                }
            }
        }
        if (precheckOpen && precheckContent != null) {
            DownloadPrecheckDialog(
                content = precheckContent,
                space =
                    DownloadPrecheckRules.spaceStatus(
                        requiredBytes = precheckContent.sizeBytes,
                        availableBytes =
                            DownloadPrecheckEnvironment.availableBytes(
                                context,
                                selectedStorageIndex,
                            ),
                    ),
                networkPrompt = DownloadPrecheckEnvironment.networkPrompt(context),
                onConfirm = {
                    precheckOpen = false
                    proceedToStorage()
                },
                // 「仅 Wi-Fi 下载」= 正常入队，由 W57 网络策略等待 Wi-Fi / 以太网。
                onWifiOnly = {
                    precheckOpen = false
                    onDownloadClick(selectedStorageIndex)
                },
                onDismiss = { precheckOpen = false },
            )
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

/**
 * 详情动作区的圆形图标键（§8.1 Outlined 变形的图标版，2.0.3-C）：1dp `outline` 细描边、无填充底、图标 24dp； 选中（已下载 / 已播放 /
 * 喜欢）只换极光描边与内容色；播放键用极光内容色保持一级辨识。触控目标由 [size] 保证 ≥44dp。
 */
@Composable
private fun DetailActionKey(
    @DrawableRes icon: Int,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    emphasized: Boolean = false,
    /** 一级 CTA（宽屏动作排的播放键）：极光渐变实底 + `onAurora` 内容 + `auroraGlow` 投影（既有 CTA 口径）。 */
    primary: Boolean = false,
    enabled: Boolean = true,
    size: Dp = 44.dp,
) {
    val colors = LocalCinefinColors.current
    val primaryFill = primary && enabled
    val contentColor =
        when {
            !enabled -> colors.onSurfaceFaint.copy(alpha = colors.disabledAlpha)
            primaryFill -> colors.onAurora
            selected -> navSelectedContent(colors)
            emphasized -> colors.aurora1
            else -> colors.onSurfaceVariant
        }
    val borderColor =
        when {
            !enabled -> colors.outline.copy(alpha = colors.disabledAlpha)
            selected -> colors.aurora1.copy(alpha = CinefinTokens.MediaOutlineAlpha)
            else -> colors.outline
        }
    Box(
        modifier =
            modifier
                .size(size)
                .clip(CircleShape)
                .then(
                    if (primaryFill) {
                        Modifier.shadow(
                            elevation = CinefinTokens.AuroraGlowElevationDp.dp,
                            shape = CircleShape,
                            clip = false,
                            ambientColor =
                                colors.aurora1.copy(alpha = CinefinTokens.AuroraGlowAlpha),
                            spotColor = colors.aurora1.copy(alpha = CinefinTokens.AuroraGlowAlpha),
                        )
                    } else {
                        Modifier
                    }
                )
                .then(if (primaryFill) Modifier.auroraGradient(colors) else Modifier)
                .then(
                    if (primaryFill) Modifier else Modifier.border(1.dp, borderColor, CircleShape)
                )
                .cinefinClickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
            tint = contentColor,
            modifier = Modifier.size(24.dp),
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
