package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButton
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonSize
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonTone
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonVariant
import com.zhangwenkang.cinefin.core.presentation.components.CinefinSwitch
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalLumenColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors
import com.zhangwenkang.cinefin.film.presentation.detail.DetailDownloadRules
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme
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
) {
    val lumen = LocalLumenColors.current
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
                                    selection.selected.size,
                                ),
                            style = CinefinType.TitleSmall,
                            color = colors.onSurface,
                        )
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
                            val selectedIds = selection?.selected.orEmpty().toSet()
                            onConfirm(targets.filter { it.id in selectedIds })
                        },
                        variant = CinefinButtonVariant.Filled,
                        tone =
                            if (lumen != null) CinefinButtonTone.Inverse
                            else CinefinButtonTone.Media,
                        size = CinefinButtonSize.Medium,
                        enabled = selection?.selected?.isNotEmpty() == true,
                    )
                }
            }
        }
    }
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
