package com.zhangwenkang.cinefin.player.core.syncplay

/**
 * 一次定位请求的落点确认（W4-T8c）。
 *
 * [requestId] 是状态机发出 [SyncPlayPlaybackPort.seekTo] 时分配的请求号，[positionMs] 是引擎**确认落到**的位置——不是请求的目标位置。
 * 漂移校正的锚点按确认落点重设：seek 从发出到落定有数百毫秒（mpv 解码器重建更久），按 intended target 重锚点会把这条延迟算成永久偏差。
 */
data class SyncPlaySeekConfirmation(val requestId: Long, val positionMs: Long)

/**
 * 状态机对播放内核的最小控制面（W4-T8c）。
 *
 * 纯 JVM 接口：T8d 装配时在 `PlayerHolder.currentEngine` 之上实现，单测全部 fake。语义与 `PlaybackEngine` 同名动词一致（主线程调用），
 * 但只开放 SyncPlay 真正需要的五个动词/读侧，避免状态机碰到条目、队列等实现细节。
 *
 * 缓冲态读不到就不读：引擎正在缓冲时 [isPlaying] 可能短暂为 false，状态机会暂停漂移校正而不是硬拉； 位置读不到（[currentPositionMs] 为
 * null）时同样跳过本轮校正，绝不用占位值参与漂移计算。
 */
interface SyncPlayPlaybackPort {
    fun play()

    fun pause()

    /**
     * 在当前条目内定位（毫秒）。
     *
     * [requestId] 由状态机单调分配，用于把随后的落点确认（[seekConfirmation]）配对回这次请求；实现只需原样带回，不解释其含义。
     */
    fun seekTo(positionMs: Long, requestId: Long)

    /** 播放倍速：漂移校正的 0.95x / 1.05x / 恢复 1.0x 都走这里。 */
    fun setRate(rate: Float)

    /**
     * 当前播放位置（毫秒），读侧用「确认位置」口径：引擎未就绪 / 正在 seek 重建时读不到可信值时返回 null。
     *
     * 状态机拿到 null 就跳过本轮漂移评估——旧实现直接读 raw 引擎位置，mpv 在解码器重建期要么停在 seek 前的旧值、要么回 `C.TIME_UNSET`，
     * 前者让漂移恒越限、后者让漂移变成天文负值，两者都会把校正打成 seek 风暴。
     */
    val currentPositionMs: Long?

    /** 引擎是否真的在播（缓冲 / 暂停 / 未准备都为 false）。 */
    val isPlaying: Boolean

    /**
     * 最近一次定位请求的落点确认；尚未落定、或该实现不提供确认时为 null。
     *
     * 状态机只认 [SyncPlaySeekConfirmation.requestId] 与自己发出的请求号相同的确认，别处的定位（UI 手势等）不会被误当成校正落点。
     */
    val seekConfirmation: SyncPlaySeekConfirmation?
        get() = null

    /**
     * 最近一次定位是否已被播放口判为失败（sink 拒绝 / 引擎错误 / 超时）。
     *
     * 失败是 in-flight 的明确收尾信号：此时按读侧位置重锚点，不必等到窗口上限。
     */
    val lastSeekFailed: Boolean
        get() = false
}
