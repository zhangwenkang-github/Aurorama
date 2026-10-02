package com.zhangwenkang.cinefin.music.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** 睡眠定时剩余时间文案（W21-R2）。 */
class MusicSleepTimerTest {

    @Test
    fun `剩余时间向上取整到秒并保持 mm ss`() {
        assertEquals("00:00", formatSleepRemaining(0L))
        assertEquals("00:01", formatSleepRemaining(999L))
        assertEquals("01:00", formatSleepRemaining(60_000L))
        assertEquals("59:01", formatSleepRemaining(59 * 60_000L + 500L))
        // 60 分钟档位初始值
        assertEquals("60:00", formatSleepRemaining(60 * 60_000L))
        // 负值按 0 处理
        assertEquals("00:00", formatSleepRemaining(-5L))
    }
}
