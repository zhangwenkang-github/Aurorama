package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.zhangwenkang.cinefin.R as AppR
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButton
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonSize
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonTone
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonVariant
import com.zhangwenkang.cinefin.core.presentation.components.CinefinSwitch
import com.zhangwenkang.cinefin.core.presentation.downloader.DownloadNetworkPrompt
import com.zhangwenkang.cinefin.core.presentation.downloader.DownloadPrecheckRules
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors
import com.zhangwenkang.cinefin.film.presentation.detail.DetailDownloadRules
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.presentation.selection.MediaBatchDownloadPreparation
import java.util.UUID

/**
 * W51 整剧 / 全季下载确认框（Lumen 面板 + 月白主行动，与下载页确认框同一语言）。
 *
 * 口径（用户 2026-10-03）：
 * - **仅补齐缺失集**：已下载 / 已在队列的集自动跳过，不重复下载（本波不提供覆盖式重下）；
 * - **默认单次上限 100 集**：可关闭上限一次加入全部缺失集；
 * - 取消 / 确认右对齐；没有可加入条目时确认键禁用。
 */
@Composable
fun BatchDownloadDialog(
    title: String,
    episodes: List<FindroidEpisode>?,
    downloadedIds: Set<UUID>,
    queuedIds: Set<UUID>,
    isLoading: Boolean,
    loadFailed: Boolean,
    onRetryLoad: () -> Unit,
    onConfirm: (List<FindroidItem>) -> Unit,
    onDismiss: () -> Unit,
    defaultLimit: Int = DetailDownloadRules.DEFAULT_BATCH_LIMIT,
    /** W7-T1：空间预检与移动数据提示并入本确认框（需求 50；null = 不预检）。 */
    availableBytes: Long? = null,
    networkPrompt: DownloadNetworkPrompt = DownloadNetworkPrompt.NONE,
) {
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    var limitEnabled by remember { mutableStateOf(true) }

    val selection =
        remember(episodes, downloadedIds, queuedIds, limitEnabled) {
            episodes?.let { targets ->
                DetailDownloadRules.selectBatch(
                    itemIds = targets.map { episode -> episode.id },
                    downloaded = downloadedIds,
                    queued = queuedIds,
                    limit = if (limitEnabled) defaultLimit else null,
                )
            }
        }

    val sizeSummary =
        remember(episodes, selection) {
            episodes?.let { targets ->
                DetailDownloadRules.summarizeBatchBytes(
                    episodes = targets,
                    selectedIds = selection?.selected.orEmpty().toSet(),
                )
            }
        }

    /** 需求 50：批次总大小超过可用空间时只入队前 N 集（未知大小不参与截断）。 */
    val spacePlan =
        remember(episodes, selection, availableBytes) {
            val targets = episodes
            val selected = selection?.selected
            if (targets == null || selected == null || availableBytes == null) {
                null
            } else {
                val sizes =
                    targets
                        .filter { episode -> episode.id in selected.toSet() }
                        .associate { episode ->
                            episode.id to DetailDownloadRules.episodeSizeBytes(episode)
                        }
                DetailDownloadRules.fitBatchToSpace(
                    orderedIds = selected,
                    sizeOf = { id -> sizes[id] },
                    availableBytes = availableBytes,
                )
            }
        }

    val finalSelectedIds = spacePlan?.selected ?: selection?.selected.orEmpty()
    val finalSizeBytes =
        if (spacePlan != null) {
            // 已知大小全为 0（全部未知）时不把「0 B」当合计。
            spacePlan.requiredBytes.takeIf { spacePlan.selected.isNotEmpty() && it > 0 }
        } else {
            sizeSummary?.sizeBytesOrNull
        }
    val unknownInFinal =
        if (spacePlan != null) {
            val ids = finalSelectedIds.toSet()
            episodes.orEmpty().count { episode ->
                episode.id in ids && DetailDownloadRules.episodeSizeBytes(episode) == null
            }
        } else {
            sizeSummary?.unknownCount ?: 0
        }

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
                when {
                    isLoading -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = media.base,
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(CinefinSpacing.Space3))
                            Text(
                                text = stringResource(CoreR.string.detail_download_batch_loading),
                                style = CinefinType.BodyMedium,
                                color = colors.onSurfaceVariant,
                            )
                        }
                    }
                    loadFailed -> {
                        Text(
                            text = stringResource(CoreR.string.detail_download_batch_load_failed),
                            style = CinefinType.BodyMedium,
                            color = colors.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(CinefinSpacing.Space3))
                        CinefinButton(
                            text = stringResource(CoreR.string.detail_download_batch_retry),
                            onClick = onRetryLoad,
                            variant = CinefinButtonVariant.Outlined,
                            size = CinefinButtonSize.Medium,
                        )
                    }
                    selection != null -> {
                        Text(
                            text =
                                stringResource(
                                    CoreR.string.detail_download_batch_count,
                                    finalSelectedIds.size,
                                ),
                            style = CinefinType.TitleSmall,
                            color = colors.onSurface,
                        )
                        Spacer(Modifier.height(CinefinSpacing.Space2))
                        Text(
                            text =
                                if (finalSizeBytes != null) {
                                    stringResource(
                                        if (unknownInFinal > 0) {
                                            CoreR.string.download_precheck_batch_total_partial
                                        } else {
                                            CoreR.string.download_precheck_batch_total
                                        },
                                        DownloadPrecheckRules.formatBytes(finalSizeBytes),
                                    )
                                } else {
                                    stringResource(CoreR.string.download_precheck_size_unknown)
                                },
                            style = CinefinType.BodyMedium,
                            color = colors.onSurfaceVariant,
                        )
                        if (availableBytes != null) {
                            Spacer(Modifier.height(CinefinSpacing.Space1))
                            val spaceInsufficient =
                                finalSelectedIds.isEmpty() ||
                                    (finalSizeBytes != null && finalSizeBytes > availableBytes)
                            Text(
                                text =
                                    if (finalSizeBytes != null) {
                                        stringResource(
                                            CoreR.string.download_precheck_space,
                                            DownloadPrecheckRules.formatBytes(finalSizeBytes),
                                            DownloadPrecheckRules.formatBytes(availableBytes),
                                        )
                                    } else {
                                        stringResource(
                                            CoreR.string.download_precheck_space_unknown,
                                            DownloadPrecheckRules.formatBytes(availableBytes),
                                        )
                                    },
                                style = CinefinType.BodyMedium,
                                color =
                                    if (spaceInsufficient) colors.error
                                    else colors.onSurfaceVariant,
                            )
                            if (spaceInsufficient) {
                                Spacer(Modifier.height(CinefinSpacing.Space1))
                                Text(
                                    text =
                                        stringResource(
                                            CoreR.string.download_precheck_space_insufficient
                                        ),
                                    style = CinefinType.BodySmall,
                                    color = colors.error,
                                )
                            }
                        }
                        spacePlan
                            ?.takeIf { plan -> plan.truncated }
                            ?.let { plan ->
                                Spacer(Modifier.height(CinefinSpacing.Space2))
                                Text(
                                    text =
                                        stringResource(
                                            CoreR.string.download_precheck_batch_truncated,
                                            plan.selected.size,
                                            DownloadPrecheckRules.formatBytes(plan.requiredBytes),
                                            plan.deferred,
                                        ),
                                    style = CinefinType.BodyMedium,
                                    color = colors.onSurfaceVariant,
                                )
                            }
                        if (networkPrompt == DownloadNetworkPrompt.CONFIRM_MOBILE_DATA) {
                            Spacer(Modifier.height(CinefinSpacing.Space2))
                            Text(
                                text = stringResource(CoreR.string.download_precheck_mobile_data),
                                style = CinefinType.BodyMedium,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        if (selection.skippedDownloaded > 0 || selection.skippedQueued > 0) {
                            Spacer(Modifier.height(CinefinSpacing.Space2))
                            Text(
                                text =
                                    stringResource(
                                        CoreR.string.detail_download_batch_skipped,
                                        selection.skippedDownloaded,
                                        selection.skippedQueued,
                                    ),
                                style = CinefinType.BodyMedium,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        if (selection.overflow > 0) {
                            Spacer(Modifier.height(CinefinSpacing.Space2))
                            Text(
                                text =
                                    stringResource(
                                        CoreR.string.detail_download_batch_overflow,
                                        selection.overflow,
                                    ),
                                style = CinefinType.BodyMedium,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(CinefinSpacing.Space4))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CinefinSwitch(
                                checked = limitEnabled,
                                onCheckedChange = { limitEnabled = it },
                            )
                            Spacer(Modifier.width(CinefinSpacing.Space3))
                            Text(
                                text =
                                    stringResource(
                                        CoreR.string.detail_download_batch_limit,
                                        defaultLimit,
                                    ),
                                style = CinefinType.BodyMedium,
                                color = colors.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
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
                        text = stringResource(CoreR.string.detail_download_batch_confirm),
                        onClick = {
                            val targets = episodes ?: return@CinefinButton
                            val selectedIds = finalSelectedIds.toSet()
                            onConfirm(targets.filter { it.id in selectedIds })
                        },
                        variant = CinefinButtonVariant.Filled,
                        tone = CinefinButtonTone.Media,
                        size = CinefinButtonSize.Medium,
                        enabled = finalSelectedIds.isNotEmpty(),
                    )
                }
            }
        }
    }
}

object EpisodeDownloadSelectionTags {
    const val SELECT_ALL = "media_download_select_all"
    const val SELECT_NONE = "media_download_select_none"
    const val CONFIRM = "media_download_confirm"

    fun candidate(itemId: UUID): String = "media_download_candidate_$itemId"
}

/**
 * Show / Season 批量下载的显式剧集确认清单。
 *
 * 默认不勾选任何剧集；用户必须先看见稳定叶子条目并主动选择。确认回调只携带当前 request 和 勾选 ID，ViewModel 据此冻结 manifest，执行期不再按容器取集。
 */
@Composable
fun EpisodeDownloadSelectionDialog(
    preparation: MediaBatchDownloadPreparation,
    downloadedIds: Set<UUID>,
    queuedIds: Set<UUID>,
    onRetryLoad: () -> Unit,
    onConfirm: (requestId: Long, selectedIds: Set<UUID>) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    var selectedIds by
        remember(preparation.requestId, preparation.candidates) { mutableStateOf(emptySet<UUID>()) }

    Dialog(onDismissRequest = { if (!preparation.isConfirming) onDismiss() }) {
        LumenCardFrame(
            shape = CinefinShapes.Xl,
            container = colors.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp),
        ) {
            Column(modifier = Modifier.padding(CinefinSpacing.Space6)) {
                Text(
                    text = stringResource(AppR.string.media_batch_episode_selection_title),
                    style = CinefinType.HeadlineSmall,
                    color = colors.onSurface,
                )
                Spacer(Modifier.height(CinefinSpacing.Space2))
                Text(
                    text =
                        stringResource(
                            AppR.string.media_batch_episode_selection_expanded,
                            preparation.expandedFromIds.size,
                            preparation.candidates.size,
                        ),
                    style = CinefinType.BodyMedium,
                    color = colors.onSurfaceVariant,
                )
                Spacer(Modifier.height(CinefinSpacing.Space4))
                when {
                    preparation.isLoading -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = media.base,
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(CinefinSpacing.Space3))
                            Text(
                                text =
                                    stringResource(
                                        AppR.string.media_batch_episode_selection_loading
                                    ),
                                style = CinefinType.BodyMedium,
                                color = colors.onSurfaceVariant,
                            )
                        }
                    }
                    preparation.loadFailureCount > 0 || preparation.candidates.isEmpty() -> {
                        Text(
                            text =
                                stringResource(
                                    AppR.string.media_batch_episode_selection_load_failed
                                ),
                            style = CinefinType.BodyMedium,
                            color = colors.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(CinefinSpacing.Space3))
                        CinefinButton(
                            text = stringResource(CoreR.string.detail_download_batch_retry),
                            onClick = onRetryLoad,
                            variant = CinefinButtonVariant.Outlined,
                            size = CinefinButtonSize.Medium,
                        )
                    }
                    else -> {
                        Text(
                            text =
                                stringResource(
                                    AppR.string.media_batch_episode_selection_summary,
                                    selectedIds.size,
                                    preparation.candidates.size,
                                ),
                            style = CinefinType.TitleSmall,
                            color = colors.onSurface,
                        )
                        Spacer(Modifier.height(CinefinSpacing.Space2))
                        Row(horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2)) {
                            CinefinButton(
                                text =
                                    stringResource(
                                        AppR.string.media_batch_episode_selection_select_all
                                    ),
                                onClick = {
                                    selectedIds =
                                        preparation.candidates.map { item -> item.id }.toSet()
                                },
                                modifier =
                                    Modifier.testTag(EpisodeDownloadSelectionTags.SELECT_ALL),
                                variant = CinefinButtonVariant.Text,
                                size = CinefinButtonSize.Small,
                            )
                            CinefinButton(
                                text =
                                    stringResource(
                                        AppR.string.media_batch_episode_selection_select_none
                                    ),
                                onClick = { selectedIds = emptySet() },
                                modifier =
                                    Modifier.testTag(EpisodeDownloadSelectionTags.SELECT_NONE),
                                variant = CinefinButtonVariant.Text,
                                size = CinefinButtonSize.Small,
                            )
                        }
                        Spacer(Modifier.height(CinefinSpacing.Space3))
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                            verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2),
                        ) {
                            items(preparation.candidates, key = { item -> item.id }) { item ->
                                val checked = item.id in selectedIds
                                val status =
                                    when {
                                        item.id in downloadedIds ->
                                            stringResource(
                                                AppR.string.media_batch_episode_downloaded
                                            )
                                        item.id in queuedIds ->
                                            stringResource(AppR.string.media_batch_episode_queued)
                                        else -> null
                                    }
                                val title = downloadCandidateTitle(item)
                                Row(
                                    modifier =
                                        Modifier.fillMaxWidth()
                                            .testTag(
                                                EpisodeDownloadSelectionTags.candidate(item.id)
                                            )
                                            .semantics {
                                                contentDescription = title
                                                stateDescription = status.orEmpty()
                                            }
                                            .clickable {
                                                selectedIds =
                                                    if (checked) selectedIds - item.id
                                                    else selectedIds + item.id
                                            }
                                            .padding(vertical = CinefinSpacing.Space1),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(
                                        checked = checked,
                                        onCheckedChange = { next ->
                                            selectedIds =
                                                if (next) selectedIds + item.id
                                                else selectedIds - item.id
                                        },
                                    )
                                    Spacer(Modifier.width(CinefinSpacing.Space2))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = title,
                                            style = CinefinType.BodyMedium,
                                            color = colors.onSurface,
                                        )
                                        status?.let { label ->
                                            Text(
                                                text = label,
                                                style = CinefinType.BodySmall,
                                                color = colors.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if (preparation.deduplicatedCandidateCount > 0) {
                            Spacer(Modifier.height(CinefinSpacing.Space2))
                            Text(
                                text =
                                    stringResource(
                                        AppR.string.media_batch_episode_selection_deduplicated,
                                        preparation.deduplicatedCandidateCount,
                                    ),
                                style = CinefinType.BodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(CinefinSpacing.Space6))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CinefinButton(
                        text = stringResource(CoreR.string.cancel),
                        onClick = onDismiss,
                        enabled = !preparation.isConfirming,
                        variant = CinefinButtonVariant.Text,
                        size = CinefinButtonSize.Medium,
                    )
                    Spacer(Modifier.width(CinefinSpacing.Space2))
                    CinefinButton(
                        text =
                            stringResource(
                                AppR.string.media_batch_episode_selection_confirm,
                                selectedIds.size,
                            ),
                        onClick = {
                            if (selectedIds.isNotEmpty() && !preparation.isConfirming) {
                                onConfirm(preparation.requestId, selectedIds)
                            }
                        },
                        modifier = Modifier.testTag(EpisodeDownloadSelectionTags.CONFIRM),
                        enabled =
                            preparation.isReady &&
                                !preparation.isConfirming &&
                                selectedIds.isNotEmpty(),
                        variant = CinefinButtonVariant.Filled,
                        tone = CinefinButtonTone.Media,
                        size = CinefinButtonSize.Medium,
                    )
                }
            }
        }
    }
}

private fun downloadCandidateTitle(item: FindroidItem): String =
    when (item) {
        is FindroidEpisode ->
            "S%02d E%02d · %s".format(item.parentIndexNumber, item.indexNumber, item.name)
        else -> item.name
    }

@Composable
@Preview
private fun BatchDownloadDialogPreview() {
    CinefinTheme {
        BatchDownloadDialog(
            title = "下载整剧",
            episodes = emptyList(),
            downloadedIds = emptySet(),
            queuedIds = emptySet(),
            isLoading = true,
            loadFailed = false,
            onRetryLoad = {},
            onConfirm = {},
            onDismiss = {},
        )
    }
}
