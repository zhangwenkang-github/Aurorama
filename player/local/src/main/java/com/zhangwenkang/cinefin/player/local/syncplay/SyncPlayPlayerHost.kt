package com.zhangwenkang.cinefin.player.local.syncplay

import com.zhangwenkang.cinefin.player.core.playback.PlaybackEngine
import com.zhangwenkang.cinefin.player.local.presentation.PlayerHolder
import com.zhangwenkang.cinefin.player.local.presentation.PlayerViewModel
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

    val currentPositionMs: Long

    /** media3 `isPlaying` 口径：缓冲 / 暂停 / 未就绪都为 false。 */
    val isPlaying: Boolean

    /** 定位入口：生产走 seek sink（`source` 由调用方给）。 */
    fun submitSeek(positionMs: Long, source: String)

    /** 原地切内核完成后的回调位（`PlayerHolder.notifyBackendRebound` 的出口）。 */
    fun addBackendReboundListener(listener: () -> Unit)
}

/**
 * [SyncPlayPlayerHost] 的生产实现：把 [PlayerHolder] 的进程级能力暴露给协调器。
 *
 * 只读现成实例（`existingPlayer` / `currentEngine`），不会为了 SyncPlay 创建播放器；播放页没开时读侧返回 0 / false。
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

    override val isPlaying: Boolean
        get() = playerHolder.existingPlayer?.isPlaying == true

    override fun submitSeek(positionMs: Long, source: String) {
        playerHolder.seekSink.submit(PlayerViewModel.SeekIntent.Absolute(positionMs), source)
    }

    override fun addBackendReboundListener(listener: () -> Unit) {
        playerHolder.addBackendReboundListener(listener)
    }
}
