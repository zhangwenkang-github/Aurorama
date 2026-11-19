package com.zhangwenkang.cinefin.player.local.syncplay

import com.zhangwenkang.cinefin.syncplay.SyncPlayError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 闸门要发的组命令（W4-T8d）。实现是 `SyncPlaySessionCoordinator`（转调 gateway），单测注入 fake。
 *
 * 返回 null = 成功；非 null = 服务器 / 网络错误，闸门只记日志（组命令是收敛型语义，下一次操作自带修正）。
 */
interface SyncPlayTransportCommands {
    suspend fun pause(): SyncPlayError?

    suspend fun unpause(): SyncPlayError?

    suspend fun seek(positionMs: Long): SyncPlayError?
}

/**
 * 入组期间的传输闸门（W4-T8d，VIDEO-42 最小态）。
 *
 * [ownsTransport] 为 true 时播放控制权归组：本地手势（双击播放 / 双击快退快进 / 滑动 seek / 面板播控）一律只向服务器发组命令，
 * 不直接写本地引擎；出组后闸门关闭，调用方恢复原有的本地直驱路径。
 *
 * 闸门本身不判断是否在组内——[updateOwned] 由协调器按状态机态驱动（NotInGroup / Joining 为关）。
 */
class SyncPlayTransportGate(
    private val scope: CoroutineScope,
    private val isPlaying: () -> Boolean,
    private val currentPositionMs: () -> Long,
    private val commands: SyncPlayTransportCommands,
) {
    private val _ownsTransport = MutableStateFlow(false)

    /** true = 入组期间，本地手势只发请求不写引擎。 */
    val ownsTransport: StateFlow<Boolean> = _ownsTransport.asStateFlow()

    internal fun updateOwned(owned: Boolean) {
        _ownsTransport.value = owned
    }

    /** 双击播放 / 暂停：按本地当前播放态决定发 PAUSE 还是 UNPAUSE。 */
    fun togglePlayback() {
        requestPlay(!isPlaying())
    }

    /** 面板播放键：闸门开启时只发组命令。 */
    fun requestPlay(play: Boolean) {
        if (!_ownsTransport.value) return
        scope.launch {
            val error = if (play) commands.unpause() else commands.pause()
            error?.let { Timber.w("SyncPlay 组命令（%s）失败：%s", if (play) "unpause" else "pause", it) }
        }
    }

    /** 绝对定位（进度条 / 滑动松手后的落点）：闸门开启时换算成组 SEEK 命令。 */
    fun requestSeek(positionMs: Long) {
        if (!_ownsTransport.value) return
        val target = positionMs.coerceAtLeast(0L)
        scope.launch {
            commands.seek(target)?.let { Timber.w("SyncPlay 组命令（seek %d）失败：%s", target, it) }
        }
    }

    /** 相对定位（±步进 / 双击快退快进 / 滑动增量）。 */
    fun requestSeekRelative(deltaMs: Long) {
        requestSeek(currentPositionMs() + deltaMs)
    }
}
