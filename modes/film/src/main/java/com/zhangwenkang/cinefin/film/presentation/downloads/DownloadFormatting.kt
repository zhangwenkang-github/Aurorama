package com.zhangwenkang.cinefin.film.presentation.downloads

import java.util.Locale

/**
 * W52 下载页纯格式化（无 Android 依赖，单测覆盖）。
 *
 * 口径（`DOWNLOAD_PLAN.md` §19）：
 *
 * - 体积 = 1024 进制，1 位 / 2 位小数按量级收敛（10 以下 2 位、100 以下 1 位、其余整数）；
 * - 速度 = 体积 + `/s`，未知（0 / 负数）显示占位「[PLACEHOLDER]」；
 * - 剩余时间 = `h:mm:ss` / `m:ss`，未知显示占位「[PLACEHOLDER]」；
 * - 「x/y」= 已完成条目数 / 总条目数，供容器「已下载 x/y」文案使用。
 */
object DownloadFormatRules {

    /** 缺数据占位（速度 / 剩余时间未知时显示）。 */
    const val PLACEHOLDER = "—"

    private val units = listOf("B", "KB", "MB", "GB", "TB", "PB")

    fun formatBytes(bytes: Long): String {
        val value = bytes.coerceAtLeast(0L)
        if (value < 1024L) return "$value B"
        var unitIndex = 0
        var scaled = value.toDouble()
        while (scaled >= 1024.0 && unitIndex < units.lastIndex) {
            scaled /= 1024.0
            unitIndex++
        }
        val text =
            when {
                scaled >= 100.0 -> String.format(Locale.US, "%.0f", scaled)
                scaled >= 10.0 -> String.format(Locale.US, "%.1f", scaled)
                else -> String.format(Locale.US, "%.2f", scaled)
            }
        return "$text ${units[unitIndex]}"
    }

    /** 下载速度（bytes/s）；未知速度显示占位「—」。 */
    fun formatSpeed(bytesPerSecond: Long): String =
        if (bytesPerSecond <= 0L) PLACEHOLDER else "${formatBytes(bytesPerSecond)}/s"

    /** 剩余时间（秒 → `m:ss` / `h:mm:ss`）；未知显示占位「—」。 */
    fun formatEta(seconds: Long?): String {
        if (seconds == null || seconds < 0L) return PLACEHOLDER
        val hours = seconds / 3600L
        val minutes = seconds % 3600L / 60L
        val rest = seconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, rest)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, rest)
        }
    }

    /** 容器「x/y」计数（已完成 / 总数）。 */
    fun formatCountProgress(completed: Int, total: Int): String =
        "${completed.coerceAtLeast(0)}/${total.coerceAtLeast(0)}"

    /** 进度百分比文本（0–100%）。 */
    fun formatPercent(progress: Float): String = "${(progress.coerceIn(0f, 1f) * 100f).toInt()}%"

    /** 「已用 / 总大小」；总大小未知时只给已用体积。 */
    fun formatSizePair(downloadedBytes: Long, totalBytes: Long): String {
        val downloaded = formatBytes(downloadedBytes)
        return if (totalBytes > 0L) "$downloaded / ${formatBytes(totalBytes)}" else downloaded
    }
}
