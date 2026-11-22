package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.presentation.components.cinefinClickable
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.film.R as FilmR
import com.zhangwenkang.cinefin.presentation.film.downloads.DownloadConfirmDialog

/**
 * W8-T5：详情页「删除服务器文件」危险操作行。
 *
 * 视觉沿用 `CinefinButton` 的 Text 变形（48dp 触控高 / 46dp 按钮高、20dp 横向内边距、LabelLarge），只把内容色换成主题 error 槽位： 现有
 * Text 变形不提供颜色覆盖，而删除入口需要与常规文本动作区分。
 */
@Composable
fun DeleteServerFileButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalCinefinColors.current
    Box(
        modifier = modifier.defaultMinSize(minHeight = 48.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier =
                Modifier.height(46.dp)
                    .clip(CinefinShapes.Button)
                    .cinefinClickable(onClick = onClick)
                    .padding(horizontal = CinefinSpacing.Space5),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            Text(
                text = stringResource(FilmR.string.detail_delete_server_action),
                style = CinefinType.LabelLarge,
                color = colors.error,
                maxLines = 1,
            )
        }
    }
}

/** W8-T5：删除服务器文件的二次确认（复用下载页同款 Lumen 面板）。 */
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
