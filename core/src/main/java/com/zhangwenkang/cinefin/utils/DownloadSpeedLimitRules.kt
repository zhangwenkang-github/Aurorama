package com.zhangwenkang.cinefin.utils

/**
 * W57 下载限速规则（纯函数，单测覆盖）。
 *
 * 用户口径：0–100 MB/s，0 = 不限速；语义 = 任务会话的平均吞吐不超过设定值。
 */
object DownloadSpeedLimitRules {

    /** 限速下限 / 上限 / 默认值（偏好 `pref_download_speed_limit_mbps`）。 */
    const val MIN_MBPS = 0
    const val MAX_MBPS = 100
    const val DEFAULT_MBPS = 0

    private const val BYTES_PER_MB = 1024L * 1024L

    /** 越界 / 非法输入的钳制入口（0 = 不限速）。 */
    fun coerceMbps(value: Int): Int = value.coerceIn(MIN_MBPS, MAX_MBPS)

    /** MB/s → 字节/秒；0 = 不限速（引擎按 0 处理为不节流）。 */
    fun bytesPerSecond(value: Int): Long = coerceMbps(value).toLong() * BYTES_PER_MB
}

/**
 * W57 分段节流计算（纯函数，单测覆盖）。
 *
 * 语义 = 令牌桶的简化实现：按「本次会话平均速率 ≤ limit」计算需要等待的毫秒数，由传输层在每个读块后调用。 允许小段突发、长期平均不超限。
 */
object DownloadThrottle {

    /**
     * @param bytesSinceStart 本次会话已读取字节（不含续传起点之前的字节）
     * @param elapsedMillis 本次会话已耗时
     * @param limitBytesPerSecond ≤ 0 = 不限速
     * @return 还需要等待的毫秒数（≥ 0）
     */
    fun waitMillis(bytesSinceStart: Long, elapsedMillis: Long, limitBytesPerSecond: Long): Long {
        if (limitBytesPerSecond <= 0L || bytesSinceStart <= 0L) return 0L
        val expectedMillis = bytesSinceStart * 1000L / limitBytesPerSecond
        return (expectedMillis - elapsedMillis).coerceAtLeast(0L)
    }
}
