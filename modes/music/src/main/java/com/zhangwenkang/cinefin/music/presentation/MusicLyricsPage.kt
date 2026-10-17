package com.zhangwenkang.cinefin.music.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Velocity
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
import com.zhangwenkang.cinefin.music.data.lyrics.LyricsFontScale
import com.zhangwenkang.cinefin.music.data.lyrics.LyricsLineSpacing
import com.zhangwenkang.cinefin.music.data.lyrics.LyricsRow
import com.zhangwenkang.cinefin.music.data.lyrics.LyricsTextSettings

/**
 * 全屏歌词页（W23-MUSIC · B 组）。
 *
 * 顶栏只保留返回 / 歌名 / 显示设置；语言、双语、跟随与字号行距收进底部抽屉。 跟随播放进度高亮当前行并居中滚动；点某一行跳到该行时间戳。返回靠左上箭头、左滑手势或系统返回键。
 *
 * W79：正文 **水平居中**（#26，与面板 / 悬浮窗统一）；新增字号 / 行距档位条（#37，与面板共用同一偏好键）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicLyricsPage(
    title: String,
    state: MusicModeViewModel.LyricsUiState,
    onBack: () -> Unit,
    onSelectLanguage: (LyricsDisplayLanguage) -> Unit,
    onToggleBilingual: () -> Unit,
    onToggleFollow: () -> Unit,
    onSelectFontScale: (LyricsFontScale) -> Unit,
    onSelectLineSpacing: (LyricsLineSpacing) -> Unit,
    onLineClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCinefinColors.current
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var followPausedByUser by remember { mutableStateOf(false) }
    val continueFollow = {
        followPausedByUser = false
        if (!state.display.follow) onToggleFollow()
    }
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
                Text(
                    text = title,
                    style = CinefinType.TitleMedium,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                CinefinIconButton(onClick = { settingsOpen = true }) { tint ->
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_settings),
                        contentDescription = "显示设置",
                        tint = tint,
                        modifier = Modifier.size(22.dp),
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
                            followPausedByUser = followPausedByUser,
                            onFollowPausedByUserChange = { followPausedByUser = it },
                            onLineClick = onLineClick,
                        )
                }
            }
        }
        if (settingsOpen) {
            LyricsDisplaySettingsSheet(
                state = state,
                followPausedByUser = followPausedByUser,
                onDismiss = { settingsOpen = false },
                onSelectLanguage = onSelectLanguage,
                onToggleBilingual = onToggleBilingual,
                onToggleFollow = onToggleFollow,
                onContinueFollow = continueFollow,
                onSelectFontScale = onSelectFontScale,
                onSelectLineSpacing = onSelectLineSpacing,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LyricsDisplaySettingsSheet(
    state: MusicModeViewModel.LyricsUiState,
    followPausedByUser: Boolean,
    onDismiss: () -> Unit,
    onSelectLanguage: (LyricsDisplayLanguage) -> Unit,
    onToggleBilingual: () -> Unit,
    onToggleFollow: () -> Unit,
    onContinueFollow: () -> Unit,
    onSelectFontScale: (LyricsFontScale) -> Unit,
    onSelectLineSpacing: (LyricsLineSpacing) -> Unit,
) {
    val colors = LocalCinefinColors.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = CinefinSpacing.Space5)) {
            Text(
                text = "显示设置",
                style = CinefinType.TitleMedium,
                color = colors.onSurface,
                modifier =
                    Modifier.padding(
                        start = CinefinSpacing.Space5,
                        end = CinefinSpacing.Space5,
                        top = CinefinSpacing.Space2,
                        bottom = CinefinSpacing.Space3,
                    ),
            )
            if (state.languages.isNotEmpty()) {
                Text(
                    text = "语言",
                    style = CinefinType.BodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = CinefinSpacing.Space5),
                )
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = CinefinSpacing.Space5),
                    horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items(state.languages.size) { index ->
                        val language = state.languages[index]
                        LyricsDisplayChip(
                            label = language.label,
                            selected = state.display.language == language,
                            onClick = { onSelectLanguage(language) },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(CinefinSpacing.Space2))
            }
            Row(
                modifier = Modifier.padding(horizontal = CinefinSpacing.Space5),
                horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2),
            ) {
                LyricsDisplayChip(
                    label = "双语对照",
                    selected = state.display.bilingual,
                    onClick = onToggleBilingual,
                )
                LyricsDisplayChip(
                    label = if (followPausedByUser) "继续跟随" else "跟随滚动",
                    selected = state.display.follow && !followPausedByUser,
                    onClick = if (followPausedByUser) onContinueFollow else onToggleFollow,
                )
            }
            if (followPausedByUser) {
                Text(
                    text = "已手动滚动，跟随已暂停",
                    style = CinefinType.BodySmall,
                    color = colors.onSurfaceVariant,
                    modifier =
                        Modifier.padding(
                            start = CinefinSpacing.Space5,
                            end = CinefinSpacing.Space5,
                            top = CinefinSpacing.Space1,
                        ),
                )
            }
            Spacer(modifier = Modifier.height(CinefinSpacing.Space2))
            LyricsTextSettingsBar(
                settings = state.text,
                onSelectFontScale = onSelectFontScale,
                onSelectLineSpacing = onSelectLineSpacing,
            )
        }
    }
}

@Composable
private fun LyricsDisplayChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val media = LocalMediaColors.current
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors =
            FilterChipDefaults.filterChipColors(
                selectedContainerColor = media.container,
                selectedLabelColor = media.bright,
            ),
    )
}

@Composable
private fun LyricsLines(
    state: MusicModeViewModel.LyricsUiState,
    followPausedByUser: Boolean,
    onFollowPausedByUserChange: (Boolean) -> Unit,
    onLineClick: (Long) -> Unit,
) {
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    val listState = rememberLazyListState()
    val pauseCallback = rememberUpdatedState(onFollowPausedByUserChange)
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // 用户输入本身就足以区分程序化跟随；动画中拖动也必须立刻暂停。
                if (source == NestedScrollSource.UserInput) pauseCallback.value(true)
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                pauseCallback.value(true)
                return Velocity.Zero
            }
        }
    }
    // W24 · B6：上下留半个视口，任何一行（含首行）都能滚到正中
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val baseTextStyle = LocalTextStyle.current
        val textMeasurer = rememberTextMeasurer()
        val verticalPadding = maxHeight / 2
        val rowWidthPx =
            with(density) {
                (maxWidth - CinefinSpacing.Space6 * 2f).coerceAtLeast(1.dp).roundToPx()
            }
        val itemSpacingPx =
            with(density) { state.text.gapDp(CinefinSpacing.Space4.value).dp.roundToPx() }
        // 已布局行直接复用真实高度；未布局行用 TextMeasurer 按同一宽度 / 字号 / 行距测量。
        // activeIndex 参与 key：当前行会从 32sp 切回 21sp，旧高度不能跨高亮态复用。
        val rowHeights =
            remember(state.rows, state.text, rowWidthPx, density, state.activeIndex) {
                mutableMapOf<Int, Int>()
            }
        fun heightOf(index: Int): Int {
            rowHeights[index]?.let {
                return it
            }
            val row = state.rows.getOrNull(index) ?: return 1
            val height =
                measureLyricsRowHeight(
                    textMeasurer = textMeasurer,
                    row = row,
                    active = index == state.activeIndex,
                    text = state.text,
                    baseStyle = baseTextStyle,
                    widthPx = rowWidthPx,
                    density = density,
                )
            rowHeights[index] = height
            return height
        }
        // 用户主动拖动 / 惯性后暂停跟随；只有再次开启「跟随滚动」才恢复。
        LaunchedEffect(state.display.follow) {
            if (state.display.follow) onFollowPausedByUserChange(false)
        }
        LaunchedEffect(
            state.activeIndex,
            state.display,
            state.document,
            state.rows.size,
            state.text,
            followPausedByUser,
        ) {
            if (!state.display.follow || followPausedByUser) return@LaunchedEffect
            listState.animateToCenteredItem(
                target = state.activeIndex,
                itemSpacingPx = itemSpacingPx,
                heightOf = ::heightOf,
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().nestedScroll(nestedScrollConnection),
            contentPadding =
                PaddingValues(
                    start = CinefinSpacing.Space6,
                    end = CinefinSpacing.Space6,
                    top = verticalPadding,
                    bottom = verticalPadding,
                ),
            // W79 · #37：行块间距随「行距」档位加减（基准 = Space4）
            verticalArrangement =
                Arrangement.spacedBy(state.text.gapDp(CinefinSpacing.Space4.value).dp),
        ) {
            itemsIndexed(state.rows) { index, row ->
                val active = index == state.activeIndex
                val startMs = row.startMs
                // W24 · B7：去掉当前行前的「杠」，只用颜色 + 字号区分
                Column(
                    modifier =
                        Modifier.fillMaxWidth()
                            .clip(CinefinShapes.Xs)
                            .clickable(enabled = startMs != null) { startMs?.let(onLineClick) }
                            .onSizeChanged { size -> rowHeights[index] = size.height }
                            .padding(vertical = CinefinSpacing.Space1)
                ) {
                    // W28-MUSIC：有逐字数据时当前行按词递进高亮；无数据回落整行高亮（原行为）
                    val activeWordText =
                        if (active)
                            wordHighlightedText(
                                text = row.mainText,
                                words = row.words,
                                positionMs = state.positionMs,
                                lineEndMs = state.rows.getOrNull(index + 1)?.startMs,
                                idleColor = colors.onSurfaceVariant.copy(alpha = 0.4f),
                                highlightColor = media.bright,
                            )
                        else null
                    if (activeWordText != null) {
                        Text(
                            text = activeWordText,
                            style =
                                CinefinType.TitleLarge.copy(fontSize = 32.sp)
                                    .lyricsScaled(state.text),
                            fontWeight = FontWeight.SemiBold,
                            textAlign = lyricsTextAlign,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(
                            text = row.mainText,
                            style =
                                (if (active) CinefinType.TitleLarge.copy(fontSize = 32.sp)
                                    else CinefinType.TitleSmall.copy(fontSize = 21.sp))
                                    .lyricsScaled(state.text),
                            color =
                                if (active) media.bright
                                else colors.onSurfaceVariant.copy(alpha = 0.4f),
                            fontWeight = if (active) FontWeight.SemiBold else null,
                            textAlign = lyricsTextAlign,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    val sub = row.subText
                    if (sub != null) {
                        Text(
                            text = sub,
                            style =
                                (if (active) CinefinType.TitleMedium else CinefinType.BodyMedium)
                                    .lyricsScaled(state.text),
                            color = if (active) media.bright else colors.onSurfaceVariant,
                            textAlign = lyricsTextAlign,
                            modifier =
                                Modifier.fillMaxWidth().padding(top = state.text.gapDp(0f).dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 按歌词行的实际字体档位 / 可用宽度测量行高。
 *
 * 必须与 [LyricsLines] 的 Column + Text 布局逐项对应：主行样式、双语副行样式及间距、行块上下 padding。远跳只累加这里的真实高度，
 * 不再用上一行或可见行平均值估算。
 */
private fun measureLyricsRowHeight(
    textMeasurer: TextMeasurer,
    row: LyricsRow,
    active: Boolean,
    text: LyricsTextSettings,
    baseStyle: TextStyle,
    widthPx: Int,
    density: Density,
): Int {
    val constraints = Constraints(maxWidth = widthPx.coerceAtLeast(1))
    val mainStyle =
        baseStyle
            .merge(
                (if (active) CinefinType.TitleLarge.copy(fontSize = 32.sp)
                    else CinefinType.TitleSmall.copy(fontSize = 21.sp))
                    .lyricsScaled(text)
            )
            .copy(fontWeight = if (active) FontWeight.SemiBold else null)
    val mainHeight =
        textMeasurer
            .measure(
                text = AnnotatedString(row.mainText),
                style = mainStyle,
                constraints = constraints,
            )
            .size
            .height
    val subText = row.subText
    val subHeight =
        if (subText == null) {
            0
        } else {
            val subStyle =
                baseStyle.merge(
                    (if (active) CinefinType.TitleMedium else CinefinType.BodyMedium).lyricsScaled(
                        text
                    )
                )
            textMeasurer
                .measure(
                    text = AnnotatedString(subText),
                    style = subStyle,
                    constraints = constraints,
                )
                .size
                .height + with(density) { text.gapDp(0f).dp.roundToPx() }
        }
    val rowPadding = with(density) { CinefinSpacing.Space1.roundToPx() * 2 }
    return mainHeight + subHeight + rowPadding
}
