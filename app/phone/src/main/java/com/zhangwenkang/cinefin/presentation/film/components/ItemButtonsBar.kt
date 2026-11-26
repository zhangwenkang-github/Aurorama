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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.CinefinIconButton
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
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.auroraGradient
import com.zhangwenkang.cinefin.film.presentation.detail.DetailDownloadRules
import com.zhangwenkang.cinefin.film.presentation.detail.DetailDownloadState
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.models.FindroidShow
import com.zhangwenkang.cinefin.models.isDownloaded

/**
 * 详情页动作区（W51 重绘）。
 *
 * 布局：第一行 = 播放 / 重播 / 预告（图标键）；第二行 = **下载 / 已播放 / 喜欢** 三枚带标签按钮（同排，选中走极光选中语言）。
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
    /**
     * W66b：竖屏 hero 布局（DetailHero 竖屏专用）——一行四键（播放 / 下载 / 已播放 / 收藏，图标上文字下、64dp）， 屏宽 <360dp
     * 自动降级为「播放整行 + 三键一行」。默认 false = 既有两行布局（横屏 / 平板不变）。
     */
    heroLayout: Boolean = false,
) {
    val context = LocalContext.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp.toFloat()
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
    /** W66b：hero 布局的下载键数据（与既有两行布局共用同一套三态 / legacy 语义）。 */
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

    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2),
        ) {
            if (heroLayout) {
                DetailHeroActionButtons(
                    item = item,
                    canPlayNow = item.canPlay && canPlay,
                    onPlayClick = { onPlayClick(false) },
                    download =
                        downloadLabel?.let { label ->
                            HeroDownloadAction(
                                label = label,
                                selected = downloadSelected,
                                enabled = downloadEnabledNow,
                                onClick = onDownloadAction,
                            )
                        },
                    onMarkAsPlayedClick = onMarkAsPlayedClick,
                    onMarkAsFavoriteClick = onMarkAsFavoriteClick,
                    degraded = detailHeroActionsDegraded(screenWidthDp),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
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
                        DetailLabeledButton(
                            label = downloadLabel ?: "",
                            icon = CoreR.drawable.ic_download,
                            selected = downloadSelected,
                            enabled = downloadEnabledNow,
                            onClick = onDownloadAction,
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
 * 详情页带标签动作键（§8.1 Outlined 变形 + 选中态）：48dp 触控高、12dp 圆角、1dp 描边； 未选中 = 中性文字 / 透明底，选中 = 极光淡色容器 +
 * 极光描边与内容（2.0.1 定稿 §2 批② 全 App 统一选中语言；2.0.2 §D5 批② 起域色退出选中槽位）。
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
    val colors = LocalCinefinColors.current
    val contentColor =
        when {
            !enabled -> colors.onSurfaceFaint.copy(alpha = colors.disabledAlpha)
            selected -> navSelectedContent(colors)
            else -> colors.onSurfaceVariant
        }
    val containerColor =
        if (selected && enabled) {
            colors.aurora1.copy(alpha = CinefinTokens.MediaContainerAlpha)
        } else {
            Color.Transparent
        }
    val borderColor =
        if (selected && enabled) {
            colors.aurora1.copy(alpha = CinefinTokens.MediaOutlineAlpha)
        } else {
            colors.outline
        }
    Row(
        modifier =
            modifier
                .defaultMinSize(minHeight = 48.dp)
                .clip(CinefinShapes.Button)
                .background(containerColor)
                .border(1.dp, borderColor, CinefinShapes.Button)
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

/** hero 布局的下载键数据（W66b；与两行布局共用同一套三态 / legacy 语义）。 */
private class HeroDownloadAction(
    val label: String,
    val selected: Boolean,
    val enabled: Boolean,
    val onClick: () -> Unit,
)

/**
 * W66b 竖屏 hero 动作区：一行四键（播放 / 下载 / 已播放 / 收藏）等宽、图标上文字下、64dp； 屏宽 <360dp 由 [degraded] 降级为「播放整行 + 三键一行」。
 */
@Composable
private fun DetailHeroActionButtons(
    item: FindroidItem,
    canPlayNow: Boolean,
    onPlayClick: () -> Unit,
    download: HeroDownloadAction?,
    onMarkAsPlayedClick: () -> Unit,
    onMarkAsFavoriteClick: () -> Unit,
    degraded: Boolean,
    modifier: Modifier = Modifier,
) {
    val playLabel = stringResource(CoreR.string.play)
    val playedLabel = stringResource(CoreR.string.detail_action_played)
    val favoriteLabel = stringResource(CoreR.string.detail_action_favorite)
    val favoriteIcon =
        if (item.favorite) CoreR.drawable.ic_bookmark_filled else CoreR.drawable.ic_bookmark

    if (degraded) {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2),
        ) {
            HeroActionKey(
                icon = CoreR.drawable.ic_play,
                label = playLabel,
                selected = false,
                emphasized = true,
                enabled = canPlayNow,
                onClick = onPlayClick,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2)) {
                download?.let { action ->
                    HeroActionKey(
                        icon = CoreR.drawable.ic_download,
                        label = action.label,
                        selected = action.selected,
                        enabled = action.enabled,
                        onClick = action.onClick,
                        modifier = Modifier.weight(1f),
                    )
                }
                HeroActionKey(
                    icon = CoreR.drawable.ic_check,
                    label = playedLabel,
                    selected = item.played,
                    enabled = true,
                    onClick = onMarkAsPlayedClick,
                    modifier = Modifier.weight(1f),
                )
                HeroActionKey(
                    icon = favoriteIcon,
                    label = favoriteLabel,
                    selected = item.favorite,
                    enabled = true,
                    onClick = onMarkAsFavoriteClick,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    } else {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2),
        ) {
            HeroActionKey(
                icon = CoreR.drawable.ic_play,
                label = playLabel,
                selected = false,
                emphasized = true,
                enabled = canPlayNow,
                onClick = onPlayClick,
                modifier = Modifier.weight(1f),
            )
            download?.let { action ->
                HeroActionKey(
                    icon = CoreR.drawable.ic_download,
                    label = action.label,
                    selected = action.selected,
                    enabled = action.enabled,
                    onClick = action.onClick,
                    modifier = Modifier.weight(1f),
                )
            }
            HeroActionKey(
                icon = CoreR.drawable.ic_check,
                label = playedLabel,
                selected = item.played,
                enabled = true,
                onClick = onMarkAsPlayedClick,
                modifier = Modifier.weight(1f),
            )
            HeroActionKey(
                icon = favoriteIcon,
                label = favoriteLabel,
                selected = item.favorite,
                enabled = true,
                onClick = onMarkAsFavoriteClick,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * hero 键（图标上 / 文字下，64dp）：[emphasized] = 播放键（一级 CTA）极光渐变实底 + `onAurora` 内容 + `auroraGlow` 投影（2.0.1
 * 定稿 §2 批②；禁用时回落中性实底）；其余三键 = 1dp 描边 Outlined，选中（已播放 / 收藏 / 已下载）走极光淡色容器 + 极光描边与内容。
 */
@Composable
private fun HeroActionKey(
    @DrawableRes icon: Int,
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
) {
    val colors = LocalCinefinColors.current
    // 一级 CTA（播放）= 极光渐变实底 + onAurora 内容 + auroraGlow 投影；禁用时回落中性实底。
    val gradientFill = emphasized && enabled
    val contentColor =
        when {
            !enabled -> colors.onSurfaceFaint.copy(alpha = colors.disabledAlpha)
            emphasized -> colors.onAurora
            selected -> navSelectedContent(colors)
            else -> colors.onSurfaceVariant
        }
    val containerColor =
        when {
            emphasized -> if (enabled) Color.Transparent else colors.onSurface
            selected && enabled -> colors.aurora1.copy(alpha = CinefinTokens.MediaContainerAlpha)
            else -> Color.Transparent
        }
    val borderColor =
        when {
            emphasized -> Color.Transparent
            selected && enabled -> colors.aurora1.copy(alpha = CinefinTokens.MediaOutlineAlpha)
            else -> colors.outline
        }
    Column(
        modifier =
            modifier
                .height(64.dp)
                .then(
                    if (gradientFill) {
                        Modifier.shadow(
                            elevation = CinefinTokens.AuroraGlowElevationDp.dp,
                            shape = CinefinShapes.Button,
                            clip = false,
                            ambientColor =
                                colors.aurora1.copy(alpha = CinefinTokens.AuroraGlowAlpha),
                            spotColor = colors.aurora1.copy(alpha = CinefinTokens.AuroraGlowAlpha),
                        )
                    } else {
                        Modifier
                    }
                )
                .clip(CinefinShapes.Button)
                .then(
                    if (gradientFill) {
                        Modifier.auroraGradient(colors)
                    } else {
                        Modifier.background(containerColor)
                    }
                )
                .border(1.dp, borderColor, CinefinShapes.Button)
                .cinefinClickable(enabled = enabled, onClick = onClick)
                .padding(vertical = CinefinSpacing.Space2),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.height(CinefinSpacing.Space1))
        Text(
            text = label,
            style = CinefinType.LabelMedium,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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
