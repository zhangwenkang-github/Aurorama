package com.zhangwenkang.cinefin.player.local.syncplay

import com.zhangwenkang.cinefin.player.core.playback.PlaybackEngine
import com.zhangwenkang.cinefin.player.local.domain.SeekState
import com.zhangwenkang.cinefin.player.local.presentation.PlayerHolder
import com.zhangwenkang.cinefin.player.local.presentation.PlayerViewModel
import com.zhangwenkang.cinefin.player.local.presentation.normalizeEnginePosition
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope

/**
 * SyncPlay 装配需要的「宿主播放器」窄面（W4-T8d）。
 *
 * 协调器只通过这里碰播放器：进程级 scope、当前内核、播放态读侧、定位入口。生产实现是 [PlayerHolderSyncPlayHost]； 单测注入 fake，不触碰 media3 /
 * Android。
 */
interface SyncPlayPlayerHost {
    /** 进程级作用域（生产 = [PlayerHolder.processScope]）：时钟、状态机、WS 收集都挂这里。 */
    val scope: CoroutineScope

    /** 当前内核标识（`exoplayer` / `mpv`），漂移档按它取。 */
    val backendName: String

    /** 当前内核适配器；没有活动实例时为 null（动词退化为空操作）。 */
    val engine: PlaybackEngine?

    /** 引擎位置读数（闸门做本地手势换算用）。 */
    val currentPositionMs: Long

    /**
     * 与 T3a-2 切内核快照同一口径的**确认位置**：mpv 取内核确认位置，Exo 取可播放窗口内的当前位置；读不到可信值时为 null。
     *
     * SyncPlay 的漂移读侧只用这个口径——raw 引擎读数在 seek / 解码器重建期会停在旧值或回 `C.TIME_UNSET`。
     */
    val confirmedPositionMs: Long?

    /** media3 `isPlaying` 口径：缓冲 / 暂停 / 未就绪都为 false。 */
    val isPlaying: Boolean

    /** 定位入口：生产走 seek sink（`source` 由调用方给），返回 seek sink 的请求代次；0 = 无法确认。 */
    fun submitSeek(positionMs: Long, source: String): Long

    /** seek sink 对某次定位请求的确认落点（毫秒）；未确认 / 失败 / 被新请求取代时为 null。 */
    fun confirmedSeekPosition(requestGeneration: Long): Long?

    /** seek sink 是否已把某次定位判定为失败（拒绝 / 引擎错误 / 超时）。 */
    fun seekFailed(requestGeneration: Long): Boolean

    /** 原地切内核完成后的回调位（`PlayerHolder.notifyBackendRebound` 的出口）。 */
    fun addBackendReboundListener(listener: () -> Unit)
}

/**
 * [SyncPlayPlayerHost] 的生产实现：把 [PlayerHolder] 的进程级能力暴露给协调器。
 *
 * 只读现成实例（`existingPlayer` / `currentEngine`），不会为了 SyncPlay 创建播放器；播放页没开时读侧返回 0 / false / null。
 */
@Singleton
internal class PlayerHolderSyncPlayHost
@Inject
constructor(private val playerHolder: PlayerHolder) : SyncPlayPlayerHost {
    override val scope: CoroutineScope
        get() = playerHolder.processScope

    override val backendName: String
        get() = playerHolder.backend

    override val engine: PlaybackEngine?
        get() = playerHolder.currentEngine

    override val currentPositionMs: Long
        get() = playerHolder.existingPlayer?.currentPosition ?: 0L

    override val confirmedPositionMs: Long?
        get() {
            val player = playerHolder.existingPlayer ?: return null
            PlayerViewModel.confirmedPosition(player)?.let {
                return it
            }
            // 回退引擎读数；mpv 未锚定时 getCurrentPosition() 是 C.TIME_UNSET，会被归一化挡掉。
            return normalizeEnginePosition(player.currentPosition)
        }

    override val isPlaying: Boolean
        get() = playerHolder.existingPlayer?.isPlaying == true

    override fun submitSeek(positionMs: Long, source: String): Long {
        val sink = playerHolder.seekSink
        sink.submit(PlayerViewModel.SeekIntent.Absolute(positionMs), source)
        return (sink.state as? SeekState.Seeking)?.requestGeneration ?: 0L
    }

    override fun confirmedSeekPosition(requestGeneration: Long): Long? {
        val confirmed = playerHolder.seekSink.state as? SeekState.Confirmed ?: return null
        return if (confirmed.requestGeneration == requestGeneration) confirmed.positionMs else null
    }

    override fun seekFailed(requestGeneration: Long): Boolean {
        val failed = playerHolder.seekSink.state as? SeekState.Failed ?: return false
        return failed.requestGeneration == requestGeneration
    }

    override fun addBackendReboundListener(listener: () -> Unit) {
        playerHolder.addBackendReboundListener(listener)
    }
}
