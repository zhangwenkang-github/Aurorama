package com.zhangwenkang.cinefin.player.local.syncplay

import android.os.SystemClock
import com.zhangwenkang.cinefin.player.core.playback.PlaybackEngine
import com.zhangwenkang.cinefin.player.core.syncplay.SyncPlayPlaybackPort
import com.zhangwenkang.cinefin.player.core.syncplay.SyncPlaySeekConfirmation
import com.zhangwenkang.cinefin.player.local.presentation.PlayerViewModel
import timber.log.Timber

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
    /** 提交定位，返回 seek sink 的请求代次（用于把落点确认配对回这次请求）；0 = 本次提交无法确认（无实例 / 未排队）。 */
    fun seekTo(positionMs: Long, source: String): Long
}

/**
 * 生产接线：SyncPlay 的绝对定位走 `PlaybackSeekSink.submit`，复用未就绪排队 / 结束确认 / 转码会话重建。
 *
 * 参数取提交函数本身（不是 sink 实例），返回值是 seek sink 为这次请求分配的代次，供落点确认配对用。
 */
fun playbackSeekSinkDispatcher(
    submit: (PlayerViewModel.SeekIntent, String) -> Long
): SyncPlaySeekDispatcher = SyncPlaySeekDispatcher { positionMs, source ->
    submit(PlayerViewModel.SeekIntent.Absolute(positionMs), source)
}

/**
 * [SyncPlayPlaybackPort] 的播放口实现（W4-T8d）。
 *
 * 动词一律委托 `PlayerHolder.currentEngine`（原地切内核后取到的是新内核，无需重建 port）；读侧走 [positionProvider]（确认位置口径， 读不到为
 * null）与 `isPlaying`——缓冲期 `isPlaying == false` / 位置读不到都是状态机「这一轮不校正」的输入，属预期行为。
 *
 * 定位**不**直调引擎：`seekTo` 一律交给 [seekDispatcher]（生产 = seek sink），避免绕开确认逻辑与转码重开会话。落点确认经
 * [confirmationProvider] 从 seek sink 的 `Confirmed` 状态取，用 seek sink 的请求代次与状态机请求号配对。
 */
class PlayerSyncPlayPlaybackPort(
    private val engineProvider: () -> PlaybackEngine?,
    private val seekDispatcher: SyncPlaySeekDispatcher,
    private val positionProvider: () -> Long?,
    private val isPlayingProvider: () -> Boolean,
    private val confirmationProvider: (requestGeneration: Long) -> Long? = { null },
    private val failureProvider: (requestGeneration: Long) -> Boolean = { false },
    /** 单调时钟（毫秒），只为读侧插值服务；单测可注入虚拟时钟。 */
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : SyncPlayPlaybackPort {

    /** 最近一次提交仍待确认的定位：(状态机请求号, seek sink 请求代次)。 */
    private var pendingConfirmation: Pair<Long, Long>? = null

    /** 读侧插值状态：mpv 的 `time-pos` 是 1s 粒度的快照，不插值会让漂移估计永久带 ±1s 台阶。 */
    private var lastPositionMs: Long? = null
    private var lastPositionAtMs: Long = 0L

    override fun play() {
        engineProvider()?.play()
    }

    override fun pause() {
        engineProvider()?.pause()
    }

    override fun seekTo(positionMs: Long, requestId: Long) {
        // 真机取证锚点：SyncPlay 触发的定位一律带该来源标签（与 seek sink 的 source 同义）。
        Timber.d(
            "SyncPlay 定位：target=%d ms request=%d source=%s",
            positionMs,
            requestId,
            SYNC_PLAY_SEEK_SOURCE,
        )
        val generation = seekDispatcher.seekTo(positionMs, SYNC_PLAY_SEEK_SOURCE)
        pendingConfirmation = if (generation > 0L) requestId to generation else null
    }

    override fun setRate(rate: Float) {
        engineProvider()?.setRate(rate)
    }

    /**
     * 读侧位置：在确认位置之上做播放中插值。
     *
     * mpv 的 `time-pos` 只有整秒粒度（每秒一个快照），直接拿来算漂移会让 600ms 档的 mpv 永远判越限—— 真机上表现为「每次落定后仍差 ~1s → 每 2
     * 秒再校正一次」。这里按墙钟把两次快照之间补成连续值， 快照跳进时直接采用更大值，绝不回退。
     */
    override val currentPositionMs: Long?
        get() {
            val raw = positionProvider() ?: return null
            val now = clock()
            val previous = lastPositionMs
            val projected =
                if (previous != null && isPlayingProvider()) {
                    previous + (now - lastPositionAtMs).coerceAtLeast(0L)
                } else {
                    raw
                }
            val value = maxOf(raw, projected)
            lastPositionMs = value
            lastPositionAtMs = now
            return value
        }

    override val isPlaying: Boolean
        get() = isPlayingProvider()

    override val seekConfirmation: SyncPlaySeekConfirmation?
        get() {
            val (requestId, generation) = pendingConfirmation ?: return null
            val positionMs = confirmationProvider(generation) ?: return null
            return SyncPlaySeekConfirmation(requestId, positionMs)
        }

    override val lastSeekFailed: Boolean
        get() {
            val (_, generation) = pendingConfirmation ?: return false
            return failureProvider(generation)
        }
}
