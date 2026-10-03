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
 * 睡眠定时统一状态源（W55）：音乐 / 视频共用同一个进程级单例。
 *
 * 背景：W21 音乐侧本是 `modes:music` 的 `MusicSleepTimer`（10/20/30/60 + 关闭，到点暂停音乐）；W12 视频侧是播放页内的
 * 局部计时（页面销毁即失效）。用户 2026-10-04 拍板统一 + 自定义 1–240 分钟后：
 * - 状态与倒计时只此一处（进程级，离开音乐页 / 播放页、熄屏后仍生效）；
 * - 到点暂停**当前活跃播放**：音视频互斥共用 [PlayerHolder] 的唯一实例，直接 `pause()` 即可——音乐侧由既有监听器继续 负责暂停上报与 `_isPlaying`
 *   同步（W21 语义），视频侧同样覆盖后台播放；
 * - 取消 / 到点都会取消计时协程并清零状态；重复选择 = 重置倒计时。
 *
 * 纯逻辑（分钟换算 / 1–240 边界 / 到点判定 / 取消清理）在 [SleepTimerStateMachine]，由 `:player:core` 单测覆盖。
 */
@Singleton
class SleepTimerController @Inject constructor(private val playerHolder: PlayerHolder) {

    data class State(val minutes: Int? = null, val remainingMs: Long = 0L) {
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

    fun cancel() = select(null)

    private suspend fun tickUntilExpired() {
        while (true) {
            val remainingMs = SleepTimerSpec.remainingMs(machine.current.deadlineMs, now())
            // 最后一跳不超过剩余时间；不忙转（下限 1ms 只在理论边界出现）
            delay(remainingMs.coerceIn(1L, TICK_MS))
            val tick = machine.tick(now())
            publish(tick.snapshot)
            if (tick.expired) {
                pauseActivePlayback()
                return
            }
        }
    }

    private fun publish(snapshot: SleepTimerStateMachine.Snapshot) {
        _state.value =
            State(
                minutes = snapshot.minutes,
                remainingMs =
                    if (snapshot.active) {
                        SleepTimerSpec.remainingMs(snapshot.deadlineMs, now())
                    } else {
                        0L
                    },
            )
    }

    private fun pauseActivePlayback() {
        // 音视频互斥：唯一共享实例上活跃的只会有一个会话（音乐 / 视频），统一暂停；音乐侧由既有监听器做暂停上报与
        // `_isPlaying` 同步（≤ POSITION_TICK_MS = 500ms）。
        playerHolder.existingPlayer?.pause()
        Timber.i("睡眠定时到点，暂停当前播放")
    }

    private fun now(): Long = SystemClock.elapsedRealtime()

    private companion object {
        const val TICK_MS = 1_000L
    }
}
