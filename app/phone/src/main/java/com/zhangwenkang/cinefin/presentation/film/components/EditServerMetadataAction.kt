package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButton
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonSize
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonVariant
import com.zhangwenkang.cinefin.core.presentation.components.cinefinClickable
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.film.R as FilmR
import com.zhangwenkang.cinefin.repository.ItemMetadataSnapshot

/**
 * W8-T6：详情页「编辑元数据」动作行。
 *
 * 视觉沿用 `CinefinButton` 的 Text 变形（48dp 触控高 / 46dp 按钮高、20dp 横向内边距、LabelLarge），与「删除服务器文件」
 * 同款，但使用常规内容色（非 error 槽位）：这是普通动作，不是危险操作。
 */
@Composable
fun EditServerMetadataButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
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
                text = stringResource(FilmR.string.detail_edit_metadata_action),
                style = CinefinType.LabelLarge,
                color = colors.onSurface,
                maxLines = 1,
            )
        }
    }
}

/**
 * W8-T6：条目元数据编辑对话框（Lumen 面板，参照音乐模块 PlaylistNameDialog）。
 *
 * 快照为 null 表示服务器当前值没取到：字段禁用，保存按钮不可用（fail-closed），用户只能取消。 保存按钮只在名称非空、三个字段与快照有差异、且没有正在提交时可用。
 */
@Composable
fun EditMetadataDialog(
    snapshot: ItemMetadataSnapshot?,
    saving: Boolean,
    onSave: (name: String, overview: String?, sortName: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable(snapshot) { mutableStateOf(snapshot?.name.orEmpty()) }
    var sortName by rememberSaveable(snapshot) { mutableStateOf(snapshot?.sortName.orEmpty()) }
    var overview by rememberSaveable(snapshot) { mutableStateOf(snapshot?.overview.orEmpty()) }

    val editable = snapshot != null
    val changed =
        snapshot != null &&
            (name != snapshot.name ||
                sortName.normalized() != snapshot.sortName.normalized() ||
                overview.normalized() != snapshot.overview.normalized())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(FilmR.string.detail_edit_metadata_action)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space3)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(FilmR.string.detail_edit_metadata_name)) },
                    singleLine = true,
                    enabled = editable,
                )
                OutlinedTextField(
                    value = sortName,
                    onValueChange = { sortName = it },
                    label = { Text(stringResource(FilmR.string.detail_edit_metadata_sort_name)) },
                    singleLine = true,
                    enabled = editable,
                )
                OutlinedTextField(
                    value = overview,
                    onValueChange = { overview = it },
                    label = { Text(stringResource(FilmR.string.detail_edit_metadata_overview)) },
                    minLines = 4,
                    enabled = editable,
                )
            }
        },
        confirmButton = {
            CinefinButton(
                text = stringResource(FilmR.string.detail_edit_metadata_save),
                onClick = { onSave(name, overview.normalized(), sortName.normalized()) },
                enabled = editable && name.isNotBlank() && changed && !saving,
                size = CinefinButtonSize.Small,
            )
        },
        dismissButton = {
            CinefinButton(
                text = stringResource(CoreR.string.cancel),
                onClick = onDismiss,
                variant = CinefinButtonVariant.Text,
                size = CinefinButtonSize.Small,
            )
        },
    )
}

/** 空白按「无值」处理：与服务器 `null` 语义对齐，避免空串与原值反复判为有差异。 */
private fun String?.normalized(): String? = this?.takeIf { it.isNotBlank() }
