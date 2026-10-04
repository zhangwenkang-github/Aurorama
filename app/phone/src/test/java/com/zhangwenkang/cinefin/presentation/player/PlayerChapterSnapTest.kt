package com.zhangwenkang.cinefin.presentation.player

import com.zhangwenkang.cinefin.player.core.domain.models.PlayerChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** W67 章节吸附纯函数测试：阈值（±2 s 进入）/ 滞回（±3 s 退出）/ 边界（首章 0 s、末章、无章节、拖到 0 与结束、 超大 duration）全覆盖。 */
class PlayerChapterSnapTest {

    private fun chapterAt(positionMs: Long, name: String? = null) =
        PlayerChapter(startPosition = positionMs, name = name)

    private fun resolve(
        positionMs: Long,
        chapters: List<PlayerChapter>,
        current: Int = CHAPTER_SNAP_NONE,
    ) = resolveChapterSnap(positionMs, chapters, current)

    // ---------- 无章节 ----------

    @Test
    fun emptyChapters_neverSnap() {
        val result = resolve(12_345L, emptyList())
        assertEquals("无章节时不吸附", CHAPTER_SNAP_NONE, result.chapterIndex)
        assertEquals("无章节时位置原样返回（纯比例拖动）", 12_345L, result.positionMs)
    }

    // ---------- 进入阈值 ±2 s ----------

    @Test
    fun entersSnap_withinTwoSeconds() {
        val chapters = listOf(chapterAt(60_000L))
        assertEquals(
            "起点前 1.5 s 进入吸附",
            ChapterSnap(0, 60_000L),
            resolve(58_500L, chapters),
        )
        assertEquals(
            "起点后 1.5 s 进入吸附",
            ChapterSnap(0, 60_000L),
            resolve(61_500L, chapters),
        )
    }

    @Test
    fun entersSnap_atExactTwoSecondBoundary() {
        val chapters = listOf(chapterAt(60_000L))
        assertEquals("±2.000 s 含端点", ChapterSnap(0, 60_000L), resolve(62_000L, chapters))
        assertEquals("±2.000 s 含端点（前侧）", ChapterSnap(0, 60_000L), resolve(58_000L, chapters))
    }

    @Test
    fun outsideTwoSeconds_doesNotSnap() {
        val chapters = listOf(chapterAt(60_000L))
        assertEquals("2.001 s 外不吸附", CHAPTER_SNAP_NONE, resolve(62_001L, chapters).chapterIndex)
        assertEquals("2.001 s 外不吸附（前侧）", CHAPTER_SNAP_NONE, resolve(57_999L, chapters).chapterIndex)
        assertEquals("不吸附时落点 = 原始位置", 62_001L, resolve(62_001L, chapters).positionMs)
    }

    @Test
    fun nearestChapterWins() {
        val chapters = listOf(chapterAt(10_000L), chapterAt(11_000L))
        assertEquals("10.9 s 离 11 s 更近", ChapterSnap(1, 11_000L), resolve(10_900L, chapters))
        assertEquals("10.1 s 离 10 s 更近", ChapterSnap(0, 10_000L), resolve(10_100L, chapters))
    }

    // ---------- 滞回 ±3 s ----------

    @Test
    fun hysteresis_holdsWithinThreeSecondsAfterSnap() {
        val chapters = listOf(chapterAt(60_000L))
        assertEquals("已吸附，+2.999 s 仍保持", ChapterSnap(0, 60_000L), resolve(62_999L, chapters, 0))
        assertEquals("已吸附，-2.999 s 仍保持", ChapterSnap(0, 60_000L), resolve(57_001L, chapters, 0))
    }

    @Test
    fun hysteresis_releasesBeyondThreeSeconds() {
        val chapters = listOf(chapterAt(60_000L))
        assertEquals("+3.001 s 解除吸附", CHAPTER_SNAP_NONE, resolve(63_001L, chapters, 0).chapterIndex)
        assertEquals("-3.001 s 解除吸附", CHAPTER_SNAP_NONE, resolve(56_999L, chapters, 0).chapterIndex)
        assertEquals("解除后落点 = 当前手指位置", 56_999L, resolve(56_999L, chapters, 0).positionMs)
    }

    @Test
    fun hysteresis_releaseZoneDoesNotResnap() {
        val chapters = listOf(chapterAt(60_000L))
        // 解除吸附后停在 +2.5 s：在 ±3 s 内但超出 ±2 s 进入阈值 → 不再重新吸附，可自由微调
        assertEquals(CHAPTER_SNAP_NONE, resolve(62_500L, chapters, CHAPTER_SNAP_NONE).chapterIndex)
    }

    @Test
    fun hysteresis_resnapsWhenBackInsideEnterThreshold() {
        val chapters = listOf(chapterAt(60_000L))
        // 微调后回到 ±2 s 内 → 重新吸附
        assertEquals(ChapterSnap(0, 60_000L), resolve(61_800L, chapters, CHAPTER_SNAP_NONE))
    }

    @Test
    fun hysteresis_staleIndexBeyondListBounds_fallsBackToEnterThreshold() {
        val chapters = listOf(chapterAt(60_000L))
        // 换集 / 章节列表变短后旧序号失效：不 crash，按进入阈值重新判定
        assertEquals(ChapterSnap(0, 60_000L), resolve(60_500L, chapters, 7))
        assertEquals(CHAPTER_SNAP_NONE, resolve(64_000L, chapters, 7).chapterIndex)
    }

    // ---------- 边界：首章 / 末章 / 0 与结束 ----------

    @Test
    fun firstChapterAtZero_snapsAtZero() {
        val chapters = listOf(chapterAt(0L), chapterAt(90_000L))
        assertEquals("拖到 0 且首章在 0 s → 吸附", ChapterSnap(0, 0L), resolve(0L, chapters))
        assertEquals("0 附近 2 s 内 → 吸附", ChapterSnap(0, 0L), resolve(1_500L, chapters))
        assertEquals("负值兜底到 0 → 吸附首章", ChapterSnap(0, 0L), resolve(-120L, chapters))
    }

    @Test
    fun positionAtZero_withoutZeroChapter_doesNotSnap() {
        val chapters = listOf(chapterAt(30_000L))
        assertEquals(CHAPTER_SNAP_NONE, resolve(0L, chapters).chapterIndex)
        assertEquals(0L, resolve(0L, chapters).positionMs)
    }

    @Test
    fun lastChapterNearEnd_snaps() {
        val chapters = listOf(chapterAt(0L), chapterAt(3_599_000L))
        assertEquals(
            "拖到视频结束、末章在 ±2 s 内 → 吸附末章",
            ChapterSnap(1, 3_599_000L),
            resolve(3_600_000L, chapters),
        )
    }

    @Test
    fun endPosition_withoutNearbyChapter_stays() {
        val chapters = listOf(chapterAt(0L), chapterAt(10_000L))
        assertEquals("末章离结束太远 → 不吸附", CHAPTER_SNAP_NONE, resolve(3_600_000L, chapters).chapterIndex)
        assertEquals(3_600_000L, resolve(3_600_000L, chapters).positionMs)
    }

    // ---------- 超大 duration / 极端值 ----------

    @Test
    fun hugePositions_doNotOverflow() {
        val chapters = listOf(chapterAt(Long.MAX_VALUE - 1_000L))
        assertEquals(
            "接近 Long.MAX_VALUE 的位置仍能按距离吸附",
            ChapterSnap(0, Long.MAX_VALUE - 1_000L),
            resolve(Long.MAX_VALUE, chapters),
        )
        assertEquals(
            "巨大跨度不误吸附（距离用 Double）",
            CHAPTER_SNAP_NONE,
            resolve(0L, chapters).chapterIndex,
        )
    }

    @Test
    fun tickFraction_clampsIntoZeroOne() {
        assertEquals(0.5f, chapterTickFraction(500L, 1_000L), 0.0001f)
        assertEquals("拖到 0", 0f, chapterTickFraction(0L, 1_000L), 0.0001f)
        assertEquals("拖到结束", 1f, chapterTickFraction(1_000L, 1_000L), 0.0001f)
        assertEquals("超出时长收敛到 1", 1f, chapterTickFraction(2_000L, 1_000L), 0.0001f)
        assertEquals("负起点收敛到 0", 0f, chapterTickFraction(-5L, 1_000L), 0.0001f)
        assertEquals("duration 非法", 0f, chapterTickFraction(500L, 0L), 0.0001f)
        assertEquals(
            "超大 duration 不溢出",
            0.5f,
            chapterTickFraction(Long.MAX_VALUE / 2, Long.MAX_VALUE),
            0.0001f,
        )
    }

    // ---------- 气泡文案素材 ----------

    @Test
    fun label_numbersFromOne() {
        val chapters = listOf(chapterAt(0L), chapterAt(60_000L, "开场"))
        assertEquals(ChapterSnapLabel(1, null), chapterSnapLabel(chapters, 0))
        assertEquals(ChapterSnapLabel(2, "开场"), chapterSnapLabel(chapters, 1))
    }

    @Test
    fun label_treatsBlankNameAsUnnamed() {
        val chapters = listOf(chapterAt(0L, "   "), chapterAt(1_000L, ""))
        assertEquals(ChapterSnapLabel(1, null), chapterSnapLabel(chapters, 0))
        assertEquals(ChapterSnapLabel(2, null), chapterSnapLabel(chapters, 1))
    }

    @Test
    fun label_nullWhenNoChapter() {
        val chapters = listOf(chapterAt(0L))
        assertNull("未吸附（-1）不给气泡", chapterSnapLabel(chapters, CHAPTER_SNAP_NONE))
        assertNull("越界序号不给气泡", chapterSnapLabel(chapters, 3))
        assertNotNull(chapterSnapLabel(chapters, 0))
    }
}
