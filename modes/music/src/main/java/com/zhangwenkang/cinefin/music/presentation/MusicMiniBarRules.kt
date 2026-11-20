package com.zhangwenkang.cinefin.music.presentation

import com.zhangwenkang.cinefin.player.core.domain.models.MusicQueue
import kotlin.math.abs

/**
 * W56：迷你播放条「关闭面板」（×）的落点判定（纯函数，单测覆盖）。
 *
 * 用户 2026-10-04 拍板：行为 = 停止播放并收起面板；队列保留（[MusicQueuePersister] 的存档只写不清），收起后再次选择曲目即可恢复播放。
 */
internal enum class MusicMiniBarDismissTarget {
    /** 没有可关闭的会话（理论上面板此时不可见）。 */
    None,
    /** 活动会话：停止播放（清内存队列；磁盘存档保留）。 */
    StopPlayback,
    /** 恢复态（只有内存快照、没有播放会话）：只丢弃本次进页面的展示快照，磁盘存档保留。 */
    DropRestoredQueue,
}

internal fun musicMiniBarDismissTarget(
    hasLiveQueue: Boolean,
    hasRestoredQueue: Boolean,
): MusicMiniBarDismissTarget =
    when {
        hasLiveQueue -> MusicMiniBarDismissTarget.StopPlayback
        hasRestoredQueue -> MusicMiniBarDismissTarget.DropRestoredQueue
        else -> MusicMiniBarDismissTarget.None
    }

/**
 * W5-T2（MUSIC-23）：迷你条手势与进度线的阈值（纯函数，单测覆盖）。
 *
 * 设计定稿一 §2：左滑下一首 / 右滑上一首需「水平位移 ≥ 56dp **且** 同向水平速度 ≥ 800dp/s」；上滑展开需「位移 ≥48dp 或 向上速度 ≥600dp/s」；左右各
 * 16dp 是系统边缘返回手势安全区，不响应。
 */
internal const val MusicMiniBarSwipeDistanceDp = 56f
internal const val MusicMiniBarSwipeVelocityDpPerSec = 800f
internal const val MusicMiniBarExpandDistanceDp = 48f
internal const val MusicMiniBarExpandVelocityDpPerSec = 600f
internal const val MusicMiniBarEdgeGuardDp = 16f

/** 左右滑切歌方向（[Previous] = 右滑，[Next] = 左滑）。 */
internal enum class MusicMiniBarSwipe {
    Previous,
    Next,
}

/** 位移与速度同向且都过阈值才切歌；否则返回 null（松手 `SpringSnap` 回位）。 */
internal fun musicMiniBarSwipeTarget(
    displacementDp: Float,
    velocityDpPerSec: Float,
): MusicMiniBarSwipe? {
    if (abs(displacementDp) < MusicMiniBarSwipeDistanceDp) return null
    if (abs(velocityDpPerSec) < MusicMiniBarSwipeVelocityDpPerSec) return null
    if (displacementDp * velocityDpPerSec <= 0f) return null
    return if (displacementDp > 0f) MusicMiniBarSwipe.Previous else MusicMiniBarSwipe.Next
}

/** 上滑展开：向上位移 ≥48dp **或** 向上速度 ≥600dp/s（屏幕坐标向上为负）。 */
internal fun musicMiniBarExpands(dragYDp: Float, velocityYDpPerSec: Float): Boolean =
    dragYDp <= -MusicMiniBarExpandDistanceDp ||
        velocityYDpPerSec <= -MusicMiniBarExpandVelocityDpPerSec

/** 手势起点落在左右各 16dp 内 = 系统边缘返回安全区，迷你条不响应。 */
internal fun musicMiniBarEdgeGuardHit(startXDp: Float, widthDp: Float): Boolean =
    startXDp < MusicMiniBarEdgeGuardDp || startXDp > widthDp - MusicMiniBarEdgeGuardDp

/** 极细进度线填充比例：总时长未知或非正时 0，其余夹到 0–1。 */
internal fun musicMiniBarProgressFraction(currentMs: Long, totalMs: Long): Float =
    if (totalMs <= 0L) 0f else (currentMs.toFloat() / totalMs).coerceIn(0f, 1f)

/**
 * 滑切歌时从左侧探入的邻曲封面 = 队列顺序里的前一条。
 *
 * 随机播放时实际「上一首」由播放历史决定（见 `skipToPrevious`），这里只服务探入动画的视觉取材，不承诺落点。
 */
internal fun MusicQueue.previousArtworkUri(): String? =
    items.getOrNull(currentIndex - 1)?.thumbnailUri

/** 滑切歌时从右侧探入的邻曲封面 = 队列顺序里的后一条（同上：只是视觉取材）。 */
internal fun MusicQueue.nextArtworkUri(): String? = items.getOrNull(currentIndex + 1)?.thumbnailUri

/**
 * 迷你条时间文本（纯函数，单测覆盖）——`02:31 / 04:56`；总时长未知（0）时回 `--:--`。
 *
 * W5-T2 起迷你条不再单独占位显示时间（定稿 §2 组成改为标题 marquee + 艺术家次行，进度由 2dp 进度线承担），本函数供进度线的无障碍描述使用——
 * 「时间必须完整显示」的用户口径由 TalkBack 读出（W66b）与全屏页保留。
 */
internal fun musicMiniBarTimeText(currentMs: Long, totalMs: Long): String = buildString {
    append(formatPositionMs(currentMs))
    append(" / ")
    append(if (totalMs > 0L) formatPositionMs(totalMs) else "--:--")
}
