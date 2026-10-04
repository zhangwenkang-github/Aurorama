package com.zhangwenkang.cinefin.presentation.player

import com.zhangwenkang.cinefin.player.core.domain.models.PlayerChapter
import kotlin.math.abs

/**
 * W67 章节吸附：拖动 / 点击进度条时对章节起点做磁性吸附。
 *
 * 进入吸附的阈值是 ±2 s（[CHAPTER_SNAP_ENTER_MS]），已经吸附后要离开 ±3 s（[CHAPTER_SNAP_EXIT_MS]）才解除——
 * 滞回区间让手指停在目标附近时不会被一两个像素的抖动甩出吸附，超出之后即可自由微调。
 */
internal const val CHAPTER_SNAP_ENTER_MS = 2_000L
internal const val CHAPTER_SNAP_EXIT_MS = 3_000L

/** 未吸附到任何章节。 */
internal const val CHAPTER_SNAP_NONE = -1

/** 一次吸附判定的结果：命中的章节序号（[CHAPTER_SNAP_NONE] = 未吸附）与吸附后的落点。 */
internal data class ChapterSnap(val chapterIndex: Int, val positionMs: Long)

/**
 * 章节吸附判定（纯函数，无副作用）。
 *
 * - [chapters] 为空（没有章节数据 / 用户关掉章节刻度开关）时原样返回输入位置，等价旧版纯比例拖动；
 * - [currentChapterIndex] 是上一帧吸附到的章节（没有传 [CHAPTER_SNAP_NONE]）：仍在该章起点 ±3 s 内则保持吸附（滞回）， 否则按 ±2 s
 *   阈值重新寻找最近的一章；
 * - 距离用 Double 计算，超大的起始位置 / 时长不会出现 Long 溢出。
 */
internal fun resolveChapterSnap(
    positionMs: Long,
    chapters: List<PlayerChapter>,
    currentChapterIndex: Int = CHAPTER_SNAP_NONE,
): ChapterSnap {
    if (chapters.isEmpty()) return ChapterSnap(CHAPTER_SNAP_NONE, positionMs)
    val position = positionMs.coerceAtLeast(0L)
    // 已吸附：±3 s（退出阈值）内保持，手停在章节附近时不被抖动甩开
    if (currentChapterIndex in chapters.indices) {
        val start = chapters[currentChapterIndex].startPosition.coerceAtLeast(0L)
        if (chapterSnapDistance(position, start) <= CHAPTER_SNAP_EXIT_MS) {
            return ChapterSnap(currentChapterIndex, start)
        }
    }
    // 新吸附：只有最近的一章在 ±2 s（进入阈值）内才吸附
    var bestIndex = CHAPTER_SNAP_NONE
    var bestDistance = Double.MAX_VALUE
    chapters.forEachIndexed { index, chapter ->
        val start = chapter.startPosition.coerceAtLeast(0L)
        val distance = chapterSnapDistance(position, start)
        if (distance <= CHAPTER_SNAP_ENTER_MS && distance < bestDistance) {
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
