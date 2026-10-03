package com.zhangwenkang.cinefin.player.core.domain.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 睡眠定时纯逻辑（W55）：分钟换算 / 1–240 边界 / 到点判定 / 取消清理。 */
class SleepTimerTest {

    @Test
    fun `分钟换算为毫秒`() {
        assertEquals(60_000L, SleepTimerSpec.totalMs(1))
        assertEquals(1_800_000L, SleepTimerSpec.totalMs(30))
        assertEquals(240 * 60_000L, SleepTimerSpec.totalMs(240))
    }

    @Test
    fun `自定义分钟收敛到 1 到 240`() {
        assertEquals(1, SleepTimerSpec.clampMinutes(0))
        assertEquals(1, SleepTimerSpec.clampMinutes(-5))
        assertEquals(1, SleepTimerSpec.clampMinutes(1))
        assertEquals(240, SleepTimerSpec.clampMinutes(240))
        assertEquals(240, SleepTimerSpec.clampMinutes(241))
        assertEquals(240, SleepTimerSpec.clampMinutes(9999))
        // >240 的入参按 240 计
        assertEquals(240 * 60_000L, SleepTimerSpec.totalMs(300))
    }

    @Test
    fun `到点判定与剩余毫秒`() {
        assertEquals(600L, SleepTimerSpec.remainingMs(deadlineMs = 1_000L, nowMs = 400L))
        assertEquals(0L, SleepTimerSpec.remainingMs(deadlineMs = 1_000L, nowMs = 1_000L))
        assertEquals(0L, SleepTimerSpec.remainingMs(deadlineMs = 1_000L, nowMs = 1_500L))
        assertFalse(SleepTimerSpec.isExpired(deadlineMs = 1_000L, nowMs = 999L))
        assertTrue(SleepTimerSpec.isExpired(deadlineMs = 1_000L, nowMs = 1_000L))
        assertTrue(SleepTimerSpec.isExpired(deadlineMs = 1_000L, nowMs = 1_001L))
    }

    @Test
    fun `state machine 选择计时并按 deadline 到期`() {
        val machine = SleepTimerStateMachine()

        val selected = machine.select(minutes = 30, nowMs = 1_000L)
        assertTrue(selected.active)
        assertEquals(30, selected.minutes)
        assertEquals(1_000L + 1_800_000L, selected.deadlineMs)

        // 未到点：状态不变、不报到期
        val midway = machine.tick(nowMs = 901_000L)
        assertFalse(midway.expired)
        assertTrue(midway.snapshot.active)
        assertEquals(900_000L, SleepTimerSpec.remainingMs(midway.snapshot.deadlineMs, 901_000L))

        // 到点：清理状态并只报告一次
        val expired = machine.tick(nowMs = 1_801_000L)
        assertTrue(expired.expired)
        assertFalse(expired.snapshot.active)
        assertEquals(0L, expired.snapshot.deadlineMs)

        val after = machine.tick(nowMs = 1_900_000L)
        assertFalse(after.expired)
        assertFalse(after.snapshot.active)
    }

    @Test
    fun `取消与空选择清理计时且不再触发到点`() {
        val machine = SleepTimerStateMachine()

        machine.select(minutes = 30, nowMs = 0L)
        val cancelled = machine.cancel()
        assertFalse(cancelled.active)
        assertEquals(0L, cancelled.deadlineMs)
        assertFalse(machine.tick(nowMs = 1_800_000L).expired)
        assertFalse(machine.current.active)

        machine.select(minutes = 10, nowMs = 5_000L)
        val cleared = machine.select(minutes = null, nowMs = 6_000L)
        assertFalse(cleared.active)
        assertFalse(machine.tick(nowMs = 605_000L).expired)

        // 0 与负数按取消处理，与既有音乐侧语义一致
        machine.select(minutes = 20, nowMs = 7_000L)
        assertFalse(machine.select(minutes = 0, nowMs = 8_000L).active)
        machine.select(minutes = 20, nowMs = 9_000L)
        assertFalse(machine.select(minutes = -1, nowMs = 10_000L).active)

        // 取消后重新选择：按新的 now 重新计时
        val restarted = machine.select(minutes = 15, nowMs = 20_000L)
        assertTrue(restarted.active)
        assertEquals(20_000L + 900_000L, restarted.deadlineMs)
    }

    @Test
    fun `选择时分钟超上限按 240 计时`() {
        val machine = SleepTimerStateMachine()
        val selected = machine.select(minutes = 300, nowMs = 0L)
        assertEquals(240, selected.minutes)
        assertEquals(240 * 60_000L, selected.deadlineMs)
    }

    @Test
    fun `剩余时间文案向上取整到秒并保持 mm ss`() {
        assertEquals("00:00", SleepTimerSpec.formatRemaining(0L))
        assertEquals("00:01", SleepTimerSpec.formatRemaining(999L))
        assertEquals("01:00", SleepTimerSpec.formatRemaining(60_000L))
        assertEquals("59:01", SleepTimerSpec.formatRemaining(59 * 60_000L + 500L))
        // 60 / 240 分钟档位初始值
        assertEquals("60:00", SleepTimerSpec.formatRemaining(60 * 60_000L))
        assertEquals("240:00", SleepTimerSpec.formatRemaining(240 * 60_000L))
        // 负值按 0 处理
        assertEquals("00:00", SleepTimerSpec.formatRemaining(-5L))
    }
}
