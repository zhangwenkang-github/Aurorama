package com.zhangwenkang.cinefin.presentation.player

import com.zhangwenkang.cinefin.player.local.presentation.PlayerViewModel
import com.zhangwenkang.cinefin.player.local.subtitle.SubtitleCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 字幕回归（VIDEO-11 / 12 / 13）：
 * - seek 到另一 cue 区间后，旧 cue 不再参与渲染（自研渲染只按当前位置取 cue，不留上一段文本）；
 * - 延迟两位小数显示能看出 50ms 步进；
 * - 轨道按语言分组且组内内封在前、外挂在后（稳定排序）。
 */
class SubtitlePanelLogicTest {

    private val cues =
        listOf(
            SubtitleCue(startMs = 1_000L, endMs = 3_000L, text = "第一句"),
            SubtitleCue(startMs = 3_000L, endMs = 5_000L, text = "第二句"),
            SubtitleCue(startMs = 10_000L, endMs = 12_000L, text = "第三句"),
        )

    private fun option(
        id: Int,
        language: String,
        origin: PlayerViewModel.SubtitleOrigin,
        graphic: Boolean = false,
    ) =
        PlayerViewModel.SubtitleOption(
            id = id,
            label = "track-$id",
            language = language,
            format = if (graphic) "PGS" else "ASS",
            origin = origin,
            textAssignable = !graphic,
            graphic = graphic,
        )

    // ---------- VIDEO-13：seek 后不残留 ----------

    @Test
    fun `seek 到另一 cue 区间后旧 cue 不再渲染`() {
        assertEquals("第一句", visibleText(cues, 2_000L, delayMs = 0L))

        // 模拟拖到 4.2s：只应看到新区间，旧 cue 不得残留
        val afterSeek = visibleText(cues, 4_200L, delayMs = 0L)
        assertEquals("第二句", afterSeek)
        assertFalse("旧 cue 不得叠在新 cue 上", afterSeek.contains("第一句"))
    }

    @Test
    fun `seek 到 cue 空档不残留上一句`() {
        assertEquals("第二句", visibleText(cues, 4_900L, delayMs = 0L))
        assertEquals("", visibleText(cues, 7_000L, delayMs = 0L))
    }

    @Test
    fun `seek 回退后只显示回退位置命中 的 cue`() {
        val backward = visibleText(cues, 1_500L, delayMs = 0L)
        assertEquals("第一句", backward)
        assertFalse(backward.contains("第二句"))
    }

    @Test
    fun `延迟偏移参与 seek 后的命中判定`() {
        // +500ms：4.2s 时实际取 3.7s 的 cue 区间，仍命中第二句
        assertEquals("第二句", visibleText(cues, 4_200L, delayMs = 500L))
        // +1.5s：4.2s 时取 2.7s，回到第一句
        assertEquals("第一句", visibleText(cues, 4_200L, delayMs = 1_500L))
    }

    // ---------- VIDEO-12：延迟显示 ----------

    @Test
    fun `延迟显示两位小数可区分 50ms`() {
        assertEquals("0.00s", formatSubtitleDelay(0L))
        assertEquals("+0.05s", formatSubtitleDelay(50L))
        assertEquals("−0.05s", formatSubtitleDelay(-50L))
        assertEquals("+0.10s", formatSubtitleDelay(100L))
        assertEquals("+10.00s", formatSubtitleDelay(10_000L))
        assertEquals("0.05s", formatSubtitleDelayStep(50L))
    }

    // ---------- VIDEO-11：语言分组 ----------

    @Test
    fun `按语言分组且组内内封优先`() {
        val tracks =
            listOf(
                option(1, "en", PlayerViewModel.SubtitleOrigin.External),
                option(2, "zh-Hans", PlayerViewModel.SubtitleOrigin.External),
                option(3, "en", PlayerViewModel.SubtitleOrigin.Embedded),
                option(4, "zh-Hans", PlayerViewModel.SubtitleOrigin.Embedded),
                option(5, "", PlayerViewModel.SubtitleOrigin.Embedded),
            )

        val groups = groupSubtitleTracksByLanguage(tracks)

        assertEquals(listOf("en", "zh-Hans", ""), groups.map { it.language })
        assertEquals(
            "同语言组内内封在前、外挂在后，且同来源保持原标题序",
            listOf(3, 1),
            groups[0].options.map { it.id },
        )
        assertEquals(listOf(4, 2), groups[1].options.map { it.id })
    }

    @Test
    fun `侧载字幕按外挂排在组末尾`() {
        val tracks =
            listOf(
                option(1, "en", PlayerViewModel.SubtitleOrigin.Sideloaded),
                option(2, "en", PlayerViewModel.SubtitleOrigin.External),
                option(3, "en", PlayerViewModel.SubtitleOrigin.Embedded),
            )

        val groups = groupSubtitleTracksByLanguage(tracks)

        assertEquals(listOf(3, 1, 2), groups.single().options.map { it.id })
        assertTrue(
            groups.single().options.first().origin == PlayerViewModel.SubtitleOrigin.Embedded
        )
    }
}
