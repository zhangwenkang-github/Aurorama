package com.zhangwenkang.cinefin.utils

import android.os.SystemClock

/** W50：单个进度采样点（时间 + 累计字节数）。 */
data class DownloadSpeedSample(val timestampMs: Long, val bytes: Long)

/**
 * W50 下载速度 / ETA 纯函数（滑动窗口，单测覆盖）。
 *
 * 速度口径 = 窗口内「最晚样本 - 最早样本」的差值 / 实际间隔，避免重启或长暂停后的历史字节被计入瞬时速度。
 */
object DownloadSpeedRules {
    /** 默认滑动窗口：5 秒。 */
    const val DEFAULT_WINDOW_MS = 5_000L

    /** 裁剪窗口外样本；保留窗口边界前最近的一个样本作为求速率基线。 */
    fun prune(
        samples: List<DownloadSpeedSample>,
        nowMs: Long,
        windowMs: Long = DEFAULT_WINDOW_MS,
    ): List<DownloadSpeedSample> {
        if (samples.isEmpty()) return emptyList()
        if (windowMs <= 0L) return samples.takeLast(1)
        val cutoff = nowMs - windowMs
        val baseline = samples.lastOrNull { it.timestampMs < cutoff }
        val inside = samples.filter { it.timestampMs >= cutoff }
        return if (baseline == null) inside else listOf(baseline) + inside
    }

    /** 窗口内平均速度（bytes/s）；样本不足或时间未前进时返回 0。 */
    fun speedBytesPerSecond(
        samples: List<DownloadSpeedSample>,
        windowMs: Long = DEFAULT_WINDOW_MS,
    ): Long {
        if (samples.size < 2) return 0L
        val first = samples.first()
        val last = samples.last()
        val elapsed = last.timestampMs - first.timestampMs
        if (elapsed <= 0L) return 0L
        val delta = last.bytes - first.bytes
        if (delta <= 0L) return 0L
        val window = if (windowMs > 0L) minOf(elapsed, windowMs) else elapsed
        return delta * 1000L / window
    }

    /** ETA（秒，向上取整）；总大小或速度未知返回 null，已完成返回 0。 */
    fun etaSeconds(totalBytes: Long, downloadedBytes: Long, speedBytesPerSecond: Long): Long? {
        if (totalBytes <= 0L || speedBytesPerSecond <= 0L) return null
        val remaining = (totalBytes - downloadedBytes).coerceAtLeast(0L)
        if (remaining == 0L) return 0L
        return (remaining + speedBytesPerSecond - 1L) / speedBytesPerSecond
    }
}

/**
 * W50 滑动窗口速度计（内存态，clock 可注入以便 JVM 单测）。
 *
 * 下载协程每收到一段字节调用 [onProgress]；进程重启后自然归零，由 UI 显示「--」。
 */
class DownloadSpeedMeter(
    private val windowMs: Long = DownloadSpeedRules.DEFAULT_WINDOW_MS,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private var samples: List<DownloadSpeedSample> = emptyList()
    private var currentSpeed = 0L

    fun reset() {
        samples = emptyList()
        currentSpeed = 0L
    }

    fun onProgress(downloadedBytes: Long, nowMs: Long = clock()) {
        samples =
            DownloadSpeedRules.prune(
                samples + DownloadSpeedSample(timestampMs = nowMs, bytes = downloadedBytes),
                nowMs = nowMs,
                windowMs = windowMs,
            )
        currentSpeed = DownloadSpeedRules.speedBytesPerSecond(samples, windowMs)
    }

    fun speedBytesPerSecond(): Long = currentSpeed

    fun etaSeconds(totalBytes: Long, downloadedBytes: Long): Long? =
        DownloadSpeedRules.etaSeconds(totalBytes, downloadedBytes, currentSpeed)
}
