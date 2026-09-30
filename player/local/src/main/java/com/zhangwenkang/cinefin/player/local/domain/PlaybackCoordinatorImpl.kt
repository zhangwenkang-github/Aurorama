package com.zhangwenkang.cinefin.player.local.domain

import androidx.media3.common.C
import androidx.media3.common.Player
import com.zhangwenkang.cinefin.player.local.presentation.PlayerHolder
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 音视频互斥仲裁（W1 R2 实现，接口由 W0 冻结）。
 *
 * 音乐与视频共用唯一的 [PlayerHolder] 实例与单个 `MediaSession`：
 * - 音乐起播：如果当前是视频会话，先按协议上报 `Sessions/Playing/Stopped`（挂起等待）再停队列；
 * - 视频起播：本地立即停音乐并释放音频焦点，停止上报异步补发（接口约定不等待）。
 */
@Singleton
class PlaybackCoordinatorImpl
@Inject
constructor(
    private val playerHolder: PlayerHolder,
    private val repository: JellyfinRepository,
) : PlaybackCoordinator {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override suspend fun onMusicStartRequested() {
        val player = playerHolder.existingPlayer ?: return
        if (playerHolder.musicSessionActive && player.isPlayingMusicItem()) {
            // 已在音乐会话：换队列前先本地停掉旧音乐（停止上报由控制器负责）
            stopLocal(player)
            return
        }
        if (player.mediaItemCount == 0 && player.playbackState == Player.STATE_IDLE) {
            // 没有会话语义的内容：顺手清掉可能残留的音乐标志
            playerHolder.musicSessionActive = false
            return
        }

        val snapshot = playbackSnapshot(player)
        if (snapshot != null) {
            runCatching {
                repository.postPlaybackStop(
                    snapshot.itemId,
                    playbackPositionTicks(snapshot.positionMs),
                    playbackPercentage(snapshot.positionMs, snapshot.durationMs),
                )
            }
                .onFailure { Timber.w(it, "视频停止上报失败") }
        }
        stopLocal(player)
        playerHolder.musicSessionActive = false
        Timber.i("音乐起播：已停止视频会话")
    }

    override fun onVideoStartRequested() {
        val player = playerHolder.existingPlayer ?: return
        if (!playerHolder.musicSessionActive || !player.isPlayingMusicItem()) return

        val snapshot = playbackSnapshot(player)
        stopLocal(player)
        playerHolder.musicSessionActive = false
        if (snapshot != null) {
            scope.launch {
                runCatching {
                    repository.postPlaybackStop(
                        snapshot.itemId,
                        playbackPositionTicks(snapshot.positionMs),
                        playbackPercentage(snapshot.positionMs, snapshot.durationMs),
                    )
                }
                    .onFailure { Timber.w(it, "音乐停止上报失败") }
            }
        }
        Timber.i("视频起播：已停止音乐会话")
    }

    private fun stopLocal(player: Player) {
        runCatching { player.stop() }.onFailure { Timber.w(it, "停止当前会话失败") }
        // mpv 内核没有实现 removeMediaItems；清空失败不影响后续 setMediaItems 替换队列
        runCatching { player.clearMediaItems() }.onFailure { Timber.d(it, "当前内核不支持清空媒体项（可忽略）") }
    }

    private fun playbackSnapshot(player: Player): PlaybackSnapshot? {
        val mediaId = player.currentMediaItem?.mediaId ?: return null
        val itemId = runCatching { UUID.fromString(mediaId) }.getOrNull() ?: return null
        val durationMs = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L
        return PlaybackSnapshot(
            itemId = itemId,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = durationMs,
        )
    }

    /** 上报所需的会话快照（当前条目 / 位置 / 时长）。 */
    private data class PlaybackSnapshot(
        val itemId: UUID,
        val positionMs: Long,
        val durationMs: Long,
    )
}
