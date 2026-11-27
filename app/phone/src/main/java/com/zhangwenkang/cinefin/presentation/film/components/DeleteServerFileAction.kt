package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.zhangwenkang.cinefin.film.R as FilmR
import com.zhangwenkang.cinefin.presentation.film.downloads.DownloadConfirmDialog

/** W8-T5：删除服务器文件的二次确认（复用下载页同款 Lumen 面板）。2.0.3-C：入口移到海报 / 详情页的 ⋮ 菜单。 */
@Composable
fun DeleteServerFileDialog(
    itemName: String,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    DownloadConfirmDialog(
        title = stringResource(FilmR.string.detail_delete_server_action),
        message = stringResource(FilmR.string.detail_delete_server_message, itemName),
        confirmText = stringResource(FilmR.string.detail_delete_server_confirm),
        onConfirm = onDelete,
        onDismiss = onDismiss,
    )
}
