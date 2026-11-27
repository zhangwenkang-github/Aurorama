package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButton
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonSize
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonVariant
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.film.R as FilmR
import com.zhangwenkang.cinefin.repository.ItemMetadataSnapshot

/**
 * W8-T6：条目元数据编辑对话框（2.0.3-C：入口移到海报 / 详情页的 ⋮ 菜单）（Lumen 面板，参照音乐模块 PlaylistNameDialog）。
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
