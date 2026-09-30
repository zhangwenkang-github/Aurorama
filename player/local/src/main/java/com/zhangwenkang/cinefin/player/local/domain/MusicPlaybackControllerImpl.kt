package com.zhangwenkang.cinefin.player.local.domain

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.zhangwenkang.cinefin.player.core.domain.models.MusicQueue
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerItem
import com.zhangwenkang.cinefin.player.core.domain.models.RepeatMode
import com.zhangwenkang.cinefin.player.local.presentation.PlayerHolder
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 音乐队列 → ExoPlayer 的唯一入口（W1 R2 实现，接口由 W0 冻结）。
 *
 * 约定：
 * - 复用 [PlayerHolder] 的单实例与单个 MediaSession，UI 不直接调用播放器 API；
 * - 起播前经 [PlaybackCoordinator] 与视频互斥（先停视频、等待停止上报）；
 * - 音乐会话强制 ExoPlayer（[PlayerHolder.audioSession]），并把 [PlayerHolder.musicSessionActive]
 *   置位供服务与仲裁器判断会话类型；
 * - 线程约定与 Media3 一致：所有调用在主线程（内部协程固定 Main.immediate）。
 */
@Singleton
class MusicPlaybackControllerImpl
@Inject
constructor(
    private val playerHolder: PlayerHolder,
    private val coordinator: PlaybackCoordinator,
    private val serviceStarter: PlaybackServiceStarter,
    private val repository: JellyfinRepository,
) : MusicPlaybackController, MusicPlaybackStateSource {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _queue = MutableStateFlow<MusicQueue?>(null)
    override val queue: StateFlow<MusicQueue?> = _queue.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    override val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private var playbackJob: Job? = null
    private var attachedPlayer: Player? = null

    /** 自动切歌（含播完自动下一首）时把队列索引跟上，UI 高亮才不会停在旧曲目。 */
    private val playerListener =
        object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (!playerHolder.musicSessionActive) return
                val player = playerHolder.existingPlayer ?: return
                _queue.update { queue -> queue?.copy(currentIndex = player.currentMediaItemIndex) }
            }
        }

    init {
        scope.launch {
            while (isActive) {
                delay(POSITION_TICK_MS)
                val player = playerHolder.existingPlayer
                val inMusicSession =
                    player != null && playerHolder.musicSessionActive && player.isPlayingMusicItem()
                _positionMs.value =
                    if (inMusicSession) player.currentPosition.coerceAtLeast(0L) else 0L
                _isPlaying.value = inMusicSession && player.isPlaying
            }
        }
    }

    override fun setQueue(queue: MusicQueue, startIndex: Int) {
        if (queue.items.isEmpty()) {
            stop()
            return
        }
        val normalized =
            queue.copy(currentIndex = normalizeStartIndex(startIndex, queue.items.size))
        _queue.value = normalized
        playbackJob?.cancel()
        playbackJob = scope.launch {
            try {
                // 1) 音视频互斥：先停视频会话并等待停止上报完成
                coordinator.onMusicStartRequested()
                // 2) 先声明音乐会话：防止后台视频页的 player 访问按偏好重建实例
                playerHolder.musicSessionActive = true
                // 3) 音频强制 ExoPlayer：若当前实例是 mpv 会在此重建（先固定实例）
                val player = playerHolder.audioSession()
                // 4) 通知 / 锁屏可控：服务已在运行时会把 MediaSession 对齐到新实例
                serviceStarter.ensureSessionService()
                attachListener(player)
                // 起播前的极短窗口里用户可能又改了队列（move / insertNext），以最新状态为准
                val latest = _queue.value ?: normalized
                player.setMediaItems(
                    latest.items.map { it.toMusicMediaItem() },
                    latest.currentIndex,
                    0L,
                )
                player.repeatMode = latest.repeatMode.toPlayerRepeatMode()
                player.shuffleModeEnabled = latest.shuffleEnabled
                player.prepare()
                player.play()
                latest.currentItem?.let { item ->
                    runCatching { repository.postPlaybackStart(item.itemId) }
                        .onFailure { Timber.w(it, "音乐起播上报失败") }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "音乐起播失败")
                playerHolder.musicSessionActive = false
            }
        }
    }

    override fun playPause() {
        val player = musicPlayer() ?: return
        if (player.isPlaying) player.pause() else player.play()
        _isPlaying.value = player.isPlaying
    }

    override fun play() {
        val player = musicPlayer() ?: return
        player.play()
        _isPlaying.value = player.isPlaying
    }

    override fun pause() {
        val player = musicPlayer() ?: return
        player.pause()
        _isPlaying.value = false
    }

    override fun next() {
        musicPlayer()?.seekToNext()
    }

    override fun previous() {
        musicPlayer()?.seekToPrevious()
    }

    override fun seekTo(positionMs: Long) {
        musicPlayer()?.seekTo(positionMs.coerceAtLeast(0L))
    }

    override fun setRepeatMode(mode: RepeatMode) {
        _queue.update { queue -> queue?.copy(repeatMode = mode) }
        musicPlayer()?.repeatMode = mode.toPlayerRepeatMode()
    }

    override fun setShuffleEnabled(enabled: Boolean) {
        _queue.update { queue -> queue?.copy(shuffleEnabled = enabled) }
        musicPlayer()?.shuffleModeEnabled = enabled
    }

    override fun move(fromIndex: Int, toIndex: Int) {
        val current = _queue.value ?: return
        val moved = current.move(fromIndex, toIndex)
        if (moved == current) return
        _queue.value = moved
        musicPlayer()?.moveMediaItem(fromIndex, toIndex)
    }

    override fun insertNext(item: PlayerItem) {
        val current = _queue.value ?: return
        _queue.value = current.insertNext(item)
        val player = musicPlayer() ?: return
        val insertAt = (current.currentIndex + 1).coerceIn(0, player.mediaItemCount)
        player.addMediaItem(insertAt, item.toMusicMediaItem())
    }

    override fun stop() {
        playbackJob?.cancel()
        playbackJob = null
        _queue.value = null
        _positionMs.value = 0L
        _isPlaying.value = false

        val player = playerHolder.existingPlayer ?: return
        if (!playerHolder.musicSessionActive) return
        if (!player.isPlayingMusicItem()) {
            // 实例已被视频路径复用：只清理音乐状态，不碰视频播放
            playerHolder.musicSessionActive = false
            return
        }
        val snapshot = currentSnapshot(player)
        playerHolder.musicSessionActive = false
        runCatching {
            player.stop()
            player.clearMediaItems()
        }
            .onFailure { Timber.w(it, "停止音乐失败") }
        if (snapshot != null) {
            scope.launch {
                runCatching {
                    repository.postPlaybackStop(
                        snapshot.first,
                        playbackPositionTicks(snapshot.second),
                        0,
                    )
                }
                    .onFailure { Timber.w(it, "音乐停止上报失败") }
            }
        }
    }

    /** 只有音乐会话活跃时才把命令转发给播放器，避免误伤视频会话。 */
    private fun musicPlayer(): Player? =
        playerHolder.existingPlayer?.takeIf {
            playerHolder.musicSessionActive && it.isPlayingMusicItem()
        }

    private fun attachListener(player: Player) {
        if (attachedPlayer === player) return
        attachedPlayer?.removeListener(playerListener)
        player.addListener(playerListener)
        attachedPlayer = player
    }

    private fun currentSnapshot(player: Player): Pair<UUID, Long>? {
        val mediaId = player.currentMediaItem?.mediaId ?: return null
        val itemId = runCatching { UUID.fromString(mediaId) }.getOrNull() ?: return null
        return itemId to player.currentPosition.coerceAtLeast(0L)
    }

    private companion object {
        /** 位置轮询间隔：只喂 UI 进度条，够用且不费电。 */
        const val POSITION_TICK_MS = 500L
    }
}
