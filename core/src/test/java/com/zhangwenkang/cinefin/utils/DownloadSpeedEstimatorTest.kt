package com.zhangwenkang.cinefin.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** W50 下载速度 / ETA 滑动窗口纯函数单测。 */
class DownloadSpeedEstimatorTest {

    @Test
    fun `窗口内平均速度按实际间隔计算`() {
        val samples =
            listOf(
                DownloadSpeedSample(timestampMs = 0L, bytes = 0L),
                DownloadSpeedSample(timestampMs = 1_000L, bytes = 2_000L),
                DownloadSpeedSample(timestampMs = 2_000L, bytes = 4_000L),
            )
        assertEquals(2_000L, DownloadSpeedRules.speedBytesPerSecond(samples, 5_000L))
    }

    @Test
    fun `裁剪保留窗口外最近基线`() {
        val samples =
            listOf(
                DownloadSpeedSample(timestampMs = 0L, bytes = 0L),
                DownloadSpeedSample(timestampMs = 4_000L, bytes = 4_000L),
                DownloadSpeedSample(timestampMs = 10_000L, bytes = 11_000L),
            )
        val pruned = DownloadSpeedRules.prune(samples, nowMs = 10_000L, windowMs = 5_000L)
        assertEquals(2, pruned.size)
        assertEquals(4_000L, pruned.first().bytes)
        // 7000 字节 / 5 秒窗口（实际间隔 6 秒但按窗口上限折算）= 1400 B/s
        assertEquals(1_400L, DownloadSpeedRules.speedBytesPerSecond(pruned, 5_000L))
    }

    @Test
    fun `样本不足或时间未前进返回零`() {
        assertEquals(0L, DownloadSpeedRules.speedBytesPerSecond(emptyList(), 5_000L))
        assertEquals(
            0L,
            DownloadSpeedRules.speedBytesPerSecond(
                listOf(DownloadSpeedSample(timestampMs = 100L, bytes = 500L)),
                5_000L,
            ),
        )
        assertEquals(
            0L,
            DownloadSpeedRules.speedBytesPerSecond(
                listOf(
                    DownloadSpeedSample(timestampMs = 100L, bytes = 500L),
                    DownloadSpeedSample(timestampMs = 100L, bytes = 900L),
                ),
                5_000L,
            ),
        )
    }

    @Test
    fun `ETA 向上取整_未知返回空_已完成归零`() {
        assertEquals(10L, DownloadSpeedRules.etaSeconds(10_000L, 0L, 1_000L))
        assertEquals(1L, DownloadSpeedRules.etaSeconds(10_000L, 9_500L, 1_000L))
        assertEquals(0L, DownloadSpeedRules.etaSeconds(10_000L, 10_000L, 1_000L))
        assertNull(DownloadSpeedRules.etaSeconds(0L, 0L, 1_000L))
        assertNull(DownloadSpeedRules.etaSeconds(10_000L, 0L, 0L))
    }

    @Test
    fun `速度计按注入时钟推进并可算 ETA`() {
        var now = 0L
        val meter = DownloadSpeedMeter(windowMs = 5_000L, clock = { now })
        meter.onProgress(downloadedBytes = 0L)
        now = 1_000L
        meter.onProgress(downloadedBytes = 2_000L)
        now = 2_000L
        meter.onProgress(downloadedBytes = 4_000L)
        assertEquals(2_000L, meter.speedBytesPerSecond())
        assertEquals(3L, meter.etaSeconds(totalBytes = 10_000L, downloadedBytes = 4_000L))
        meter.reset()
        assertEquals(0L, meter.speedBytesPerSecond())
        assertNull(meter.etaSeconds(totalBytes = 10_000L, downloadedBytes = 4_000L))
    }
}
