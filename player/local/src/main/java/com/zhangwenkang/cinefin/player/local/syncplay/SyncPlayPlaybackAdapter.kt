package com.zhangwenkang.cinefin.player.local.syncplay

import com.zhangwenkang.cinefin.player.core.playback.PlaybackEngine
import com.zhangwenkang.cinefin.player.core.syncplay.SyncPlayPlaybackPort
import com.zhangwenkang.cinefin.player.local.presentation.PlayerViewModel

/**
 * SyncPlay 触发的定位统一带的来源标签（W4-T8d）。
 *
 * 与 `PlaybackSeekSink.submit(intent, source)` 的 source 同义：定位不能绕开 seek sink 的确认 / 排队 / 转码重开会话逻辑，
 * 标签用于日志与 `SeekRequestCoordinator` 的来源排查。
 */
const val SYNC_PLAY_SEEK_SOURCE = "syncplay-command"

/**
 * seek sink 的窄缝（W4-T8d）。
 *
 * 播放口不直接持有 `PlaybackSeekSink`（它会拖进 media3 `Player` 与 Android 时钟，端口就不可纯 JVM 单测），只依赖这一个函数； 生产实现见
 * [playbackSeekSinkDispatcher]。
 */
fun interface SyncPlaySeekDispatcher {
    fun seekTo(positionMs: Long, source: String)
}

/**
 * 生产接线：SyncPlay 的绝对定位走 `PlaybackSeekSink.submit`，复用未就绪排队 / 结束确认 / 转码会话重建。
 *
 * 参数取 `submit` 本身（不是 sink 实例），调用方传 `holder.seekSink::submit`。
 */
fun playbackSeekSinkDispatcher(
    submit: (PlayerViewModel.SeekIntent, String) -> Unit
): SyncPlaySeekDispatcher = SyncPlaySeekDispatcher { positionMs, source ->
    submit(PlayerViewModel.SeekIntent.Absolute(positionMs), source)
}

/**
 * [SyncPlayPlaybackPort] 的播放口实现（W4-T8d）。
 *
 * 动词一律委托 `PlayerHolder.currentEngine`（原地切内核后取到的是新内核，无需重建 port）；读侧走 media3 实例的 `currentPosition` /
 * `isPlaying`——缓冲期 `isPlaying == false` 是状态机「这一轮不校正」的输入，属预期行为。
 *
 * 定位**不**直调引擎：`seekTo` 一律交给 [seekDispatcher]（生产 = seek sink），避免绕开确认逻辑与转码重开会话。
 */
class PlayerSyncPlayPlaybackPort(
    private val engineProvider: () -> PlaybackEngine?,
    private val seekDispatcher: SyncPlaySeekDispatcher,
    private val positionProvider: () -> Long,
    private val isPlayingProvider: () -> Boolean,
) : SyncPlayPlaybackPort {
    override fun play() {
        engineProvider()?.play()
    }

    override fun pause() {
        engineProvider()?.pause()
    }

    override fun seekTo(positionMs: Long) {
        seekDispatcher.seekTo(positionMs, SYNC_PLAY_SEEK_SOURCE)
    }

    override fun setRate(rate: Float) {
        engineProvider()?.setRate(rate)
    }

    override val currentPositionMs: Long
        get() = positionProvider()

    override val isPlaying: Boolean
        get() = isPlayingProvider()
}
