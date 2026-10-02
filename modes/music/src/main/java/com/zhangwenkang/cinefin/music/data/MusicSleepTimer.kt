package com.zhangwenkang.cinefin.music.data

import com.zhangwenkang.cinefin.player.local.domain.MusicPlaybackController
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
 * 音乐睡眠定时（W21-R2，MU-7）。
 *
 * 与视频侧播放页内的睡眠定时完全独立：本定时器是 App 进程级单例，离开音乐页 / 后台播放时仍生效； 到点只调用音乐控制器
 * `pause()`——`MusicPlaybackControllerImpl.musicPlayer()` 已确认当前是音乐会话， 不会误伤视频。跨度档位与视频侧一致（10 / 20 / 30
 * / 60 分钟）。
 */
@Singleton
class MusicSleepTimer @Inject constructor(private val playbackController: MusicPlaybackController) {

    data class State(val minutes: Int? = null, val remainingMs: Long = 0L) {
        val active: Boolean
            get() = minutes != null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var tickJob: Job? = null

    /** 选择档位；`null` = 关闭。重复选择会重置倒计时。 */
    fun select(minutes: Int?) {
        tickJob?.cancel()
        tickJob = null
        if (minutes == null || minutes <= 0) {
            _state.value = State()
            return
        }
        val totalMs = minutes * 60_000L
        _state.value = State(minutes = minutes, remainingMs = totalMs)
        tickJob = scope.launch {
            var remainingMs = totalMs
            while (remainingMs > 0L) {
                delay(TICK_MS)
                remainingMs -= TICK_MS
                _state.value = State(minutes = minutes, remainingMs = remainingMs.coerceAtLeast(0L))
            }
            Timber.i("音乐睡眠定时到点，暂停播放")
            playbackController.pause()
            _state.value = State()
        }
    }

    fun cancel() = select(null)

    private companion object {
        const val TICK_MS = 1_000L
    }
}

/** 剩余时间文案（mm:ss；向上取整到秒，避免最后一秒显示 00:00 仍在播）。 */
internal fun formatSleepRemaining(remainingMs: Long): String {
    val totalSeconds = (remainingMs.coerceAtLeast(0L) + 999L) / 1_000L
    return "%02d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
}
