package com.zhangwenkang.cinefin.presentation.selection

import android.content.Context
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.CinefinBatchBar
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButton
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonSize
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonVariant
import com.zhangwenkang.cinefin.core.presentation.components.CinefinIconButton
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.film.R as FilmR

/** 多选态顶栏动作（W58b）：全选 / 取消全选 + × 退出（与音乐打样同款）。 */
@Composable
fun MediaBatchTopBarActions(
    selectedCount: Int,
    visibleCount: Int,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    onExit: () -> Unit,
) {
    val allSelected = visibleCount > 0 && selectedCount >= visibleCount
    CinefinButton(
        text =
            stringResource(
                if (allSelected) CoreR.string.selection_select_none
                else CoreR.string.selection_select_all
            ),
        onClick = { if (allSelected) onSelectNone() else onSelectAll() },
        size = CinefinButtonSize.Small,
        variant = CinefinButtonVariant.Text,
    )
    CinefinIconButton(onClick = onExit) { tint ->
        Icon(
            painter = painterResource(CoreR.drawable.ic_close),
            contentDescription = stringResource(CoreR.string.selection_exit),
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 多选底部工具条（§8.5 / 下载页同款 `CinefinBatchBar`）：动作集合由批量形态决定。 */
@Composable
fun MediaBatchActionBar(
    mode: MediaBatchMode,
    selectedCount: Int,
    isEnabled: (MediaBatchAction) -> Boolean,
    onAction: (MediaBatchAction) -> Unit,
) {
    CinefinBatchBar(selectedCount = selectedCount) {
        mediaBatchActions(mode).forEach { action ->
            CinefinIconButton(enabled = isEnabled(action), onClick = { onAction(action) }) { tint ->
                Icon(
                    painter = painterResource(action.iconRes()),
                    contentDescription = action.label(mode),
                    tint = tint,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** 批量删除确认（红线：只删本机下载；纯服务器条目不在目标内）。 */
@Composable
fun MediaBatchDeleteDialog(
    selectedCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalCinefinColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceContainerHighest,
        shape = CinefinShapes.Xl,
        title = {
            Text(
                text = stringResource(FilmR.string.batch_delete_local_title),
                style = CinefinType.HeadlineSmall,
                color = colors.onSurface,
            )
        },
        text = {
            Text(
                text = stringResource(FilmR.string.batch_delete_local_message, selectedCount),
                style = CinefinType.BodyMedium,
                color = colors.onSurfaceVariant,
            )
        },
        confirmButton = {
            CinefinButton(
                text = stringResource(FilmR.string.batch_action_delete),
                onClick = onConfirm,
                size = CinefinButtonSize.Medium,
                variant = CinefinButtonVariant.Filled,
            )
        },
        dismissButton = {
            CinefinButton(
                text = stringResource(CoreR.string.cancel),
                onClick = onDismiss,
                size = CinefinButtonSize.Medium,
                variant = CinefinButtonVariant.Text,
            )
        },
    )
}

@Composable
private fun MediaBatchAction.label(mode: MediaBatchMode): String =
    stringResource(
        when (this) {
            MediaBatchAction.PLAY -> FilmR.string.batch_action_play
            MediaBatchAction.DOWNLOAD -> FilmR.string.batch_action_download
            MediaBatchAction.FAVORITE -> FilmR.string.batch_action_favorite
            MediaBatchAction.DELETE -> FilmR.string.batch_action_delete
            MediaBatchAction.MARK_PLAYED ->
                if (mode == MediaBatchMode.BOOK) FilmR.string.batch_action_mark_read
                else FilmR.string.batch_action_mark_watched
        }
    )

private fun MediaBatchAction.iconRes(): Int =
    when (this) {
        MediaBatchAction.PLAY -> CoreR.drawable.ic_play
        MediaBatchAction.DOWNLOAD -> CoreR.drawable.ic_download
        MediaBatchAction.MARK_PLAYED -> CoreR.drawable.ic_check
        MediaBatchAction.FAVORITE -> CoreR.drawable.ic_heart
        MediaBatchAction.DELETE -> CoreR.drawable.ic_trash
    }

/** 批量事件 → Snackbar 文案；null = 不弹提示（起播事件由播放页接管）。 */
internal fun mediaBatchEventMessage(context: Context, event: MediaBatchEvent): String? =
    when (event) {
        is MediaBatchEvent.DownloadQueued ->
            batchResultText(context, event.queued, event.failed) { count ->
                context.getString(FilmR.string.batch_download_added, count)
            }
        is MediaBatchEvent.Updated ->
            batchResultText(context, event.changed, event.failed) { count ->
                context.getString(FilmR.string.batch_action_updated, count)
            }
        is MediaBatchEvent.Deleted ->
            batchResultText(context, event.deleted, event.failed) { count ->
                context.getString(FilmR.string.batch_delete_done, count)
            }
        MediaBatchEvent.PlayUnavailable -> context.getString(FilmR.string.batch_play_unavailable)
        is MediaBatchEvent.PlayQueue -> null
    }

private fun batchResultText(
    context: Context,
    successCount: Int,
    failed: Int,
    successFormat: (Int) -> String,
): String? {
    val parts = mutableListOf<String>()
    if (successCount > 0) parts += successFormat(successCount)
    if (failed > 0) parts += context.getString(FilmR.string.batch_partial_failure, failed)
    return parts.joinToString(" · ").takeIf { it.isNotBlank() }
}
