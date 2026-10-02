package com.zhangwenkang.cinefin.book.presentation.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButton
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonSize
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonVariant
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType

/**
 * 本地高亮批注面板（W29-READER，EB-8 后置项）：
 *
 * - 「开始框选」进入批注模式（页面上单指拖动框选，单指翻页暂停），松手后弹备注输入；
 * - 列表按页序排列，支持跳转 / 删除；数据全在本地文件（`filesDir/reader/annotations/{itemId}.json`）。
 */
@Composable
internal fun ReaderAnnotationSheet(
    annotations: List<ReaderAnnotation>,
    annotating: Boolean,
    contentColor: Color,
    onToggleAnnotating: () -> Unit,
    onJumpToAnnotation: (ReaderAnnotation) -> Unit,
    onRemoveAnnotation: (ReaderAnnotation) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = CinefinSpacing.Space6)
                .padding(bottom = CinefinSpacing.Space6),
        verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space3),
    ) {
        Text(text = "高亮批注", style = CinefinType.TitleMedium, color = contentColor)
        Text(
            text = "矩形框选 + 备注，只存本地，不写服务器；没有文本层的扫描件同样可用。",
            style = CinefinType.LabelMedium,
            color = contentColor.copy(alpha = 0.72f),
        )
        CinefinButton(
            text = if (annotating) "结束框选" else "开始框选",
            onClick = onToggleAnnotating,
            variant = CinefinButtonVariant.Outlined,
            size = CinefinButtonSize.Small,
        )
        HorizontalDivider(color = contentColor.copy(alpha = 0.12f))
        if (annotations.isEmpty()) {
            Text(
                text = "还没有批注：点「开始框选」，在页面上拖出一个矩形。",
                style = CinefinType.BodyMedium,
                color = contentColor.copy(alpha = 0.72f),
                modifier = Modifier.padding(vertical = CinefinSpacing.Space3),
            )
        } else {
            Text(
                text = "共 ${annotations.size} 条",
                style = CinefinType.LabelMedium,
                color = contentColor.copy(alpha = 0.72f),
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space1),
            ) {
                items(items = annotations, key = { it.id }) { annotation ->
                    AnnotationRow(
                        annotation = annotation,
                        contentColor = contentColor,
                        onJump = { onJumpToAnnotation(annotation) },
                        onRemove = { onRemoveAnnotation(annotation) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AnnotationRow(
    annotation: ReaderAnnotation,
    contentColor: Color,
    onJump: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = annotation.label,
            style = CinefinType.BodyMedium,
            color = contentColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(vertical = CinefinSpacing.Space1),
        )
        TextButton(onClick = onJump) { Text(text = "跳转", color = contentColor) }
        TextButton(onClick = onRemove) { Text(text = "删除", color = contentColor) }
    }
}

/** 批注备注输入 / 编辑弹窗：新建时标题「添加批注」，编辑时带「删除」。 */
@Composable
internal fun AnnotationNoteDialog(
    title: String,
    initialNote: String,
    canDelete: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var note by remember(initialNote) { mutableStateOf(initialNote) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(READER_ANNOTATION_NOTE_MAX_CHARS) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(text = "备注（可留空）") },
                minLines = 3,
                maxLines = 6,
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(note) }) { Text(text = "保存") } },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2)) {
                if (canDelete) {
                    TextButton(onClick = onDelete) { Text(text = "删除") }
                }
                TextButton(onClick = onDismiss) { Text(text = "取消") }
            }
        },
    )
}

/** 批注模式提示条：常驻在阅读页顶部，明确说明「单指框选、再点一次结束」。 */
@Composable
internal fun AnnotateHintBar(
    contentColor: Color,
    accent: Color,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = CinefinSpacing.Space4, vertical = CinefinSpacing.Space1),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2),
    ) {
        Text(
            text = "框选批注：在页面上拖动拉出矩形，松手后填备注",
            style = CinefinType.LabelMedium,
            color = contentColor,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onStop) { Text(text = "结束", color = accent) }
    }
}
