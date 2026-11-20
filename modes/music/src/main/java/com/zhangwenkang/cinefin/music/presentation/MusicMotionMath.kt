package com.zhangwenkang.cinefin.music.presentation

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * W5-T3（MUSIC-21/38~46）动效参数与几何数学（纯函数，单测覆盖）。
 *
 * 参数逐字取自 `docs/V2_W5_T3_TASKS.md`「设计定稿二」§3–§8，不在 UI 层散落魔法数；Canvas 绘制、手势判定与气泡文案都从这里取值，
 * 单测钉死相位、高斯隆起、阈值与文案，避免动效「看着差不多」就漂移。
 */

/** 播放 / 暂停脉冲的压缩目标：容器 scale 1 → 0.92 → 回弹 1（定稿二 §3）。 */
internal const val MusicPlayPulseScale = 0.92f

/** 波浪进度条振幅（dp，定稿二 §4）。 */
internal const val MusicWaveAmplitudeDp = 3f

/** 波浪波长（dp，定稿二 §4）。 */
internal const val MusicWaveWavelengthDp = 28f

/** 相位速度：1.5 周 / 秒，流向右侧（定稿二 §4）。 */
internal const val MusicWaveCyclesPerSecond = 1.5f

/** 拖拽隆起的半径（dp，定稿二 §4）：半径外权重归零。 */
internal const val MusicWaveBulgeRadiusDp = 48f

/** 隆起中心的额外振幅（dp）：wave = amplitude + 该值 × 高斯权重。 */
internal const val MusicWaveBulgeAmplitudeDp = 3f

/** 拖拽时拇指弹性放大目标（定稿二 §4：1 → 1.35）。 */
internal const val MusicWaveThumbDragScale = 1.35f

/** 拖拽气泡相对拇指顶端的间距（dp，定稿二 §4）。 */
internal const val MusicSeekBubbleGapDp = 12f

/** 切歌封面交叉淡化时长（ms，定稿二 §5）。 */
internal const val MusicCoverCrossfadeMillis = 250

/** 切歌封面从反侧滑入的位移（dp，定稿二 §5：14dp）。 */
internal const val MusicCoverSlideDp = 14f

/** 切歌曲名 / 艺术家淡入时长（ms，定稿二 §5）。 */
internal const val MusicTitleFadeMillis = 150

/** 切歌曲名 / 艺术家垂直滑入位移（dp，定稿二 §5：8dp）。 */
internal const val MusicTitleSlideDp = 8f

/** 容器变换：全屏页展开初始缩放（定稿二 §2：0.94 → 1，SpringExpand）。 */
internal const val MusicFullScreenExpandScale = 0.94f

/** 容器变换：迷你条重现初始缩放（定稿二 §2：0.96，SpringCollapse）。 */
internal const val MusicMiniBarReappearScale = 0.96f

/** 容器变换：展开时迷你条淡出时长（ms，定稿二 §2：150）。 */
internal const val MusicMiniBarExpandFadeOutMillis = 150

/** 队列左滑移除的撤销 snackbar 时长（ms，定稿二 §8：3s）。 */
internal const val MusicQueueSwipeUndoMillis = 3_000L

/** 松手后等待真实播放位置追平拖拽落点的容忍度（占总时长比例）。 */
internal const val MusicSeekSettleTolerance = 0.02f

/** 松手后等待真实位置追平的上限（ms）：超时直接 SpringSnap 回实际位。 */
internal const val MusicSeekSettleTimeoutMillis = 1_500L

/** 下拉收起阈值：拖幅 = 屏高 18%（定稿二 §7）。 */
internal const val MusicPullDismissFraction = 0.18f

/** 下拉收起的下甩速度阈值（dp/s，定稿二 §7）。 */
internal const val MusicPullDismissVelocityDpPerSec = 1200f

/** 下拉拖动全程封面缩放下限（1 → 0.96，定稿二 §7）。 */
internal const val MusicPullCoverMinScale = 0.96f

/** 封面圆角起点：`CinefinShapes.Card` = 20dp（定稿二 §7：Card → 32 线性插值）。 */
internal const val MusicCoverCornerDp = 20f

/** 下拉到底时的封面圆角（dp，定稿二 §7）。 */
internal const val MusicPullCoverMaxCornerDp = 32f

/** 队列拖拽抬升 scale（定稿二 §8）。 */
internal const val MusicQueueLiftScale = 1.02f

/** 队列拖拽抬升的等效阴影高度（dp，定稿二 §8）。 */
internal const val MusicQueueLiftElevationDp = 6f

/** 无限循环波形的一个周期：1 / 1.5 周每秒 ≈ 667ms（`rememberInfiniteTransition` 直接吃这个值）。 */
internal fun musicWavePeriodMillis(): Int = (1000f / MusicWaveCyclesPerSecond).roundToInt()

/**
 * 拖拽隆起的截断高斯权重（0–1）：中心 1、半径处恰好 0，半径外 0。
 *
 * 取 σ = 半径 / 2 再把半径处的尾巴整体平移到 0 并归一化，保证权重连续、单调、有限支撑（直接截断高斯会在半径处留下 ~0.135 的跳变）。
 */
internal fun musicWaveBulgeWeight(distanceDp: Float): Float {
    val distance = abs(distanceDp)
    if (distance >= MusicWaveBulgeRadiusDp) return 0f
    val sigma = MusicWaveBulgeRadiusDp / 2f
    val tail = exp(-(MusicWaveBulgeRadiusDp * MusicWaveBulgeRadiusDp) / (2f * sigma * sigma))
    val raw = exp(-(distance * distance) / (2f * sigma * sigma))
    return ((raw - tail) / (1f - tail)).coerceIn(0f, 1f)
}

/**
 * 正弦波的纵向偏移（dp）：`振幅 × sin(2π(距离 / 波长 − 相位))`。
 *
 * 相位递增 → 波峰右移（流向右侧）。[bulgeCenterDp] 非空时按高斯权重局部抬高振幅（拖拽跟随手指）；振幅为 0（暂停压平 / 减少动态）时整条恒为 0。
 */
internal fun musicWaveOffsetDp(
    distanceDp: Float,
    phaseCycles: Float,
    amplitudeDp: Float,
    bulgeCenterDp: Float? = null,
): Float {
    if (amplitudeDp <= 0f) return 0f
    val bulge =
        bulgeCenterDp?.let { center ->
            musicWaveBulgeWeight(distanceDp - center) * MusicWaveBulgeAmplitudeDp
        } ?: 0f
    val angle = 2.0 * Math.PI * (distanceDp / MusicWaveWavelengthDp - phaseCycles)
    return ((amplitudeDp + bulge) * sin(angle)).toFloat()
}

/** 触控 x → 进度比例：拇指圆心到右端之间线性映射，越界夹住。 */
internal fun musicSeekFraction(xPx: Float, startX: Float, endX: Float): Float =
    if (endX <= startX) 0f else ((xPx - startX) / (endX - startX)).coerceIn(0f, 1f)

/** 进度比例 → 拇指圆心 x（两端各留一个拇指半径）。 */
internal fun musicThumbX(widthPx: Float, thumbRadiusPx: Float, fraction: Float): Float {
    val startX = thumbRadiusPx
    val endX = widthPx - thumbRadiusPx
    if (endX <= startX) return widthPx / 2f
    return startX + (endX - startX) * fraction.coerceIn(0f, 1f)
}

/** 拖拽气泡文案：`正在跳转到 mm:ss`（定稿二 §4）。 */
internal fun musicSeekBubbleText(positionMs: Long): String =
    "正在跳转到 ${formatPositionMs(positionMs.coerceAtLeast(0L))}"

/** 松手后真实播放位置是否已追平拖拽落点（追平才把拇指 `SpringSnap` 回实际位）。 */
internal fun musicSeekSettleReached(
    targetFraction: Float,
    actualFraction: Float,
    tolerance: Float = MusicSeekSettleTolerance,
): Boolean = abs(targetFraction - actualFraction) <= tolerance

/** 下拉进度 0–1：拖幅 / (屏高 × 18%)，夹到 0–1。 */
internal fun musicPullProgress(dragYPx: Float, screenHeightPx: Float): Float {
    val thresholdPx = screenHeightPx * MusicPullDismissFraction
    if (thresholdPx <= 0f || dragYPx <= 0f) return 0f
    return (dragYPx / thresholdPx).coerceIn(0f, 1f)
}

/** 松手判定：拖幅 ≥ 屏高 18% **或** 向下速度 ≥ 1200dp/s 即收起。 */
internal fun musicPullDismissReached(
    dragYPx: Float,
    screenHeightPx: Float,
    velocityDpPerSec: Float,
): Boolean =
    dragYPx >= screenHeightPx * MusicPullDismissFraction ||
        velocityDpPerSec >= MusicPullDismissVelocityDpPerSec

/** 下拉过程中封面缩放：1 → 0.96 线性。 */
internal fun musicPullCoverScale(progress: Float): Float =
    1f - (1f - MusicPullCoverMinScale) * progress.coerceIn(0f, 1f)

/** 下拉过程中封面圆角：Card(20) → 32 线性。 */
internal fun musicPullCoverCornerDp(progress: Float): Float =
    MusicCoverCornerDp +
        (MusicPullCoverMaxCornerDp - MusicCoverCornerDp) * progress.coerceIn(0f, 1f)

/** 队列拖拽落点：行高换算条目差，夹在 0..lastIndex。 */
internal fun musicQueueDropTarget(
    fromIndex: Int,
    dragOffsetPx: Float,
    rowHeightPx: Float,
    lastIndex: Int,
): Int {
    if (rowHeightPx <= 0f || lastIndex < 0) return fromIndex
    val delta = (dragOffsetPx / rowHeightPx).roundToInt()
    return (fromIndex + delta).coerceIn(0, lastIndex)
}

/** 左滑移除判定：位移越过负阈值即移除（未过阈值回位）。 */
internal fun musicQueueSwipeRemoves(offsetPx: Float, thresholdPx: Float): Boolean =
    offsetPx <= -thresholdPx
