package com.zhangwenkang.cinefin.presentation.film.downloads

import android.content.Context
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButton
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonSize
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonTone
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonVariant
import com.zhangwenkang.cinefin.core.presentation.components.CinefinCard
import com.zhangwenkang.cinefin.core.presentation.components.CinefinIconButton
import com.zhangwenkang.cinefin.core.presentation.components.CinefinSwitch
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinMotion
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalLumenColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadAction
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadFormatRules
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadHierarchyContainer
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadHierarchyEntry
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadHierarchyLeaf
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadHierarchyStatus
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadHierarchySubContainer
import com.zhangwenkang.cinefin.film.presentation.downloads.effectiveDownloadedBytes
import com.zhangwenkang.cinefin.film.presentation.downloads.expectedTotalBytes
import com.zhangwenkang.cinefin.presentation.components.LumenSkeletonBlock
import com.zhangwenkang.cinefin.presentation.components.LumenSkeletonLine
import com.zhangwenkang.cinefin.presentation.film.components.LumenCardFrame
import com.zhangwenkang.cinefin.utils.DownloadFailureReason
import com.zhangwenkang.cinefin.utils.DownloadMediaKind
import com.zhangwenkang.cinefin.utils.DownloadTask
import com.zhangwenkang.cinefin.utils.DownloadTaskRules
import com.zhangwenkang.cinefin.utils.DownloadTaskStatus

/**
 * W52 下载页条目尺寸 / 排版规格（`DOWNLOAD_PLAN.md` §19）。
 *
 * 手机：节目 / 季海报 **96×144dp**（2:3，贴满行高，行高 144dp）；剧集缩略图 112×63dp（16:9）； 平板：海报放大到
 * **104×156dp**，折叠容器两列并排。
 */
internal object DownloadListMetrics {
    val PosterWidth = 96.dp
    val PosterHeight = 144.dp
    val PosterWidthExpanded = 104.dp
    val PosterHeightExpanded = 156.dp
    val AlbumArtSize = 96.dp
    val AlbumArtSizeExpanded = 104.dp
    val SeasonPosterWidth = 72.dp
    val SeasonPosterHeight = 108.dp
    val EpisodeThumbWidth = 112.dp
    val EpisodeThumbHeight = 63.dp
    val MovieThumbWidth = 64.dp
    val MovieThumbHeight = 96.dp
    val TrackThumbSize = 56.dp
    val BookThumbWidth = 48.dp
    val BookThumbHeight = 72.dp
    val RowPadding = 12.dp
    val DepthIndent = 12.dp
}

/** 容器卡（节目 / 电影 / 专辑 / 书籍）：大海报 + 聚合进度 / 已下载 x/y / 已用与总大小 / 速度与剩余时间。 */
@Composable
internal fun DownloadContainerCard(
    container: DownloadHierarchyContainer,
    compact: Boolean,
    collapsed: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
    onRequestDelete: () -> Unit,
    onSetOffline: (Boolean) -> Unit,
) {
    val colors = LocalCinefinColors.current
    val square = container.mediaKind == DownloadMediaKind.MUSIC
    val artworkWidth =
        when {
            square && compact -> DownloadListMetrics.AlbumArtSize
            square -> DownloadListMetrics.AlbumArtSizeExpanded
            compact -> DownloadListMetrics.PosterWidth
            else -> DownloadListMetrics.PosterWidthExpanded
        }
    val artworkHeight =
        when {
            square -> artworkWidth
            compact -> DownloadListMetrics.PosterHeight
            else -> DownloadListMetrics.PosterHeightExpanded
        }

    CinefinCard(
        modifier = modifier,
        onClick = onToggle,
        selected = selected,
        contentPadding = PaddingValues(0.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DownloadArtwork(
                model = container.imageUri,
                kind = container.mediaKind,
                modifier = Modifier.width(artworkWidth).height(artworkHeight),
            )
            Column(
                modifier =
                    Modifier.weight(1f)
                        .padding(
                            horizontal = DownloadListMetrics.RowPadding,
                            vertical = DownloadListMetrics.RowPadding,
                        )
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = container.title,
                        style = CinefinType.TitleSmall,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(CinefinSpacing.Space2))
                    Icon(
                        painter =
                            painterResource(
                                if (collapsed) CoreR.drawable.ic_chevron_down
                                else CoreR.drawable.ic_chevron_up
                            ),
                        contentDescription =
                            stringResource(
                                if (collapsed) CoreR.string.nav_expand
                                else CoreR.string.nav_collapse
                            ),
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
                container.detail?.let { detail ->
                    Spacer(Modifier.height(CinefinSpacing.Space1))
                    Text(
                        text = detail,
                        style = CinefinType.BodySmall,
                        color =
                            if (container.status == DownloadHierarchyStatus.FAILED) colors.error
                            else colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(CinefinSpacing.Space1))
                Text(
                    text =
                        stringResource(
                            CoreR.string.download_container_progress,
                            DownloadFormatRules.formatCountProgress(
                                container.completedCount,
                                container.totalCount,
                            ),
                        ) +
                            " · " +
                            DownloadFormatRules.formatSizePair(
                                container.downloadedBytes,
                                container.totalBytes,
                            ),
                    style = CinefinType.BodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(CinefinSpacing.Space1))
                DownloadProgressBar(
                    progress = container.byteProgress,
                    indeterminate =
                        container.status == DownloadHierarchyStatus.RUNNING &&
                            container.totalBytes <= 0L,
                )
                Spacer(Modifier.height(CinefinSpacing.Space1))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = containerSpeedLine(container),
                        style = CinefinType.LabelSmall,
                        color = colors.onSurfaceFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (!selectionMode && container.status == DownloadHierarchyStatus.COMPLETED) {
                        CinefinSwitch(
                            checked = container.descendantEntries().all { it.allowOffline },
                            onCheckedChange = { allow -> onSetOffline(allow) },
                        )
                    }
                    if (!selectionMode && container.canDelete) {
                        CinefinIconButton(onClick = onRequestDelete) { tint ->
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_trash),
                                contentDescription =
                                    stringResource(CoreR.string.download_action_delete),
                                tint = tint,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 季容器卡（第二级，只展开 / 折叠）：72×108dp 海报 + 同样的聚合口径。 */
@Composable
internal fun DownloadSeasonCard(
    container: DownloadHierarchySubContainer,
    depth: Int,
    collapsed: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
) {
    val colors = LocalCinefinColors.current
    CinefinCard(
        modifier = modifier,
        onClick = onToggle,
        contentPadding = PaddingValues(0.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(DownloadListMetrics.DepthIndent * depth))
            DownloadArtwork(
                model = container.imageUri,
                kind = DownloadMediaKind.VIDEO,
                modifier =
                    Modifier.width(DownloadListMetrics.SeasonPosterWidth)
                        .height(DownloadListMetrics.SeasonPosterHeight),
            )
            Column(
                modifier =
                    Modifier.weight(1f)
                        .padding(
                            horizontal = DownloadListMetrics.RowPadding,
                            vertical = CinefinSpacing.Space3,
                        )
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = container.title,
                        style = CinefinType.TitleSmall,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(CinefinSpacing.Space2))
                    Icon(
                        painter =
                            painterResource(
                                if (collapsed) CoreR.drawable.ic_chevron_down
                                else CoreR.drawable.ic_chevron_up
                            ),
                        contentDescription =
                            stringResource(
                                if (collapsed) CoreR.string.nav_expand
                                else CoreR.string.nav_collapse
                            ),
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
                container.detail?.let { detail ->
                    Spacer(Modifier.height(CinefinSpacing.Space1))
                    Text(
                        text = detail,
                        style = CinefinType.BodySmall,
                        color =
                            if (container.status == DownloadHierarchyStatus.FAILED) colors.error
                            else colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(CinefinSpacing.Space1))
                Text(
                    text =
                        stringResource(
                            CoreR.string.download_container_progress,
                            DownloadFormatRules.formatCountProgress(
                                container.completedCount,
                                container.totalCount,
                            ),
                        ) +
                            " · " +
                            DownloadFormatRules.formatSizePair(
                                container.downloadedBytes,
                                container.totalBytes,
                            ),
                    style = CinefinType.BodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(CinefinSpacing.Space1))
                DownloadProgressBar(
                    progress = container.byteProgress,
                    indeterminate =
                        container.status == DownloadHierarchyStatus.RUNNING &&
                            container.totalBytes <= 0L,
                )
                Spacer(Modifier.height(CinefinSpacing.Space1))
                Text(
                    text = containerSpeedLine(container),
                    style = CinefinType.LabelSmall,
                    color = colors.onSurfaceFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 条目行（剧集 / 曲目 / 电影 / 书籍）：缩略图 + 季集号 / 标题 / 大小 / 状态徽标 / 进度。 */
@Composable
internal fun DownloadLeafCard(
    entry: DownloadHierarchyEntry,
    depth: Int,
    selectionMode: Boolean,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onToggleSelection: () -> Unit,
    onOpen: () -> Unit,
    onAction: (DownloadAction) -> Unit,
    onRequestDelete: (key: String, title: String) -> Unit,
) {
    val colors = LocalCinefinColors.current
    val task = entry.task
    val completed = entry.status == DownloadTaskStatus.COMPLETED
    val (artworkWidth, artworkHeight) = leafArtworkSize(entry)

    CinefinCard(
        modifier = modifier,
        onClick = {
            when {
                selectionMode -> onToggleSelection()
                entry.mediaKind == DownloadMediaKind.BOOK || completed -> onOpen()
            }
        },
        selected = selected,
        contentPadding = PaddingValues(0.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selectionMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggleSelection() },
                    modifier = Modifier.padding(start = CinefinSpacing.Space2),
                )
            }
            Spacer(Modifier.width(DownloadListMetrics.DepthIndent * depth))
            DownloadArtwork(
                model = entry.imageUri,
                kind = entry.mediaKind,
                modifier = Modifier.width(artworkWidth).height(artworkHeight),
                shape =
                    if (entry.mediaKind == DownloadMediaKind.MUSIC) CinefinShapes.Xs
                    else CinefinShapes.Sm,
            )
            Column(
                modifier =
                    Modifier.weight(1f)
                        .padding(
                            horizontal = DownloadListMetrics.RowPadding,
                            vertical = CinefinSpacing.Space3,
                        )
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    entry.indexLabel()?.let { label ->
                        Text(
                            text = label,
                            style = CinefinType.LabelMedium,
                            color = LocalMediaColors.current.bright,
                            maxLines = 1,
                        )
                        Spacer(Modifier.width(CinefinSpacing.Space2))
                    }
                    Text(
                        text = entry.name,
                        style = CinefinType.BodyLarge,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(CinefinSpacing.Space1))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DownloadStatusBadge(status = entry.status, failureReason = task?.failureReason)
                    Spacer(Modifier.width(CinefinSpacing.Space2))
                    Text(
                        text = leafSizeLine(entry),
                        style = CinefinType.BodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (task != null && task.isActive) {
                    Spacer(Modifier.height(CinefinSpacing.Space2))
                    DownloadProgressBar(
                        progress = task.progress,
                        indeterminate =
                            task.status == DownloadTaskStatus.RUNNING && task.totalBytes <= 0L,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = leafSpeedLine(task),
                        style = CinefinType.LabelSmall,
                        color = colors.onSurfaceFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (!selectionMode) {
                        if (completed) {
                            CinefinSwitch(
                                checked = entry.allowOffline,
                                onCheckedChange = {
                                    onAction(DownloadAction.ToggleOffline(entry.key))
                                },
                            )
                        }
                        if (task != null) {
                            when {
                                DownloadTaskRules.canPause(task.status) ->
                                    CinefinIconButton(
                                        onClick = { onAction(DownloadAction.Pause(task)) }
                                    ) { tint ->
                                        Icon(
                                            painter = painterResource(CoreR.drawable.ic_pause),
                                            contentDescription =
                                                stringResource(CoreR.string.download_action_pause),
                                            tint = tint,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                DownloadTaskRules.canResume(task.status) ->
                                    CinefinIconButton(
                                        onClick = { onAction(DownloadAction.Resume(task)) }
                                    ) { tint ->
                                        Icon(
                                            painter = painterResource(CoreR.drawable.ic_play),
                                            contentDescription =
                                                stringResource(CoreR.string.download_action_resume),
                                            tint = tint,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                DownloadTaskRules.canRetry(task.status) ->
                                    CinefinIconButton(
                                        onClick = { onAction(DownloadAction.Retry(task)) }
                                    ) { tint ->
                                        Icon(
                                            painter = painterResource(CoreR.drawable.ic_rotate_ccw),
                                            contentDescription =
                                                stringResource(CoreR.string.download_action_retry),
                                            tint = tint,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                            }
                        }
                        if (entry.canDelete) {
                            CinefinIconButton(
                                onClick = { onRequestDelete(entry.key, entry.name) }
                            ) { tint ->
                                Icon(
                                    painter = painterResource(CoreR.drawable.ic_trash),
                                    contentDescription =
                                        stringResource(CoreR.string.download_action_delete),
                                    tint = tint,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 下载页状态徽标（旧 Findroid 文本行 → Prism / Lumen 中性徽标 + 12dp 前置图标）。 */
@Composable
private fun DownloadStatusBadge(
    status: DownloadTaskStatus,
    failureReason: DownloadFailureReason?,
) {
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    val running = status == DownloadTaskStatus.RUNNING
    val failed = status == DownloadTaskStatus.FAILED
    val contentColor =
        when {
            failed -> colors.error
            running -> media.bright
            else -> colors.onSurfaceVariant
        }
    val borderColor =
        when {
            failed -> colors.error.copy(alpha = 0.45f)
            running -> media.outline
            else -> colors.outline
        }
    val icon =
        when (status) {
            DownloadTaskStatus.PENDING,
            DownloadTaskStatus.RUNNING -> CoreR.drawable.ic_download
            DownloadTaskStatus.PAUSED -> CoreR.drawable.ic_pause
            DownloadTaskStatus.COMPLETED -> CoreR.drawable.ic_check
            DownloadTaskStatus.FAILED -> CoreR.drawable.ic_alert_circle
        }
    val label =
        when (status) {
            DownloadTaskStatus.PENDING ->
                if (failureReason == DownloadFailureReason.NETWORK_UNAVAILABLE) {
                    stringResource(CoreR.string.download_waiting_network)
                } else {
                    stringResource(CoreR.string.download_pending)
                }
            DownloadTaskStatus.RUNNING -> stringResource(CoreR.string.download_downloading)
            DownloadTaskStatus.PAUSED ->
                if (failureReason == DownloadFailureReason.NETWORK_UNAVAILABLE) {
                    stringResource(CoreR.string.download_waiting_network)
                } else {
                    stringResource(CoreR.string.download_paused)
                }
            DownloadTaskStatus.COMPLETED -> stringResource(CoreR.string.download_tasks_completed)
            DownloadTaskStatus.FAILED -> failureLabel(failureReason)
        }
    Row(
        modifier =
            Modifier.clip(CinefinShapes.Xs)
                .background(if (running) media.container else Color.Transparent)
                .border(1.dp, borderColor, CinefinShapes.Xs)
                .padding(horizontal = CinefinSpacing.Space2, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space1),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(12.dp),
        )
        Text(text = label, style = CinefinType.LabelSmall, color = contentColor, maxLines = 1)
    }
}

/** 下载进度条：200ms linear 跟随轮询值（§6.3 进度条口径），总大小未知时用不确定态。 */
@Composable
private fun DownloadProgressBar(
    progress: Float,
    indeterminate: Boolean,
    modifier: Modifier = Modifier,
    height: Dp = 4.dp,
) {
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    if (indeterminate) {
        LinearProgressIndicator(
            modifier = modifier.fillMaxWidth().height(height).clip(CinefinShapes.Full),
            color = media.base,
            trackColor = colors.progressTrack,
        )
        return
    }
    val animated by
        animateFloatAsState(
            targetValue = progress.coerceIn(0f, 1f),
            animationSpec = tween(durationMillis = CinefinMotion.Fast, easing = LinearEasing),
            label = "downloadProgress",
        )
    LinearProgressIndicator(
        progress = { animated },
        modifier = modifier.fillMaxWidth().height(height).clip(CinefinShapes.Full),
        color = media.base,
        trackColor = colors.progressTrack,
    )
}

/** 封面 / 缩略图：本地缓存优先由 ViewModel 解析；无图时按媒体类型用图标占位（不新增位图资源）。 */
@Composable
internal fun DownloadArtwork(
    model: String?,
    kind: DownloadMediaKind,
    modifier: Modifier = Modifier,
    shape: Shape = CinefinShapes.Sm,
) {
    val colors = LocalCinefinColors.current
    val context = LocalContext.current
    val resolved = remember(model, context) { resolveArtworkModel(context, model) }
    Box(
        modifier = modifier.clip(shape).background(colors.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(kind.iconRes()),
            contentDescription = null,
            tint = colors.onSurfaceFaint,
            modifier = Modifier.size(24.dp),
        )
        if (resolved != null) {
            // 占位 = 类型图标：加载中 / 失败时露在图下层，加载完成后被图片覆盖（Coil 加载与占位规范）。
            AsyncImage(
                model = resolved,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** 删除 / 暂停类确认对话框：Lumen 面板 + 月白主行动（下载页内旧 M3 AlertDialog 的重绘）。 */
@Composable
internal fun DownloadConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val lumen = LocalLumenColors.current
    val colors = LocalCinefinColors.current
    Dialog(onDismissRequest = onDismiss) {
        LumenCardFrame(
            shape = CinefinShapes.Xl,
            container = lumen?.panel ?: colors.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp),
        ) {
            Column(modifier = Modifier.padding(CinefinSpacing.Space6)) {
                Text(
                    text = title,
                    style = CinefinType.HeadlineSmall,
                    color = colors.onSurface,
                )
                Spacer(Modifier.height(CinefinSpacing.Space3))
                Text(
                    text = message,
                    style = CinefinType.BodyMedium,
                    color = colors.onSurfaceVariant,
                )
                Spacer(Modifier.height(CinefinSpacing.Space6))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CinefinButton(
                        text = stringResource(CoreR.string.cancel),
                        onClick = onDismiss,
                        variant = CinefinButtonVariant.Text,
                        size = CinefinButtonSize.Medium,
                    )
                    Spacer(Modifier.width(CinefinSpacing.Space2))
                    CinefinButton(
                        text = confirmText,
                        onClick = onConfirm,
                        variant = CinefinButtonVariant.Filled,
                        tone =
                            if (lumen != null) CinefinButtonTone.Inverse
                            else CinefinButtonTone.Media,
                        size = CinefinButtonSize.Medium,
                    )
                }
            }
        }
    }
}

/** 加载骨架：大海报行 ×4（沿用 LumenSkeleton 组件，不新增位图 / 动效）。 */
@Composable
internal fun DownloadListSkeleton(
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space3),
    ) {
        repeat(4) {
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .height(
                            if (compact) DownloadListMetrics.PosterHeight
                            else DownloadListMetrics.PosterHeightExpanded
                        )
            ) {
                LumenSkeletonBlock(
                    modifier = Modifier.width(DownloadListMetrics.PosterWidth).fillMaxHeight(),
                    shape = CinefinShapes.Md,
                )
                Column(
                    modifier = Modifier.weight(1f).padding(DownloadListMetrics.RowPadding),
                    verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2),
                ) {
                    LumenSkeletonLine(widthFraction = 0.5f, height = 18.dp)
                    LumenSkeletonLine(widthFraction = 0.32f, height = 14.dp)
                    LumenSkeletonLine(widthFraction = 0.72f, height = 14.dp)
                    LumenSkeletonBlock(
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        shape = CinefinShapes.Full,
                    )
                }
            }
        }
    }
}

@Composable
private fun containerSpeedLine(container: DownloadHierarchyContainer): String {
    if (container.status == DownloadHierarchyStatus.COMPLETED) return ""
    return containerSpeedLine(container.speedBytesPerSecond, container.etaSeconds)
}

@Composable
private fun containerSpeedLine(container: DownloadHierarchySubContainer): String =
    containerSpeedLine(container.speedBytesPerSecond, container.etaSeconds)

@Composable
private fun containerSpeedLine(speedBytesPerSecond: Long, etaSeconds: Long?): String =
    DownloadFormatRules.formatSpeed(speedBytesPerSecond) +
        " · " +
        stringResource(
            CoreR.string.download_eta_remaining,
            DownloadFormatRules.formatEta(etaSeconds),
        )

@Composable
private fun leafSpeedLine(task: DownloadTask?): String {
    if (task == null || task.status == DownloadTaskStatus.COMPLETED) return ""
    return DownloadFormatRules.formatSpeed(task.speedBytesPerSecond) +
        " · " +
        stringResource(
            CoreR.string.download_eta_remaining,
            DownloadFormatRules.formatEta(task.etaSeconds),
        )
}

private fun leafSizeLine(entry: DownloadHierarchyEntry): String {
    val downloaded = entry.effectiveDownloadedBytes
    val total = entry.expectedTotalBytes
    return when {
        entry.status == DownloadTaskStatus.RUNNING ->
            DownloadFormatRules.formatPercent(
                if (total > 0L) downloaded.toFloat() / total.toFloat() else 0f
            ) + " · " + DownloadFormatRules.formatSizePair(downloaded, total)
        total > 0L && downloaded < total -> DownloadFormatRules.formatSizePair(downloaded, total)
        else -> DownloadFormatRules.formatBytes(downloaded)
    }
}

private fun leafArtworkSize(entry: DownloadHierarchyEntry): Pair<Dp, Dp> =
    when {
        entry.mediaKind == DownloadMediaKind.MUSIC ->
            DownloadListMetrics.TrackThumbSize to DownloadListMetrics.TrackThumbSize
        entry.mediaKind == DownloadMediaKind.BOOK ->
            DownloadListMetrics.BookThumbWidth to DownloadListMetrics.BookThumbHeight
        entry.seriesId != null ->
            DownloadListMetrics.EpisodeThumbWidth to DownloadListMetrics.EpisodeThumbHeight
        else -> DownloadListMetrics.MovieThumbWidth to DownloadListMetrics.MovieThumbHeight
    }

/** 季集号 / 音轨号（纯展示，缺数据不占位）。 */
private fun DownloadHierarchyEntry.indexLabel(): String? =
    when {
        mediaKind == DownloadMediaKind.VIDEO && seriesId != null && episodeIndex > 0 ->
            "S${seasonIndex.coerceAtLeast(1)}E$episodeIndex"
        mediaKind == DownloadMediaKind.MUSIC && trackIndex > 0 ->
            trackIndex.toString().padStart(2, '0')
        else -> null
    }

/** 容器内的全部条目（容器级离线开关的聚合口径）。 */
internal fun DownloadHierarchyContainer.descendantEntries(): List<DownloadHierarchyEntry> =
    children.flatMap { child ->
        when (child) {
            is DownloadHierarchyLeaf -> listOf(child.entry)
            is DownloadHierarchySubContainer -> child.children.map { it.entry }
        }
    }

private fun DownloadMediaKind.iconRes(): Int =
    when (this) {
        DownloadMediaKind.VIDEO -> CoreR.drawable.ic_film
        DownloadMediaKind.MUSIC -> CoreR.drawable.ic_music
        DownloadMediaKind.BOOK -> CoreR.drawable.ic_book
    }

private fun resolveArtworkModel(context: Context, raw: String?): String? =
    when {
        raw == null -> null
        raw.contains("://") -> raw
        raw.startsWith("/") -> raw
        else -> "${context.filesDir}/$raw"
    }

@Composable
private fun failureLabel(reason: DownloadFailureReason?): String =
    when (reason) {
        DownloadFailureReason.STORAGE_INSUFFICIENT ->
            stringResource(CoreR.string.download_failure_storage)
        DownloadFailureReason.NETWORK_UNAVAILABLE ->
            stringResource(CoreR.string.download_failure_network)
        DownloadFailureReason.SERVER_ERROR -> stringResource(CoreR.string.download_failure_server)
        DownloadFailureReason.AUTHENTICATION ->
            stringResource(CoreR.string.download_failure_authentication)
        DownloadFailureReason.CANNOT_RESUME ->
            stringResource(CoreR.string.download_failure_cannot_resume)
        DownloadFailureReason.FILE_ERROR -> stringResource(CoreR.string.download_failure_file)
        DownloadFailureReason.CANCELLED -> stringResource(CoreR.string.download_failure_cancelled)
        DownloadFailureReason.UNKNOWN,
        null -> stringResource(CoreR.string.download_failure_unknown)
    }
