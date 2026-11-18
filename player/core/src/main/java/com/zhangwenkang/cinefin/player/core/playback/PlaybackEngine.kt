package com.zhangwenkang.cinefin.player.core.playback

/**
 * 播放内核的统一控制面（W2-T4）。
 *
 * 只暴露播放控制动词与能力位，不暴露 media3 具体实现类：调用方拿不到内核私有方法，内核怪癖必须在实现内部收口。
 *
 * 线程约定与 media3 一致：所有方法都在主线程调用。
 *
 * 本步只定义接口与逐一委托，不改任何既有状态 / 错误流转；ViewModel 的调用点迁移归 T5。
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

    companion object {
        /** 与 `PlayerHolder.BACKEND_EXOPLAYER` 同值，后者引用这里作为唯一来源。 */
        const val BACKEND_EXOPLAYER = "exoplayer"

        /** 与 `PlayerHolder.BACKEND_MPV` 同值，后者引用这里作为唯一来源。 */
        const val BACKEND_MPV = "mpv"
    }
}
