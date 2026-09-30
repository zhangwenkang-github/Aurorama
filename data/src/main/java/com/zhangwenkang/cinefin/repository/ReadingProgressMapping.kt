package com.zhangwenkang.cinefin.repository

import java.util.UUID
import kotlin.math.roundToLong

/**
 * 书籍通常没有 `RunTimeTicks`，这里给 UserData 一个稳定的合成时间轴， 让 `PlaybackPositionTicks` 与 `PlayedPercentage`
 * 保持单调一致。
 */
const val DEFAULT_BOOK_TIMELINE_TICKS = 10_000_000_000L

fun normalizedProgression(progression: Double?): Double = (progression ?: 0.0).coerceIn(0.0, 1.0)

fun progressionToTicks(progression: Double, runtimeTicks: Long = 0L): Long {
    val timeline = if (runtimeTicks > 0) runtimeTicks else DEFAULT_BOOK_TIMELINE_TICKS
    return (normalizedProgression(progression) * timeline).roundToLong()
}

/** 合并本地与服务器进度：最近时间戳优先；本地 locator 始终保留用于精确恢复。 */
fun resolveReadingProgress(
    itemId: UUID,
    local: ReadingProgress?,
    remote: ReadingProgress?,
): ReadingProgress? {
    val winner =
        when {
            local == null -> remote
            remote == null -> local
            local.updatedAt.isAfter(remote.updatedAt) -> local
            else -> remote
        } ?: return null

    return winner.copy(
        itemId = itemId,
        locatorJson = local?.locatorJson.orEmpty(),
        progression = normalizedProgression(winner.progression),
    )
}
