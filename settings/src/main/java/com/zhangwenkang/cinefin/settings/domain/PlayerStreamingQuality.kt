package com.zhangwenkang.cinefin.settings.domain

/**
 * 播放码率档位（W12 反馈 B）：与 Jellyfin 官方客户端的「质量 / 码率」选择对齐。
 *
 * 三个语义：
 * - [AUTO]（0）：不设码率上限，服务器自行判断直连还是转码（默认，与改造前的行为一致）；
 * - [ORIGINAL]（-1）：只直连播放，明确不允许服务器转码；
 * - 具体 Mbps（>0）：按该码率上限请求服务器转码，播放返回的 `transcodingPath`。
 *
 * 抽成纯函数（+ 单测）是因为它同时被 data 层（构造 PlaybackInfo 请求）与播放页 UI（档位显示）使用， 两边必须用同一套映射，否则会出现「选了 8 Mbps 但请求里还是 1
 * Gbps」这类静默不一致。
 */
object PlayerStreamingQuality {
    /** 自动：服务器自行判断 */
    const val AUTO: Long = 0L

    /** 原始画质：只直连、不转码 */
    const val ORIGINAL: Long = -1L

    /** 不设上限时的码率值（1 Gbps ≈ 无上限，沿用改造前的常量） */
    const val UNLIMITED_BITRATE: Long = 1_000_000_000L

    /** 具体档位（Mbps）：覆盖常见码率区间，数值与 Jellyfin 官方客户端档位同量级 */
    val PRESET_MBPS: List<Int> = listOf(1, 2, 3, 5, 8, 12, 20, 40)

    /** 档位 → 请求里的 `maxStreamingBitrate`（bps） */
    fun maxStreamingBitrate(preference: Long): Long =
        if (preference > 0L) preference * 1_000_000L else UNLIMITED_BITRATE

    /** 档位 → 是否允许服务器转码（原始画质明确只直连） */
    fun transcodingEnabled(preference: Long): Boolean = preference != ORIGINAL

    /** 档位 → 面板标签；自动 / 原始画质由调用方按资源字符串显示 */
    fun bitrateLabel(preference: Long): String =
        if (preference > 0L) "$preference Mbps" else "$preference"

    /** 是否是需要请求服务器转码的具体码率档 */
    fun requestsTranscoding(preference: Long): Boolean = preference > 0L
}
