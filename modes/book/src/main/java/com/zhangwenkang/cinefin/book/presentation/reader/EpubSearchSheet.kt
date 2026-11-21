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
import androidx.compose.foundation.lazy.itemsIndexed
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
import kotlinx.coroutines.delay

/** 输入去抖（全书扫描逐字触发太重）。 */
private const val EPUB_SEARCH_DEBOUNCE_MS = 350L

/**
 * EPUB 书内搜索面板（W6-T10，READER-31）。
 *
 * 与 PDF 搜索面板同一交互骨架（关键词输入 → 命中列表 → 点击跳转），差异只有两处：
 * - 结果行第一列是**章节名**（不是页码），章节名来自目录归因，未知时显示「第 N 章」或留空；
 * - 片段里命中词上底色，上下文按定稿七 §4 前后各 30 字裁剪。
 *
 * 三态（加载 / 空 / 错误）由 [epubSearchStatusText] + [epubSearchEmptyHint] 统一给文案，纯逻辑已单测。
 */
@Composable
internal fun EpubSearchSheet(
    state: EpubSearchUiState,
    accent: Color,
    contentColor: Color,
    onQueryChange: (String) -> Unit,
    onJumpToHit: (EpubSearchHit) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf(state.query) }
    val listState = rememberLazyListState()
    // 输入去抖：逐字触发全书扫描太重；停顿 [EPUB_SEARCH_DEBOUNCE_MS] 后才交给执行层。
    // 外部改词（选区「搜索」预填）时 query 与 state.query 相同，直接跳过。
    LaunchedEffect(query) {
        if (query == state.query) return@LaunchedEffect
        delay(EPUB_SEARCH_DEBOUNCE_MS)
        onQueryChange(query)
    }
    // 新查询从顶部开始；结果只追加，不跟着滚动（与 PDF 搜索同一口径）。
    LaunchedEffect(state.query) {
        query = state.query
        listState.scrollToItem(0)
    }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = CinefinSpacing.Space6)
                .padding(bottom = CinefinSpacing.Space6),
        verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space3),
    ) {
        Text(text = "书内搜索", style = CinefinType.TitleMedium, color = contentColor)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
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
                text = epubSearchStatusText(state),
                style = CinefinType.LabelMedium,
                color = contentColor.copy(alpha = 0.72f),
            )
        }
        HorizontalDivider(color = contentColor.copy(alpha = 0.12f))
        if (state.hits.isEmpty()) {
            Text(
                text = epubSearchEmptyHint(state),
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
                itemsIndexed(
                    items = state.hits,
                    key = { index, hit -> epubSearchHitKey(index, hit) },
                ) { _, hit ->
                    EpubSearchHitRow(
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
private fun EpubSearchHitRow(
    hit: EpubSearchHit,
    accent: Color,
    contentColor: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clip(CinefinShapes.Button)
                .background(contentColor.copy(alpha = 0.04f))
                .padding(horizontal = CinefinSpacing.Space2, vertical = CinefinSpacing.Space1),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2),
    ) {
        Text(
            text = hit.chapterTitle.ifBlank { "—" },
            style = CinefinType.LabelMedium,
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(88.dp),
        )
        Text(
            text = epubSnippetAnnotated(hit, accent, contentColor),
            style = CinefinType.BodyMedium,
            color = contentColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onClick) { Text(text = "跳转", color = contentColor) }
    }
}

/** 片段里的命中词加底色（区间越界时原样显示，防御脏数据）。 */
private fun epubSnippetAnnotated(
    hit: EpubSearchHit,
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
