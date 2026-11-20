package com.zhangwenkang.cinefin.settings.domain

/**
 * 音乐音质档位（MUSIC-03；V2-D116 旧裁决口径）。
 *
 * 三语义，与视频的 [PlayerStreamingQuality] 同构：
 * - [AUTO]（0）：不设码率上限，能直连就直连，解码不了才由服务端转码；
 * - [ORIGINAL]（-1）：只直连、禁止转码；
 * - 具体档位（>0）：规范值就是 bps，既是最低可接受码率上限，也是转码目标码率。
 *
 * 抽成纯对象（+ 单测）是因为它同时被 `data` 层（构造 `/Audio/{itemId}/universal` 请求）与音乐播放链路（决策直连 / 转码）消费，两边必须同源。
 */
object MusicStreamingQuality {
    /** 自动（默认）：服务端按能力自行判断。 */
    const val AUTO: Long = 0L

    /** 原始：只直连，明确禁止服务端转码。 */
    const val ORIGINAL: Long = -1L

    /** 可选档位（bps，越小音质越低）。 */
    val presets: List<Long> = listOf(320_000L, 256_000L, 192_000L, 128_000L)

    /** 档位语义是否合法（越界值按 [AUTO] 处理，避免旧值 / 脏值把播放卡死）。 */
    fun normalize(preference: Long): Long =
        when {
            preference == ORIGINAL -> ORIGINAL
            preference in presets -> preference
            else -> AUTO
        }

    /** 档位 → 码率上限（bps）；0 = 不设上限（自动）。 */
    fun bitrateCeilingBps(preference: Long): Long =
        normalize(preference).let { if (it > 0L) it else 0L }

    /** 档位 → 是否允许服务端转码（原始档明确禁止）。 */
    fun transcodingEnabled(preference: Long): Boolean = normalize(preference) != ORIGINAL

    /** 档位 → 显示标签（`自动` / `原始` / `320 kbps` …）。 */
    fun label(preference: Long): String =
        when (val normalized = normalize(preference)) {
            AUTO -> "自动"
            ORIGINAL -> "原始"
            else -> "${normalized / 1_000} kbps"
        }
}
