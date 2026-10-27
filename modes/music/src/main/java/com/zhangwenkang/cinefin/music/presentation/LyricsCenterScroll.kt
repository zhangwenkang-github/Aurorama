package com.zhangwenkang.cinefin.music.presentation

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

internal const val LYRICS_CENTER_SCROLL_DURATION_MS = 240
internal const val LYRICS_FOLLOW_RESUME_DELAY_MS = 300L

internal enum class LyricsScrollOrigin {
    USER,
    PROGRAMMATIC,
}

/**
 * 歌词页单次可见会话的跟随状态。
 *
 * [manualPause] 只表示本次停留中用户主动滚动；[restoreToken] 在重新进入且全局跟随开启时递增，用于强制恢复一次居中。
 */
internal data class LyricsFollowUiState(
    val manualPause: Boolean = false,
    val restoreToken: Long = 0L,
    val userScrollGeneration: Long = 0L,
    val autoResumeGeneration: Long? = null,
)

internal fun LyricsFollowUiState.onPageEntered(displayFollow: Boolean): LyricsFollowUiState =
    copy(
        manualPause = false,
        restoreToken = if (displayFollow) restoreToken + 1L else restoreToken,
    )

internal fun LyricsFollowUiState.onScrollStarted(origin: LyricsScrollOrigin): LyricsFollowUiState =
    if (origin == LyricsScrollOrigin.USER && !manualPause) {
        copy(
            manualPause = true,
            userScrollGeneration = userScrollGeneration + 1L,
            autoResumeGeneration = null,
        )
    } else {
        this
    }

/**
 * 用户停稳后延迟判定恢复。
 *
 * [elapsedSinceStopMs] 由调用方用真实停稳时钟提供；只有达到 300ms、全局跟随开启、局部已暂停、当前行满足双阈值时才恢复。 [autoResumeGeneration]
 * 保证同一次暂停只恢复一次，后续延迟回调或布局变化不会重复抢回位置。
 */
internal fun LyricsFollowUiState.onUserScrollSettled(
    displayFollow: Boolean,
    activeLineCanResume: Boolean,
    elapsedSinceStopMs: Long,
): LyricsFollowUiState {
    val shouldResume =
        displayFollow &&
            manualPause &&
            activeLineCanResume &&
            elapsedSinceStopMs >= LYRICS_FOLLOW_RESUME_DELAY_MS &&
            autoResumeGeneration != userScrollGeneration
    return if (shouldResume) {
        copy(manualPause = false, autoResumeGeneration = userScrollGeneration)
    } else {
        this
    }
}

internal fun LyricsFollowUiState.onActiveLineChanged(): LyricsFollowUiState = this

/**
 * 当前高亮行至少 60% 可见，且中心位于视口中央 60% 带内时才允许恢复跟随。
 *
 * 所有输入都使用同一内容坐标系，与 [lyricsCenterScrollDelta] 保持一致，因此首行、末行和中间行共用同一判据。
 */
internal fun lyricsLineCanResumeFollow(
    viewportStart: Int,
    viewportEnd: Int,
    itemOffset: Int,
    itemSize: Int,
): Boolean {
    if (itemSize <= 0 || viewportEnd <= viewportStart) return false
    val visibleStart = max(itemOffset, viewportStart)
    val visibleEnd = min(itemOffset + itemSize, viewportEnd)
    val visibleFraction = (visibleEnd - visibleStart).coerceAtLeast(0).toFloat() / itemSize
    val viewportHeight = (viewportEnd - viewportStart).toFloat()
    val centralStart = viewportStart + viewportHeight * 0.2f
    val centralEnd = viewportEnd - viewportHeight * 0.2f
    val itemCenter = itemOffset + itemSize / 2f
    return visibleFraction >= 0.6f && itemCenter in centralStart..centralEnd
}

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
 * 目标行相对首个可见行的内容坐标。
 *
 * 只累加首见行与目标之间的实际行高（由调用方用 `TextMeasurer` 或已布局缓存给出），不再用平均行高乘行数估算； 这样远跳后的 `animateScrollBy`
 * 才能一次落到精确中心。
 */
internal fun lyricsTargetItemOffset(
    firstVisibleIndex: Int,
    firstVisibleOffset: Int,
    targetIndex: Int,
    itemSpacingPx: Int,
    heightOf: (Int) -> Int,
): Int {
    if (targetIndex == firstVisibleIndex) return firstVisibleOffset
    var offset = firstVisibleOffset
    if (targetIndex > firstVisibleIndex) {
        for (index in firstVisibleIndex until targetIndex) {
            offset += heightOf(index).coerceAtLeast(0) + itemSpacingPx
        }
    } else {
        for (index in firstVisibleIndex - 1 downTo targetIndex) {
            offset -= heightOf(index).coerceAtLeast(0) + itemSpacingPx
        }
    }
    return offset
}

/**
 * 歌词页当前行跟随（W84）：目标已布局时按实际位移做一次 240ms 缓动；尚未布局时用真实行高推算目标内容坐标， 同样只做一次 240ms 缓动，不再“先滚到顶部再回中”。
 *
 * [heightOf] 必须返回与 `LazyColumn` 完全一致的行高（含行块 padding / 副行 / 行距）；旧动画会随新 activeIndex 或暂停态的 effect
 * 取消，不会排队堆积。
 */
internal suspend fun LazyListState.animateToCenteredItem(
    target: Int,
    itemSpacingPx: Int,
    heightOf: (Int) -> Int,
) {
    if (target < 0) return
    withFrameNanos {}
    val layout = layoutInfo
    if (target >= layout.totalItemsCount) return
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
    val first = layout.visibleItemsInfo.firstOrNull() ?: return
    val targetHeight = heightOf(target).coerceAtLeast(1)
    val targetOffset =
        lyricsTargetItemOffset(
            firstVisibleIndex = first.index,
            firstVisibleOffset = first.offset,
            targetIndex = target,
            itemSpacingPx = itemSpacingPx,
            heightOf = heightOf,
        )
    val delta =
        lyricsCenterScrollDelta(
            viewportStart = layout.viewportStartOffset,
            viewportEnd = layout.viewportEndOffset,
            itemOffset = targetOffset,
            itemSize = targetHeight,
        )
    if (abs(delta) >= 0.5f) {
        animateScrollBy(
            delta,
            animationSpec =
                tween(
                    durationMillis = LYRICS_CENTER_SCROLL_DURATION_MS,
                    easing = FastOutSlowInEasing,
                ),
        )
    }
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
