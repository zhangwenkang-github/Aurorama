package com.zhangwenkang.cinefin.player.local.domain

import android.os.SystemClock
import com.zhangwenkang.cinefin.player.core.domain.models.SleepTimerSpec
import com.zhangwenkang.cinefin.player.core.domain.models.SleepTimerStateMachine
import com.zhangwenkang.cinefin.player.local.presentation.PlayerHolder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 睡眠定时统一状态源（W55；W5-T6 补「播完当前曲目停止」与结束前 5s 淡出）：音乐 / 视频共用同一个进程级单例。
 *
 * 背景：W21 音乐侧本是 `modes:music` 的 `MusicSleepTimer`（10/20/30/60 + 关闭，到点暂停音乐）；W12 视频侧是播放页内的
 * 局部计时（页面销毁即失效）。用户 2026-10-04 拍板统一 + 自定义 1–240 分钟后：
 * - 状态与倒计时只此一处（进程级，离开音乐页 / 播放页、熄屏后仍生效）；
 * - 到点暂停**当前活跃播放**：音视频互斥共用 [PlayerHolder] 的唯一实例，直接 `pause()` 即可——音乐侧由既有监听器继续 负责暂停上报与 `_isPlaying`
 *   同步（W21 语义），视频侧同样覆盖后台播放；
 * - 取消 / 到点都会取消计时协程并清零状态；重复选择 = 重置倒计时。
 *
 * W5-T6：
 * - 档位改 15/30/45/60/90（见 [SleepTimerSpec.PRESET_MINUTES]），自定义 1–240 保留；
 * - 「播完当前曲目停止」= 暂停挂起，音乐播放链在 `onMediaItemTransition(AUTO)` 时调 [consumeStopAfterCurrentTrack] 并暂停；
 * - 结束前 [SleepTimerSpec.FADE_OUT_MS] 由播放链的音量包络循环读 [volumeGain] 执行淡出，这里只提供纯计算。
 *
 * 纯逻辑（分钟换算 / 1–240 边界 / 到点判定 / 淡出触发 / 播完停止消费）在 [SleepTimerSpec] 与 [SleepTimerStateMachine]， 由
 * `:player:core` 单测覆盖。
 */
@Singleton
class SleepTimerController @Inject constructor(private val playerHolder: PlayerHolder) {

    data class State(
        val minutes: Int? = null,
        val remainingMs: Long = 0L,
        val stopAfterCurrentTrack: Boolean = false,
    ) {
        val active: Boolean
            get() = minutes != null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val machine = SleepTimerStateMachine()
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var tickJob: Job? = null

    /** 选择档位 / 自定义分钟（1–240）；null / 非正数 = 关闭。重复选择会重置倒计时。 */
    fun select(minutes: Int?) {
        tickJob?.cancel()
        tickJob = null
        val snapshot = machine.select(minutes, now())
        publish(snapshot)
        if (!snapshot.active) return
        tickJob = scope.launch { tickUntilExpired() }
    }

    /** 「播完当前曲目停止」：取消分钟计时，挂起等待 AUTO 切歌后暂停。 */
    fun selectStopAfterCurrentTrack() {
        tickJob?.cancel()
        tickJob = null
        publish(machine.selectStopAfterCurrentTrack())
        Timber.i("睡眠定时：已设置「播完当前曲目停止」")
    }

    /** 用户主动停止音乐 / 关闭音乐会话：只清「播完当前曲目停止」，不影响分钟计时。 */
    fun clearStopAfterCurrentTrack() {
        publish(machine.clearStopAfterCurrentTrack())
    }

    /** 音乐播放链在 `onMediaItemTransition` 时调用：AUTO 切歌且此前挂起了「播完当前曲目停止」→ 返回 true（只返回一次）。 */
    fun consumeStopAfterCurrentTrack(): Boolean {
        val consumed = machine.consumeStopAfterCurrentTrack()
        if (consumed) {
            publish(machine.current)
            Timber.i("睡眠定时：「播完当前曲目停止」已触发，暂停播放")
        }
        return consumed
    }

    /** 是否处于结束前淡出窗口（播放链据此提高音量采样频率）。 */
    fun fadeActive(): Boolean = SleepTimerSpec.fadeActive(currentRemainingMs())

    /** 距离到点的剩余毫秒；未开启计时时为 0。 */
    fun remainingMs(): Long = if (machine.current.active) currentRemainingMs() else 0L

    /** 当前应由 `Player.volume` 表达的音量（未开启 / 淡出窗口外恒为 1）。 */
    fun volumeGain(): Float =
        if (machine.current.active) SleepTimerSpec.fadeOutGain(currentRemainingMs()) else 1f

    fun cancel() = select(null)

    private suspend fun tickUntilExpired() {
        while (true) {
            val remainingMs = currentRemainingMs()
            // 进入淡出窗口后降到 100ms，保证到点暂停与音量曲线贴合
            delay(SleepTimerSpec.tickIntervalMs(remainingMs).coerceAtLeast(1L))
            val tick = machine.tick(now())
            publish(tick.snapshot)
            if (tick.expired) {
                pauseActivePlayback()
                return
            }
        }
    }

    private fun publish(snapshot: SleepTimerStateMachine.Snapshot): State {
        val state =
            State(
                minutes = snapshot.minutes,
                remainingMs =
                    if (snapshot.active) {
                        SleepTimerSpec.remainingMs(snapshot.deadlineMs, now())
                    } else {
                        0L
                    },
                stopAfterCurrentTrack = snapshot.stopAfterCurrentTrack,
            )
        _state.value = state
        return state
    }

    private fun currentRemainingMs(): Long =
        if (machine.current.active) {
            SleepTimerSpec.remainingMs(machine.current.deadlineMs, now())
        } else {
            Long.MAX_VALUE
        }

    private fun pauseActivePlayback() {
        // 音视频互斥：唯一共享实例上活跃的只会有一个会话（音乐 / 视频），统一暂停；音乐侧由既有监听器做暂停上报与
        // `_isPlaying` 同步（≤ POSITION_TICK_MS = 500ms）。
        val player = playerHolder.existingPlayer
        player?.pause()
        // 淡出可能把音量留在 0：恢复时为 1（与交叉淡化包络的复位口径一致）
        player?.volume = 1f
        Timber.i("睡眠定时到点，暂停当前播放（音量已复位为 1）")
    }

    private fun now(): Long = SystemClock.elapsedRealtime()
}
