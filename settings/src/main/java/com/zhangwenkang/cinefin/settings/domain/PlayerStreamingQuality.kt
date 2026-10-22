package com.zhangwenkang.cinefin.settings.domain

/**
 * 一个具体播放质量档。
 *
 * [labelHeight] 参考 Android client `v2.7.3` 快照的非协议硬限制标签：它只用于显示官方档位语义，不会作为 `maxWidth/maxHeight`
 * 发给服务器，也不保证服务端最终输出该分辨率。
 */
data class QualityPreset(val bitsPerSecond: Long, val labelHeight: Int)

/**
 * 播放码率档位：采用 Jellyfin Android phone `v2.7.3` 的质量档快照。
 *
 * 三个语义：
 * - [AUTO]（0）：不设码率上限，服务器自行判断直连还是转码（默认，与改造前的行为一致）；
 * - [ORIGINAL]（-1）：只直连播放，明确不允许服务器转码；
 * - 具体档位（>0）：规范值就是 bps，只映射请求里的 `maxStreamingBitrate`。
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

    private val ANDROID_PHONE_V273_PRESETS: List<QualityPreset> =
        listOf(
            QualityPreset(bitsPerSecond = 120_000_000L, labelHeight = 2160),
            QualityPreset(bitsPerSecond = 80_000_000L, labelHeight = 2160),
            QualityPreset(bitsPerSecond = 60_000_000L, labelHeight = 1080),
            QualityPreset(bitsPerSecond = 40_000_000L, labelHeight = 1080),
            QualityPreset(bitsPerSecond = 20_000_000L, labelHeight = 1080),
            QualityPreset(bitsPerSecond = 15_000_000L, labelHeight = 1080),
            QualityPreset(bitsPerSecond = 10_000_000L, labelHeight = 1080),
            QualityPreset(bitsPerSecond = 8_000_000L, labelHeight = 720),
            QualityPreset(bitsPerSecond = 6_000_000L, labelHeight = 720),
            QualityPreset(bitsPerSecond = 4_000_000L, labelHeight = 720),
            QualityPreset(bitsPerSecond = 3_000_000L, labelHeight = 480),
            QualityPreset(bitsPerSecond = 1_500_000L, labelHeight = 480),
            QualityPreset(bitsPerSecond = 720_000L, labelHeight = 480),
            QualityPreset(bitsPerSecond = 420_000L, labelHeight = 360),
        )

    /** Android phone `v2.7.3` 完整档表；标签只用于显示，不是服务端硬限制。 */
    fun qualityPresets(): List<QualityPreset> = ANDROID_PHONE_V273_PRESETS

    /**
     * 旧键迁移：旧值明确是整数 Mbps，先乘 1,000,000，再向下对齐不超过原请求的最近官方档。
     *
     * 只能对旧键使用。已经是 bps 的新键值不得调用此函数。
     */
    fun migrateLegacyMbpsToBps(legacyValue: Long): Long {
        if (legacyValue <= 0L) return legacyValue
        val requestedBitsPerSecond = legacyValue * 1_000_000L
        return qualityPresets().first { it.bitsPerSecond <= requestedBitsPerSecond }.bitsPerSecond
    }

    /** 根据一次性迁移标记解释存储值：未迁移只读旧 Mbps 键；已迁移只读新 bps 键，绝不再次换算。 */
    fun resolveStoredPreference(
        legacyValue: Long,
        bitsPerSecondValue: Long,
        migratedToBitsPerSecond: Boolean,
    ): Long =
        if (migratedToBitsPerSecond) {
            bitsPerSecondValue
        } else {
            migrateLegacyMbpsToBps(legacyValue)
        }

    /** 档位 → 请求里的 `maxStreamingBitrate`；规范值已含单位，不再乘 1,000,000。 */
    fun maxStreamingBitrate(preference: Long): Long =
        if (preference > 0L) preference else UNLIMITED_BITRATE

    /** 档位 → 是否允许服务器转码（原始画质明确只直连） */
    fun transcodingEnabled(preference: Long): Boolean = preference != ORIGINAL

    /** 档位 → 官方标签 + 码率；自动 / 原始画质由调用方按资源字符串显示。 */
    fun bitrateLabel(preference: Long): String {
        val preset = qualityPresets().firstOrNull { it.bitsPerSecond == preference }
        return if (preset != null) {
            "${preset.labelHeight}p · ${formatBitrate(preset.bitsPerSecond)}"
        } else {
            if (preference > 0L) formatBitrate(preference) else "$preference"
        }
    }

    private fun formatBitrate(bitsPerSecond: Long): String =
        when {
            bitsPerSecond % 1_000_000L == 0L -> "${bitsPerSecond / 1_000_000L} Mbps"
            bitsPerSecond % 1_000L == 0L && bitsPerSecond < 1_000_000L ->
                "${bitsPerSecond / 1_000L} kbps"
            else -> {
                val wholeMbps = bitsPerSecond / 1_000_000L
                val decimal = (bitsPerSecond % 1_000_000L) / 100_000L
                "$wholeMbps.$decimal Mbps"
            }
        }

    /** 是否是需要请求服务器转码的具体码率档 */
    fun requestsTranscoding(preference: Long): Boolean = preference > 0L
}
