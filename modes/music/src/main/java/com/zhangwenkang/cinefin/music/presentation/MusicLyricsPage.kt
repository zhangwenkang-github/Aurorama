package com.zhangwenkang.cinefin.music.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.CinefinIconButton
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors
import com.zhangwenkang.cinefin.music.data.lyrics.LyricsDisplayLanguage

/**
 * 全屏歌词页（W23-MUSIC · B 组）。
 *
 * 跟随播放进度高亮当前行并居中滚动；点某一行跳到该行时间戳；语言 chip / 双语对照 / 跟随滚动
 * 与底栏「词」面板共用同一份显示状态（[MusicModeViewModel.LyricsUiState]）。 返回靠左上箭头、左滑手势或系统返回键。
 */
@Composable
fun MusicLyricsPage(
    title: String,
    state: MusicModeViewModel.LyricsUiState,
    onBack: () -> Unit,
    onSelectLanguage: (LyricsDisplayLanguage) -> Unit,
    onToggleBilingual: () -> Unit,
    onToggleFollow: () -> Unit,
    onLineClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    Box(modifier = modifier.fillMaxSize().background(colors.surface)) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(
                            start = CinefinSpacing.Space3,
                            end = CinefinSpacing.Space5,
                            top = CinefinSpacing.Space3,
                        ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CinefinIconButton(onClick = onBack) { tint ->
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_arrow_left),
                        contentDescription = "返回全屏播放",
                        tint = tint,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(modifier = Modifier.width(CinefinSpacing.Space2))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = CinefinType.TitleMedium,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val source = state.document?.source?.label
                    val languages =
                        state.document?.availableLanguages?.joinToString(" / ") { it.label }
                    Text(
                        text = listOfNotNull(source?.let { "来源：$it" }, languages).joinToString("　"),
                        style = CinefinType.BodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(modifier = Modifier.height(CinefinSpacing.Space2))
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = CinefinSpacing.Space5),
                horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(state.languages.size) { index ->
                    val language = state.languages[index]
                    FilterChip(
                        selected = state.display.language == language,
                        onClick = { onSelectLanguage(language) },
                        label = { Text(language.label) },
                        colors =
                            FilterChipDefaults.filterChipColors(
                                selectedContainerColor = media.container,
                                selectedLabelColor = media.bright,
                            ),
                    )
                }
                item {
                    FilterChip(
                        selected = state.display.bilingual,
                        onClick = onToggleBilingual,
                        label = { Text("双语对照") },
                        colors =
                            FilterChipDefaults.filterChipColors(
                                selectedContainerColor = media.container,
                                selectedLabelColor = media.bright,
                            ),
                    )
                }
                item {
                    FilterChip(
                        selected = state.display.follow,
                        onClick = onToggleFollow,
                        label = { Text("跟随滚动") },
                        colors =
                            FilterChipDefaults.filterChipColors(
                                selectedContainerColor = media.container,
                                selectedLabelColor = media.bright,
                            ),
                    )
                }
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.loading ->
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    state.rows.isEmpty() ->
                        Text(
                            text = state.message ?: "该曲目暂无歌词",
                            style = CinefinType.BodyMedium,
                            color = colors.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier =
                                Modifier.align(Alignment.Center)
                                    .padding(horizontal = CinefinSpacing.Space8),
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
private fun LyricsLines(
    state: MusicModeViewModel.LyricsUiState,
    onLineClick: (Long) -> Unit,
) {
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    val listState = rememberLazyListState()
    val viewportHeight = listState.layoutInfo.viewportSize.height
    // §8.13：当前行固定在可视区 38% 高度
    val centerOffset = with(LocalDensity.current) { (40.dp).roundToPx() }

    LaunchedEffect(state.activeIndex, state.display.follow, state.rows.size) {
        if (!state.display.follow) return@LaunchedEffect
        val target = state.activeIndex
        if (target in state.rows.indices) {
            listState.animateScrollToItem(
                target,
                scrollOffset = -(viewportHeight * 38 / 100 - centerOffset),
            )
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding =
            PaddingValues(
                start = CinefinSpacing.Space6,
                end = CinefinSpacing.Space6,
                top = CinefinSpacing.Space8,
                bottom = CinefinSpacing.Space16,
            ),
        verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space4),
    ) {
        itemsIndexed(state.rows) { index, row ->
            val active = index == state.activeIndex
            val startMs = row.startMs
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .clip(CinefinShapes.Xs)
                        .clickable(enabled = startMs != null) { startMs?.let(onLineClick) }
                        .padding(vertical = CinefinSpacing.Space1),
                verticalAlignment = Alignment.Top,
            ) {
                if (active) {
                    Box(
                        modifier =
                            Modifier.padding(top = CinefinSpacing.Space2)
                                .size(width = 16.dp, height = 2.dp)
                                .background(media.base)
                    )
                    Spacer(modifier = Modifier.width(CinefinSpacing.Space3))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.mainText,
                        style =
                            if (active) CinefinType.TitleLarge.copy(fontSize = 32.sp)
                            else CinefinType.TitleSmall.copy(fontSize = 21.sp),
                        color =
                            if (active) colors.onSurface
                            else colors.onSurfaceVariant.copy(alpha = 0.4f),
                        fontWeight = if (active) FontWeight.SemiBold else null,
                    )
                    val sub = row.subText
                    if (sub != null) {
                        Text(
                            text = sub,
                            style = if (active) CinefinType.TitleMedium else CinefinType.BodyMedium,
                            color = if (active) media.bright else colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
