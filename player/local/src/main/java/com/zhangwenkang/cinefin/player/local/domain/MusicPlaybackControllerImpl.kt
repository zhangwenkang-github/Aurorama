package com.zhangwenkang.cinefin.player.local.domain

import android.os.SystemClock
import androidx.media3.common.C
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
 * 音乐队列 → ExoPlayer 的唯一入口（W1 R2 建立，W2 R2 补 gapless 与 MU-9 上报）。
 *
 * 约定：
 * - 复用 [PlayerHolder] 的单实例与单个 MediaSession，UI 不直接调用播放器 API；
 * - 起播前经 [PlaybackCoordinator] 与视频互斥（先停视频、等待停止上报）；
 * - 音乐期间关闭 `pauseAtEndOfMediaItems`（否则一首播完即停，连播与 gapless 失效）；
 * - MU-9 上报：起播 Start、10s 周期 Progress、暂停 / 恢复即时 Progress、切歌 Stop→Start、停止 Stop；
 * - 线程约定与 Media3 一致：所有播放器调用在主线程（内部协程固定 Main.immediate）。
 */
@Singleton
class MusicPlaybackControllerImpl
@Inject
constructor(
    private val playerHolder: PlayerHolder,
    private val coordinator: PlaybackCoordinator,
    private val serviceStarter: PlaybackServiceStarter,
    private val repository: JellyfinRepository,
) : MusicPlaybackController, MusicPlaybackStateSource, MusicQueueEditor {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _queue = MutableStateFlow<MusicQueue?>(null)
    override val queue: StateFlow<MusicQueue?> = _queue.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    override val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private var playbackJob: Job? = null
    private var attachedPlayer: Player? = null

    /** 已上报 Start、尚未上报 Stop 的曲目（MU-9 上报状态机）。 */
    private var activeItemId: UUID? = null
    private var lastKnownPositionMs = 0L
    private var lastKnownDurationMs = 0L
    private var lastProgressReportAtMs = 0L

    private val playerListener =
        object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (!playerHolder.musicSessionActive) return
                val player = playerHolder.existingPlayer ?: return
                if (!player.isPlayingMusicItem()) return
                _queue.update { queue -> queue?.copy(currentIndex = player.currentMediaItemIndex) }
                // 自己 setMediaItems 造成的整体替换不是"切歌"
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
                onTrackChanged(mediaItem?.mediaId.toItemId())
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) return
                val reportable =
                    reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST ||
                        reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS ||
                        reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY ||
                        reason == Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE
                if (!reportable) return
                if (!playerHolder.musicSessionActive) return
                val player = playerHolder.existingPlayer ?: return
                if (!player.isPlayingMusicItem()) return
                val itemId = activeItemId ?: return
                // 暂停 / 恢复 / 耳机拔出：立即上报，不等 10s 周期
                reportProgress(
                    itemId,
                    player.currentPosition.coerceAtLeast(0L),
                    isPaused = !playWhenReady,
                )
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState != Player.STATE_ENDED) return
                if (!playerHolder.musicSessionActive) return
                // 队列播完：按"播到结尾"收尾，避免服务器把最后位置当成未听完
                val itemId = activeItemId ?: return
                activeItemId = null
                reportStop(itemId, lastKnownDurationMs, lastKnownDurationMs)
            }
        }

    init {
        scope.launch {
            while (isActive) {
                delay(POSITION_TICK_MS)
                val player = playerHolder.existingPlayer
                val inMusicSession =
                    player != null && playerHolder.musicSessionActive && player.isPlayingMusicItem()
                if (inMusicSession) {
                    val position = player.currentPosition.coerceAtLeast(0L)
                    lastKnownPositionMs = position
                    lastKnownDurationMs =
                        player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L
                    _positionMs.value = position
                    _isPlaying.value = player.isPlaying
                    // 队列可能被移动 / 删除 / 自动切换，索引以播放器为准最可靠
                    _queue.update { queue ->
                        queue?.copy(currentIndex = player.currentMediaItemIndex)
                    }
                    reportPeriodicProgressIfDue(player, position)
                } else {
                    _positionMs.value = 0L
                    _isPlaying.value = false
                    // 视频起播等外部路径停掉音乐：上报由仲裁器负责，这里只清理本地状态
                    if (!playerHolder.musicSessionActive) activeItemId = null
                }
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
                // 0) 换队列前先把旧曲目按 MU-9 收尾（Stop 之后才允许 Start 新曲目）
                reportActiveStopNow()
                // 1) 音视频互斥：先停视频会话并等待停止上报完成
                coordinator.onMusicStartRequested()
                // 2) 先声明音乐会话：防止后台视频页的 player 访问按偏好重建实例
                playerHolder.musicSessionActive = true
                // 3) 音频强制 ExoPlayer：若当前实例是 mpv 会在此重建（先固定实例）
                val player = playerHolder.audioSession()
                // 4) 音乐连播：关掉"播完一件暂停"（视频分集用的行为）
                playerHolder.applyMusicPlaybackTuning(inMusicSession = true)
                // 5) 通知 / 锁屏可控：服务已在运行时会把 MediaSession 对齐到新实例
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
                // 6) 续播（MU-9）：优先使用服务器记录的位置
                latest.currentItem
                    ?.playbackPosition
                    ?.takeIf { it > 0L }
                    ?.let { position -> player.seekTo(latest.currentIndex, position) }
                player.play()
                latest.currentItem?.let { item ->
                    activeItemId = item.itemId
                    lastKnownPositionMs = item.playbackPosition.coerceAtLeast(0L)
                    lastKnownDurationMs = 0L
                    lastProgressReportAtMs = SystemClock.elapsedRealtime()
                    reportStartNow(item.itemId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "音乐起播失败")
                playerHolder.musicSessionActive = false
                activeItemId = null
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

    override fun jumpTo(index: Int) {
        val player = musicPlayer() ?: return
        if (index !in 0 until player.mediaItemCount) return
        player.seekTo(index, 0L)
        player.play()
    }

    override fun removeAt(index: Int) {
        val current = _queue.value ?: return
        if (index !in current.items.indices) return
        _queue.value = current.copy(items = current.items.toMutableList().apply { removeAt(index) })
        // 索引由播放器接管（移除当前曲目会自动顺延），ticker 会把 currentIndex 同步回来
        musicPlayer()?.removeMediaItem(index)
    }

    override fun stop() {
        playbackJob?.cancel()
        playbackJob = null
        val itemId = activeItemId
        val positionMs = lastKnownPositionMs
        val durationMs = lastKnownDurationMs
        activeItemId = null
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
        playerHolder.musicSessionActive = false
        runCatching {
            player.stop()
            player.clearMediaItems()
        }
            .onFailure { Timber.w(it, "停止音乐失败") }
        playerHolder.applyMusicPlaybackTuning(inMusicSession = false)
        if (itemId != null) {
            reportStop(itemId, positionMs, durationMs)
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

    /** 切歌（自动 / 手动 / 循环 / 点队列）：上一首 Stop → 下一首 Start。 */
    private fun onTrackChanged(nextItemId: UUID?) {
        val previousItemId = activeItemId
        val positionMs = lastKnownPositionMs
        val durationMs = lastKnownDurationMs
        activeItemId = nextItemId
        lastKnownPositionMs = 0L
        lastKnownDurationMs = 0L
        lastProgressReportAtMs = SystemClock.elapsedRealtime()
        when {
            previousItemId == null -> nextItemId?.let(::reportStart)
            nextItemId == null -> reportStop(previousItemId, positionMs, durationMs)
            else ->
                scope.launch {
                    reportStopNow(previousItemId, positionMs, durationMs)
                    reportStartNow(nextItemId)
                }
        }
    }

    /** 换队列 / 停止前的收尾：把当前曲目的 Stop 上报同步做完，保证与后续 Start 的顺序。 */
    private suspend fun reportActiveStopNow() {
        val itemId = activeItemId ?: return
        val positionMs = lastKnownPositionMs
        val durationMs = lastKnownDurationMs
        activeItemId = null
        reportStopNow(itemId, positionMs, durationMs)
    }

    private fun reportPeriodicProgressIfDue(player: Player, positionMs: Long) {
        val itemId = activeItemId ?: return
        if (!player.isPlaying) return
        val now = SystemClock.elapsedRealtime()
        if (!isProgressReportDue(now, lastProgressReportAtMs)) return
        lastProgressReportAtMs = now
        reportProgress(itemId, positionMs, isPaused = false)
    }

    private fun reportStart(itemId: UUID) {
        scope.launch { reportStartNow(itemId) }
    }

    private suspend fun reportStartNow(itemId: UUID) {
        runCatching { repository.postPlaybackStart(itemId) }.onFailure { Timber.w(it, "音乐起播上报失败") }
    }

    private fun reportProgress(itemId: UUID, positionMs: Long, isPaused: Boolean) {
        scope.launch { reportProgressNow(itemId, positionMs, isPaused) }
    }

    private suspend fun reportProgressNow(itemId: UUID, positionMs: Long, isPaused: Boolean) {
        runCatching {
            repository.postPlaybackProgress(itemId, playbackPositionTicks(positionMs), isPaused)
        }
            .onFailure { Timber.w(it, "音乐进度上报失败") }
    }

    private fun reportStop(itemId: UUID, positionMs: Long, durationMs: Long) {
        scope.launch { reportStopNow(itemId, positionMs, durationMs) }
    }

    private suspend fun reportStopNow(itemId: UUID, positionMs: Long, durationMs: Long) {
        runCatching {
            repository.postPlaybackStop(
                itemId,
                playbackPositionTicks(positionMs),
                playbackPercentage(positionMs, durationMs),
            )
        }
            .onFailure { Timber.w(it, "音乐停止上报失败") }
    }

    private fun String?.toItemId(): UUID? =
        this?.let { mediaId -> runCatching { UUID.fromString(mediaId) }.getOrNull() }

    private companion object {
        /** 位置轮询间隔：喂 UI 进度条 + 采样上报位置，够用且不费电。 */
        const val POSITION_TICK_MS = 500L
    }
}
