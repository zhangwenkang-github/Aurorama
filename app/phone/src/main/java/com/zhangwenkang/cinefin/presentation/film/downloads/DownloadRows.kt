package com.zhangwenkang.cinefin.presentation.film.downloads

import android.content.Context
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButton
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonSize
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonTone
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonVariant
import com.zhangwenkang.cinefin.core.presentation.components.CinefinCard
import com.zhangwenkang.cinefin.core.presentation.components.CinefinIconButton
import com.zhangwenkang.cinefin.core.presentation.components.CinefinSelectIndicator
import com.zhangwenkang.cinefin.core.presentation.components.CinefinSwitch
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinMotion
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadAction
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadBookSection
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadContainerTaskAction
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadDeletionProjection
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadDeletionSummary
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadFormatRules
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadHierarchyContainer
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadHierarchyEntry
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadHierarchyStatus
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadHierarchySubContainer
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadPendingAction
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadSortMode
import com.zhangwenkang.cinefin.film.presentation.downloads.allEntries
import com.zhangwenkang.cinefin.film.presentation.downloads.effectiveDownloadedBytes
import com.zhangwenkang.cinefin.film.presentation.downloads.expectedTotalBytes
import com.zhangwenkang.cinefin.film.presentation.downloads.taskActionSpecs
import com.zhangwenkang.cinefin.presentation.components.LumenSkeletonBlock
import com.zhangwenkang.cinefin.presentation.components.LumenSkeletonLine
import com.zhangwenkang.cinefin.presentation.film.components.LumenCardFrame
import com.zhangwenkang.cinefin.utils.CanonicalDownloadAction
import com.zhangwenkang.cinefin.utils.DownloadControlPlaneRules
import com.zhangwenkang.cinefin.utils.DownloadFailureReason
import com.zhangwenkang.cinefin.utils.DownloadMediaKind
import com.zhangwenkang.cinefin.utils.DownloadTask
import com.zhangwenkang.cinefin.utils.DownloadTaskPhase
import com.zhangwenkang.cinefin.utils.DownloadTaskStatus
import com.zhangwenkang.cinefin.utils.ImageCacheRules
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 纯函数（W60b）：任务是否处于「自动重试」窗口——等待下一次重试（PENDING）或正在重试（RUNNING）且已有重试计数。
 *
 * 下载页行内状态徽标据此把「正在下载… / 正在等待…」换成「重试中 · 第 N 次」（core 文案由 W60 落地，W60b 接线）。
 */
internal fun showsRetryLabel(task: DownloadTask?): Boolean =
    task != null &&
        task.retryCount > 0 &&
        (task.status == DownloadTaskStatus.PENDING || task.status == DownloadTaskStatus.RUNNING)

/** 已完成条目必须显示成功态，不能落入失败原因回落。 */
internal fun showsCompletedDownloadLabel(status: DownloadTaskStatus): Boolean =
    status == DownloadTaskStatus.COMPLETED

/** 任务 / 书籍的失败详情优先级；无详情时继续走既有 failureReason 文案。 */
internal fun visibleFailureDetail(task: DownloadTask?, bookDetail: String?): String? =
    task?.failureDetail?.takeIf { it.isNotBlank() } ?: bookDetail?.takeIf { it.isNotBlank() }

/** 普通下载行尾部可独立聚焦的动作。 */
internal enum class DownloadRowTaskAction {
    PAUSE,
    RESUME,
    RETRY,
    DELETE,
}

/** 尾部动作与本地化标签的唯一选择规则，Composable 与 JVM 契约测试共同消费。 */
internal data class DownloadRowActionSpec(
    val action: DownloadRowTaskAction,
    val labelRes: Int,
    val eligibleTaskKeys: Set<String> = emptySet(),
)

/** 下载行内容区的点击能力；null 表示该行没有可执行的单击动作。 */
internal enum class DownloadRowContentAction(val labelRes: Int) {
    OPEN_DETAILS(CoreR.string.download_detail_open),
    /** W9-T5（F4）：已完成音乐条目单击 = 直接本地播放（不经服务器详情 / `Items` 请求）。 */
    PLAY(CoreR.string.play),
    TOGGLE_SELECTION(CoreR.string.download_action_select),
}

/** pending 只保留原图标动作位，不恢复禁用按钮语义。 */
internal object PendingDownloadActionMetrics {
    val Width = 44.dp
    val Height = 48.dp
}

internal fun downloadRowActionSpecs(
    status: DownloadTaskStatus?,
    canDelete: Boolean,
    pending: Boolean,
    intentOnly: Boolean = false,
    deleteLabelRes: Int = CoreR.string.download_action_delete,
    canonicalActions: Set<CanonicalDownloadAction>? = null,
): List<DownloadRowActionSpec> {
    if (pending || status == DownloadTaskStatus.DELETING) return emptyList()
    return buildList {
        when {
            canonicalActions != null -> {
                if (CanonicalDownloadAction.PAUSE in canonicalActions) {
                    add(
                        DownloadRowActionSpec(
                            DownloadRowTaskAction.PAUSE,
                            CoreR.string.download_action_pause,
                        )
                    )
                }
                if (CanonicalDownloadAction.RESUME in canonicalActions) {
                    add(
                        DownloadRowActionSpec(
                            DownloadRowTaskAction.RESUME,
                            CoreR.string.download_action_resume,
                        )
                    )
                }
            }
            status == DownloadTaskStatus.RUNNING || status == DownloadTaskStatus.PENDING ->
                if (!intentOnly) {
                    add(
                        DownloadRowActionSpec(
                            DownloadRowTaskAction.PAUSE,
                            CoreR.string.download_action_pause,
                        )
                    )
                }
            status == DownloadTaskStatus.PAUSED ->
                add(
                    DownloadRowActionSpec(
                        DownloadRowTaskAction.RESUME,
                        CoreR.string.download_action_resume,
                    )
                )
            status == DownloadTaskStatus.FAILED ->
                add(
                    DownloadRowActionSpec(
                        DownloadRowTaskAction.RETRY,
                        CoreR.string.download_action_retry,
                    )
                )
        }
        if (canDelete) {
            add(
                DownloadRowActionSpec(
                    DownloadRowTaskAction.DELETE,
                    deleteLabelRes,
                )
            )
        }
    }
}

internal fun downloadRowActionSpecs(
    task: DownloadTask,
    canDelete: Boolean,
    pending: Boolean,
    deleteLabelRes: Int = CoreR.string.download_action_delete,
    canonicalActions: Set<CanonicalDownloadAction>? = null,
): List<DownloadRowActionSpec> =
    downloadRowActionSpecs(
        status = task.status,
        canDelete = canDelete,
        pending = pending,
        intentOnly = task.isIntentOnly,
        deleteLabelRes = deleteLabelRes,
        canonicalActions = canonicalActions,
    )

/** 顶层容器动作由真实任务能力生成，eligible keys 原样传给 ViewModel。 */
internal fun downloadContainerRowActionSpecs(
    container: DownloadHierarchyContainer,
    pending: Boolean,
    deleteLabelRes: Int = CoreR.string.download_action_delete,
): List<DownloadRowActionSpec> {
    if (pending || container.allEntries().any { it.status == DownloadTaskStatus.DELETING }) {
        return emptyList()
    }
    return buildList {
        container.taskActionSpecs().forEach { taskSpec ->
            val action =
                when (taskSpec.action) {
                    DownloadContainerTaskAction.PAUSE -> DownloadRowTaskAction.PAUSE
                    DownloadContainerTaskAction.RESUME -> DownloadRowTaskAction.RESUME
                    DownloadContainerTaskAction.RETRY -> DownloadRowTaskAction.RETRY
                }
            add(
                DownloadRowActionSpec(
                    action = action,
                    labelRes =
                        when (action) {
                            DownloadRowTaskAction.PAUSE -> CoreR.string.download_action_pause
                            DownloadRowTaskAction.RESUME -> CoreR.string.download_action_resume
                            DownloadRowTaskAction.RETRY -> CoreR.string.download_action_retry
                            DownloadRowTaskAction.DELETE -> error("不支持的任务动作")
                        },
                    eligibleTaskKeys = taskSpec.eligibleTaskKeys,
                )
            )
        }
        if (container.canDelete) {
            add(
                DownloadRowActionSpec(
                    action = DownloadRowTaskAction.DELETE,
                    labelRes = deleteLabelRes,
                )
            )
        }
    }
}

/** 尾部动作在 UI 中映射到真实任务回调；删除由行级确认回调处理。 */
internal fun downloadActionForRowAction(
    task: DownloadTask,
    action: DownloadRowTaskAction,
): DownloadAction? =
    when (action) {
        DownloadRowTaskAction.PAUSE -> DownloadAction.Pause(task)
        DownloadRowTaskAction.RESUME -> DownloadAction.Resume(task)
        DownloadRowTaskAction.RETRY -> DownloadAction.Retry(task)
        DownloadRowTaskAction.DELETE -> null
    }

internal fun downloadActionForContainerRowAction(
    containerKey: String,
    spec: DownloadRowActionSpec,
): DownloadAction? =
    when (spec.action) {
        DownloadRowTaskAction.PAUSE ->
            DownloadAction.PauseContainer(containerKey, spec.eligibleTaskKeys)
        DownloadRowTaskAction.RESUME ->
            DownloadAction.ResumeContainer(containerKey, spec.eligibleTaskKeys)
        DownloadRowTaskAction.RETRY ->
            DownloadAction.RetryContainer(containerKey, spec.eligibleTaskKeys)
        DownloadRowTaskAction.DELETE -> null
    }

/** W7-T3：书籍行动作按条目 key 派发（书籍走独立 `bookDownloadTasks` 链路，没有 `DownloadTask`）。 */
internal fun bookDownloadActionForRowAction(
    key: String,
    action: DownloadRowTaskAction,
): DownloadAction? =
    when (action) {
        DownloadRowTaskAction.PAUSE -> DownloadAction.PauseBook(key)
        DownloadRowTaskAction.RESUME -> DownloadAction.ResumeBook(key)
        DownloadRowTaskAction.RETRY -> DownloadAction.RetryBook(key)
        DownloadRowTaskAction.DELETE -> null
    }

/**
 * 叶子条目内容区单击能力。
 *
 * W9-T5（F4）：**已完成音乐**直接播放本地文件（离线可播、不发 `Items`）；书籍 / 视频 / 其它状态保持「打开详情」不变。
 */
internal fun downloadLeafContentAction(
    selectionMode: Boolean,
    mediaKind: DownloadMediaKind,
    completed: Boolean,
): DownloadRowContentAction? =
    when {
        selectionMode -> DownloadRowContentAction.TOGGLE_SELECTION
        mediaKind == DownloadMediaKind.MUSIC && completed -> DownloadRowContentAction.PLAY
        mediaKind == DownloadMediaKind.BOOK || completed -> DownloadRowContentAction.OPEN_DETAILS
        else -> null
    }

internal fun downloadTopLevelContentAction(selectionMode: Boolean): DownloadRowContentAction =
    if (selectionMode) DownloadRowContentAction.TOGGLE_SELECTION
    else DownloadRowContentAction.OPEN_DETAILS

@Composable
private fun Modifier.downloadRowContentInteraction(
    contentAction: DownloadRowContentAction?,
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
): Modifier {
    val selectLabel = stringResource(CoreR.string.download_action_select)
    val latestOnLongPress by rememberUpdatedState(onLongPress)
    return if (contentAction != null) {
        val clickLabel = stringResource(contentAction.labelRes)
        combinedClickable(
            interactionSource = interactionSource,
            indication = null,
            onClickLabel = clickLabel,
            onLongClickLabel = selectLabel,
            onClick = onClick,
            onLongClick = onLongPress,
        )
    } else {
        pointerInput(interactionSource) {
                detectTapGestures(
                    onPress = { offset ->
                        val press = PressInteraction.Press(offset)
                        interactionSource.tryEmit(press)
                        var releasedNormally = false
                        try {
                            val released = tryAwaitRelease()
                            releasedNormally = true
                            interactionSource.tryEmit(
                                if (released) PressInteraction.Release(press)
                                else PressInteraction.Cancel(press)
                            )
                        } finally {
                            if (!releasedNormally) {
                                interactionSource.tryEmit(PressInteraction.Cancel(press))
                            }
                        }
                    },
                    onLongPress = { latestOnLongPress() },
                )
            }
            .focusable(interactionSource = interactionSource)
            .semantics {
                onLongClick(label = selectLabel) {
                    onLongPress()
                    true
                }
            }
    }
}

/**
 * W59 下载页排版规格（`DOWNLOAD_PLAN.md` §22）。
 *
 * - 顶层卡片（Show / 专辑）：海报 / 方图 96dp（手机）/ 104dp（平板），行高随图贴满；
 * - 平铺条目（电影 / 书籍）：电影 64×96dp、书籍 48×72dp；
 * - 钻取详情：头部海报 104×156dp（歌曲专辑 104 方图），季卡 72×108dp，剧集行 112×63dp（16:9）；
 * - 操作键统一右对齐（行尾图标 / 详情头部按钮排）。
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
    val DetailArtworkSize = 104.dp
    val RowPadding = 12.dp
    val DepthIndent = 12.dp
}

/**
 * 顶层卡片（Show / 音乐专辑）：聚合进度 + 速度，点击进入钻取详情。
 *
 * 书籍 / 电影不进入这里——它们是平铺单条目行（[DownloadTopLevelRow]）。
 */
@Composable
internal fun DownloadTopLevelCard(
    container: DownloadHierarchyContainer,
    compact: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongPress: () -> Unit = onClick,
    onAction: (DownloadAction) -> Unit,
    onRequestDelete: () -> Unit,
    pendingAction: DownloadPendingAction? = null,
    deletionProjection: DownloadDeletionProjection =
        DownloadDeletionProjection(pending = false, deleting = false, failedCount = 0),
    deletionSummary: DownloadDeletionSummary? = null,
) {
    val colors = LocalCinefinColors.current
    val contentInteractionSource = remember { MutableInteractionSource() }
    val effectivePendingAction =
        pendingAction ?: DownloadPendingAction.DELETE.takeIf { deletionProjection.deleting }
    val hasDeletionFailure =
        deletionProjection.failedCount > 0 || (deletionSummary?.failedCount ?: 0) > 0
    val deleteLabelRes =
        if (hasDeletionFailure) CoreR.string.download_action_retry_delete
        else CoreR.string.download_action_delete
    val actionSpecs =
        downloadContainerRowActionSpecs(
            container = container,
            pending = effectivePendingAction != null || deletionProjection.pending,
            deleteLabelRes = deleteLabelRes,
        )
    val deletionText = deletionStatusText(deletionProjection, deletionSummary)
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
        onClick = null,
        onLongClick = null,
        selected = selected,
        contentPadding = PaddingValues(0.dp),
        interactionSource = contentInteractionSource,
    ) {
        Row {
            Row(
                modifier =
                    Modifier.weight(1f)
                        .downloadRowContentInteraction(
                            contentAction = downloadTopLevelContentAction(selectionMode),
                            interactionSource = contentInteractionSource,
                            onClick = onClick,
                            onLongPress = onLongPress,
                        ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
                        if (selectionMode) {
                            CinefinSelectIndicator(selected = selected)
                        } else {
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_arrow_right),
                                contentDescription =
                                    stringResource(CoreR.string.download_detail_open),
                                tint = colors.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(CinefinSpacing.Space1))
                    Text(
                        text =
                            (deletionText ?: hierarchyStatusLabel(container.status)) +
                                " · " +
                                containerCountText(container),
                        style = CinefinType.BodySmall,
                        color =
                            if (
                                hasDeletionFailure ||
                                    container.status == DownloadHierarchyStatus.FAILED
                            ) {
                                colors.error
                            } else colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(CinefinSpacing.Space1))
                    Text(
                        text =
                            DownloadFormatRules.formatSizePair(
                                container.downloadedBytes,
                                container.totalBytes,
                            ),
                        style = CinefinType.BodySmall,
                        color = colors.onSurfaceFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(CinefinSpacing.Space2))
                    DownloadProgressBar(
                        progress = container.byteProgress,
                        indeterminate =
                            container.status == DownloadHierarchyStatus.RUNNING &&
                                container.totalBytes <= 0L,
                    )
                    val speedLine = containerSpeedLine(container)
                    if (speedLine.isNotEmpty()) {
                        Spacer(Modifier.height(CinefinSpacing.Space1))
                        Text(
                            text = speedLine,
                            style = CinefinType.LabelSmall,
                            color = colors.onSurfaceFaint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (!selectionMode) {
                Row(
                    modifier = Modifier.padding(end = CinefinSpacing.Space2),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space1),
                ) {
                    DownloadContainerActionIcon(
                        containerKey = container.key,
                        actionSpecs = actionSpecs,
                        pendingAction = effectivePendingAction,
                        onAction = onAction,
                        onRequestDelete = onRequestDelete,
                    )
                }
            }
        }
    }
}

/** 平铺单条目行（电影 / 书籍）：左图固定比例 + 标题 / 状态行 + 右侧操作键（W59 用户口径）。 */
@Composable
internal fun DownloadTopLevelRow(
    container: DownloadHierarchyContainer,
    selectionMode: Boolean,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongPress: () -> Unit = onClick,
    onAction: (DownloadAction) -> Unit,
    onRequestDelete: (key: String, title: String) -> Unit,
    pendingAction: DownloadPendingAction? = null,
    deletionProjection: DownloadDeletionProjection =
        DownloadDeletionProjection(pending = false, deleting = false, failedCount = 0),
    deletionSummary: DownloadDeletionSummary? = null,
) {
    val colors = LocalCinefinColors.current
    val entry = container.allEntries().firstOrNull() ?: return
    val task = entry.task
    val completed = entry.status == DownloadTaskStatus.COMPLETED
    val failureDetail = visibleFailureDetail(task, entry.bookDetail)
    val explanationText = taskExplanationText(task, failureDetail)
    var showTechnicalDetails by remember(task?.sourceId) { mutableStateOf(false) }
    val (artworkWidth, artworkHeight) = leafArtworkSize(entry)
    val contentInteractionSource = remember { MutableInteractionSource() }
    val effectivePendingAction =
        pendingAction ?: DownloadPendingAction.DELETE.takeIf { deletionProjection.deleting }
    val deletionText = deletionStatusText(deletionProjection, deletionSummary)
    val hasDeletionFailure =
        deletionProjection.failedCount > 0 || (deletionSummary?.failedCount ?: 0) > 0
    val deleteLabelRes =
        if (hasDeletionFailure) CoreR.string.download_action_retry_delete
        else CoreR.string.download_action_delete

    // W59：书籍行可见时懒生成一次封面（已下载书籍走本地文件；生成完成由 ViewModel 刷新出图）。
    LaunchedEffect(entry.itemId, entry.mediaKind) {
        if (entry.mediaKind == DownloadMediaKind.BOOK && entry.imageUri == null) {
            onAction(DownloadAction.EnsureBookCover(entry.itemId))
        }
    }

    CinefinCard(
        modifier = modifier,
        onClick = null,
        onLongClick = null,
        selected = selected,
        contentPadding = PaddingValues(0.dp),
        interactionSource = contentInteractionSource,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier =
                    Modifier.weight(1f)
                        .downloadRowContentInteraction(
                            contentAction = downloadTopLevelContentAction(selectionMode),
                            interactionSource = contentInteractionSource,
                            onClick = onClick,
                            onLongPress = onLongPress,
                        ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selectionMode) {
                    CinefinSelectIndicator(
                        selected = selected,
                        modifier = Modifier.padding(start = CinefinSpacing.Space3),
                    )
                }
                DownloadArtwork(
                    model = entry.imageUri,
                    kind = entry.mediaKind,
                    modifier =
                        Modifier.padding(start = if (selectionMode) 0.dp else CinefinSpacing.Space3)
                            .width(artworkWidth)
                            .height(artworkHeight),
                    shape =
                        if (entry.mediaKind == DownloadMediaKind.MUSIC) CinefinShapes.Xs
                        else CinefinShapes.Poster,
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
                        // W7-T4（需求 45）：书籍格式徽标（EPUB / PDF / M4B…），扩展名未知时不显示。
                        container.formatLabel?.let { badge ->
                            Spacer(Modifier.width(CinefinSpacing.Space2))
                            Text(
                                text = badge,
                                style = CinefinType.LabelSmall,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                                modifier =
                                    Modifier.clip(CinefinShapes.Xs)
                                        .background(colors.surfaceContainerHigh)
                                        .padding(horizontal = 6.dp, vertical = 1.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(CinefinSpacing.Space1))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DownloadStatusBadge(
                            status = entry.status,
                            failureReason = task?.failureReason,
                            retrying = showsRetryLabel(task),
                            retryCount = task?.retryCount ?: 0,
                            phase =
                                task?.let {
                                    DownloadControlPlaneRules.phase(
                                        status = it.status,
                                        downloadedBytes = it.downloadedBytes,
                                        totalBytes = it.totalBytes,
                                        failureReason = it.failureReason,
                                        nextRetryAt = it.nextRetryAt,
                                        nowMs = System.currentTimeMillis(),
                                        intentPhase = it.intentPhase,
                                    )
                                },
                            // W80b（D3）：书籍下载失败带服务端原始原因（`BookDownloadTask.error`）。
                            failureDetail = failureDetail,
                            deletionProjection = deletionProjection,
                            deletionText = deletionText,
                        )
                        Spacer(Modifier.width(CinefinSpacing.Space2))
                        Text(
                            text = explanationText ?: leafSizeLine(entry),
                            style = CinefinType.BodySmall,
                            color = colors.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier =
                                Modifier.weight(1f).clickable(enabled = explanationText != null) {
                                    showTechnicalDetails = true
                                },
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
                    val speedLine = leafSpeedLine(task)
                    if (speedLine.isNotEmpty()) {
                        Spacer(Modifier.height(CinefinSpacing.Space1))
                        Text(
                            text = speedLine,
                            style = CinefinType.LabelSmall,
                            color = colors.onSurfaceFaint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (!selectionMode) {
                Row(
                    modifier = Modifier.padding(end = CinefinSpacing.Space2),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space1),
                ) {
                    if (completed) {
                        CinefinSwitch(
                            checked = entry.allowOffline,
                            onCheckedChange = { onAction(DownloadAction.ToggleOffline(entry.key)) },
                        )
                    }
                    DownloadTaskActionIcon(
                        task = task,
                        pendingAction = effectivePendingAction,
                        onAction = onAction,
                        onRequestDelete = { onRequestDelete(entry.key, entry.name) },
                        canDelete = entry.canDelete,
                        deleteLabelRes = deleteLabelRes,
                        canonicalActions = entry.canonicalActions,
                        bookStatus =
                            entry.status.takeIf { entry.mediaKind == DownloadMediaKind.BOOK },
                        bookEntryKey = entry.key,
                    )
                }
            }
        }
    }
    if (showTechnicalDetails && task != null) {
        DownloadTechnicalDetailsDialog(
            task = task,
            failureDetail = failureDetail,
            onDismiss = { showTechnicalDetails = false },
        )
    }
}

/** W7-T4：排序菜单文案（DEFAULT 走「排序」总标签，作为未选排序时的触发器文字）。 */
internal fun DownloadSortMode.menuLabelRes(): Int =
    when (this) {
        DownloadSortMode.DEFAULT -> CoreR.string.download_sort_default
        DownloadSortMode.NAME -> CoreR.string.download_sort_name
        DownloadSortMode.DOWNLOAD_TIME -> CoreR.string.download_sort_time
        DownloadSortMode.SIZE -> CoreR.string.download_sort_size
    }

/**
 * W7-T4（需求 51）：页签行右侧的排序文字菜单。
 *
 * 没有现成 `CinefinOverflowMenu` 组件，按设计口径用文字菜单：触发器显示当前排序（未选 = 「排序」）， 菜单四档 = 默认（现有分组排序）/ 名称 / 下载时间 /
 * 大小；作用范围由调用方限定为当前页签 + 当前筛选的同级节点。
 */
@Composable
internal fun DownloadSortMenu(
    selected: DownloadSortMode,
    onSelect: (DownloadSortMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCinefinColors.current
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        CinefinButton(
            text =
                stringResource(
                    if (selected == DownloadSortMode.DEFAULT) {
                        CoreR.string.download_sort
                    } else {
                        selected.menuLabelRes()
                    }
                ),
            onClick = { expanded = true },
            variant = CinefinButtonVariant.Text,
            size = CinefinButtonSize.Small,
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = CinefinShapes.Card,
            containerColor = colors.surfaceContainerHighest,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            DownloadSortMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(mode.menuLabelRes()),
                            style = CinefinType.BodyMedium,
                            color =
                                if (mode == selected) colors.onSurface else colors.onSurfaceVariant,
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(mode)
                    },
                    trailingIcon =
                        if (mode == selected) {
                            {
                                Icon(
                                    painter = painterResource(CoreR.drawable.ic_check),
                                    contentDescription = null,
                                    tint = colors.onSurface,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        } else {
                            null
                        },
                )
            }
        }
    }
}

/** W7-T4（需求 44）：书籍区类型分区标题（电子书 / 有声书 / 漫画），可折叠。 */
@Composable
internal fun DownloadBookSectionHeader(
    section: DownloadBookSection,
    count: Int,
    collapsed: Boolean,
    onToggle: () -> Unit,
) {
    val colors = LocalCinefinColors.current
    val label = stringResource(section.labelRes())
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clip(CinefinShapes.Xs)
                .clickable(onClick = onToggle)
                .padding(vertical = CinefinSpacing.Space1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = CinefinType.TitleSmall,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(CinefinSpacing.Space2))
        Text(
            text = count.toString(),
            style = CinefinType.BodySmall,
            color = colors.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        Icon(
            painter =
                painterResource(
                    if (collapsed) CoreR.drawable.ic_chevron_down else CoreR.drawable.ic_chevron_up
                ),
            contentDescription = label,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

private fun DownloadBookSection.labelRes(): Int =
    when (this) {
        DownloadBookSection.EBOOK -> CoreR.string.download_book_section_ebook
        DownloadBookSection.AUDIOBOOK -> CoreR.string.download_book_section_audiobook
        DownloadBookSection.COMIC -> CoreR.string.download_book_section_comic
    }

/** 行尾任务操作键（暂停 / 继续 / 重试 + 删除），统一右对齐。 */
@Composable
private fun DownloadTaskActionIcon(
    task: DownloadTask?,
    pendingAction: DownloadPendingAction?,
    onAction: (DownloadAction) -> Unit,
    onRequestDelete: () -> Unit,
    canDelete: Boolean,
    deleteLabelRes: Int = CoreR.string.download_action_delete,
    canonicalActions: Set<CanonicalDownloadAction>? = null,
    /** 书籍条目没有 `DownloadTask`：用任务态 + 条目 key 生成动作（W7-T3）。 */
    bookStatus: DownloadTaskStatus? = null,
    bookEntryKey: String? = null,
) {
    val actionSpecs =
        when {
            task != null ->
                downloadRowActionSpecs(
                    task = task,
                    canDelete = canDelete,
                    pending = pendingAction != null,
                    deleteLabelRes = deleteLabelRes,
                    canonicalActions = canonicalActions,
                )
            bookStatus != null ->
                downloadRowActionSpecs(
                    status = bookStatus,
                    canDelete = canDelete,
                    pending = pendingAction != null,
                    deleteLabelRes = deleteLabelRes,
                    canonicalActions = canonicalActions,
                )
            else ->
                downloadRowActionSpecs(
                    status = null,
                    canDelete = canDelete,
                    pending = pendingAction != null,
                    deleteLabelRes = deleteLabelRes,
                    canonicalActions = canonicalActions,
                )
        }
    DownloadRowActionIcon(
        actionSpecs = actionSpecs,
        pendingAction = pendingAction,
    ) { spec ->
        val action =
            when {
                task != null -> downloadActionForRowAction(task, spec.action)
                bookStatus != null && bookEntryKey != null ->
                    bookDownloadActionForRowAction(bookEntryKey, spec.action)
                else -> null
            }
        if (action != null) onAction(action)
        else if (spec.action == DownloadRowTaskAction.DELETE) onRequestDelete()
    }
}

@Composable
private fun DownloadRowActionIcon(
    actionSpecs: List<DownloadRowActionSpec>,
    pendingAction: DownloadPendingAction?,
    onAction: (DownloadRowActionSpec) -> Unit,
) {
    if (actionSpecs.isEmpty() && pendingAction != null) {
        val colors = LocalCinefinColors.current
        Box(
            modifier =
                Modifier.width(PendingDownloadActionMetrics.Width)
                    .height(PendingDownloadActionMetrics.Height),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = colors.onSurfaceFaint.copy(alpha = colors.disabledAlpha),
            )
        }
        return
    }
    actionSpecs.forEach { spec ->
        CinefinIconButton(onClick = { onAction(spec) }) { tint ->
            Icon(
                painter =
                    painterResource(
                        when (spec.action) {
                            DownloadRowTaskAction.PAUSE -> CoreR.drawable.ic_pause
                            DownloadRowTaskAction.RESUME -> CoreR.drawable.ic_play
                            DownloadRowTaskAction.RETRY -> CoreR.drawable.ic_rotate_ccw
                            DownloadRowTaskAction.DELETE -> CoreR.drawable.ic_trash
                        }
                    ),
                contentDescription = stringResource(spec.labelRes),
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun DownloadContainerActionIcon(
    containerKey: String,
    actionSpecs: List<DownloadRowActionSpec>,
    pendingAction: DownloadPendingAction?,
    onAction: (DownloadAction) -> Unit,
    onRequestDelete: () -> Unit,
) {
    DownloadRowActionIcon(
        actionSpecs = actionSpecs,
        pendingAction = pendingAction,
    ) { spec ->
        val action = downloadActionForContainerRowAction(containerKey, spec)
        when {
            action != null -> onAction(action)
            spec.action == DownloadRowTaskAction.DELETE -> onRequestDelete()
        }
    }
}

/** 钻取详情头部：海报 + 必要信息（标题 / 状态 / 总进度 x/y + 体积 + 速度剩余）+ 操作键（全部暂停 / 全部继续 / 删除）。 */
@Composable
internal fun DownloadDrilldownHeader(
    container: DownloadHierarchyContainer,
    modifier: Modifier = Modifier,
    onPauseAll: (Set<String>) -> Unit,
    onResumeAll: (Set<String>) -> Unit,
    onRequestDelete: () -> Unit,
    deletionProjection: DownloadDeletionProjection =
        DownloadDeletionProjection(pending = false, deleting = false, failedCount = 0),
    deletionSummary: DownloadDeletionSummary? = null,
) {
    val colors = LocalCinefinColors.current
    val actionsEnabled = !deletionProjection.pending && !deletionProjection.deleting
    val actionSpecs = if (actionsEnabled) container.taskActionSpecs() else emptyList()
    val pauseSpec = actionSpecs.firstOrNull { it.action == DownloadContainerTaskAction.PAUSE }
    val resumeSpec = actionSpecs.firstOrNull { it.action == DownloadContainerTaskAction.RESUME }
    val deletionText = deletionStatusText(deletionProjection, deletionSummary)
    val hasDeletionFailure =
        deletionProjection.failedCount > 0 || (deletionSummary?.failedCount ?: 0) > 0
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DownloadArtwork(
                model = container.imageUri,
                kind = container.mediaKind,
                modifier =
                    Modifier.width(DownloadListMetrics.DetailArtworkSize)
                        .height(
                            if (container.mediaKind == DownloadMediaKind.MUSIC)
                                DownloadListMetrics.DetailArtworkSize
                            else DownloadListMetrics.PosterHeightExpanded
                        ),
            )
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = DownloadListMetrics.RowPadding)
            ) {
                Text(
                    text = container.title,
                    style = CinefinType.TitleMedium,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(CinefinSpacing.Space1))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text =
                            (deletionText ?: hierarchyStatusLabel(container.status)) +
                                " · " +
                                containerCountText(container),
                        style = CinefinType.BodySmall,
                        color =
                            if (
                                hasDeletionFailure ||
                                    container.status == DownloadHierarchyStatus.FAILED
                            ) {
                                colors.error
                            } else colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(CinefinSpacing.Space1))
                Text(
                    text =
                        DownloadFormatRules.formatSizePair(
                            container.downloadedBytes,
                            container.totalBytes,
                        ),
                    style = CinefinType.BodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(CinefinSpacing.Space2))
                DownloadProgressBar(
                    progress = container.byteProgress,
                    indeterminate =
                        container.status == DownloadHierarchyStatus.RUNNING &&
                            container.totalBytes <= 0L,
                )
                val speedLine = containerSpeedLine(container)
                if (speedLine.isNotEmpty()) {
                    Spacer(Modifier.height(CinefinSpacing.Space1))
                    Text(
                        text = speedLine,
                        style = CinefinType.LabelSmall,
                        color = colors.onSurfaceFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.height(CinefinSpacing.Space3))
        Row(horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2)) {
            CinefinButton(
                text = stringResource(CoreR.string.download_detail_pause_all),
                onClick = { pauseSpec?.let { onPauseAll(it.eligibleTaskKeys) } },
                variant = CinefinButtonVariant.Outlined,
                size = CinefinButtonSize.Small,
                enabled = actionsEnabled && pauseSpec != null,
            )
            CinefinButton(
                text = stringResource(CoreR.string.download_detail_resume_all),
                onClick = { resumeSpec?.let { onResumeAll(it.eligibleTaskKeys) } },
                variant = CinefinButtonVariant.Outlined,
                size = CinefinButtonSize.Small,
                enabled = actionsEnabled && resumeSpec != null,
            )
            CinefinButton(
                text =
                    stringResource(
                        if (hasDeletionFailure) {
                            CoreR.string.download_action_retry_delete
                        } else {
                            CoreR.string.download_action_delete
                        }
                    ),
                onClick = onRequestDelete,
                variant = CinefinButtonVariant.Text,
                size = CinefinButtonSize.Small,
                enabled = actionsEnabled,
            )
        }
    }
}

/** 季卡（钻取详情第二级）：72×108dp 海报 + 聚合口径；点击展开 / 折叠该季剧集。 */
@Composable
internal fun DownloadSeasonCard(
    season: DownloadHierarchySubContainer,
    expanded: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongPress: () -> Unit = onClick,
    deletionProjection: DownloadDeletionProjection =
        DownloadDeletionProjection(pending = false, deleting = false, failedCount = 0),
    deletionSummary: DownloadDeletionSummary? = null,
) {
    val colors = LocalCinefinColors.current
    val deletionText = deletionStatusText(deletionProjection, deletionSummary)
    val contentEnabled = !deletionProjection.pending && !deletionProjection.deleting
    CinefinCard(
        modifier = modifier,
        onClick = onClick.takeIf { contentEnabled },
        onLongClick = onLongPress.takeIf { contentEnabled },
        selected = selected,
        contentPadding = PaddingValues(0.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selectionMode) {
                CinefinSelectIndicator(
                    selected = selected,
                    modifier = Modifier.padding(start = CinefinSpacing.Space3),
                )
            }
            DownloadArtwork(
                model = season.imageUri,
                kind = DownloadMediaKind.VIDEO,
                modifier =
                    Modifier.padding(start = if (selectionMode) 0.dp else CinefinSpacing.Space3)
                        .width(DownloadListMetrics.SeasonPosterWidth)
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
                        text = season.title,
                        style = CinefinType.TitleSmall,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(CinefinSpacing.Space2))
                    if (!selectionMode) {
                        Icon(
                            painter =
                                painterResource(
                                    if (expanded) CoreR.drawable.ic_chevron_up
                                    else CoreR.drawable.ic_chevron_down
                                ),
                            contentDescription =
                                stringResource(
                                    if (expanded) CoreR.string.nav_collapse
                                    else CoreR.string.nav_expand
                                ),
                            tint = colors.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                (deletionText ?: season.detail)?.let { detail ->
                    Spacer(Modifier.height(CinefinSpacing.Space1))
                    Text(
                        text = detail,
                        style = CinefinType.BodySmall,
                        color =
                            if (
                                deletionProjection.failedCount > 0 ||
                                    season.status == DownloadHierarchyStatus.FAILED
                            ) {
                                colors.error
                            } else colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(CinefinSpacing.Space1))
                Text(
                    text =
                        DownloadFormatRules.formatSizePair(
                            season.downloadedBytes,
                            season.totalBytes,
                        ),
                    style = CinefinType.BodySmall,
                    color = colors.onSurfaceFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(CinefinSpacing.Space1))
                DownloadProgressBar(
                    progress = season.byteProgress,
                    indeterminate =
                        season.status == DownloadHierarchyStatus.RUNNING && season.totalBytes <= 0L,
                )
                val speedLine = subContainerSpeedLine(season)
                if (speedLine.isNotEmpty()) {
                    Spacer(Modifier.height(CinefinSpacing.Space1))
                    Text(
                        text = speedLine,
                        style = CinefinType.LabelSmall,
                        color = colors.onSurfaceFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 详情条目行（剧集 / 曲目）：缩略图 + 标题 / 大小 / 状态徽标 / 进度 + 行尾操作键。 */
@Composable
internal fun DownloadLeafCard(
    entry: DownloadHierarchyEntry,
    depth: Int = 0,
    selectionMode: Boolean,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onToggleSelection: () -> Unit,
    onLongPress: () -> Unit = onToggleSelection,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onAction: (DownloadAction) -> Unit,
    onRequestDelete: (key: String, title: String) -> Unit,
    pendingAction: DownloadPendingAction? = null,
    deletionProjection: DownloadDeletionProjection =
        DownloadDeletionProjection(pending = false, deleting = false, failedCount = 0),
) {
    val colors = LocalCinefinColors.current
    val task = entry.task
    val completed = entry.status == DownloadTaskStatus.COMPLETED
    val (artworkWidth, artworkHeight) = leafArtworkSize(entry)
    val contentAction =
        downloadLeafContentAction(
            selectionMode = selectionMode,
            mediaKind = entry.mediaKind,
            completed = completed,
        )
    val contentInteractionSource = remember { MutableInteractionSource() }
    val effectivePendingAction =
        pendingAction ?: DownloadPendingAction.DELETE.takeIf { deletionProjection.deleting }
    val deleteLabelRes =
        if (deletionProjection.failedCount > 0) CoreR.string.download_action_retry_delete
        else CoreR.string.download_action_delete

    CinefinCard(
        modifier = modifier,
        onClick = null,
        onLongClick = null,
        selected = selected,
        contentPadding = PaddingValues(0.dp),
        interactionSource = contentInteractionSource,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier =
                    Modifier.weight(1f)
                        .downloadRowContentInteraction(
                            contentAction = contentAction,
                            interactionSource = contentInteractionSource,
                            onClick = {
                                when (contentAction) {
                                    DownloadRowContentAction.OPEN_DETAILS -> onOpen()
                                    DownloadRowContentAction.PLAY -> onPlay()
                                    DownloadRowContentAction.TOGGLE_SELECTION -> onToggleSelection()
                                    null -> Unit
                                }
                            },
                            onLongPress = onLongPress,
                        ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selectionMode) {
                    CinefinSelectIndicator(
                        selected = selected,
                        modifier = Modifier.padding(start = CinefinSpacing.Space3),
                    )
                }
                Spacer(Modifier.width(DownloadListMetrics.DepthIndent * depth))
                DownloadArtwork(
                    model = entry.imageUri,
                    kind = entry.mediaKind,
                    modifier =
                        Modifier.padding(start = if (selectionMode) 0.dp else CinefinSpacing.Space3)
                            .width(artworkWidth)
                            .height(artworkHeight),
                    shape =
                        if (entry.mediaKind == DownloadMediaKind.MUSIC) CinefinShapes.Xs
                        else CinefinShapes.Poster,
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
                    Text(
                        text = leafSizeLine(entry),
                        style = CinefinType.BodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (deletionProjection.deleting || deletionProjection.failedCount > 0) {
                        Spacer(Modifier.height(CinefinSpacing.Space1))
                        DownloadStatusBadge(
                            status = entry.status,
                            failureReason = task?.failureReason,
                            retrying = showsRetryLabel(task),
                            retryCount = task?.retryCount ?: 0,
                            phase =
                                task?.let {
                                    DownloadControlPlaneRules.phase(
                                        status = it.status,
                                        downloadedBytes = it.downloadedBytes,
                                        totalBytes = it.totalBytes,
                                        failureReason = it.failureReason,
                                        nextRetryAt = it.nextRetryAt,
                                        nowMs = System.currentTimeMillis(),
                                        intentPhase = it.intentPhase,
                                    )
                                },
                            failureDetail = visibleFailureDetail(task, entry.bookDetail),
                            deletionProjection = deletionProjection,
                            deletionText = deletionStatusText(deletionProjection, null),
                        )
                    }
                    DownloadTaskExplanationLine(
                        task = task,
                        failureDetail = visibleFailureDetail(task, entry.bookDetail),
                    )
                    if (task != null && task.isActive) {
                        Spacer(Modifier.height(CinefinSpacing.Space2))
                        DownloadProgressBar(
                            progress = task.progress,
                            indeterminate =
                                task.status == DownloadTaskStatus.RUNNING && task.totalBytes <= 0L,
                        )
                    }
                    val speedLine = leafSpeedLine(task)
                    if (speedLine.isNotEmpty()) {
                        Spacer(Modifier.height(CinefinSpacing.Space1))
                        Text(
                            text = speedLine,
                            style = CinefinType.LabelSmall,
                            color = colors.onSurfaceFaint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (!selectionMode) {
                Row(
                    modifier = Modifier.padding(end = CinefinSpacing.Space2),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space1),
                ) {
                    if (completed) {
                        CinefinSwitch(
                            checked = entry.allowOffline,
                            onCheckedChange = { onAction(DownloadAction.ToggleOffline(entry.key)) },
                        )
                    }
                    DownloadTaskActionIcon(
                        task = task,
                        pendingAction = effectivePendingAction,
                        onAction = onAction,
                        onRequestDelete = { onRequestDelete(entry.key, entry.name) },
                        canDelete = entry.canDelete,
                        deleteLabelRes = deleteLabelRes,
                        canonicalActions = entry.canonicalActions,
                        bookStatus =
                            entry.status.takeIf { entry.mediaKind == DownloadMediaKind.BOOK },
                        bookEntryKey = entry.key,
                    )
                }
            }
        }
    }
}

/** 下载页状态徽标（旧 Findroid 文本行 → App 主题 / Lumen 中性徽标 + 12dp 前置图标）。 */
@Composable
private fun DownloadStatusBadge(
    status: DownloadTaskStatus,
    failureReason: DownloadFailureReason?,
    retrying: Boolean = false,
    retryCount: Int = 0,
    phase: DownloadTaskPhase? = null,
    /**
     * 失败条目的**真实原因**（W80b / D3）。视频任务只有分类枚举（[failureReason]）， 书籍下载带的是服务端原始文案（如「下载书籍失败：HTTP
     * 401…」）；有真实文案时优先显示它， 否则才回落「未知错误」占位。
     */
    failureDetail: String? = null,
    deletionProjection: DownloadDeletionProjection =
        DownloadDeletionProjection(pending = false, deleting = false, failedCount = 0),
    deletionText: String? = null,
) {
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    val displayStatus =
        when {
            deletionProjection.deleting -> DownloadTaskStatus.DELETING
            deletionProjection.failedCount > 0 -> DownloadTaskStatus.FAILED
            else -> status
        }
    val running = displayStatus == DownloadTaskStatus.RUNNING
    val failed = displayStatus == DownloadTaskStatus.FAILED
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
        when (displayStatus) {
            DownloadTaskStatus.PENDING,
            DownloadTaskStatus.RUNNING -> CoreR.drawable.ic_download
            DownloadTaskStatus.PAUSED -> CoreR.drawable.ic_pause
            DownloadTaskStatus.COMPLETED -> CoreR.drawable.ic_check
            DownloadTaskStatus.FAILED -> CoreR.drawable.ic_alert_circle
            DownloadTaskStatus.DELETING -> CoreR.drawable.ic_trash
        }
    val intentPhaseLabel = phase?.let(::downloadIntentPhaseLabel)
    val label =
        when {
            deletionProjection.deleting -> stringResource(CoreR.string.download_deleting)
            deletionProjection.failedCount > 0 ->
                deletionText ?: stringResource(CoreR.string.download_delete_failed)
            showsCompletedDownloadLabel(displayStatus) ->
                stringResource(CoreR.string.download_tasks_completed)
            // W60b：自动重试（等待下一次 / 正在重试）优先显示「重试中 · 第 N 次」。
            retrying && retryCount > 0 ->
                stringResource(CoreR.string.download_retry_in_progress, retryCount)
            intentPhaseLabel != null -> stringResource(intentPhaseLabel)
            phase == DownloadTaskPhase.PREPARING -> stringResource(CoreR.string.download_preparing)
            phase == DownloadTaskPhase.CONNECTING ->
                stringResource(CoreR.string.download_connecting)
            phase == DownloadTaskPhase.DOWNLOADING ->
                stringResource(CoreR.string.download_downloading)
            phase == DownloadTaskPhase.VERIFYING -> stringResource(CoreR.string.download_verifying)
            phase == DownloadTaskPhase.WAITING_RETRY ->
                if (failureReason == DownloadFailureReason.NETWORK_UNAVAILABLE) {
                    stringResource(CoreR.string.download_waiting_network)
                } else {
                    stringResource(CoreR.string.download_waiting_retry)
                }
            phase == DownloadTaskPhase.PAUSED ->
                if (failureReason == DownloadFailureReason.NETWORK_UNAVAILABLE) {
                    stringResource(CoreR.string.download_waiting_network)
                } else {
                    stringResource(CoreR.string.download_paused)
                }
            phase == DownloadTaskPhase.COMPLETED ->
                stringResource(CoreR.string.download_tasks_completed)
            phase == DownloadTaskPhase.DELETING -> stringResource(CoreR.string.download_deleting)
            displayStatus == DownloadTaskStatus.PENDING ->
                stringResource(CoreR.string.download_pending)
            displayStatus == DownloadTaskStatus.RUNNING ->
                stringResource(CoreR.string.download_downloading)
            else -> failureDetail?.takeIf { it.isNotBlank() } ?: failureLabel(failureReason)
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
        Text(
            text = label,
            style = CinefinType.LabelSmall,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal fun downloadIntentPhaseLabel(phase: DownloadTaskPhase): Int? =
    when (phase) {
        DownloadTaskPhase.QUEUED -> CoreR.string.download_intent_queued
        DownloadTaskPhase.RESOLVING -> CoreR.string.download_intent_resolving
        DownloadTaskPhase.SOURCE_PENDING -> CoreR.string.download_intent_source_pending
        DownloadTaskPhase.DELETING -> CoreR.string.download_deleting
        else -> null
    }

/** W84：等待 / 失败原因与重试时间常显；技术字段按需展开。 */
@Composable
private fun DownloadTaskExplanationLine(
    task: DownloadTask?,
    failureDetail: String?,
) {
    task ?: return
    val explanation = DownloadControlPlaneRules.explanation(task, System.currentTimeMillis())
    explanation ?: return
    val colors = LocalCinefinColors.current
    var expanded by remember(task.sourceId) { mutableStateOf(false) }
    val reason = failureDetail?.takeIf { it.isNotBlank() } ?: failureLabel(explanation.reason)
    val retryAt =
        explanation.nextRetryAt
            .takeIf { it > System.currentTimeMillis() }
            ?.let { timestamp ->
                remember(timestamp) {
                    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
                }
            }
    val retrySuffix =
        retryAt?.let { stringResource(CoreR.string.download_next_retry_at, it) }.orEmpty()
    Spacer(Modifier.height(CinefinSpacing.Space1))
    Text(
        text = reason + retrySuffix,
        style = CinefinType.LabelSmall,
        color = colors.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    Text(
        text =
            stringResource(
                if (expanded) CoreR.string.download_hide_technical_details
                else CoreR.string.download_show_technical_details
            ),
        style = CinefinType.LabelSmall,
        color = LocalMediaColors.current.bright,
        modifier = Modifier.clickable { expanded = !expanded },
    )
    if (expanded) {
        Text(
            text =
                stringResource(
                    CoreR.string.download_technical_details,
                    explanation.reason?.name ?: "UNKNOWN",
                    explanation.retryCount,
                    task.sourceId,
                ),
            style = CinefinType.LabelSmall,
            color = colors.onSurfaceFaint,
        )
    }
}

@Composable
private fun taskExplanationText(
    task: DownloadTask?,
    failureDetail: String?,
): String? {
    task ?: return null
    val explanation = DownloadControlPlaneRules.explanation(task, System.currentTimeMillis())
    explanation ?: return null
    val reason = failureDetail?.takeIf { it.isNotBlank() } ?: failureLabel(explanation.reason)
    val retryAt =
        explanation.nextRetryAt
            .takeIf { it > System.currentTimeMillis() }
            ?.let { timestamp ->
                SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
            }
    return reason +
        retryAt?.let { stringResource(CoreR.string.download_next_retry_at, it) }.orEmpty()
}

@Composable
private fun DownloadTechnicalDetailsDialog(
    task: DownloadTask,
    failureDetail: String?,
    onDismiss: () -> Unit,
) {
    val colors = LocalCinefinColors.current
    val retryAt =
        task.nextRetryAt
            .takeIf { it > 0L }
            ?.let { timestamp ->
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
            } ?: stringResource(CoreR.string.none)
    val reason = failureDetail?.takeIf { it.isNotBlank() } ?: failureLabel(task.failureReason)
    Dialog(onDismissRequest = onDismiss) {
        CinefinCard(contentPadding = PaddingValues(CinefinSpacing.Space4)) {
            Column(verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2)) {
                Text(
                    text = stringResource(CoreR.string.download_technical_details_title),
                    style = CinefinType.TitleMedium,
                    color = colors.onSurface,
                )
                Text(
                    text = reason,
                    style = CinefinType.BodyMedium,
                    color = colors.onSurfaceVariant,
                )
                Text(
                    text =
                        stringResource(
                            CoreR.string.download_technical_details,
                            task.failureReason?.name ?: "UNKNOWN",
                            task.retryCount,
                            task.sourceId,
                        ) + stringResource(CoreR.string.download_next_retry_value, retryAt),
                    style = CinefinType.BodySmall,
                    color = colors.onSurfaceFaint,
                )
                CinefinButton(
                    text = stringResource(CoreR.string.close),
                    onClick = onDismiss,
                    variant = CinefinButtonVariant.Text,
                )
            }
        }
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
    shape: Shape = CinefinShapes.Poster,
) {
    val colors = LocalCinefinColors.current
    val context = LocalContext.current
    val resolved = remember(model, context) { resolveArtworkModel(context, model) }
    // 2.0.1 定稿 §3 T10：去方框磁贴底，占位类型图标扁平呈现（图片加载完成后照常覆盖）。
    Box(modifier = modifier.clip(shape), contentAlignment = Alignment.Center) {
        Icon(
            painter = painterResource(kind.iconRes()),
            contentDescription = null,
            tint = colors.onSurfaceFaint,
            modifier = Modifier.size(24.dp),
        )
        if (resolved != null) {
            // 占位 = 类型图标：加载中 / 失败时露在图下层，加载完成后被图片覆盖（Coil 加载与占位规范）。
            // W66：本地缓存路径的内存键带 mtime——过期替换（原子改名）后 key 变化，立即显示新图。
            val request =
                remember(resolved) {
                    ImageCacheRules.artworkMemoryCacheKey(resolved)?.let { key ->
                        ImageRequest.Builder(context).data(resolved).memoryCacheKey(key).build()
                    } ?: resolved
                }
            AsyncImage(
                model = request,
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
    val colors = LocalCinefinColors.current
    Dialog(onDismissRequest = onDismiss) {
        LumenCardFrame(
            shape = CinefinShapes.Xl,
            container = colors.surfaceContainerHigh,
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
                        tone = CinefinButtonTone.Media,
                        size = CinefinButtonSize.Medium,
                    )
                }
            }
        }
    }
}

/** 加载骨架：顶层大海报行 ×4（沿用 LumenSkeleton 组件，不新增位图 / 动效）。 */
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
                    shape = CinefinShapes.Poster,
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

// ---------------------------------------------------------------- 文案 / 数值

@Composable
internal fun deletionStatusText(
    projection: DownloadDeletionProjection,
    summary: DownloadDeletionSummary?,
): String? {
    if (projection.deleting) return stringResource(CoreR.string.download_deleting)
    val failedCount = maxOf(projection.failedCount, summary?.failedCount ?: 0)
    val parts = buildList {
        if (failedCount > 0) {
            add(
                stringResource(
                    CoreR.string.download_delete_failed_count,
                    failedCount,
                )
            )
        }
        if (summary != null && summary.totalCount > 0) {
            add(
                stringResource(
                    CoreR.string.download_delete_converged,
                    summary.convergedCount,
                    summary.totalCount,
                )
            )
        }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** 容器 / 详情状态文案（聚合层没有单项失败原因，按状态给中性文案）。 */
@Composable
private fun hierarchyStatusLabel(status: DownloadHierarchyStatus): String =
    when (status) {
        DownloadHierarchyStatus.PENDING -> stringResource(CoreR.string.download_pending)
        DownloadHierarchyStatus.RUNNING -> stringResource(CoreR.string.download_downloading)
        DownloadHierarchyStatus.PAUSED -> stringResource(CoreR.string.download_paused)
        DownloadHierarchyStatus.FAILED -> stringResource(CoreR.string.download_tasks_failed)
        DownloadHierarchyStatus.COMPLETED -> stringResource(CoreR.string.download_tasks_completed)
    }

/** 聚合计数：视频 / 书籍 = 集，音乐 = 首。 */
@Composable
private fun containerCountText(container: DownloadHierarchyContainer): String =
    if (container.mediaKind == DownloadMediaKind.MUSIC) {
        stringResource(
            CoreR.string.download_count_tracks,
            container.completedCount,
            container.totalCount,
        )
    } else {
        stringResource(
            CoreR.string.download_count_episodes,
            container.completedCount,
            container.totalCount,
        )
    }

@Composable
private fun containerSpeedLine(container: DownloadHierarchyContainer): String {
    if (container.status == DownloadHierarchyStatus.COMPLETED) return ""
    return speedLine(container.speedBytesPerSecond, container.etaSeconds)
}

@Composable
private fun subContainerSpeedLine(container: DownloadHierarchySubContainer): String {
    if (container.status == DownloadHierarchyStatus.COMPLETED) return ""
    return speedLine(container.speedBytesPerSecond, container.etaSeconds)
}

@Composable
private fun speedLine(speedBytesPerSecond: Long, etaSeconds: Long?): String =
    DownloadFormatRules.formatSpeed(speedBytesPerSecond) +
        " · " +
        stringResource(
            CoreR.string.download_eta_remaining,
            DownloadFormatRules.formatEta(etaSeconds),
        )

@Composable
private fun leafSpeedLine(task: DownloadTask?): String {
    if (task == null || task.status == DownloadTaskStatus.COMPLETED) return ""
    return speedLine(task.speedBytesPerSecond, task.etaSeconds)
}

private fun leafSizeLine(entry: DownloadHierarchyEntry): String {
    val downloaded = entry.effectiveDownloadedBytes
    val total = entry.expectedTotalBytes
    val sizeLine =
        when {
            entry.status == DownloadTaskStatus.RUNNING ->
                DownloadFormatRules.formatPercent(
                    if (total > 0L) downloaded.toFloat() / total.toFloat() else 0f
                ) + " · " + DownloadFormatRules.formatSizePair(downloaded, total)
            total > 0L && downloaded < total ->
                DownloadFormatRules.formatSizePair(downloaded, total)
            else -> DownloadFormatRules.formatBytes(downloaded)
        }
    // V2-DOWNLOAD-34：只有识别为有声书才会带 runtimeTicks（电子书为 0），没识别出来就不显示时长。
    val audiobookRuntime =
        entry.runtimeTicks.takeIf { entry.mediaKind == DownloadMediaKind.BOOK && it > 0L }
            ?: return sizeLine
    return "$sizeLine · ${formatRuntimeTicks(audiobookRuntime)}"
}

/** 有声书时长文案（V2-DOWNLOAD-34）：`24:02` / `1:23:00`，与影视片长同一口径。 */
internal fun formatRuntimeTicks(runtimeTicks: Long): String {
    val totalSeconds = (runtimeTicks / 10_000_000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
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
