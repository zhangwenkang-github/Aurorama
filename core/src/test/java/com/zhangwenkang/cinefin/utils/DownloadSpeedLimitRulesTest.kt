package com.zhangwenkang.cinefin.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/** W57 下载限速换算 / 钳制 / 分段节流纯函数单测。 */
class DownloadSpeedLimitRulesTest {

    @Test
    fun `默认不限速且越界钳制到0到100`() {
        assertEquals(0, DownloadSpeedLimitRules.DEFAULT_MBPS)
        assertEquals(0, DownloadSpeedLimitRules.coerceMbps(-5))
        assertEquals(0, DownloadSpeedLimitRules.coerceMbps(0))
        assertEquals(1, DownloadSpeedLimitRules.coerceMbps(1))
        assertEquals(100, DownloadSpeedLimitRules.coerceMbps(100))
        assertEquals(100, DownloadSpeedLimitRules.coerceMbps(999))
    }

    @Test
    fun `MB每秒换算为字节每秒`() {
        assertEquals(0L, DownloadSpeedLimitRules.bytesPerSecond(0))
        assertEquals(1_048_576L, DownloadSpeedLimitRules.bytesPerSecond(1))
        assertEquals(50L * 1024L * 1024L, DownloadSpeedLimitRules.bytesPerSecond(50))
        assertEquals(100L * 1024L * 1024L, DownloadSpeedLimitRules.bytesPerSecond(200))
    }

    @Test
    fun `不限速时无需等待`() {
        assertEquals(
            0L,
            DownloadThrottle.waitMillis(
                bytesSinceStart = 1_000_000L,
                elapsedMillis = 0L,
                limitBytesPerSecond = 0L,
            ),
        )
    }

    @Test
    fun `节流等待按平均速率补足`() {
        // 512 KB 在 1 MB/s 下限速 = 500ms；已经过 200ms → 再等 300ms。
        assertEquals(
            300L,
            DownloadThrottle.waitMillis(
                bytesSinceStart = 512L * 1024L,
                elapsedMillis = 200L,
                limitBytesPerSecond = 1024L * 1024L,
            ),
        )
        // 已超过应有的时间：不等待。
        assertEquals(
            0L,
            DownloadThrottle.waitMillis(
                bytesSinceStart = 512L * 1024L,
                elapsedMillis = 900L,
                limitBytesPerSecond = 1024L * 1024L,
            ),
        )
        // 无字节：不等待。
        assertEquals(
            0L,
            DownloadThrottle.waitMillis(
                bytesSinceStart = 0L,
                elapsedMillis = 0L,
                limitBytesPerSecond = 1024L * 1024L,
            ),
        )
    }
}
