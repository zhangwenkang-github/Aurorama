package com.zhangwenkang.cinefin.presentation.film.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButton
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonSize
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonVariant
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadFormatRules
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadIndexReconciliationState
import com.zhangwenkang.cinefin.presentation.film.components.LumenCardFrame
import com.zhangwenkang.cinefin.utils.DownloadIndexReconciliationReport

/**
 * W9：下载索引对账对话框（dry-run 报告 + 显式清理）。
 *
 * 复用既有 Lumen 面板与确认对话框，不新增视觉样式。只有 [DownloadIndexReconciliationState.Ready] 且存在孤儿时 才出现删除入口；残片 /
 * 侧车只展示计数，没有删除入口。
 */
@Composable
internal fun DownloadIndexReconcileDialog(
    state: DownloadIndexReconciliationState,
    onDelete: (List<String>) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalCinefinColors.current
    var confirmingDelete by remember { mutableStateOf(false) }
    val ready = state as? DownloadIndexReconciliationState.Ready
    val orphanPaths = ready?.report?.orphanMedia?.map { it.relativePath }.orEmpty()

    if (confirmingDelete && ready != null) {
        DownloadConfirmDialog(
            title = stringResource(CoreR.string.download_index_reconcile_delete_title),
            message =
                stringResource(
                    CoreR.string.download_index_reconcile_delete_message,
                    ready.report.orphanMediaCount,
                    DownloadFormatRules.formatBytes(ready.report.orphanMediaBytes),
                ),
            confirmText = stringResource(CoreR.string.download_action_delete),
            onConfirm = {
                confirmingDelete = false
                onDelete(orphanPaths)
            },
            onDismiss = { confirmingDelete = false },
        )
        return
    }

    Dialog(onDismissRequest = onDismiss) {
        LumenCardFrame(
            shape = CinefinShapes.Xl,
            container = colors.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp),
        ) {
            Column(modifier = Modifier.padding(CinefinSpacing.Space6)) {
                Text(
                    text = stringResource(CoreR.string.download_index_reconcile),
                    style = CinefinType.HeadlineSmall,
                    color = colors.onSurface,
                )
                Spacer(Modifier.height(CinefinSpacing.Space3))
                when (state) {
                    DownloadIndexReconciliationState.Idle,
                    DownloadIndexReconciliationState.Scanning ->
                        Text(
                            text = stringResource(CoreR.string.download_index_reconcile_scanning),
                            style = CinefinType.BodyMedium,
                            color = colors.onSurfaceVariant,
                        )
                    DownloadIndexReconciliationState.Failed ->
                        Text(
                            text = stringResource(CoreR.string.download_index_reconcile_failed),
                            style = CinefinType.BodyMedium,
                            color = colors.error,
                        )
                    is DownloadIndexReconciliationState.Ready ->
                        DownloadIndexReconcileReport(
                            report = state.report,
                            deleting = state.deleting,
                        )
                }
                Spacer(Modifier.height(CinefinSpacing.Space6))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CinefinButton(
                        text = stringResource(CoreR.string.close),
                        onClick = onDismiss,
                        variant = CinefinButtonVariant.Text,
                        size = CinefinButtonSize.Medium,
                    )
                    if (state is DownloadIndexReconciliationState.Failed) {
                        Spacer(Modifier.width(CinefinSpacing.Space2))
                        CinefinButton(
                            text = stringResource(CoreR.string.retry),
                            onClick = onRetry,
                            variant = CinefinButtonVariant.Filled,
                            size = CinefinButtonSize.Medium,
                        )
                    }
                    if (ready != null && ready.report.orphanMediaCount > 0) {
                        Spacer(Modifier.width(CinefinSpacing.Space2))
                        CinefinButton(
                            text =
                                stringResource(
                                    CoreR.string.download_index_reconcile_delete,
                                    ready.report.orphanMediaCount,
                                ),
                            onClick = { confirmingDelete = true },
                            variant = CinefinButtonVariant.Filled,
                            size = CinefinButtonSize.Medium,
                            enabled = !ready.deleting,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadIndexReconcileReport(
    report: DownloadIndexReconciliationReport,
    deleting: Boolean,
) {
    val colors = LocalCinefinColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text =
                stringResource(
                    CoreR.string.download_index_reconcile_linked,
                    report.linkedCount,
                    DownloadFormatRules.formatBytes(report.linkedBytes),
                ),
            style = CinefinType.BodySmall,
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(CinefinSpacing.Space2))
        Text(
            text =
                stringResource(
                    CoreR.string.download_index_reconcile_orphans,
                    report.orphanMediaCount,
                    DownloadFormatRules.formatBytes(report.orphanMediaBytes),
                ),
            style = CinefinType.BodySmall,
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(CinefinSpacing.Space2))
        Text(
            text =
                stringResource(
                    CoreR.string.download_index_reconcile_residual,
                    report.residualCount,
                    DownloadFormatRules.formatBytes(report.residualBytes),
                ),
            style = CinefinType.BodySmall,
            color = colors.onSurfaceFaint,
        )
        when {
            report.orphanMedia.isEmpty() -> {
                Spacer(Modifier.height(CinefinSpacing.Space3))
                Text(
                    text = stringResource(CoreR.string.download_index_reconcile_clean),
                    style = CinefinType.BodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
            else -> {
                Spacer(Modifier.height(CinefinSpacing.Space3))
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp),
                    verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2),
                ) {
                    items(report.orphanMedia, key = { it.relativePath }) { orphan ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = orphan.relativePath,
                                style = CinefinType.BodySmall,
                                color =
                                    if (deleting) colors.onSurfaceFaint
                                    else colors.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.width(CinefinSpacing.Space2))
                            Text(
                                text = DownloadFormatRules.formatBytes(orphan.sizeBytes),
                                style = CinefinType.BodySmall,
                                color = colors.onSurfaceFaint,
                            )
                        }
                    }
                }
            }
        }
    }
}
