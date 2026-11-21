package com.zhangwenkang.cinefin.book.presentation.reader

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoAdvanceTest {

    /** 虚拟时钟：单测不依赖真实时间。 */
    private class VirtualClock(var now: Long = 0L) {
        fun advance(ms: Long) {
            now += ms
        }

        operator fun invoke(): Long = now
    }

    private fun engine(clock: VirtualClock, stateChanges: MutableList<AutoAdvanceState>? = null) =
        AutoAdvanceEngine(
            clock = { clock.now },
            onStateChanged = { stateChanges?.add(it) },
        )

    @Test
    fun `默认关闭且不产生任何节拍`() {
        val clock = VirtualClock()
        val engine = engine(clock)
        assertEquals(AutoAdvanceState.Off, engine.state)
        clock.advance(120_000)
        assertEquals(AutoAdvanceTick.Idle, engine.tick(paged = true))
        assertEquals(AutoAdvanceTick.Idle, engine.tick(paged = false))
        assertEquals(AutoAdvanceState.Off, engine.state)
    }

    @Test
    fun `分页档到间隔才翻页且不会连翻`() {
        val clock = VirtualClock()
        val engine = engine(clock)
        engine.start()
        assertEquals(AutoAdvanceTick.Idle, engine.tick(paged = true))
        clock.advance(29_000)
        assertEquals(AutoAdvanceTick.Idle, engine.tick(paged = true))
        clock.advance(1_000)
        assertEquals(AutoAdvanceTick.PageForward, engine.tick(paged = true))
        // 同一时刻再 tick 不应再翻页（累计清零，重新等一个间隔）。
        assertEquals(AutoAdvanceTick.Idle, engine.tick(paged = true))
        clock.advance(30_000)
        assertEquals(AutoAdvanceTick.PageForward, engine.tick(paged = true))
    }

    @Test
    fun `间隔实时调整不打断已累计的进度`() {
        val clock = VirtualClock()
        val engine = engine(clock)
        engine.start()
        // 第一帧只建立时间基线。
        assertEquals(AutoAdvanceTick.Idle, engine.tick(paged = true))
        // 默认 30s：先走 10s。
        clock.advance(10_000)
        assertEquals(AutoAdvanceTick.Idle, engine.tick(paged = true))
        // 调到 15s：已过 10s，再 5s 就该翻页（而不是重新等 15s）。
        engine.updateConfig(engine.config.copy(pageIntervalSeconds = 15))
        clock.advance(4_000)
        assertEquals(AutoAdvanceTick.Idle, engine.tick(paged = true))
        clock.advance(1_000)
        assertEquals(AutoAdvanceTick.PageForward, engine.tick(paged = true))
        // 再调到 5s：翻页后累计已清零，5s 后下一翻。
        engine.updateConfig(engine.config.copy(pageIntervalSeconds = 5))
        clock.advance(5_000)
        assertEquals(AutoAdvanceTick.PageForward, engine.tick(paged = true))
    }

    @Test
    fun `滚动档按速度输出整像素并保留小数余量`() {
        val clock = VirtualClock()
        val engine = engine(clock)
        engine.updateConfig(AutoAdvanceConfig(scrollSpeedPxPerSecond = 60f))
        engine.start()
        engine.tick(paged = false)
        // 60px/s、16ms 一帧 = 0.96px：第一帧不足 1px 不输出，余量留到下一帧。
        clock.advance(16)
        assertEquals(AutoAdvanceTick.Idle, engine.tick(paged = false))
        clock.advance(16)
        assertEquals(AutoAdvanceTick.Scroll(1), engine.tick(paged = false))
    }

    @Test
    fun `滚动档一秒的像素总量与速度一致`() {
        val clock = VirtualClock()
        val engine = engine(clock)
        engine.updateConfig(AutoAdvanceConfig(scrollSpeedPxPerSecond = 120f))
        engine.start()
        engine.tick(paged = false)
        var total = 0
        repeat(100) {
            clock.advance(10)
            val tick = engine.tick(paged = false)
            if (tick is AutoAdvanceTick.Scroll) total += tick.deltaPx
        }
        // 120px/s × 1s = 120px（允许取整误差 1px）。
        assertTrue("实际 $total", abs(total - 120) <= 1)
    }

    @Test
    fun `滚动速度实时调整立即生效`() {
        val clock = VirtualClock()
        val engine = engine(clock)
        engine.updateConfig(AutoAdvanceConfig(scrollSpeedPxPerSecond = 50f))
        engine.start()
        engine.tick(paged = false)
        clock.advance(100)
        assertEquals(AutoAdvanceTick.Scroll(5), engine.tick(paged = false))
        engine.updateConfig(engine.config.copy(scrollSpeedPxPerSecond = 200f))
        clock.advance(100)
        assertEquals(AutoAdvanceTick.Scroll(20), engine.tick(paged = false))
    }

    @Test
    fun `暂停不累计时间恢复后从零开始`() {
        val clock = VirtualClock()
        val engine = engine(clock)
        engine.start()
        clock.advance(20_000)
        assertEquals(AutoAdvanceTick.Idle, engine.tick(paged = true))
        engine.pause()
        assertEquals(AutoAdvanceState.Paused, engine.state)
        // 暂停期间即使调用 tick 也不累计。
        clock.advance(60_000)
        assertEquals(AutoAdvanceTick.Idle, engine.tick(paged = true))
        engine.resume()
        assertEquals(AutoAdvanceState.Running, engine.state)
        // 恢复后的第一帧建立时间基线（暂停期间不补课）。
        assertEquals(AutoAdvanceTick.Idle, engine.tick(paged = true))
        clock.advance(29_000)
        assertEquals(AutoAdvanceTick.Idle, engine.tick(paged = true))
        clock.advance(1_000)
        assertEquals(AutoAdvanceTick.PageForward, engine.tick(paged = true))
    }

    @Test
    fun `暂停态不会被开始以外的操作误启动`() {
        val clock = VirtualClock()
        val engine = engine(clock)
        engine.resume()
        assertEquals(AutoAdvanceState.Off, engine.state)
        engine.tick(paged = true)
        assertEquals(AutoAdvanceState.Off, engine.state)
    }

    @Test
    fun `手动干预只在运行态暂停`() {
        val clock = VirtualClock()
        val engine = engine(clock)
        engine.pauseForManualIntervention()
        assertEquals(AutoAdvanceState.Off, engine.state)
        engine.start()
        engine.pauseForManualIntervention()
        assertEquals(AutoAdvanceState.Paused, engine.state)
        engine.stop()
        assertEquals(AutoAdvanceState.Off, engine.state)
    }

    @Test
    fun `停止回到关闭并清空翻页时刻`() {
        val clock = VirtualClock()
        val engine = engine(clock)
        engine.start()
        assertEquals(AutoAdvanceTick.Idle, engine.tick(paged = true))
        clock.advance(30_000)
        assertEquals(AutoAdvanceTick.PageForward, engine.tick(paged = true))
        assertEquals(30_000L, engine.lastPageForwardAtMs)
        engine.stop()
        assertEquals(AutoAdvanceState.Off, engine.state)
        // 重新开始时清空：新的会话不带上一次的翻页时刻。
        engine.start()
        assertNull(engine.lastPageForwardAtMs)
    }

    @Test
    fun `到书末停止后不再翻页`() {
        val clock = VirtualClock()
        val engine = engine(clock)
        engine.start()
        assertEquals(AutoAdvanceTick.Idle, engine.tick(paged = true))
        clock.advance(30_000)
        assertEquals(AutoAdvanceTick.PageForward, engine.tick(paged = true))
        // 执行层判定已到书末 → stop；此后无论过多久都不再翻页。
        engine.stop()
        clock.advance(120_000)
        assertEquals(AutoAdvanceTick.Idle, engine.tick(paged = true))
        assertEquals(AutoAdvanceState.Off, engine.state)
    }

    @Test
    fun `参数越界与非有限值被收进区间`() {
        val config = AutoAdvanceConfig(pageIntervalSeconds = 1, scrollSpeedPxPerSecond = 900f)
        val sanitized = config.sanitized()
        assertEquals(AutoAdvanceMinIntervalSeconds, sanitized.pageIntervalSeconds)
        assertEquals(AutoAdvanceMaxSpeedPxPerSecond, sanitized.scrollSpeedPxPerSecond, 0f)
        assertEquals(
            AutoAdvanceMaxIntervalSeconds,
            AutoAdvanceConfig(pageIntervalSeconds = 999).sanitized().pageIntervalSeconds,
        )
        assertEquals(
            AutoAdvanceDefaultSpeedPxPerSecond,
            AutoAdvanceConfig(scrollSpeedPxPerSecond = Float.NaN)
                .sanitized()
                .scrollSpeedPxPerSecond,
            0f,
        )
        assertEquals(
            AutoAdvanceMinSpeedPxPerSecond,
            AutoAdvanceConfig(scrollSpeedPxPerSecond = 1f).sanitized().scrollSpeedPxPerSecond,
            0f,
        )
    }

    @Test
    fun `状态回调只在状态变化时触发`() {
        val clock = VirtualClock()
        val changes = mutableListOf<AutoAdvanceState>()
        val engine = engine(clock, changes)
        engine.start()
        engine.start()
        engine.pause()
        engine.pause()
        engine.resume()
        engine.stop()
        engine.stop()
        assertEquals(
            listOf(
                AutoAdvanceState.Running,
                AutoAdvanceState.Paused,
                AutoAdvanceState.Running,
                AutoAdvanceState.Off,
            ),
            changes,
        )
    }

    // ---- W6 T6r：滚动档进度追踪（章末续接 / 书末 / 停滞兜底）----

    @Test
    fun `滚动追踪器位移变化即视为推进`() {
        val tracker = AutoScrollTracker()
        tracker.reset(nowMs = 0L)
        assertEquals(
            AutoScrollOutcome.Continue,
            tracker.onFrame(
                1_000L,
                offset = 100.0,
                resourceProgression = 0.2,
                bookProgression = 0.2,
                canScrollForward = true,
            ),
        )
        assertEquals(
            AutoScrollOutcome.Continue,
            tracker.onFrame(
                2_000L,
                offset = 160.0,
                resourceProgression = 0.21,
                bookProgression = 0.21,
                canScrollForward = true,
            ),
        )
        // 累计 2s 有位移，不应判停滞。
        assertEquals(
            AutoScrollOutcome.Continue,
            tracker.onFrame(
                3_500L,
                offset = 220.0,
                resourceProgression = 0.22,
                bookProgression = 0.22,
                canScrollForward = true,
            ),
        )
    }

    @Test
    fun `滚动追踪器资源内到底但未到书末判定章末`() {
        val tracker = AutoScrollTracker()
        tracker.reset(nowMs = 0L)
        assertEquals(
            AutoScrollOutcome.ChapterEnd,
            tracker.onFrame(
                1_000L,
                offset = 800.0,
                resourceProgression = 0.996,
                bookProgression = 0.42,
                canScrollForward = true,
            ),
        )
        // 已不能继续前滚（短章一屏装下）同样视为章末。
        val shortChapter = AutoScrollTracker()
        shortChapter.reset(nowMs = 0L)
        assertEquals(
            AutoScrollOutcome.ChapterEnd,
            shortChapter.onFrame(
                1_000L,
                offset = 0.0,
                resourceProgression = 0.0,
                bookProgression = 0.1,
                canScrollForward = false,
            ),
        )
    }

    @Test
    fun `滚动追踪器整书到末判定书末`() {
        val tracker = AutoScrollTracker()
        tracker.reset(nowMs = 0L)
        assertEquals(
            AutoScrollOutcome.BookEnd,
            tracker.onFrame(
                1_000L,
                offset = 900.0,
                resourceProgression = 0.999,
                bookProgression = 0.999,
                canScrollForward = false,
            ),
        )
        // 整书进度未知时退化为章末（调用方还会再 goForward 一次，下一章能判定书末）。
        val unknownBook = AutoScrollTracker()
        unknownBook.reset(nowMs = 0L)
        assertEquals(
            AutoScrollOutcome.ChapterEnd,
            unknownBook.onFrame(
                1_000L,
                offset = 900.0,
                resourceProgression = 1.0,
                bookProgression = null,
                canScrollForward = false,
            ),
        )
    }

    @Test
    fun `滚动追踪器连续三秒无位移判定停滞`() {
        val tracker = AutoScrollTracker()
        tracker.reset(nowMs = 0L)
        assertEquals(
            AutoScrollOutcome.Continue,
            tracker.onFrame(
                0L,
                offset = 0.0,
                resourceProgression = 0.1,
                bookProgression = 0.1,
                canScrollForward = true,
            ),
        )
        assertEquals(
            AutoScrollOutcome.Continue,
            tracker.onFrame(
                2_900L,
                offset = 0.0,
                resourceProgression = 0.1,
                bookProgression = 0.1,
                canScrollForward = true,
            ),
        )
        assertEquals(
            AutoScrollOutcome.Stalled,
            tracker.onFrame(
                3_000L,
                offset = 0.0,
                resourceProgression = 0.1,
                bookProgression = 0.1,
                canScrollForward = true,
            ),
        )
        // 有位移就重置计时。
        val moving = AutoScrollTracker()
        moving.reset(nowMs = 0L)
        moving.onFrame(0L, offset = 0.0, resourceProgression = 0.1, bookProgression = 0.1, true)
        moving.onFrame(2_500L, offset = 0.0, resourceProgression = 0.1, bookProgression = 0.1, true)
        moving.onFrame(
            2_600L,
            offset = 10.0,
            resourceProgression = 0.1,
            bookProgression = 0.1,
            true,
        )
        assertEquals(
            AutoScrollOutcome.Continue,
            moving.onFrame(
                4_000L,
                offset = 10.0,
                resourceProgression = 0.1,
                bookProgression = 0.1,
                true,
            ),
        )
    }

    @Test
    fun `滚动追踪器重置后不把换章旧计时算进停滞`() {
        val tracker = AutoScrollTracker()
        tracker.reset(nowMs = 0L)
        tracker.onFrame(0L, offset = 0.0, resourceProgression = 0.1, bookProgression = 0.1, true)
        // 章末换 WebView：重置后 2.5s 内不得判停滞。
        tracker.reset(nowMs = 10_000L)
        assertEquals(
            AutoScrollOutcome.Continue,
            tracker.onFrame(
                12_500L,
                offset = 0.0,
                resourceProgression = 0.0,
                bookProgression = 0.5,
                canScrollForward = true,
            ),
        )
        assertEquals(
            AutoScrollOutcome.Stalled,
            tracker.onFrame(
                15_500L,
                offset = 0.0,
                resourceProgression = 0.0,
                bookProgression = 0.5,
                canScrollForward = true,
            ),
        )
    }

    // ---- W6 T6r2：末章到底的 goForward 空转判定 ----

    @Test
    fun `空转判定 首次章末不算空转`() {
        val streak = AutoScrollForwardStreak()
        val check = streak.onChapterEnd("chapter-3.xhtml")
        assertEquals(0, check.misses)
        assertFalse(check.bookEnd)
    }

    @Test
    fun `空转判定 位置键未变连续两次即书末`() {
        val streak = AutoScrollForwardStreak()
        // 正常跨章：位置键变了，不计空转。
        streak.onChapterEnd("chapter-1.xhtml")
        assertEquals(0, streak.onChapterEnd("chapter-2.xhtml").misses)
        // 末章到底：goForward 不换章，位置键一直是同一个。
        val first = streak.onChapterEnd("chapter-2.xhtml")
        assertEquals(1, first.misses)
        assertFalse(first.bookEnd)
        val second = streak.onChapterEnd("chapter-2.xhtml")
        assertEquals(AutoScrollForwardMissLimit, second.misses)
        assertTrue(second.bookEnd)
    }

    @Test
    fun `空转判定 中途位置键变化即清零`() {
        val streak = AutoScrollForwardStreak()
        streak.onChapterEnd("chapter-7.xhtml")
        assertEquals(1, streak.onChapterEnd("chapter-7.xhtml").misses)
        // 慢换页 / 用户跳转后位置终于变了：计数清零，不会误判书末。
        val recovered = streak.onChapterEnd("chapter-8.xhtml")
        assertEquals(0, recovered.misses)
        assertFalse(recovered.bookEnd)
        assertEquals(1, streak.onChapterEnd("chapter-8.xhtml").misses)
    }

    @Test
    fun `空转判定 书末后复位可重新计数`() {
        val streak = AutoScrollForwardStreak()
        streak.onChapterEnd("last.xhtml")
        streak.onChapterEnd("last.xhtml")
        assertTrue(streak.onChapterEnd("last.xhtml").bookEnd)
        // 重新开启自动滚动：同一位置从头计，而不是立刻再判书末。
        val reopened = streak.onChapterEnd("last.xhtml")
        assertEquals(0, reopened.misses)
        assertFalse(reopened.bookEnd)
    }

    @Test
    fun `空转判定 位置键未知按未变计数且不会无限空转`() {
        val streak = AutoScrollForwardStreak()
        assertEquals(0, streak.onChapterEnd(null).misses)
        assertEquals(1, streak.onChapterEnd(null).misses)
        val last = streak.onChapterEnd(null)
        assertTrue(last.bookEnd)
        assertEquals(AutoScrollForwardMissLimit, last.misses)
    }

    @Test
    fun `空转判定 重置后不把旧基线算进空转`() {
        val streak = AutoScrollForwardStreak()
        streak.onChapterEnd("chapter-1.xhtml")
        assertEquals(1, streak.onChapterEnd("chapter-1.xhtml").misses)
        // 换 WebView = 位置推进，下一次章末从 0 开始。
        streak.reset()
        assertEquals(0, streak.onChapterEnd("chapter-1.xhtml").misses)
    }

    @Test
    fun `空转判定 上限可配`() {
        val streak = AutoScrollForwardStreak(maxMisses = 1)
        streak.onChapterEnd("last.xhtml")
        val check = streak.onChapterEnd("last.xhtml")
        assertTrue(check.bookEnd)
        assertEquals(1, check.misses)
    }
}
