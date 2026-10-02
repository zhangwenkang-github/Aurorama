package com.zhangwenkang.cinefin.music.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.music.data.lyrics.LyricsDisplayLanguage

/**
 * 歌词面板（MU-5 W3-R2）。
 *
 * 能力：默认简体中文（[MusicModeViewModel.LyricsUiState.display] 决定）、原文 / 简中 / 繁中 / 日文 / 英文切换、 双语对照、当前行高亮 +
 * 跟随滚动、点击行跳转播放位置。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsSheet(
    state: MusicModeViewModel.LyricsUiState,
    onDismiss: () -> Unit,
    onSelectLanguage: (LyricsDisplayLanguage) -> Unit,
    onToggleBilingual: () -> Unit,
    onToggleFollow: () -> Unit,
    onLineClick: (Long) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.82f)) {
            LyricsHeader(state)
            LyricsLanguageBar(
                state = state,
                onSelectLanguage = onSelectLanguage,
                onToggleBilingual = onToggleBilingual,
                onToggleFollow = onToggleFollow,
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.loading ->
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    state.rows.isEmpty() ->
                        Text(
                            text = state.message ?: "该曲目暂无歌词",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center).padding(24.dp),
                            textAlign = TextAlign.Center,
                        )
                    else ->
                        LyricsLines(
                            state = state,
                            onLineClick = onLineClick,
                        )
                }
            }
        }
    }
}

@Composable
private fun LyricsHeader(state: MusicModeViewModel.LyricsUiState) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Text(
            text = state.title ?: "歌词",
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
        )
        val source = state.document?.source?.label
        val languages = state.document?.availableLanguages?.joinToString(" / ") { it.label }
        Text(
            text = listOfNotNull(source?.let { "来源：$it" }, languages).joinToString("　"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun LyricsLanguageBar(
    state: MusicModeViewModel.LyricsUiState,
    onSelectLanguage: (LyricsDisplayLanguage) -> Unit,
    onToggleBilingual: () -> Unit,
    onToggleFollow: () -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(state.languages.size) { index ->
            val language = state.languages[index]
            FilterChip(
                selected = state.display.language == language,
                onClick = { onSelectLanguage(language) },
                label = { Text(language.label) },
            )
        }
        item {
            FilterChip(
                selected = state.display.bilingual,
                onClick = onToggleBilingual,
                label = { Text("双语对照") },
            )
        }
        item {
            FilterChip(
                selected = state.display.follow,
                onClick = onToggleFollow,
                label = { Text("跟随滚动") },
            )
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun LyricsLines(
    state: MusicModeViewModel.LyricsUiState,
    onLineClick: (Long) -> Unit,
) {
    val listState = rememberLazyListState()
    // W24 · B6：上下留半个视口 + centerItem()，当前行进入时即居中
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val verticalPadding = maxHeight / 2
        // 当前行变化 / 重开面板时把高亮行滚到视口中部；"跟随滚动"关闭后不再自动滚。
        LaunchedEffect(state.activeIndex, state.display.follow, state.rows.size) {
            if (!state.display.follow) return@LaunchedEffect
            listState.centerItem(state.activeIndex)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = verticalPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(state.rows) { index, row ->
                val active = index == state.activeIndex
                val startMs = row.startMs
                Column(
                    modifier =
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (active) MaterialTheme.colorScheme.surfaceVariant
                                else MaterialTheme.colorScheme.background
                            )
                            .clickable(enabled = startMs != null) { startMs?.let(onLineClick) }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = row.mainText,
                        style =
                            if (active) MaterialTheme.typography.titleMedium
                            else MaterialTheme.typography.bodyLarge,
                        color =
                            if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (active) FontWeight.SemiBold else null,
                    )
                    val sub = row.subText
                    if (sub != null) {
                        Text(
                            text = sub,
                            style = MaterialTheme.typography.bodyMedium,
                            color =
                                if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
