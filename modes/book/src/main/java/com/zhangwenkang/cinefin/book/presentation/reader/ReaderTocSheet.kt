package com.zhangwenkang.cinefin.book.presentation.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButton
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonSize
import com.zhangwenkang.cinefin.core.presentation.components.CinefinEmptyState
import com.zhangwenkang.cinefin.core.presentation.components.cinefinClickable
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType

/**
 * 目录抽屉（W6 T2 定稿二 §2）：底部半屏 `ModalBottomSheet`，多级嵌套每级缩进 16dp。
 *
 * 数据源：EPUB 用 Readium `tableOfContents`（[ReaderTocNode] 展平成 [ReaderTocEntry]）；PDF / CBZ 没有章节概念，走
 * [ReaderTocUiState.Empty]。 已读标记 = 该章 progression 区间（到下一个同级 / 更高级条目的起点，末章到 1.0）整体小于当前位置。
 */

/** 每级缩进（定稿二 §2：16dp）。 */
private val READER_TOC_INDENT = 16.dp

/** 目录行最小触控高度。 */
private val READER_TOC_ROW_HEIGHT = 48.dp

/** 当前章左侧竖条（3dp 宽）与强调底透明度（accent 12%）。 */
private val READER_TOC_CURRENT_BAR_WIDTH = 3.dp
private val READER_TOC_CURRENT_BAR_HEIGHT = 24.dp
private const val READER_TOC_CURRENT_BACKGROUND_ALPHA = 0.12f

/** 目录列表最大高度（超出后在抽屉内滚动，抽屉本身仍可拖成全屏）。 */
private val READER_TOC_LIST_MAX_HEIGHT = 520.dp

/** 已读判定容差：`end <= current + 容差` 视为整章读完（positions 往返换算有 ±1 ulp 误差）。 */
private const val READER_TOC_READ_EPSILON = 1e-6

/** 目录条目（已展平；[level] 为缩进层级，0 = 顶层）。 */
data class ReaderTocEntry(
    val title: String,
    val level: Int,
    /** 章节资源地址（与 positions 对齐用，已去 fragment）。 */
    val href: String? = null,
    /** 章节起点 progression（positions 未就绪 / 无 positions 时为 null）。 */
    val progression: Double? = null,
    /** 跳转载体：Readium `Link` 派生的 Locator JSON（可能为 null）。 */
    val locatorJson: String? = null,
)

/** 目录三态（V2-UX-10）：加载 / 空 / 错误；就绪时携带展平后的条目。 */
sealed interface ReaderTocUiState {
    data object Loading : ReaderTocUiState

    data object Empty : ReaderTocUiState

    data class Ready(val entries: List<ReaderTocEntry>) : ReaderTocUiState

    data class Error(val message: String) : ReaderTocUiState
}

/** 阅读页的导航元数据（随 [ReaderUiState.Ready] 一起下发，避免阅读页额外接线）。 */
data class ReaderNavigation(
    val toc: ReaderTocUiState = ReaderTocUiState.Loading,
    /** 章节分界刻度（0–1，升序；PDF / CBZ 为空）。 */
    val chapterTicks: List<Float> = emptyList(),
    /** EPUB positions 页数（`第 X 页 / 共 Y 页` 与进度条浮层用；不可用时为 null）。 */
    val positionPageCount: Int? = null,
)

/** 目录源节点（Readium `Link` 的最小投影，便于纯逻辑单测）。 */
internal data class ReaderTocNode(
    val title: String,
    val href: String? = null,
    val locatorJson: String? = null,
    val children: List<ReaderTocNode> = emptyList(),
)

/** 目录展平：深度优先保留层级；空标题节点被跳过，其子级提级到同一层。 */
internal fun readerTocEntries(nodes: List<ReaderTocNode>): List<ReaderTocEntry> {
    val out = mutableListOf<ReaderTocEntry>()
    fun visit(nodes: List<ReaderTocNode>, level: Int) {
        for (node in nodes) {
            val title = node.title.trim()
            if (title.isEmpty()) {
                visit(node.children, level)
            } else {
                out +=
                    ReaderTocEntry(
                        title = title,
                        level = level,
                        href = node.href,
                        locatorJson = node.locatorJson,
                    )
                visit(node.children, level + 1)
            }
        }
    }
    visit(nodes, 0)
    return out
}

/**
 * 用 positions 给目录条目补章节起点 progression。
 *
 * 按资源地址（去 fragment）对齐：同一资源的首条 position 即该章起点；找不到的条目保持 null（只影响刻度与已读标记，不影响跳转）。
 */
internal fun readerTocWithProgressions(
    entries: List<ReaderTocEntry>,
    positions: List<ReaderPosition>,
): List<ReaderTocEntry> {
    if (entries.isEmpty() || positions.isEmpty()) return entries
    val starts = LinkedHashMap<String, Double>()
    for (position in positions) {
        if (!position.progression.isFinite()) continue
        val href = position.href.substringBefore('#')
        val value = position.progression.coerceIn(0.0, 1.0)
        val existing = starts[href]
        if (existing == null || value < existing) starts[href] = value
    }
    return entries.map { entry ->
        val key = entry.href?.substringBefore('#')
        val progression = key?.let { starts[it] }
        if (progression == null) entry else entry.copy(progression = progression)
    }
}

/**
 * 已读标记：条目 progression 区间（到下一个同级 / 更高级条目起点，末章到 1.0）整体小于当前位置。
 *
 * 与 [entries] 一一对应；progression 缺失（positions 不可用）一律 false。
 */
internal fun readerTocReadFlags(entries: List<ReaderTocEntry>, current: Double): List<Boolean> {
    if (entries.isEmpty()) return emptyList()
    val position = navigableProgression(current)
    return entries.indices.map { index ->
        val start = entries[index].progression ?: return@map false
        val end = readerTocChapterEnd(entries, index) ?: return@map false
        end <= position + READER_TOC_READ_EPSILON
    }
}

/** 当前章下标：progression ≤ 当前的最深 / 最后一条（同起点取更深一级）；positions 不可用时返回 null。 */
internal fun readerCurrentTocIndex(entries: List<ReaderTocEntry>, current: Double): Int? {
    if (entries.isEmpty()) return null
    val position = navigableProgression(current)
    var best: Int? = null
    var bestProgression = Double.NEGATIVE_INFINITY
    var bestLevel = Int.MIN_VALUE
    entries.forEachIndexed { index, entry ->
        val progression = entry.progression ?: return@forEachIndexed
        if (progression > position + READER_TOC_READ_EPSILON) return@forEachIndexed
        if (
            progression > bestProgression ||
                (progression == bestProgression && entry.level > bestLevel)
        ) {
            best = index
            bestProgression = progression
            bestLevel = entry.level
        }
    }
    return best
}

/** 当前位置所属章节标题（进度条拖动浮层用）；positions 不可用时返回 null。 */
internal fun readerChapterTitleAt(entries: List<ReaderTocEntry>, progression: Double): String? =
    readerCurrentTocIndex(entries, progression)?.let { entries[it].title }

/** 章末 progression：向后找第一个同级或更高级条目，找到则用其起点；末章为 1.0。 */
private fun readerTocChapterEnd(entries: List<ReaderTocEntry>, index: Int): Double? {
    val level = entries[index].level
    for (next in index + 1 until entries.size) {
        if (entries[next].level <= level) return entries[next].progression ?: 1.0
    }
    return 1.0
}

private fun navigableProgression(value: Double): Double =
    if (value.isFinite()) value.coerceIn(0.0, 1.0) else 0.0

/**
 * 目录抽屉内容（底部半屏 `ModalBottomSheet` 的 body）。
 *
 * 顶部只留标题「目录」；「显示已读」开关（W6 T5）统一在排版面板的布局组里，抽屉不再重复。 列表每行 = 缩进 + 标题 + 可选 accent ✓；当前章行 accent 12% 底 +
 * 左侧 3dp 竖条。三态：加载 / 空（PDF / CBZ 无章节）/ 错误（可重试）。
 */
@Composable
internal fun ReaderTocSheet(
    state: ReaderTocUiState,
    currentProgression: Double,
    showReadMarks: Boolean,
    accent: Color,
    contentColor: Color,
    onSelect: (ReaderTocEntry) -> Unit,
    /** 错误态的重试动作；null = 不显示重试（调用方没有重载通道时）。 */
    onRetry: (() -> Unit)? = null,
) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = CinefinSpacing.Space6)
                .padding(bottom = CinefinSpacing.Space6),
        verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text = "目录", style = CinefinType.TitleMedium, color = contentColor)
        }

        when (state) {
            ReaderTocUiState.Loading ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = CinefinSpacing.Space6),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        color = accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = "正在解析目录…",
                        style = CinefinType.BodyMedium,
                        color = contentColor.copy(alpha = 0.72f),
                        modifier = Modifier.padding(start = CinefinSpacing.Space3),
                    )
                }

            ReaderTocUiState.Empty ->
                CinefinEmptyState(
                    title = "这本书没有目录",
                    message = "PDF / CBZ 没有章节概念，可以用进度条定位。",
                )

            is ReaderTocUiState.Error ->
                CinefinEmptyState(
                    title = "目录加载失败",
                    message = state.message,
                    action =
                        onRetry?.let { retry ->
                            {
                                CinefinButton(
                                    text = "重试",
                                    onClick = retry,
                                    size = CinefinButtonSize.Medium,
                                )
                            }
                        },
                )

            is ReaderTocUiState.Ready -> {
                val entries = state.entries
                if (entries.isEmpty()) {
                    CinefinEmptyState(title = "这本书没有目录")
                    return@Column
                }
                val currentIndex =
                    remember(entries, currentProgression) {
                        readerCurrentTocIndex(entries, currentProgression)
                    }
                val readFlags =
                    remember(entries, currentProgression, showReadMarks) {
                        if (showReadMarks) readerTocReadFlags(entries, currentProgression)
                        else emptyList()
                    }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = READER_TOC_LIST_MAX_HEIGHT)
                ) {
                    itemsIndexed(entries, key = { index, entry -> "$index:${entry.title}" }) {
                        index,
                        entry ->
                        TocRow(
                            entry = entry,
                            current = index == currentIndex,
                            read = readFlags.getOrElse(index) { false },
                            accent = accent,
                            contentColor = contentColor,
                            onSelect = onSelect,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TocRow(
    entry: ReaderTocEntry,
    current: Boolean,
    read: Boolean,
    accent: Color,
    contentColor: Color,
    onSelect: (ReaderTocEntry) -> Unit,
) {
    Box(
        modifier =
            Modifier.fillMaxWidth()
                .padding(start = READER_TOC_INDENT * entry.level)
                .clip(CinefinShapes.Xs)
                .background(
                    if (current) accent.copy(alpha = READER_TOC_CURRENT_BACKGROUND_ALPHA)
                    else Color.Transparent
                )
                .cinefinClickable(onClickLabel = entry.title) { onSelect(entry) }
                .heightIn(min = READER_TOC_ROW_HEIGHT)
    ) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(
                        start = CinefinSpacing.Space4,
                        end = CinefinSpacing.Space4,
                    )
                    .heightIn(min = READER_TOC_ROW_HEIGHT),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = entry.title,
                modifier = Modifier.weight(1f),
                style = CinefinType.BodyMedium,
                color = contentColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (read) {
                Text(
                    text = "✓",
                    style = CinefinType.LabelLarge,
                    color = accent,
                    modifier = Modifier.padding(start = CinefinSpacing.Space2),
                )
            }
        }
        if (current) {
            Box(
                modifier =
                    Modifier.align(Alignment.CenterStart)
                        .width(READER_TOC_CURRENT_BAR_WIDTH)
                        .height(READER_TOC_CURRENT_BAR_HEIGHT)
                        .clip(CinefinShapes.TwoXs)
                        .background(accent)
            )
        }
    }
}
