package com.zhangwenkang.cinefin.player.core.playback

/**
 * 内核能力位（W2-T4）。
 *
 * 取值是两套内核的**当前实现**，不是目标能力：翻转任何一位都要有真机或契约证据，并在 ADR-0010 的取值表里同步。
 *
 * 纯数据类，无 Android 依赖，JVM 单测可直接断言。
 */
data class EngineCapabilities(
    /** 能否播放 HLS（m3u8）：直放与服务器转码流都走它。 */
    val canPlayHls: Boolean,
    /** 能否本地软解：Exo 靠扩展渲染器 + 解码器回退，mpv 靠 `hwdec=no`。 */
    val supportsSoftwareDecode: Boolean,
    /** 字幕外观（字体 / 字号 / 间距 / 背景）能否生效：mpv 走 libass 原生，Exo 走自绘字幕层。 */
    val supportsSubtitleStyling: Boolean,
    /** 音轨延迟（正 = 声音延后）能否生效。 */
    val supportsAudioDelay: Boolean,
    /** 能否加载侧载字幕文件（导入 App 私有目录的外来字幕）。 */
    val supportsSideloadedSubtitles: Boolean,
    /** 内核是否接受随机播放（media3 `COMMAND_SET_SHUFFLE_MODE`）。 */
    val supportsShuffleMode: Boolean,
    /** 能否枚举与选择轨道（W4-T3a-1）：Exo 走 `Tracks`，mpv 走 `track-list`。 */
    val supportsTrackEnumeration: Boolean,
    /**
     * 字幕时间偏移能否生效（W4-T3a-1）。
     *
     * 与 [supportsAudioDelay] 对称：音频延迟在 Exo 由 `AudioDelayProcessor` 兑现，字幕延迟在 Exo 由自研字幕覆盖层
     * （`PlayerSubtitleController`）兑现、mpv 由 `sub-delay` 属性兑现——都算「内核面支持」，只是执行者不一定是内核本身。
     */
    val supportsSubtitleDelay: Boolean,
)
