package com.zhangwenkang.cinefin.presentation.player

import java.time.Instant
import java.time.ZoneId

/**
 * W67 补充：播放器时钟（时间格式偏好 + 预计结束时刻）。
 *
 * 偏好键 `pref_player_clock_format` 取值 = [CLOCK_FORMAT_SYSTEM] / [CLOCK_FORMAT_24H] /
 * [CLOCK_FORMAT_12H]； 「跟随系统」读 `Settings.System.TIME_12_24`（"12" = 12 小时，其余按 24 小时）。
 */
internal const val CLOCK_FORMAT_SYSTEM = "system"
internal const val CLOCK_FORMAT_24H = "24"
internal const val CLOCK_FORMAT_12H = "12"

/** 设置面板的档位顺序（与偏好值一一对应）。 */
internal val PlayerClockFormats = listOf(CLOCK_FORMAT_SYSTEM, CLOCK_FORMAT_24H, CLOCK_FORMAT_12H)

internal enum class ClockStyle {
    H24,
    H12,
}

/** 解析偏好值 + 系统 `TIME_12_24` 为最终样式；显式档位忽略系统值。 */
internal fun resolveClockStyle(mode: String?, systemTime1224: String?): ClockStyle =
    when (mode) {
        CLOCK_FORMAT_24H -> ClockStyle.H24
        CLOCK_FORMAT_12H -> ClockStyle.H12
        else -> if (systemTime1224?.trim() == "12") ClockStyle.H12 else ClockStyle.H24
    }

/**
 * 格式化本地时刻：24 小时 = 零填充 `HH:mm`；12 小时 = `h:mm AM/PM`（12 点档按 12 而非 0）。
 *
 * [zoneId] 只在单测里显式传入（跨天用例），运行时用系统时区。
 */
internal fun formatClockTime(
    epochMs: Long,
    style: ClockStyle,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    val time = Instant.ofEpochMilli(epochMs).atZone(zoneId).toLocalTime()
    return when (style) {
        ClockStyle.H24 -> "%02d:%02d".format(time.hour, time.minute)
        ClockStyle.H12 -> {
            val hour12 = time.hour % 12
            val displayHour = if (hour12 == 0) 12 else hour12
            val marker = if (time.hour < 12) "AM" else "PM"
            "%d:%02d %s".format(displayHour, time.minute, marker)
        }
    }
}

/**
 * 预计结束时刻 = 「当前时钟」+ 剩余时长；剩余 ≤ 0 时就是当前时钟。
 *
 * 播放中「当前时钟」每秒推进（结束时刻保持稳定）；暂停后冻结在暂停点（自洽口径：若就此继续，将在此刻结束）。
 */
internal fun estimatedEndTimeMs(nowEpochMs: Long, positionMs: Long, durationMs: Long): Long =
    nowEpochMs + (durationMs - positionMs).coerceAtLeast(0L)
