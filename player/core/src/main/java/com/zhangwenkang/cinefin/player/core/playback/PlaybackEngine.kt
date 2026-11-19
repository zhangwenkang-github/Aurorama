package com.zhangwenkang.cinefin.player.core.playback

import com.zhangwenkang.cinefin.player.core.domain.models.SubtitleStyle

/**
 * 播放内核的统一控制面（W2-T4；W4-T3a-1 补齐轨道 / 延迟 / 样式动词）。
 *
 * 只暴露播放控制动词与能力位，不暴露 media3 具体实现类：调用方拿不到内核私有方法，内核怪癖必须在实现内部收口。
 *
 * 线程约定与 media3 一致：所有方法都在主线程调用。
 *
 * 轨道动词只做底层读写，不重算选择策略：语言优先级归 `TrackSelectionEngine`，字幕启用 / 接管归上层字幕路由； 引擎的 `selectTrack` 相当于「直接落
 * override / 画内核属性」，供原地切换与映射重选使用。
 *
 * 不支持某动词的内核约定为返回空表 / 空操作（见 [EngineCapabilities] 的能力位），不得抛异常。
 */
interface PlaybackEngine {
    /** 当前内核的静态能力位；实例生命周期内不变。 */
    val capabilities: EngineCapabilities

    /** 内核标识，取值与偏好 `pref_player_backend` 一致：`exoplayer` / `mpv`。 */
    val backendName: String

    /**
     * 准备当前队列（起播前的 prepare）。
     *
     * 具体语义由内核自行解释，调用方不得假设「先载 0 号条」这类实现细节；mpv 的已知怪癖见 `MpvPlaybackEngine.prepare()` 的 KDoc。
     */
    fun prepare()

    fun play()

    fun pause()

    /** 在当前条目内定位。 */
    fun seekTo(positionMs: Long)

    /**
     * 跨条目定位：跳到第 [itemIndex] 条目的 [positionMs]。
     *
     * 重试 / 换集必须用这个重载，位置才不会落到错误的条目上下文里。
     */
    fun seekTo(itemIndex: Int, positionMs: Long)

    /** 播放速度倍率。 */
    fun setRate(rate: Float)

    fun release()

    // ---------- 轨道（W4-T3a-1） ----------

    /**
     * 枚举 [type] 当前可选的轨道（按内核内的稳定顺序）。
     *
     * 没有轨道、轨道尚未就绪或内核不支持枚举时返回空表，不抛异常。
     */
    fun availableTracks(type: EngineTrackType): List<EngineTrack>

    /** 当前选中的轨道 id；null = 该类型未启用（字幕关闭最常见）。 */
    fun selectedTrack(type: EngineTrackType): String?

    /**
     * 选择轨道。[trackId] = null 只在 [EngineTrackType.SUBTITLE] 上表示「关闭字幕」；其余类型传 null 视为无操作。
     *
     * 只做底层写：Exo 落 `TrackSelectionParameters` 的 override / 禁用位，mpv 写 `sid` / `aid` / `vid` 属性。
     * 语言优先级、字幕自定义层接管等策略仍归上层，本动词不参与重算。
     */
    fun selectTrack(type: EngineTrackType, trackId: String?)

    // ---------- 时间偏移（W4-T3a-1） ----------

    /** 字幕时间偏移（毫秒，正 = 字幕延后）；两个内核都在 ±10s 内夹取。 */
    fun setSubtitleDelay(delayMs: Long)

    /** 当前字幕时间偏移（毫秒）。 */
    fun getSubtitleDelay(): Long

    /** 音轨时间偏移（毫秒，正 = 声音延后）；两个内核都在 ±5s 内夹取。 */
    fun setAudioDelay(delayMs: Long)

    /** 当前音轨时间偏移（毫秒）。 */
    fun getAudioDelay(): Long

    // ---------- 字幕外观（W4-T3a-1） ----------

    /** 字幕外观；不支持的内核无操作（能力位见 [EngineCapabilities.supportsSubtitleStyling]）。 */
    fun setSubtitleStyle(style: SubtitleStyle)

    companion object {
        /** 与 `PlayerHolder.BACKEND_EXOPLAYER` 同值，后者引用这里作为唯一来源。 */
        const val BACKEND_EXOPLAYER = "exoplayer"

        /** 与 `PlayerHolder.BACKEND_MPV` 同值，后者引用这里作为唯一来源。 */
        const val BACKEND_MPV = "mpv"
    }
}
