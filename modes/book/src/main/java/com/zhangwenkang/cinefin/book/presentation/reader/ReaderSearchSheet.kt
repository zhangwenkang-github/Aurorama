package com.zhangwenkang.cinefin.book.presentation.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType

/**
 * PDF 搜索面板（W29-READER）：
 *
 * 关键词输入（去抖 400ms）→ 命中列表（页码 + 上下文片段，命中词高亮）→ 点击跳页， 页面上的命中矩形由 [SimpleBookView] 的叠加层画出。
 *
 * 3649 页文档的懒加载口径：结果**流式追加**、列表用 LazyColumn + 稳定 key，进度每 25 页才刷新一次； 结果上限 400 条（达上限时显示提示并停止扫描）。
 */
@Composable
internal fun ReaderSearchSheet(
    state: PdfSearchUiState,
    accent: Color,
    contentColor: Color,
    onQueryChange: (String) -> Unit,
    onJumpToHit: (PdfSearchHit) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf(state.query) }
    val listState = rememberLazyListState()
    // 结果只在「新命中的页码」越界时才需要动列表：保持用户当前滚动位置，不自动跳顶。
    LaunchedEffect(state.query) { if (state.query.isBlank()) listState.scrollToItem(0) }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = CinefinSpacing.Space6)
                .padding(bottom = CinefinSpacing.Space6),
        verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space3),
    ) {
        Text(text = "PDF 搜索", style = CinefinType.TitleMedium, color = contentColor)
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                onQueryChange(it)
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text(text = "输入关键词", style = CinefinType.BodyMedium) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            query = ""
                            onQueryChange("")
                        }
                    ) {
                        Text(text = "清除", color = contentColor)
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onQueryChange(query) }),
        )
        SearchStatusRow(state = state, accent = accent, contentColor = contentColor)
        HorizontalDivider(color = contentColor.copy(alpha = 0.12f))
        if (state.hits.isEmpty()) {
            Text(
                text = emptySearchHint(state),
                style = CinefinType.BodyMedium,
                color = contentColor.copy(alpha = 0.72f),
                modifier = Modifier.padding(vertical = CinefinSpacing.Space3),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space1),
            ) {
                items(
                    items = state.hits,
                    key = { hit ->
                        "${hit.pageIndex}-${hit.matchStartInSnippet}-${hit.matchLength}"
                    },
                ) { hit ->
                    SearchHitRow(
                        hit = hit,
                        accent = accent,
                        contentColor = contentColor,
                        onClick = { onJumpToHit(hit) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchStatusRow(state: PdfSearchUiState, accent: Color, contentColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2),
    ) {
        if (state.running) {
            CircularProgressIndicator(
                color = accent,
                strokeWidth = 2.dp,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            text = searchStatusText(state),
            style = CinefinType.LabelMedium,
            color = contentColor.copy(alpha = 0.72f),
        )
    }
}

/** 状态行文案：扫描进度 / 无文本层 / 上限 / 错误，都是真机与自测的文本证据。 */
internal fun searchStatusText(state: PdfSearchUiState): String {
    val error = state.error
    if (error != null) return "搜索失败：$error"
    if (state.query.isEmpty()) return "输入关键词后自动扫描文本层"
    if (state.running) {
        return "扫描中 · 已扫描 ${state.scannedPages}/${state.pageCount} 页 · 命中 ${state.hits.size}"
    }
    if (state.cancelled && !state.finished) {
        return "已暂停 · 已扫描 ${state.scannedPages}/${state.pageCount} 页 · 命中 ${state.hits.size}"
    }
    if (state.finished && !state.hasTextLayer) return "该 PDF 没有文本层，无法搜索（可用矩形批注）"
    if (state.truncated) {
        return "命中已达上限 ${PDF_SEARCH_MAX_HITS} 条，只显示前 ${state.hits.size} 条"
    }
    return "扫描完成 · 共 ${state.hits.size} 条命中"
}

private fun emptySearchHint(state: PdfSearchUiState): String =
    when {
        state.query.isEmpty() -> "搜索只读本地已下载的 PDF；不向服务器发送任何请求。"
        state.running -> "正在扫描…已扫描的页会先出结果。"
        state.cancelled && !state.finished -> "已暂停扫描：重新输入或改关键词会从头扫描。"
        state.finished && !state.hasTextLayer -> "扫描件没有文本层：可以退出搜索，用「批注」在页面上框选高亮。"
        state.finished -> "没有找到「${state.query}」。"
        else -> "等待输入。"
    }

@Composable
private fun SearchHitRow(
    hit: PdfSearchHit,
    accent: Color,
    contentColor: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clip(CinefinShapes.Sm)
                .background(contentColor.copy(alpha = 0.04f))
                .padding(horizontal = CinefinSpacing.Space2, vertical = CinefinSpacing.Space1),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2),
    ) {
        Text(
            text = "第 ${hit.pageIndex + 1} 页",
            style = CinefinType.LabelMedium,
            color = accent,
            modifier = Modifier.width(72.dp),
        )
        Text(
            text = highlightSnippet(hit, accent, contentColor),
            style = CinefinType.BodyMedium,
            color = contentColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onClick) { Text(text = "跳转", color = contentColor) }
    }
}

/** 片段里的命中词加底色（没有命中区间时原样显示）。 */
private fun highlightSnippet(
    hit: PdfSearchHit,
    accent: Color,
    contentColor: Color,
): AnnotatedString {
    val start = hit.matchStartInSnippet
    val end = start + hit.matchLength
    if (hit.matchLength <= 0 || start < 0 || end > hit.snippet.length) {
        return AnnotatedString(hit.snippet)
    }
    return buildAnnotatedString {
        append(hit.snippet.substring(0, start))
        withStyle(
            SpanStyle(background = accent.copy(alpha = 0.32f), fontWeight = FontWeight.Medium)
        ) {
            append(hit.snippet.substring(start, end))
        }
        append(hit.snippet.substring(end))
    }
}
