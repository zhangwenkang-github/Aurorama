package com.zhangwenkang.cinefin.player.local.domain

/**
 * 音乐播放的纯计算辅助（可 JVM 单测，不依赖 Android 运行时）。
 *
 * 只放"有明确边界语义"的小函数：起播索引归一化与播放上报换算，避免上报把越界值 / 非法百分比 发给服务器。
 */
internal fun normalizeStartIndex(index: Int, size: Int): Int {
    if (size <= 0) return 0
    return index.coerceIn(0, size - 1)
}

/** 毫秒 → Jellyfin 的 positionTicks（1 tick = 100 ns）。 */
internal fun playbackPositionTicks(positionMs: Long): Long = positionMs.coerceAtLeast(0L) * 10_000L

/** 播放百分比（0–100）；时长未知（≤0）时返回 0，避免除零。 */
internal fun playbackPercentage(positionMs: Long, durationMs: Long): Int {
    if (durationMs <= 0L) return 0
    val safePosition = positionMs.coerceAtLeast(0L)
    return ((safePosition * 100L) / durationMs).toInt().coerceIn(0, 100)
}
