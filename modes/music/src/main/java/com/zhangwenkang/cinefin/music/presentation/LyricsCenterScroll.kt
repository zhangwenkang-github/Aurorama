package com.zhangwenkang.cinefin.music.presentation

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

internal const val LYRICS_CENTER_SCROLL_DURATION_MS = 240

/**
 * 当前歌词行相对滚动视口中心的位移（内容坐标系）。
 *
 * [LazyListState.layoutInfo] 的 item offset 与 viewport offset 同为内容坐标；不能把 item offset 和仅由 viewport
 * 高度算出的屏幕距离混用。
 */
internal fun lyricsCenterScrollDelta(
    viewportStart: Int,
    viewportEnd: Int,
    itemOffset: Int,
    itemSize: Int,
): Float {
    val viewportCenter = (viewportStart + viewportEnd) / 2f
    val itemCenter = itemOffset + itemSize / 2f
    return itemCenter - viewportCenter
}

/** 目标行尚未布局时，`animateScrollToItem` 让行中心落在视口中心所需的 offset。 */
internal fun lyricsCenteredItemScrollOffset(viewportHeight: Int, itemHeight: Int): Int {
    if (viewportHeight <= 0 || itemHeight <= 0) return 0
    return -((viewportHeight - itemHeight) / 2f).roundToInt()
}

/**
 * 歌词页当前行跟随（W84）：目标已布局时按实际位移做一次 240ms 缓动；尚未布局时一次 `animateScrollToItem` 定位到中心。
 *
 * 不再「先滚到目标顶部、再回中」，旧动画会随新 activeIndex 的 effect 取消，不会排队堆积。
 */
internal suspend fun LazyListState.animateToCenteredItem(
    target: Int,
    estimatedItemHeight: Int? = null,
) {
    if (target < 0) return
    withFrameNanos {}
    val layout = layoutInfo
    val visible = layout.visibleItemsInfo.firstOrNull { item -> item.index == target }
    if (visible != null) {
        val delta =
            lyricsCenterScrollDelta(
                viewportStart = layout.viewportStartOffset,
                viewportEnd = layout.viewportEndOffset,
                itemOffset = visible.offset,
                itemSize = visible.size,
            )
        if (delta != 0f) {
            animateScrollBy(
                delta,
                animationSpec =
                    tween(
                        durationMillis = LYRICS_CENTER_SCROLL_DURATION_MS,
                        easing = FastOutSlowInEasing,
                    ),
            )
        }
        return
    }
    val viewportHeight = layout.viewportEndOffset - layout.viewportStartOffset
    val fallbackHeight =
        layout.visibleItemsInfo
            .map { item -> item.size }
            .average()
            .takeIf { size -> size > 0f }
            ?.roundToInt() ?: 1
    val itemHeight = estimatedItemHeight?.takeIf { it > 0 } ?: fallbackHeight
    animateScrollToItem(
        index = target,
        scrollOffset = lyricsCenteredItemScrollOffset(viewportHeight, itemHeight),
    )
}

/**
 * 把 [target] 行滚到视口正中。
 *
 * 先 `animateScrollToItem` 把目标行带进视口，等一帧让字号 / 行距变化后的新高度完成布局，再读取实际 item offset / size 做一次内容坐标微调。
 */
internal suspend fun LazyListState.centerItem(target: Int) {
    if (target < 0) return
    animateScrollToItem(target)
    withFrameNanos {}
    val info = snapshotFlow {
        layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }
    }
        .filterNotNull()
        .first()
    val layoutInfo = layoutInfo
    val delta =
        lyricsCenterScrollDelta(
            viewportStart = layoutInfo.viewportStartOffset,
            viewportEnd = layoutInfo.viewportEndOffset,
            itemOffset = info.offset,
            itemSize = info.size,
        )
    if (delta != 0f) animateScrollBy(delta)
}
