package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.presentation.film.downloads.DownloadConfirmDialog
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme

/** W51 重绘：详情页删除下载确认 = 下载页同款 Lumen 面板（替换旧 M3 AlertDialog）。 */
@Composable
fun DeleteDownloadDialog(onDelete: () -> Unit, onDismiss: () -> Unit) {
    DownloadConfirmDialog(
        title = stringResource(CoreR.string.delete_download),
        message = stringResource(CoreR.string.delete_download_message),
        confirmText = stringResource(CoreR.string.delete_download),
        onConfirm = onDelete,
        onDismiss = onDismiss,
    )
}

@Composable
@Preview
private fun DeleteDownloadDialogPreview() {
    CinefinTheme { DeleteDownloadDialog(onDelete = {}, onDismiss = {}) }
}
