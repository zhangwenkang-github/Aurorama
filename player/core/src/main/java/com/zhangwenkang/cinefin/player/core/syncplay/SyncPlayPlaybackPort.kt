package com.zhangwenkang.cinefin.player.core.syncplay

/**
 * 状态机对播放内核的最小控制面（W4-T8c）。
 *
 * 纯 JVM 接口：T8d 装配时在 `PlayerHolder.currentEngine` 之上实现，单测全部 fake。语义与 `PlaybackEngine` 同名动词一致（主线程调用），
 * 但只开放 SyncPlay 真正需要的五个动词/读侧，避免状态机碰到条目、队列等实现细节。
 *
 * 缓冲态读不到就不读：引擎正在缓冲时 [isPlaying] 可能短暂为 false，状态机会暂停漂移校正而不是硬拉。
 */
interface SyncPlayPlaybackPort {
    fun play()

    fun pause()

    /** 在当前条目内定位（毫秒）。 */
    fun seekTo(positionMs: Long)

    /** 播放倍速：漂移校正的 0.95x / 1.05x / 恢复 1.0x 都走这里。 */
    fun setRate(rate: Float)

    /** 当前播放位置（毫秒）。 */
    val currentPositionMs: Long

    /** 引擎是否真的在播（缓冲 / 暂停 / 未准备都为 false）。 */
    val isPlaying: Boolean
}
