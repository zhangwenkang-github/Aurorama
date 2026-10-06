package com.zhangwenkang.cinefin.presentation.player

import com.zhangwenkang.cinefin.player.core.domain.models.PlayerChapter
import kotlin.math.abs

/**
 * W67 章节吸附：拖动 / 点击进度条时对章节起点做磁性吸附。
 *
 * W67 原口径是写死的 ±2 s 进入 / ±3 s 退出（滞回：手指停在目标附近时不会被一两个像素的抖动甩出吸附，
 * 超出之后即可自由微调）。W75（#1）用户反馈「感觉不到吸附」，真机复现后改成**按片长比例**取窗口：
 *
 * K60 竖屏进度条 ≈ 1008px、24 分钟片 ≈ 1.5 s/px，写死的 ±2 s 在屏上只有 ≈ ±1.3px —— 手指的落点 精度远达不到，磁吸形同不存在。改为取「片长的
 * 2%（进入）/ 3%（退出）」与时间下限的较大者： 24 分钟片 → ±29 s / ±44 s（屏上 ≈ ±6dp / ±10dp），长片同样可感，短片（< 100 s）仍走 ±2 s /
 * ±3 s 旧口径。
 */
internal const val CHAPTER_SNAP_ENTER_FLOOR_MS = 2_000L
internal const val CHAPTER_SNAP_EXIT_FLOOR_MS = 3_000L
internal const val CHAPTER_SNAP_ENTER_FRACTION = 0.02
internal const val CHAPTER_SNAP_EXIT_FRACTION = 0.03

/** 进入吸附的时间阈值：片长的 [CHAPTER_SNAP_ENTER_FRACTION]，不低于 [CHAPTER_SNAP_ENTER_FLOOR_MS]。 */
internal fun chapterSnapEnterMs(durationMs: Long): Long =
    maxOf(
        CHAPTER_SNAP_ENTER_FLOOR_MS,
        (durationMs.coerceAtLeast(0L) * CHAPTER_SNAP_ENTER_FRACTION).toLong(),
    )

/** 退出吸附的时间阈值（滞回外沿）：片长的 [CHAPTER_SNAP_EXIT_FRACTION]，不低于 [CHAPTER_SNAP_EXIT_FLOOR_MS]。 */
internal fun chapterSnapExitMs(durationMs: Long): Long =
    maxOf(
        CHAPTER_SNAP_EXIT_FLOOR_MS,
        (durationMs.coerceAtLeast(0L) * CHAPTER_SNAP_EXIT_FRACTION).toLong(),
    )

/** 未吸附到任何章节。 */
internal const val CHAPTER_SNAP_NONE = -1

/** 一次吸附判定的结果：命中的章节序号（[CHAPTER_SNAP_NONE] = 未吸附）与吸附后的落点。 */
internal data class ChapterSnap(val chapterIndex: Int, val positionMs: Long)

/**
 * 章节吸附判定（纯函数，无副作用）。
 *
 * - [chapters] 为空（没有章节数据 / 用户关掉章节刻度开关）时原样返回输入位置，等价旧版纯比例拖动；
 * - [currentChapterIndex] 是上一帧吸附到的章节（没有传 [CHAPTER_SNAP_NONE]）：仍在 [exitMs] 内则保持吸附（滞回）， 否则按 [enterMs]
 *   阈值重新寻找最近的一章；
 * - [enterMs] / [exitMs] 由调用方按片长派生（[chapterSnapEnterMs] / [chapterSnapExitMs]），缺省是 W67 的 ±2 s / ±3
 *   s；
 * - 距离用 Double 计算，超大的起始位置 / 时长不会出现 Long 溢出。
 */
internal fun resolveChapterSnap(
    positionMs: Long,
    chapters: List<PlayerChapter>,
    currentChapterIndex: Int = CHAPTER_SNAP_NONE,
    enterMs: Long = CHAPTER_SNAP_ENTER_FLOOR_MS,
    exitMs: Long = CHAPTER_SNAP_EXIT_FLOOR_MS,
): ChapterSnap {
    if (chapters.isEmpty()) return ChapterSnap(CHAPTER_SNAP_NONE, positionMs)
    val position = positionMs.coerceAtLeast(0L)
    // 已吸附：退出阈值内保持，手停在章节附近时不被抖动甩开
    if (currentChapterIndex in chapters.indices) {
        val start = chapters[currentChapterIndex].startPosition.coerceAtLeast(0L)
        if (chapterSnapDistance(position, start) <= exitMs) {
            return ChapterSnap(currentChapterIndex, start)
        }
    }
    // 新吸附：只有最近的一章在进入阈值内才吸附
    var bestIndex = CHAPTER_SNAP_NONE
    var bestDistance = Double.MAX_VALUE
    chapters.forEachIndexed { index, chapter ->
        val start = chapter.startPosition.coerceAtLeast(0L)
        val distance = chapterSnapDistance(position, start)
        if (distance <= enterMs && distance < bestDistance) {
            bestIndex = index
            bestDistance = distance
        }
    }
    return if (bestIndex == CHAPTER_SNAP_NONE) {
        ChapterSnap(CHAPTER_SNAP_NONE, position)
    } else {
        ChapterSnap(bestIndex, chapters[bestIndex].startPosition.coerceAtLeast(0L))
    }
}

/** 两点距离（毫秒），用 Double 避免极端值下的 Long 溢出。 */
internal fun chapterSnapDistance(a: Long, b: Long): Double = abs(a.toDouble() - b.toDouble())

/** 章节气泡的文案素材：序号从 1 计，名称空白视为无名。 */
internal data class ChapterSnapLabel(val number: Int, val name: String?)

internal fun chapterSnapLabel(chapters: List<PlayerChapter>, chapterIndex: Int): ChapterSnapLabel? {
    val chapter = chapters.getOrNull(chapterIndex) ?: return null
    return ChapterSnapLabel(chapterIndex + 1, chapter.name?.takeIf { it.isNotBlank() })
}

/** 章节刻度在进度条上的比例（0..1）；duration 非法 / 超大时收敛，不做除法溢出。 */
internal fun chapterTickFraction(startPositionMs: Long, durationMs: Long): Float {
    if (durationMs <= 0L) return 0f
    return (startPositionMs.toDouble() / durationMs.toDouble()).coerceIn(0.0, 1.0).toFloat()
}
