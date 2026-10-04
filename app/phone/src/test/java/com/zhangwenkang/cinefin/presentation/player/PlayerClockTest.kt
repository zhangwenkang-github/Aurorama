package com.zhangwenkang.cinefin.presentation.player

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * W67 补充：时间格式（跟随系统 / 24 / 12、跨天、剩余 <1 分钟）与预计结束时刻（播放 / 暂停）单测。
 *
 * 固定时区 = Asia/Shanghai（+08:00），避免用运行机器的时区。
 */
class PlayerClockTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    /** 2026-10-04 23:45:00 +08:00 */
    private val t2345 = at(2026, 10, 4, 23, 45)

    // ---------- 偏好解析 ----------

    @Test
    fun resolve_followsSystemWhenModeIsSystem() {
        assertEquals(ClockStyle.H12, resolveClockStyle(CLOCK_FORMAT_SYSTEM, "12"))
        assertEquals(ClockStyle.H24, resolveClockStyle(CLOCK_FORMAT_SYSTEM, "24"))
        assertEquals("系统值缺失按 24 小时兜底", ClockStyle.H24, resolveClockStyle(CLOCK_FORMAT_SYSTEM, null))
    }

    @Test
    fun resolve_explicitModeOverridesSystem() {
        assertEquals(ClockStyle.H24, resolveClockStyle(CLOCK_FORMAT_24H, "12"))
        assertEquals(ClockStyle.H12, resolveClockStyle(CLOCK_FORMAT_12H, "24"))
        assertEquals("未写过的键按跟随系统处理", ClockStyle.H12, resolveClockStyle(null, "12"))
    }

    // ---------- 格式化 ----------

    @Test
    fun format24_zeroPadsHourAndMinute() {
        assertEquals("23:45", formatClockTime(t2345, ClockStyle.H24, zone))
        assertEquals("09:05", formatClockTime(at(2026, 10, 5, 9, 5), ClockStyle.H24, zone))
        assertEquals("00:00", formatClockTime(at(2026, 10, 5, 0, 0), ClockStyle.H24, zone))
    }

    @Test
    fun format12_usesAmPmAndTwelveHourClock() {
        assertEquals("23:45 → 11:45 PM", "11:45 PM", formatClockTime(t2345, ClockStyle.H12, zone))
        assertEquals(
            "跨天 00:00 → 12:00 AM",
            "12:00 AM",
            formatClockTime(at(2026, 10, 5, 0, 0), ClockStyle.H12, zone),
        )
        assertEquals(
            "正午 → 12:00 PM",
            "12:00 PM",
            formatClockTime(at(2026, 10, 5, 12, 0), ClockStyle.H12, zone),
        )
        assertEquals("9:05 AM", formatClockTime(at(2026, 10, 5, 9, 5), ClockStyle.H12, zone))
    }

    // ---------- 预计结束时刻 ----------

    @Test
    fun endTime_playing_keepsProjectionStableWhileProgressing() {
        val position = 60_000L
        val duration = 120_000L
        val first = estimatedEndTimeMs(t2345, position, duration)
        assertEquals("结束时刻 = 当前时钟 + 剩余", t2345 + 60_000L, first)
        // 播放 30 s 后（位置 +30 s、时钟 +30 s）→ 结束时刻不变
        assertEquals(first, estimatedEndTimeMs(t2345 + 30_000L, position + 30_000L, duration))
    }

    @Test
    fun endTime_paused_usesFrozenClockSoValueDoesNotDrift() {
        val pausedAt = t2345
        val endAtPause = estimatedEndTimeMs(pausedAt, positionMs = 60_000L, durationMs = 120_000L)
        // 暂停 10 分钟后再渲染：仍用冻结时钟（暂停点推算值），显示值不漂移
        val later = estimatedEndTimeMs(pausedAt, positionMs = 60_000L, durationMs = 120_000L)
        assertEquals(endAtPause, later)
        assertEquals("暂停点推算 = 暂停时刻 + 剩余", t2345 + 60_000L, endAtPause)
    }

    @Test
    fun endTime_remainingUnderOneMinute_rollsToNextDay() {
        // 23:59:40，剩 30 s → 次日 00:00:10
        val now = at(2026, 10, 4, 23, 59) + 40_000L
        val end = estimatedEndTimeMs(now, positionMs = 90_000L, durationMs = 120_000L)
        assertEquals("剩余 30 s（<1 分钟）", now + 30_000L, end)
        assertEquals("00:00", formatClockTime(end, ClockStyle.H24, zone))
        assertEquals("12:00 AM", formatClockTime(end, ClockStyle.H12, zone))
    }

    @Test
    fun endTime_positionBeyondDuration_clampsToNow() {
        val end = estimatedEndTimeMs(t2345, positionMs = 200_000L, durationMs = 120_000L)
        assertEquals("已播完 / 越界 → 结束时刻 = 当前时钟", t2345, end)
    }
}
