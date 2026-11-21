package com.zhangwenkang.cinefin.book.presentation.reader.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderTtsControllerTest {

    private class FakeEngine : TtsEngine {
        val spoken = mutableListOf<String>()
        var stopped = 0
        var lastRate = ReaderTtsDefaultRate
        var available = true
        var lastUtteranceId: String? = null

        override fun setRate(rate: Float) {
            this.lastRate = rate
        }

        override fun speak(text: String, utteranceId: String): Boolean {
            if (!available) return false
            spoken += text
            lastUtteranceId = utteranceId
            return true
        }

        override fun stop() {
            stopped++
        }
    }

    private class VirtualClock(var now: Long = 0L) {
        operator fun invoke(): Long = now
    }

    private fun chapter(
        index: Int,
        vararg sentences: String,
        href: String = "ch$index.xhtml",
        lostLocators: Set<Int> = emptySet(),
    ): TtsChapter {
        val chapterTitle = "第${index + 1}章"
        return TtsChapter(
            index = index,
            title = chapterTitle,
            href = href,
            sentences =
                sentences.mapIndexed { position, text ->
                    TtsSentence(
                        chapterIndex = index,
                        index = position,
                        text = text,
                        href = href,
                        locatorJson =
                            if (position in lostLocators) null
                            else
                                """{"href":"$href","locations":{"progression":${index + position / 100.0}}}""",
                        progression =
                            if (position in lostLocators) null else index + position / 100.0,
                    )
                },
        )
    }

    private class Harness(engine: FakeEngine, clock: VirtualClock) {
        val requested = mutableListOf<Int>()
        val states = mutableListOf<ReaderTtsUiState>()
        val controller =
            ReaderTtsController(
                engine = engine,
                clock = { clock.now },
                onStateChanged = { states += it },
                onRequestChapter = { requested += it },
            )

        val state: ReaderTtsUiState
            get() = controller.state
    }

    @Test
    fun `从章首开始朗读第一句`() {
        val engine = FakeEngine()
        val harness = Harness(engine, VirtualClock())
        harness.controller.addChapter(chapter(0, "第一句。", "第二句。"))
        harness.controller.startAt(0)
        assertEquals(TtsPlayback.Playing, harness.state.playback)
        assertEquals(listOf("第一句。"), engine.spoken)
        assertEquals("第一句。", harness.state.active?.text)
    }

    @Test
    fun `下一句在同一章内推进`() {
        val engine = FakeEngine()
        val harness = Harness(engine, VirtualClock())
        harness.controller.addChapter(chapter(0, "一。", "二。", "三。"))
        harness.controller.startAt(0)
        harness.controller.next()
        assertEquals("二。", harness.state.active?.text)
        assertEquals(listOf("一。", "二。"), engine.spoken)
    }

    @Test
    fun `章末自动续接下一章`() {
        val engine = FakeEngine()
        val harness = Harness(engine, VirtualClock())
        harness.controller.addChapter(chapter(0, "一。", "二。"))
        harness.controller.addChapter(chapter(1, "三。", "四。"))
        harness.controller.startAt(0)
        harness.controller.next() // 二
        harness.controller.onUtteranceFinished(engine.lastUtteranceId!!) // 二 播完 → 三
        assertEquals("三。", harness.state.active?.text)
        assertEquals(1, harness.state.active?.chapterIndex)
    }

    @Test
    fun `跨到未加载的章节先准备再续播`() {
        val engine = FakeEngine()
        val harness = Harness(engine, VirtualClock())
        harness.controller.addChapter(chapter(0, "一。", "二。"))
        harness.controller.startAt(0)
        harness.controller.onUtteranceFinished(engine.lastUtteranceId!!)
        harness.controller.onUtteranceFinished(engine.lastUtteranceId!!)
        assertEquals(listOf(1), harness.requested)
        assertEquals(TtsPlayback.Preparing, harness.state.playback)
        assertEquals(1, harness.state.loadingChapter)

        harness.controller.addChapter(chapter(1, "三。"))
        assertEquals(TtsPlayback.Playing, harness.state.playback)
        assertEquals("三。", harness.state.active?.text)
    }

    @Test
    fun `书末没有更多章节则停止`() {
        val engine = FakeEngine()
        val harness = Harness(engine, VirtualClock())
        harness.controller.addChapter(chapter(0, "一。"))
        harness.controller.startAt(0)
        harness.controller.onUtteranceFinished(engine.lastUtteranceId!!)
        assertEquals(listOf(1), harness.requested)
        harness.controller.onChapterUnavailable(1)
        assertEquals(TtsPlayback.Idle, harness.state.playback)
        assertNull(harness.state.active)
        assertTrue(harness.state.message.orEmpty().contains("书末"))
    }

    @Test
    fun `本章结束后停止`() {
        val engine = FakeEngine()
        val harness = Harness(engine, VirtualClock())
        harness.controller.addChapter(chapter(0, "一。", "二。"))
        harness.controller.addChapter(chapter(1, "三。"))
        harness.controller.startAt(0)
        harness.controller.setTimer(TtsTimerOption.EndOfChapter)
        harness.controller.next() // 二
        harness.controller.onUtteranceFinished(engine.lastUtteranceId!!) // 二 播完 → 停
        assertEquals(TtsPlayback.Idle, harness.state.playback)
        assertTrue(harness.state.message.orEmpty().contains("本章结束"))
    }

    @Test
    fun `上一句到章首时回到上一章末句`() {
        val engine = FakeEngine()
        val harness = Harness(engine, VirtualClock())
        harness.controller.addChapter(chapter(0, "一。", "二。"))
        harness.controller.addChapter(chapter(1, "三。", "四。"))
        harness.controller.startAt(1)
        harness.controller.previous()
        assertEquals("二。", harness.state.active?.text)
        assertEquals(0, harness.state.active?.chapterIndex)
    }

    @Test
    fun `暂停停止引擎且复位后重读当前句`() {
        val engine = FakeEngine()
        val harness = Harness(engine, VirtualClock())
        harness.controller.addChapter(chapter(0, "一。"))
        harness.controller.startAt(0)
        harness.controller.pause()
        assertEquals(TtsPlayback.Paused, harness.state.playback)
        assertEquals(1, engine.stopped)
        harness.controller.resume()
        assertEquals(TtsPlayback.Playing, harness.state.playback)
        assertEquals(listOf("一。", "一。"), engine.spoken)
    }

    @Test
    fun `定时 15 分钟到点停止`() {
        val clock = VirtualClock()
        val engine = FakeEngine()
        val harness = Harness(engine, clock)
        harness.controller.addChapter(chapter(0, "一。", "二。"))
        harness.controller.startAt(0)
        harness.controller.setTimer(TtsTimerOption.Fifteen)
        clock.now += 15 * 60_000L - 1
        harness.controller.onTick()
        assertEquals(TtsPlayback.Playing, harness.state.playback)
        clock.now += 1
        harness.controller.onTick()
        assertEquals(TtsPlayback.Idle, harness.state.playback)
        assertTrue(harness.state.message.orEmpty().contains("定时"))
        assertEquals(TtsTimerOption.Off, harness.state.timer)
    }

    @Test
    fun `定时剩余时间随心跳递减`() {
        val clock = VirtualClock()
        val engine = FakeEngine()
        val harness = Harness(engine, clock)
        harness.controller.addChapter(chapter(0, "一。"))
        harness.controller.startAt(0)
        harness.controller.setTimer(TtsTimerOption.Thirty)
        clock.now += 60_000L
        harness.controller.onTick()
        assertEquals(29 * 60_000L, harness.state.timerRemainingMs)
    }

    @Test
    fun `语速越界被收进区间并在播放时重读当前句`() {
        val engine = FakeEngine()
        val harness = Harness(engine, VirtualClock())
        harness.controller.addChapter(chapter(0, "一。"))
        harness.controller.startAt(0)
        harness.controller.setRate(5f)
        assertEquals(ReaderTtsRateRange.endInclusive, harness.state.rate)
        assertEquals(ReaderTtsRateRange.endInclusive, engine.lastRate)
        harness.controller.setRate(0.1f)
        assertEquals(ReaderTtsRateRange.start, harness.state.rate)
        assertEquals(3, engine.spoken.size)
    }

    @Test
    fun `映射失败的句子仍可朗读但不参与高亮`() {
        val engine = FakeEngine()
        val harness = Harness(engine, VirtualClock())
        harness.controller.addChapter(chapter(0, "一。", "二。", lostLocators = setOf(0)))
        harness.controller.startAt(0)
        assertEquals("一。", harness.state.active?.text)
        assertFalse(harness.state.active?.highlightable ?: true)
        assertNull(harness.state.active?.locatorJson)
        assertEquals(1, harness.state.degradedSentences)
        harness.controller.next()
        assertTrue(harness.state.active?.highlightable ?: false)
    }

    @Test
    fun `连续三次引擎出错后停止`() {
        val engine = FakeEngine()
        val harness = Harness(engine, VirtualClock())
        harness.controller.addChapter(chapter(0, "一。", "二。", "三。", "四。", "五。"))
        harness.controller.startAt(0)
        repeat(3) { harness.controller.onUtteranceFailed(engine.lastUtteranceId!!) }
        assertEquals(TtsPlayback.Idle, harness.state.playback)
        assertTrue(harness.state.message.orEmpty().contains("出错"))
    }

    @Test
    fun `引擎不可用时暂停并提示`() {
        val engine = FakeEngine()
        engine.available = false
        val harness = Harness(engine, VirtualClock())
        harness.controller.addChapter(chapter(0, "一。"))
        harness.controller.startAt(0)
        assertEquals(TtsPlayback.Paused, harness.state.playback)
        assertEquals("朗读引擎不可用", harness.state.message)
    }

    @Test
    fun `点按跳转朗读位置按进度落句`() {
        val engine = FakeEngine()
        val harness = Harness(engine, VirtualClock())
        harness.controller.addChapter(chapter(0, "一。", "二。", "三。"))
        harness.controller.startAt(0)
        assertTrue(harness.controller.relocate("ch0.xhtml", 0.02))
        assertEquals("三。", harness.state.active?.text)
        assertTrue(harness.controller.relocate("ch0.xhtml", 0.0, pause = true))
        assertEquals("一。", harness.state.active?.text)
        assertEquals(TtsPlayback.Paused, harness.state.playback)
        assertFalse(harness.controller.relocate("missing.xhtml", 0.0))
    }

    @Test
    fun `指定章首启动时未加载则准备后跳到指定句`() {
        val engine = FakeEngine()
        val harness = Harness(engine, VirtualClock())
        harness.controller.addChapter(chapter(0, "一。"))
        harness.controller.startAt(2, sentenceIndex = 1)
        assertEquals(listOf(2), harness.requested)
        assertEquals(TtsPlayback.Preparing, harness.state.playback)
        harness.controller.addChapter(chapter(2, "三。", "四。"))
        assertEquals("四。", harness.state.active?.text)
        assertEquals(TtsPlayback.Playing, harness.state.playback)
    }

    @Test
    fun `空章自动跳过`() {
        val engine = FakeEngine()
        val harness = Harness(engine, VirtualClock())
        harness.controller.addChapter(chapter(0, "一。"))
        harness.controller.addChapter(TtsChapter(1, "封面", "cover.xhtml", emptyList()))
        harness.controller.addChapter(chapter(2, "三。"))
        harness.controller.startAt(0)
        harness.controller.onUtteranceFinished(engine.lastUtteranceId!!)
        assertEquals("三。", harness.state.active?.text)
        assertEquals(2, harness.state.active?.chapterIndex)
    }

    @Test
    fun `停止清空位置与定时`() {
        val engine = FakeEngine()
        val harness = Harness(engine, VirtualClock())
        harness.controller.addChapter(chapter(0, "一。"))
        harness.controller.startAt(0)
        harness.controller.setTimer(TtsTimerOption.Sixty)
        harness.controller.stop()
        assertEquals(TtsPlayback.Idle, harness.state.playback)
        assertNull(harness.state.active)
        assertNull(harness.state.timerRemainingMs)
        assertEquals(TtsTimerOption.Off, harness.state.timer)
    }

    @Test
    fun `引擎不可用进入错误态且不自动重试`() {
        val engine = FakeEngine()
        val harness = Harness(engine, VirtualClock())
        harness.controller.addChapter(chapter(0, "一。"))
        harness.controller.reportEngineUnavailable()

        assertEquals(TtsPlayback.Idle, harness.state.playback)
        assertNull(harness.state.active)
        assertEquals(ReaderTtsNoEngineMessage, harness.state.engineError)
        // 不自动重试：状态机不会因为出错就再次开口 / 请求章节。
        assertEquals(emptyList<String>(), engine.spoken)
        assertTrue(harness.requested.isEmpty())
        // 重复上报保持同一条提示（防重试风暴）。
        harness.controller.reportEngineUnavailable("自定义提示")
        assertEquals("自定义提示", harness.state.engineError)
    }

    @Test
    fun `用户重试成功后清除引擎错误态`() {
        val engine = FakeEngine()
        val harness = Harness(engine, VirtualClock())
        harness.controller.addChapter(chapter(0, "一。"))
        harness.controller.reportEngineUnavailable()
        assertEquals(TtsPlayback.Idle, harness.state.playback)

        // 模拟用户重新点「听书」：绑定成功 → 清错误 → 从章首开始。
        harness.controller.clearEngineUnavailable()
        harness.controller.startAt(0)
        assertNull(harness.state.engineError)
        assertEquals(TtsPlayback.Playing, harness.state.playback)
        assertEquals(listOf("一。"), engine.spoken)
    }

    @Test
    fun `引擎不可用态在成功起步时自动清空`() {
        val engine = FakeEngine()
        val harness = Harness(engine, VirtualClock())
        harness.controller.addChapter(chapter(0, "一。"))
        harness.controller.reportEngineUnavailable()
        harness.controller.startAt(0)
        assertNull(harness.state.engineError)
    }

    @Test
    fun `阅读位置换算朗读位置`() {
        val chapters =
            listOf(
                chapter(0, "一。", "二。"),
                chapter(1, "三。", "四。"),
            )
        assertEquals(0 to 0, locateReadingPoint(chapters, "ch0.xhtml", null))
        assertEquals(0 to 1, locateReadingPoint(chapters, "ch0.xhtml#frag", 0.01))
        assertEquals(1 to 0, locateReadingPoint(chapters, "ch1.xhtml", 1.0))
        assertEquals(1 to 1, locateReadingPoint(chapters, "ch1.xhtml", 1.5))
        assertNull(locateReadingPoint(chapters, "unknown.xhtml", 0.0))
    }
}
