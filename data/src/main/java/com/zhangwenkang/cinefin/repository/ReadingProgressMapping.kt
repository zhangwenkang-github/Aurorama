package com.zhangwenkang.cinefin.repository

import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * 书籍通常没有 `RunTimeTicks`，这里给 UserData 一个稳定的合成时间轴， 让 `PlaybackPositionTicks` 与 `PlayedPercentage`
 * 保持单调一致。
 */
const val DEFAULT_BOOK_TIMELINE_TICKS = 10_000_000_000L

/** 进度近似相等阈值：约一本书的 0.05%，小于一次翻页的位移。 */
const val PROGRESSION_MATCH_EPSILON = 0.0005

fun normalizedProgression(progression: Double?): Double = (progression ?: 0.0).coerceIn(0.0, 1.0)

fun progressionsMatch(
    first: Double,
    second: Double,
    epsilon: Double = PROGRESSION_MATCH_EPSILON,
): Boolean = abs(normalizedProgression(first) - normalizedProgression(second)) <= epsilon

fun progressionToTicks(progression: Double, runtimeTicks: Long = 0L): Long {
    val timeline = if (runtimeTicks > 0) runtimeTicks else DEFAULT_BOOK_TIMELINE_TICKS
    return (normalizedProgression(progression) * timeline).roundToLong()
}

/**
 * 合并本地与服务器进度：最近时间戳优先（多设备冲突策略，ARCHITECTURE §3.6）。
 *
 * 本地 `locatorJson` 只在**与服务端胜出进度一致**时才保留：本机自己回传的进度（`LastPlayedDate` 略晚于本地
 * `updatedAt`）要能精确回到原位置，而另一台设备读到的新位置不能继续套用本机的旧 locator， 否则会跳回旧章节（由调用方改用 `progression` 定位）。
 */
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

    val progression = normalizedProgression(winner.progression)
    val runtimeTicks = maxOf(local?.runtimeTicks ?: 0L, remote?.runtimeTicks ?: 0L)
    val keepLocalLocator =
        local != null &&
            local.locatorJson.isNotBlank() &&
            progressionsMatch(local.progression, progression)

    return winner.copy(
        itemId = itemId,
        locatorJson = if (keepLocalLocator) local.locatorJson else "",
        progression = progression,
        positionTicks =
            if (runtimeTicks > 0) progressionToTicks(progression, runtimeTicks)
            else winner.positionTicks,
        runtimeTicks = runtimeTicks,
    )
}
