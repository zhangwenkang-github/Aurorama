package com.zhangwenkang.cinefin.book.presentation.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import kotlin.math.abs
import kotlin.math.floor

/**
 * 控制层底片的全书进度条（W6 T2 定稿二 §1）。
 *
 * 值 = `totalProgression`（EPUB）/ 页索引换算的 progression（PDF / CBZ），与进度持久化同一语义。轨道 3dp、thumb 12dp、触控区
 * 44dp； 章节刻度按 positions 的资源分组聚合（PDF / CBZ 不画）；书签点 4dp 圆点叠在轨道上，点按即跳转。拖动过程只预览，**松手执行一次跳转**。
 */

/** 触控区高度（定稿二 §1：44dp）。 */
internal val READER_PROGRESS_TOUCH_HEIGHT = 44.dp

/** 轨道高度（定稿二 §1：3dp）。 */
private val READER_PROGRESS_TRACK_HEIGHT = 3.dp

/** thumb 直径（定稿二 §1：12dp）。 */
private val READER_PROGRESS_THUMB_DIAMETER = 12.dp

/** 章节刻度：2dp 高、1dp 宽的竖 tick（定稿二 §1）。 */
private val READER_PROGRESS_TICK_HEIGHT = 2.dp
private val READER_PROGRESS_TICK_WIDTH = 1.dp

/** 书签标记圆点直径（定稿二 §1：4dp）。 */
private val READER_PROGRESS_BOOKMARK_DIAMETER = 4.dp

/** 书签点的点按容差（半径）：轨道上 4dp 的点太细，命中半径按 12dp 算。 */
private val READER_PROGRESS_BOOKMARK_TOUCH_RADIUS = 12.dp

/** 拖动浮层与进度条的间距（定稿二 §1：8dp）。 */
internal val READER_PROGRESS_OVERLAY_GAP = 8.dp

/** 「进度」入口聚焦时浮层的停留时长（与缩放宽条的瞬时提示同量级）。 */
internal const val READER_PROGRESS_OVERLAY_FOCUS_MS = 1_600L

/** 浮层淡入淡出时长。 */
internal const val READER_PROGRESS_OVERLAY_FADE_MS = 120

/**
 * 当前位置是否已有书签（双击正文切换书签用）：按 progression 近似匹配，返回书签下标。
 *
 * 容差取 1e-4（0.01%）：PDF / CBZ 的 progression 由同一公式换算，同页必然相等；相邻页的间距通常远大于该容差。
 */
internal const val READER_PROGRESS_BOOKMARK_EPSILON = 1e-4

/** EPUB positions 的纯数据投影（资源地址 + 整书进度），便于 JVM 单测。 */
data class ReaderPosition(val href: String, val progression: Double)

/**
 * positions → 章节分界刻度：按资源分组，取每组最小 totalProgression，升序去重。
 *
 * 去掉 0（与轨道起点重合）与 1（轨道终点）；非有限值忽略。
 */
internal fun readerChapterTicks(positions: List<ReaderPosition>): List<Float> {
    if (positions.isEmpty()) return emptyList()
    val starts = LinkedHashMap<String, Double>()
    for (position in positions) {
        if (!position.progression.isFinite()) continue
        val href = position.href.substringBefore('#')
        val value = position.progression.coerceIn(0.0, 1.0)
        val existing = starts[href]
        if (existing == null || value < existing) starts[href] = value
    }
    return starts.values.map { it.toFloat() }.filter { it > 0f && it < 1f }.distinct().sorted()
}

/**
 * 整书进度 → `positions` 页码（1-based）。
 *
 * `floor(p * count)` 与 [pageIndexForProgression] 同口径（含 ±1 ulp 容差），再加 1 便于展示；`count <= 0` 返回 0。
 */
internal fun readerPositionPage(progression: Double, positionCount: Int): Int {
    if (positionCount <= 0) return 0
    val value = if (progression.isFinite()) progression.coerceIn(0.0, 1.0) else 0.0
    val index = floor(value * positionCount + 1e-6).toInt().coerceIn(0, positionCount - 1)
    return index + 1
}

/** 页码文案（定稿二 §1：`第 X 页 / 共 Y 页`）。 */
internal fun readerPageLabel(page: Int, pageCount: Int): String = "第 $page 页 / 共 $pageCount 页"

/** 拖动浮层两行文案：[title] 可空（PDF / CBZ 与 positions 未就绪时没有章节名）。 */
internal data class ReaderScrubOverlay(val title: String?, val detail: String)

/**
 * 拖动浮层文案。
 *
 * EPUB（[percentVisible] = true）：第二行百分比，positions 可用时追加页码；PDF / CBZ：第二行只有页码。
 */
internal fun readerScrubOverlay(
    progression: Double,
    chapterTitle: String?,
    pageCount: Int?,
    percentVisible: Boolean,
): ReaderScrubOverlay {
    val percent = formatProgressionPercent(progression)
    val page =
        pageCount
            ?.takeIf { it > 0 }
            ?.let { readerPageLabel(readerPositionPage(progression, it), it) }
    val detail =
        when {
            percentVisible && page != null -> "$percent · $page"
            percentVisible -> percent
            page != null -> page
            else -> percent
        }
    return ReaderScrubOverlay(
        title = chapterTitle?.trim()?.takeIf { it.isNotEmpty() },
        detail = detail,
    )
}

/** 点按是否命中书签点：返回最近的书签下标（偏差 ≤ [tolerance]，0–1 轨道比例）。 */
internal fun readerBookmarkHit(
    progressions: List<Float>,
    position: Float,
    tolerance: Float,
): Int? {
    if (!position.isFinite() || !tolerance.isFinite() || tolerance < 0f) return null
    var best: Int? = null
    var bestDistance = Float.MAX_VALUE
    progressions.forEachIndexed { index, progression ->
        if (!progression.isFinite()) return@forEachIndexed
        val distance = abs(progression - position)
        if (distance <= tolerance && distance < bestDistance) {
            best = index
            bestDistance = distance
        }
    }
    return best
}

/** 当前位置是否已有书签（按 progression 近似匹配）；返回命中下标。 */
internal fun readerBookmarkIndexAt(progressions: List<Double>, current: Double): Int? {
    if (!current.isFinite()) return null
    progressions.forEachIndexed { index, progression ->
        if (!progression.isFinite()) return@forEachIndexed
        if (abs(progression - current) < READER_PROGRESS_BOOKMARK_EPSILON) return index
    }
    return null
}

/** 进度条抬手动作：点中书签 → 跳书签；否则提交目标位置。拖动过程只走预览，不产生本动作。 */
internal sealed interface ReaderScrubRelease {
    data class JumpTo(val fraction: Float) : ReaderScrubRelease

    data class Bookmark(val index: Int) : ReaderScrubRelease
}

/** 抬手判定（节流语义的唯一出口）：**一次手势只在抬手时产生一个动作**，拖动中只更新预览。 */
internal fun readerScrubRelease(
    fraction: Float,
    dragging: Boolean,
    bookmarkProgressions: List<Float>,
    tolerance: Float,
): ReaderScrubRelease {
    if (dragging) return ReaderScrubRelease.JumpTo(fraction.coerceIn(0f, 1f))
    val hit = readerBookmarkHit(bookmarkProgressions, fraction, tolerance)
    return if (hit != null) ReaderScrubRelease.Bookmark(hit)
    else ReaderScrubRelease.JumpTo(fraction.coerceIn(0f, 1f))
}

/**
 * 轨道本体：只负责画 + 收手势，拖动预览上抛给 [ReaderControlLayer]（浮层要画在底片容器之外，避免被圆角裁掉）。
 *
 * - 拖动中：每帧回调 [onScrubPreview]；松手回调 [onScrubCommit] 一次（只跳一次）。
 * - 点按：命中书签点 → [onBookmarkTap]；否则等同「拖动到该位置并松手」。
 */
@Composable
internal fun ReaderProgressBar(
    progression: Float,
    chapterTicks: List<Float>,
    bookmarkProgressions: List<Float>,
    accent: Color,
    contentColor: Color,
    scrubFraction: Float?,
    onScrubPreview: (Float?) -> Unit,
    onScrubCommit: (Float) -> Unit,
    onBookmarkTap: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val bookmarkTolerance = with(density) { READER_PROGRESS_BOOKMARK_TOUCH_RADIUS.toPx() }
    val current = (scrubFraction ?: progression).let { if (it.isFinite()) it else 0f }
    val latestPreview = rememberUpdatedState(onScrubPreview)
    val latestCommit = rememberUpdatedState(onScrubCommit)
    val latestBookmarkTap = rememberUpdatedState(onBookmarkTap)
    val thumbRadiusPx = with(density) { READER_PROGRESS_THUMB_DIAMETER.toPx() / 2f }

    Canvas(
        modifier =
            modifier.fillMaxWidth().height(READER_PROGRESS_TOUCH_HEIGHT).pointerInput(
                chapterTicks,
                bookmarkProgressions,
                bookmarkTolerance,
            ) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val width = size.width.toFloat()
                    if (width <= 0f) return@awaitEachGesture
                    // thumb 圆心在 [thumbRadius, width - thumbRadius] 内移动，与绘制保持同一映射。
                    fun fractionAt(x: Float): Float {
                        val span = (width - thumbRadiusPx * 2f).coerceAtLeast(1f)
                        return ((x - thumbRadiusPx) / span).coerceIn(0f, 1f)
                    }
                    val slop = viewConfiguration.touchSlop
                    var fraction = fractionAt(down.position.x)
                    var dragging = false
                    down.consume()
                    latestPreview.value(fraction)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.pressed) {
                            if (!dragging && abs(change.position.x - down.position.x) > slop) {
                                dragging = true
                            }
                            if (dragging) {
                                fraction = fractionAt(change.position.x)
                                latestPreview.value(fraction)
                                change.consume()
                            }
                        } else {
                            latestPreview.value(null)
                            val tolerance = bookmarkTolerance / width
                            when (
                                val release =
                                    readerScrubRelease(
                                        fraction = fraction,
                                        dragging = dragging,
                                        bookmarkProgressions = bookmarkProgressions,
                                        tolerance = tolerance,
                                    )
                            ) {
                                is ReaderScrubRelease.JumpTo -> latestCommit.value(release.fraction)

                                is ReaderScrubRelease.Bookmark ->
                                    latestBookmarkTap.value(release.index)
                            }
                            break
                        }
                    }
                }
            }
    ) {
        val trackHeightPx = READER_PROGRESS_TRACK_HEIGHT.toPx()
        val trackY = size.height / 2f
        val thumbRadius = READER_PROGRESS_THUMB_DIAMETER.toPx() / 2f
        val span = (size.width - thumbRadius * 2f).coerceAtLeast(1f)
        fun xFor(fraction: Float): Float = thumbRadius + fraction.coerceIn(0f, 1f) * span
        val trackColor = contentColor.copy(alpha = 0.16f)
        drawRoundRect(
            color = trackColor,
            topLeft = Offset(thumbRadius, trackY - trackHeightPx / 2f),
            size = Size(span, trackHeightPx),
            cornerRadius = CornerRadius(trackHeightPx / 2f),
        )
        drawRoundRect(
            color = accent,
            topLeft = Offset(thumbRadius, trackY - trackHeightPx / 2f),
            size = Size(span * current.coerceIn(0f, 1f), trackHeightPx),
            cornerRadius = CornerRadius(trackHeightPx / 2f),
        )
        val tickWidth = READER_PROGRESS_TICK_WIDTH.toPx()
        val tickHeight = READER_PROGRESS_TICK_HEIGHT.toPx()
        chapterTicks.forEach { tick ->
            val x = xFor(tick)
            drawRect(
                color = contentColor.copy(alpha = 0.36f),
                topLeft = Offset(x - tickWidth / 2f, trackY - tickHeight / 2f),
                size = Size(tickWidth, tickHeight),
            )
        }
        val bookmarkRadius = READER_PROGRESS_BOOKMARK_DIAMETER.toPx() / 2f
        bookmarkProgressions.forEach { bookmark ->
            drawCircle(
                color = accent,
                radius = bookmarkRadius,
                center = Offset(xFor(bookmark), trackY),
            )
        }
        drawCircle(
            color = accent,
            radius = thumbRadius,
            center = Offset(xFor(current), trackY),
        )
    }
}

/**
 * 拖动 / 聚焦浮层：进度条上方 8dp 的胶囊（圆角 16，容器同控制层底片）。
 *
 * 第一行章节名（单行省略），第二行百分比 / 页码；PDF / CBZ 只有第二行页码。
 */
@Composable
internal fun ReaderProgressOverlay(
    progression: Float,
    chapters: List<ReaderTocEntry>,
    pageCount: Int?,
    percentVisible: Boolean,
    containerColor: Color,
    contentColor: Color,
    borderColor: Color,
    modifier: Modifier = Modifier,
) {
    val value = if (progression.isFinite()) progression.toDouble() else 0.0
    val overlay =
        readerScrubOverlay(
            progression = value,
            chapterTitle = readerChapterTitleAt(chapters, value),
            pageCount = pageCount,
            percentVisible = percentVisible,
        )
    val shape = RoundedCornerShape(CinefinSpacing.Space4)
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier =
                Modifier.clip(shape)
                    .background(containerColor)
                    .border(1.dp, borderColor, shape)
                    .padding(
                        horizontal = CinefinSpacing.Space4,
                        vertical = CinefinSpacing.Space2,
                    ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            overlay.title?.let { title ->
                Text(
                    text = title,
                    style = CinefinType.LabelLarge,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = overlay.detail,
                style = CinefinType.BodyMedium,
                color = contentColor,
            )
        }
    }
}
