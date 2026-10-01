/*
 * 播放页常驻内容区（D9）。
 *
 * 三种落点共用同一批组件：
 *  - 平板 / 折叠展开横屏：画面右侧 320dp 侧栏（可收起）
 *  - 手机竖屏：画面下方内容区（上滑可全屏化）
 *  - 小窗：单行控制条（[PlayerCompactBar]）
 *
 * 内容只有两类：**选集**（按季分组，来自播放队列的季/集号）与**队列**（按顺序平铺）。
 * 字幕 / 音轨 / 画面 / 信息仍然走底部面板，避免同一功能出现两套入口。
 */
package com.zhangwenkang.cinefin.presentation.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerChapter
import com.zhangwenkang.cinefin.player.core.domain.models.Trickplay
import com.zhangwenkang.cinefin.player.local.R as PlayerR
import kotlin.math.roundToInt

/** 常驻内容区的两个页签。信息 / 字幕 / 音轨等仍在底部面板，不在这里重复开入口。 */
internal enum class PlayerContentTab {
    Episodes,
    Queue,
}

/** 队列行近似高度（dp）：拖拽换位按「位移超过一行就换位」计算，与队列行的缩略图高度匹配 */
private val QUEUE_ROW_HEIGHT = 72.dp

/** 侧栏 / 内容区顶部页签行；[trailing] 放「收起侧栏」这类局部动作 */
@Composable
internal fun PlayerContentTabRow(
    selected: PlayerContentTab,
    onSelect: (PlayerContentTab) -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        PlayerContentTab.entries.forEach { tab ->
            PlayerContentTabItem(
                tab = tab,
                selected = tab == selected,
                onClick = { onSelect(tab) },
            )
        }
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
private fun PlayerContentTabItem(
    tab: PlayerContentTab,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val label =
        when (tab) {
            PlayerContentTab.Episodes -> stringResource(PlayerR.string.player_controls_episodes)
            PlayerContentTab.Queue -> stringResource(PlayerR.string.player_controls_queue)
        }
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            Modifier.clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) media.bright else colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Box(
            modifier =
                Modifier.width(20.dp)
                    .height(2.dp)
                    .clip(CircleShape)
                    .background(if (selected) media.base else Color.Transparent)
        )
    }
}

/**
 * 选集 / 队列列表。
 *
 * 选集按季分组（有季号时插入季标题），队列保持播放顺序平铺；两者都用同一份 [QueueEntry]， 不额外请求接口——播放队列就是「这一部剧可播的全部条目」。
 */
@Composable
internal fun PlayerEpisodeQueueList(
    entries: List<QueueEntry>,
    currentIndex: Int,
    tab: PlayerContentTab,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(bottom = 24.dp),
    /** 队列整理（§1.7）：拖拽排序 / 删除 / 清空；只有播放队列页给回调，选集页传 null */
    onMove: ((Int, Int) -> Unit)? = null,
    onRemove: ((Int) -> Unit)? = null,
    onClear: (() -> Unit)? = null,
) {
    val colors = LocalCinefinColors.current
    if (entries.isEmpty()) {
        Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(PlayerR.string.player_controls_queue_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier =
                    Modifier.padding(
                        horizontal = CinefinSpacing.Space5,
                        vertical = CinefinSpacing.Space6,
                    ),
            )
        }
        return
    }

    LazyColumn(modifier = modifier, contentPadding = contentPadding) {
        when (tab) {
            PlayerContentTab.Queue -> {
                val editing = onMove != null && onRemove != null
                if (editing && onClear != null) {
                    item(key = "queue-edit-header") { QueueEditHeader(onClear = onClear) }
                }
                itemsIndexed(entries) { index, entry ->
                    val row: @Composable () -> Unit = {
                        PlayerContentRow(
                            index = index,
                            entry = entry,
                            selected = index == currentIndex,
                            onClick = { onSelect(index) },
                            trailing =
                                if (editing && onRemove != null) {
                                    {
                                        QueueRemoveButton(
                                            enabled = index != currentIndex,
                                            onClick = { onRemove(index) },
                                        )
                                    }
                                } else {
                                    null
                                },
                        )
                    }
                    if (editing && onMove != null) {
                        QueueDragRow(
                            index = index,
                            lastIndex = entries.lastIndex,
                            onMove = onMove,
                        ) {
                            row()
                        }
                    } else {
                        row()
                    }
                }
            }
            PlayerContentTab.Episodes -> {
                val grouped = entries.withIndex().groupBy { it.value.seasonNumber }
                grouped.forEach { (season, indexedEntries) ->
                    if (season != null && grouped.size > 1) {
                        item(key = "season-$season") {
                            Text(
                                text =
                                    stringResource(
                                        PlayerR.string.player_controls_season,
                                        season,
                                    ),
                                style = MaterialTheme.typography.labelLarge,
                                color = colors.onSurfaceVariant,
                                modifier =
                                    Modifier.padding(
                                        start = CinefinSpacing.Space5,
                                        end = CinefinSpacing.Space5,
                                        top = CinefinSpacing.Space4,
                                        bottom = CinefinSpacing.Space1,
                                    ),
                            )
                        }
                    }
                    itemsIndexed(
                        items = indexedEntries,
                        key = { _, entry -> entry.index },
                    ) { _, entry ->
                        PlayerContentRow(
                            index = entry.index,
                            entry = entry.value,
                            selected = entry.index == currentIndex,
                            onClick = { onSelect(entry.index) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerContentRow(
    index: Int,
    entry: QueueEntry,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** 行尾动作（队列页的删除键）；选集页不传 */
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = CinefinSpacing.Space2, vertical = 3.dp)
                .clip(CinefinShapes.Sm)
                .background(if (selected) media.container else Color.Transparent)
                .then(
                    if (selected) Modifier.border(1.dp, media.outline, CinefinShapes.Sm)
                    else Modifier
                )
                .clickable(onClick = onClick)
                .padding(CinefinSpacing.Space2),
    ) {
        // 缩略图：剧集截图（16:9），没有图时留一块底色占位，列表不会跳高度
        Box(
            modifier =
                Modifier.width(96.dp)
                    .aspectRatio(16f / 9f)
                    .clip(CinefinShapes.Xs)
                    .background(colors.surfaceContainerHigh)
        ) {
            if (!entry.artworkUri.isNullOrBlank()) {
                AsyncImage(
                    model = entry.artworkUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.width(CinefinSpacing.Space3))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = episodeLabel(index, entry),
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) media.bright else colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = entry.title.ifBlank { "—" },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Spacer(Modifier.width(CinefinSpacing.Space2))
            // 选中指示用图标而不是独立色点（§2.6 第 1 条：禁止色点 / 色块）
            Icon(
                painter = painterResource(CoreR.drawable.ic_check),
                contentDescription = null,
                tint = media.base,
                modifier = Modifier.size(18.dp),
            )
        }
        trailing?.invoke()
    }
}

/**
 * 队列整理行（§1.7 → 反馈③）：长按拖动换位。
 *
 * 拖拽用「累计位移每超过一行高度就换一次位」的简化实现（不引新依赖），一次拖拽可以连续换位， 松手即停在当前位置；点行跳转与行尾删除由内容承担。
 */
@Composable
private fun QueueDragRow(
    index: Int,
    lastIndex: Int,
    onMove: (Int, Int) -> Unit,
    content: @Composable () -> Unit,
) {
    var dragOffset by remember { mutableFloatStateOf(0f) }
    // 拖动过程中列表顺序会变：用最新的下标去算目标位，避免用捕获的旧下标
    val currentIndex by rememberUpdatedState(index)
    val currentLastIndex by rememberUpdatedState(lastIndex)
    val rowHeightPx = with(LocalDensity.current) { QUEUE_ROW_HEIGHT.toPx() }

    Box(
        modifier =
            Modifier.graphicsLayer { translationY = dragOffset }
                .pointerInput(index) {
                    detectDragGesturesAfterLongPress(
                        onDragEnd = { dragOffset = 0f },
                        onDragCancel = { dragOffset = 0f },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragOffset += dragAmount.y
                            val steps = (dragOffset / rowHeightPx).roundToInt()
                            if (steps != 0) {
                                val target = (currentIndex + steps).coerceIn(0, currentLastIndex)
                                if (target != currentIndex) onMove(currentIndex, target)
                                dragOffset = 0f
                            }
                        },
                    )
                }
    ) {
        content()
    }
}

/** 队列行尾删除键：正在播放的那条不允许移除（清空 = 只留它） */
@Composable
private fun QueueRemoveButton(enabled: Boolean, onClick: () -> Unit) {
    PlayerIconButton(
        iconRes = CoreR.drawable.ic_close,
        contentDescription = stringResource(PlayerR.string.player_queue_remove),
        onClick = onClick,
        size = 40.dp,
        glass = false,
        enabled = enabled,
    )
}

/** 队列整理头：拖拽提示 + 清空（保留正在播的条目） */
@Composable
private fun QueueEditHeader(onClear: () -> Unit) {
    val colors = LocalCinefinColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.fillMaxWidth()
                .padding(
                    start = CinefinSpacing.Space4,
                    end = CinefinSpacing.Space4,
                    top = CinefinSpacing.Space2,
                    bottom = CinefinSpacing.Space1,
                ),
    ) {
        Text(
            text = stringResource(PlayerR.string.player_queue_drag_hint),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        PanelChip(
            label = stringResource(PlayerR.string.player_queue_clear),
            selected = false,
            onClick = onClear,
        )
    }
}

/**
 * 平板 / 折叠展开的右侧常驻内容栏。
 *
 * 宽度由调用方给定（320dp），这里只负责「页签 + 列表 + 收起按钮」。收起后画面区会, 通过 [PlayerOverlayContainer] 的命中区同步变宽，不需要重新创建播放器。
 */
@Composable
internal fun PlayerSideContent(
    entries: List<QueueEntry>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
    /** 队列整理（§1.7）：选集页不显示，队列页显示拖拽 / 删除 / 清空 */
    onQueueMove: (Int, Int) -> Unit = { _, _ -> },
    onQueueRemove: (Int) -> Unit = {},
    onQueueClear: () -> Unit = {},
) {
    var tab by remember { mutableStateOf(PlayerContentTab.Episodes) }
    val colors = LocalCinefinColors.current
    Column(modifier = modifier.background(colors.surfaceDim)) {
        PlayerContentTabRow(
            selected = tab,
            onSelect = { tab = it },
            trailing = {
                PlayerIconButton(
                    iconRes = CoreR.drawable.ic_arrow_right,
                    contentDescription =
                        stringResource(PlayerR.string.player_controls_side_panel_collapse),
                    onClick = onCollapse,
                    glass = false,
                )
            },
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.outlineVariant))
        PlayerEpisodeQueueList(
            entries = entries,
            currentIndex = currentIndex,
            tab = tab,
            onSelect = onSelect,
            onMove = onQueueMove,
            onRemove = onQueueRemove,
            onClear = onQueueClear,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 手机竖屏画面下方的内容区。
 *
 * 顶部留 [PlayerContentTabRow] 与一条把手：默认只露标题行，上滑展开列表、下滑收回， 视频区高度由 [PlayerLayoutContext.videoHeightDp]
 * 决定，两者互不影响。
 */
@Composable
internal fun PlayerBottomContent(
    entries: List<QueueEntry>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** 折叠半开的下半屏是控制台，不需要「可上滑」的把手暗示 */
    showHandle: Boolean = true,
    /** 队列整理（§1.7）：队列页的拖拽 / 删除 / 清空 */
    onQueueMove: (Int, Int) -> Unit = { _, _ -> },
    onQueueRemove: (Int) -> Unit = {},
    onQueueClear: () -> Unit = {},
) {
    var tab by remember { mutableStateOf(PlayerContentTab.Episodes) }
    val colors = LocalCinefinColors.current
    Column(modifier = modifier.background(colors.surfaceDim)) {
        if (showHandle) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.width(36.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(colors.outlineVariant)
                )
            }
        }
        PlayerContentTabRow(selected = tab, onSelect = { tab = it })
        when (tab) {
            PlayerContentTab.Episodes ->
                PlayerEpisodeCards(
                    entries = entries,
                    currentIndex = currentIndex,
                    onSelect = onSelect,
                )
            PlayerContentTab.Queue ->
                PlayerEpisodeQueueList(
                    entries = entries,
                    currentIndex = currentIndex,
                    tab = tab,
                    onSelect = onSelect,
                    onMove = onQueueMove,
                    onRemove = onQueueRemove,
                    onClear = onQueueClear,
                    modifier = Modifier.weight(1f),
                )
        }
    }
}

/** 竖屏选集：横滑卡片，卡片里放集号与标题；当前集用朱砂描边标出 */
@Composable
private fun PlayerEpisodeCards(
    entries: List<QueueEntry>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    if (entries.isEmpty()) {
        Text(
            text = stringResource(PlayerR.string.player_controls_queue_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            modifier =
                Modifier.padding(
                    horizontal = CinefinSpacing.Space5,
                    vertical = CinefinSpacing.Space4,
                ),
        )
        return
    }
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space3),
        contentPadding =
            PaddingValues(
                horizontal = CinefinSpacing.Space4,
                vertical = CinefinSpacing.Space2,
            ),
        modifier = modifier.fillMaxWidth(),
    ) {
        itemsIndexed(entries) { index, entry ->
            val selected = index == currentIndex
            val shape = CinefinShapes.Sm
            Column(
                modifier =
                    Modifier.width(184.dp)
                        .clip(shape)
                        .background(if (selected) media.container else colors.surfaceContainerHigh)
                        .then(
                            if (selected) {
                                Modifier.border(1.dp, media.outline, shape)
                            } else {
                                Modifier
                            }
                        )
                        .clickable { onSelect(index) }
            ) {
                // 缩略图铺满卡片上半部分（16:9），正在播放的集角上压一个媒体色角标
                Box(
                    modifier =
                        Modifier.fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .background(colors.surfaceContainerHigh)
                ) {
                    if (!entry.artworkUri.isNullOrBlank()) {
                        AsyncImage(
                            model = entry.artworkUri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    if (selected) {
                        Text(
                            text = stringResource(PlayerR.string.player_controls_now_playing),
                            style = MaterialTheme.typography.labelSmall,
                            color = media.onBase,
                            modifier =
                                Modifier.align(Alignment.BottomStart)
                                    .padding(CinefinSpacing.Space2)
                                    .clip(CinefinShapes.Xs)
                                    .background(media.base)
                                    .padding(
                                        horizontal = CinefinSpacing.Space2,
                                        vertical = 2.dp,
                                    ),
                        )
                    }
                }
                Column(modifier = Modifier.padding(CinefinSpacing.Space3)) {
                    Text(
                        text = episodeLabel(index, entry),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) media.bright else colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(CinefinSpacing.Space1))
                    Text(
                        text = entry.title.ifBlank { "—" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 卡片上的集号：有集号用 E03，没有就用序号 */
private fun episodeLabel(index: Int, entry: QueueEntry): String {
    val episode = entry.episodeNumber
    return if (episode != null) "E%02d".format(episode) else "${index + 1}"
}

/**
 * 小窗 / 分屏单行控制条。
 *
 * 小窗里手势与花哨控件都没有意义，只留「播放暂停 + 标题 + 时间 + 更多」， 更多菜单直接打开既有面板，功能不打折、界面不塞满。
 */
@Composable
internal fun PlayerCompactBar(
    isPlaying: Boolean,
    /** 缓冲中：主播放键图标位换成转圈（W11 反馈⑤，全屏唯一的一个加载图标） */
    buffering: Boolean,
    title: String,
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    chapters: List<PlayerChapter>,
    trickplay: Trickplay?,
    isFullscreen: Boolean,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onScrubStart: () -> Unit,
    onToggleFullscreen: () -> Unit,
    /** 工具行（W11 反馈①：取消「更多」后，小窗用一行横向可滚的小键兜住全部入口） */
    tools: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onHeightChanged: (Int) -> Unit = {},
) {
    val colors = LocalCinefinColors.current
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .onSizeChanged { onHeightChanged(it.height) }
                .background(playerBottomScrim())
                .padding(horizontal = CinefinSpacing.Space2, vertical = CinefinSpacing.Space1)
    ) {
        /*
         * 标题行（W11 反馈③）：小窗同样把播放 / 快进 / 下一个 摆回中间偏左的位置，
         * 标题吃掉剩余宽度，时间码与全屏键贴右端——窗口再窄也不会把时间挤出屏幕（反馈⑧）。
         */
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlayerIconButton(
                iconRes = CoreR.drawable.ic_skip_back,
                contentDescription =
                    stringResource(PlayerR.string.player_controls_previous_episode),
                onClick = onPrevious,
                size = 36.dp,
            )
            PlayerIconButton(
                iconRes = CoreR.drawable.ic_rewind,
                contentDescription = stringResource(PlayerR.string.player_controls_rewind),
                onClick = onRewind,
                size = 36.dp,
            )
            PlayerPlayKey(
                isPlaying = isPlaying,
                onClick = onPlayPause,
                size = 40.dp,
                buffering = buffering,
            )
            PlayerIconButton(
                iconRes = CoreR.drawable.ic_fast_forward,
                contentDescription = stringResource(PlayerR.string.player_controls_fast_forward),
                onClick = onForward,
                size = 36.dp,
            )
            PlayerIconButton(
                iconRes = CoreR.drawable.ic_skip_forward,
                contentDescription = stringResource(PlayerR.string.player_controls_next_episode),
                onClick = onNext,
                size = 36.dp,
            )
            Spacer(Modifier.width(CinefinSpacing.Space2))
            Text(
                text = title.ifBlank { "—" },
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(CinefinSpacing.Space2))
            Text(
                text = "${formatTime(positionMs)} / ${formatTime(durationMs)}",
                style = CinefinType.MonoDataSmall,
                color = colors.onSurfaceVariant,
            )
            PlayerIconButton(
                iconRes =
                    if (isFullscreen) {
                        PlayerR.drawable.ic_player_fullscreen_exit
                    } else {
                        PlayerR.drawable.ic_player_fullscreen
                    },
                contentDescription =
                    stringResource(
                        if (isFullscreen) {
                            PlayerR.string.player_controls_fullscreen_exit
                        } else {
                            PlayerR.string.player_controls_fullscreen
                        }
                    ),
                onClick = onToggleFullscreen,
                size = 40.dp,
            )
        }
        Spacer(Modifier.height(CinefinSpacing.Space1))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space1),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        ) {
            tools()
        }
        Spacer(Modifier.height(CinefinSpacing.Space1))
        PlayerSeekBar(
            positionMs = positionMs,
            durationMs = durationMs,
            bufferedMs = bufferedMs,
            chapters = chapters,
            trickplay = trickplay,
            onScrubStart = onScrubStart,
            onScrub = onSeek,
        )
    }
}
