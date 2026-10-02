package com.zhangwenkang.cinefin.music.presentation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButton
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonSize
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonVariant
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.music.data.lyrics.LyricTextCodec

/**
 * 本机歌词编辑器（W25-MUSIC）。
 *
 * 时间戳 + 文本逐行编辑，支持增行 / 删行；保存写"本机覆盖"（来源链最高优先级，不写服务器）。 「导入 LRC」走系统文件选择器（`OpenDocument`，任意 MIME
 * 通配——.lrc 的 MIME 因文件管理器而异），读入后原样保存并回填编辑器； 「清除覆盖」删除覆盖文件，歌词回落到外挂 LRC / 服务端 / 缓存。
 */
@Composable
fun LyricsEditorDialog(
    state: MusicModeViewModel.LyricsEditorUiState,
    onDismiss: () -> Unit,
    onAddLine: () -> Unit,
    onRemoveLine: (Long) -> Unit,
    onSelectLine: (Long) -> Unit,
    onLineTextChange: (Long, String) -> Unit,
    onLineTimeChange: (Long, String) -> Unit,
    onShiftAllLines: (Long) -> Unit,
    onShiftSelectedLine: (Long) -> Unit,
    onOffsetTextChange: (String) -> Unit,
    onStepTextChange: (String) -> Unit,
    onApplyOffsetText: () -> Unit,
    onApplyStepText: () -> Unit,
    onSave: () -> Unit,
    onClearOverride: () -> Unit,
    onImportText: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    val colors = LocalCinefinColors.current
    val context = LocalContext.current
    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    LyricTextCodec.decode(stream.readBytes())
                }
            }
                .getOrNull()
            if (text.isNullOrBlank()) onMessage("无法读取所选文件（或文件为空）") else onImportText(text)
        }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.88f),
            shape = CinefinShapes.Xl,
            color = colors.surfaceContainerHighest,
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(CinefinSpacing.Space5)) {
                Text(
                    text = "编辑歌词",
                    style = CinefinType.TitleMedium,
                    color = colors.onSurface,
                )
                Text(
                    text = state.title.orEmpty(),
                    style = CinefinType.BodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "保存为本机覆盖（优先于外挂 LRC / 服务端 / 缓存），不会写入服务器",
                    style = CinefinType.BodySmall,
                    color = colors.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(CinefinSpacing.Space2))
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space1),
                ) {
                    itemsIndexed(state.lines, key = { _, line -> line.id }) { index, line ->
                        val selected = line.id == state.selectedLineId
                        Row(
                            modifier =
                                Modifier.fillMaxWidth()
                                    .clip(CinefinShapes.Xs)
                                    .background(
                                        if (selected) colors.surfaceContainerHigh
                                        else colors.surfaceContainer
                                    )
                                    .clickable { onSelectLine(line.id) }
                                    .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = line.timeText,
                                onValueChange = { text -> onLineTimeChange(line.id, text) },
                                modifier =
                                    Modifier.width(102.dp).onFocusChanged { focus ->
                                        if (focus.isFocused) onSelectLine(line.id)
                                    },
                                singleLine = true,
                                textStyle = CinefinType.BodySmall,
                                placeholder = { Text("mm:ss.xx") },
                            )
                            Spacer(modifier = Modifier.width(CinefinSpacing.Space1))
                            OutlinedTextField(
                                value = line.text,
                                onValueChange = { text -> onLineTextChange(line.id, text) },
                                modifier = Modifier.weight(1f),
                                maxLines = 2,
                                textStyle = CinefinType.BodyMedium,
                            )
                            IconButton(onClick = { onRemoveLine(line.id) }) {
                                Icon(
                                    painter = painterResource(CoreR.drawable.ic_close),
                                    contentDescription = "删除第 ${index + 1} 行",
                                    tint = colors.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                state.message?.let { message ->
                    Spacer(modifier = Modifier.height(CinefinSpacing.Space1))
                    Text(
                        text = message,
                        style = CinefinType.BodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(CinefinSpacing.Space1))
                // W28-MUSIC：整段时间轴偏移 + 选中行单行微调（±100ms 档 / 自定义毫秒）
                val selectedIndex = state.lines.indexOfFirst { it.id == state.selectedLineId }
                TimeShiftRow(
                    label = "整体偏移",
                    value = state.offsetText,
                    onValueChange = onOffsetTextChange,
                    onShift = onShiftAllLines,
                    onApply = onApplyOffsetText,
                )
                TimeShiftRow(
                    label = if (selectedIndex >= 0) "第 ${selectedIndex + 1} 行" else "选中行",
                    value = state.stepText,
                    onValueChange = onStepTextChange,
                    onShift = onShiftSelectedLine,
                    onApply = onApplyStepText,
                    enabled = selectedIndex >= 0,
                )
                Spacer(modifier = Modifier.height(CinefinSpacing.Space1))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onAddLine) { Text("添加行") }
                    TextButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                        Text("导入 LRC")
                    }
                    TextButton(onClick = onClearOverride) { Text("清除覆盖") }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("取消") }
                    CinefinButton(
                        text = "保存",
                        onClick = onSave,
                        size = CinefinButtonSize.Medium,
                        variant = CinefinButtonVariant.Filled,
                    )
                }
            }
        }
    }
}

/**
 * 时间平移控制行（W28-MUSIC）：`±100ms` 预设档 + 自定义毫秒输入 + 应用。
 *
 * 整段时间轴与选中行共用同一布局；窄屏横向可滚动，避免按钮被裁切。
 */
@Composable
private fun TimeShiftRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onShift: (Long) -> Unit,
    onApply: () -> Unit,
    enabled: Boolean = true,
) {
    val colors = LocalCinefinColors.current
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = CinefinSpacing.Space1),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space1),
    ) {
        Text(
            text = label,
            style = CinefinType.BodySmall,
            color = colors.onSurfaceVariant,
        )
        TextButton(
            onClick = { onShift(-100L) },
            enabled = enabled,
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
            Text(text = "−100ms", style = CinefinType.BodySmall)
        }
        TextButton(
            onClick = { onShift(100L) },
            enabled = enabled,
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
            Text(text = "+100ms", style = CinefinType.BodySmall)
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.width(92.dp),
            enabled = enabled,
            singleLine = true,
            textStyle = CinefinType.BodySmall,
            placeholder = { Text(text = "自定义 ms") },
        )
        TextButton(
            onClick = onApply,
            enabled = enabled,
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
            Text(text = "应用", style = CinefinType.BodySmall)
        }
    }
}
