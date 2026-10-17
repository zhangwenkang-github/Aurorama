package com.zhangwenkang.cinefin.music.presentation

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

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
