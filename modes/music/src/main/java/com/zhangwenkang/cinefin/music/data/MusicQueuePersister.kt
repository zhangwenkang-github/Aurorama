package com.zhangwenkang.cinefin.music.data

import com.zhangwenkang.cinefin.player.core.domain.models.MusicQueue
import com.zhangwenkang.cinefin.player.local.domain.MusicPlaybackController
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 音乐队列自动存档（W21-R2）。
 *
 * App 进程级单例：用户离开音乐页后（音乐仍在后台播放）位置照样持续落盘， 队列结构变化立即保存、播放位置按 [SAVE_INTERVAL_MS] 节流保存，杀进程 /
 * 重启后从最近一次快照恢复。 存档只写不清：音乐被视频抢占（`stop()`）时保留快照，下次进入音乐模式仍可恢复。
 */
@Singleton
class MusicQueuePersister
@Inject
constructor(
    private val store: MusicQueueStore,
    private val playbackController: MusicPlaybackController,
    private val appPreferences: AppPreferences,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val saveMutex = Mutex()
    private var started = false

    /** 幂等启动：由音乐 ViewModel 首次进入时调用。 */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            playbackController.queue.collect { queue -> if (queue != null) persist(queue) }
        }
        scope.launch {
            while (isActive) {
                delay(SAVE_INTERVAL_MS)
                playbackController.queue.value?.let { queue -> persist(queue) }
            }
        }
    }

    /** 读取上次快照；用户在偏好里关闭恢复开关时返回 null。 */
    suspend fun load(): MusicQueueSnapshot? = if (enabled()) store.load() else null

    /** 恢复态下的队列编辑（拖拽 / 移除）立即落盘。 */
    fun persistNow(queue: MusicQueue) {
        scope.launch { persist(queue) }
    }

    suspend fun clear() = store.clear()

    private suspend fun persist(queue: MusicQueue) {
        if (!enabled()) return
        // 活动会话读实时位置；恢复态（播放器未装载）读快照里写回的续播位置，避免编辑队列时把位置清零。
        val positionMs =
            queuePersistPositionMs(
                liveQueuePresent = playbackController.queue.value != null,
                livePositionMs = playbackController.positionMs.value,
                restoredPositionMs = queue.currentItem?.playbackPosition ?: 0L,
            )
        saveMutex.withLock { store.save(queue, positionMs) }
    }

    private fun enabled(): Boolean = appPreferences.getValue(appPreferences.musicResumeQueue)

    private companion object {
        /** 位置落盘节流：最多丢 5 秒播放进度。 */
        const val SAVE_INTERVAL_MS = 5_000L
    }
}

/**
 * 存档位置取值（W21-R2）：活动会话用播放器实时位置；恢复态用快照里写回的续播位置。
 *
 * 真机回归拦下的 bug：恢复态编辑队列时若照读播放器位置（无会话 = 0），会把保存的位置清零。
 */
internal fun queuePersistPositionMs(
    liveQueuePresent: Boolean,
    livePositionMs: Long,
    restoredPositionMs: Long,
): Long =
    if (liveQueuePresent) {
        livePositionMs.coerceAtLeast(0L)
    } else {
        restoredPositionMs.coerceAtLeast(0L)
    }
