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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerChapter
import com.zhangwenkang.cinefin.player.core.domain.models.Trickplay
import com.zhangwenkang.cinefin.player.local.R as PlayerR

/** 常驻内容区的两个页签。信息 / 字幕 / 音轨等仍在底部面板，不在这里重复开入口。 */
internal enum class PlayerContentTab {
    Episodes,
    Queue,
}

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
            color = if (selected) Paper else Mist,
        )
        Spacer(Modifier.height(4.dp))
        Box(
            modifier =
                Modifier.width(20.dp)
                    .height(2.dp)
                    .clip(CircleShape)
                    .background(if (selected) Vermilion else Color.Transparent)
        )
    }
}

/**
 * 选集 / 队列列表。
 *
 * 选集按季分组（有季号时插入季标题），队列保持播放顺序平铺；两者都用同一份 [QueueEntry]，
 * 不额外请求接口——播放队列就是「这一部剧可播的全部条目」。
 */
@Composable
internal fun PlayerEpisodeQueueList(
    entries: List<QueueEntry>,
    currentIndex: Int,
    tab: PlayerContentTab,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(bottom = 24.dp),
) {
    if (entries.isEmpty()) {
        Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(PlayerR.string.player_controls_queue_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = Mist,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
            )
        }
        return
    }

    LazyColumn(modifier = modifier, contentPadding = contentPadding) {
        when (tab) {
            PlayerContentTab.Queue ->
                itemsIndexed(entries) { index, entry ->
                    PlayerContentRow(
                        index = index,
                        entry = entry,
                        selected = index == currentIndex,
                        onClick = { onSelect(index) },
                    )
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
                                color = Mist,
                                modifier =
                                    Modifier.padding(
                                        start = 20.dp,
                                        end = 20.dp,
                                        top = 16.dp,
                                        bottom = 4.dp,
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
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 3.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (selected) Vermilion.copy(alpha = 0.14f) else Color.Transparent
                )
                .clickable(onClick = onClick)
                .padding(6.dp),
    ) {
        // 缩略图：剧集截图（16:9），没有图时留一块底色占位，列表不会跳高度
        Box(
            modifier =
                Modifier.width(96.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(SurfaceHigh)
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
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = episodeLabel(index, entry),
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) Vermilion else Mist,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = entry.title.ifBlank { "—" },
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) Vermilion else Paper,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Spacer(Modifier.width(6.dp))
            Box(Modifier.size(6.dp).clip(CircleShape).background(Vermilion))
        }
    }
}

/**
 * 平板 / 折叠展开的右侧常驻内容栏。
 *
 * 宽度由调用方给定（320dp），这里只负责「页签 + 列表 + 收起按钮」。收起后画面区会,
 * 通过 [PlayerOverlayContainer] 的命中区同步变宽，不需要重新创建播放器。
 */
@Composable
internal fun PlayerSideContent(
    entries: List<QueueEntry>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableStateOf(PlayerContentTab.Episodes) }
    Column(modifier = modifier.background(SurfaceLow)) {
        PlayerContentTabRow(
            selected = tab,
            onSelect = { tab = it },
            trailing = {
                PlayerIconButton(
                    iconRes = CoreR.drawable.ic_arrow_right,
                    contentDescription =
                        stringResource(PlayerR.string.player_controls_side_panel_collapse),
                    onClick = onCollapse,
                )
            },
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
        PlayerEpisodeQueueList(
            entries = entries,
            currentIndex = currentIndex,
            tab = tab,
            onSelect = onSelect,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 手机竖屏画面下方的内容区。
 *
 * 顶部留 [PlayerContentTabRow] 与一条把手：默认只露标题行，上滑展开列表、下滑收回，
 * 视频区高度由 [PlayerLayoutContext.videoHeightDp] 决定，两者互不影响。
 */
@Composable
internal fun PlayerBottomContent(
    entries: List<QueueEntry>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** 折叠半开的下半屏是控制台，不需要「可上滑」的把手暗示 */
    showHandle: Boolean = true,
) {
    var tab by remember { mutableStateOf(PlayerContentTab.Episodes) }
    Column(modifier = modifier.background(SurfaceLow)) {
        if (showHandle) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.width(36.dp).height(4.dp).clip(CircleShape).background(Hairline))
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
    if (entries.isEmpty()) {
        Text(
            text = stringResource(PlayerR.string.player_controls_queue_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = Mist,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        )
        return
    }
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        itemsIndexed(entries) { index, entry ->
            val selected = index == currentIndex
            val shape = RoundedCornerShape(12.dp)
            Column(
                modifier =
                    Modifier.width(184.dp)
                        .clip(shape)
                        .background(if (selected) SurfaceRow else SurfaceHigh)
                        .then(
                            if (selected) {
                                Modifier.border(1.dp, Vermilion.copy(alpha = 0.6f), shape)
                            } else {
                                Modifier
                            }
                        )
                        .clickable { onSelect(index) },
            ) {
                // 缩略图铺满卡片上半部分（16:9），正在播放的集角上压一个朱砂角标
                Box(
                    modifier =
                        Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(SurfaceHigh)
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
                            color = Paper,
                            modifier =
                                Modifier.align(Alignment.BottomStart)
                                    .padding(6.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Vermilion)
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        text = episodeLabel(index, entry),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) Vermilion else Mist,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = entry.title.ifBlank { "—" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Paper,
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
 * 小窗里手势与花哨控件都没有意义，只留「播放暂停 + 标题 + 时间 + 更多」，
 * 更多菜单直接打开既有面板，功能不打折、界面不塞满。
 */
@Composable
internal fun PlayerCompactBar(
    isPlaying: Boolean,
    title: String,
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    chapters: List<PlayerChapter>,
    trickplay: Trickplay?,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onScrubStart: () -> Unit,
    onOpenMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(ScrimBottom)
                .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlayerIconButton(
                iconRes = if (isPlaying) CoreR.drawable.ic_pause else CoreR.drawable.ic_play,
                contentDescription = stringResource(PlayerR.string.player_controls_play_pause),
                onClick = onPlayPause,
                size = 40.dp,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = title.ifBlank { "—" },
                style = MaterialTheme.typography.labelLarge,
                color = Paper,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${formatTime(positionMs)} / ${formatTime(durationMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = Mist,
            )
            PlayerIconButton(
                iconRes = CoreR.drawable.ic_logs,
                contentDescription = stringResource(PlayerR.string.player_controls_more),
                onClick = onOpenMore,
                size = 40.dp,
            )
        }
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
