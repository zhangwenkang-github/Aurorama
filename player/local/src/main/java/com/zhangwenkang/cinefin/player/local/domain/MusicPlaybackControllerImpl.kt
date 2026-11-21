package com.zhangwenkang.cinefin.player.local.domain

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import com.zhangwenkang.cinefin.player.core.domain.models.MusicQueue
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerItem
import com.zhangwenkang.cinefin.player.core.domain.models.QueueEndedEvent
import com.zhangwenkang.cinefin.player.core.domain.models.RepeatMode
import com.zhangwenkang.cinefin.player.core.domain.models.ShuffleMode
import com.zhangwenkang.cinefin.player.core.domain.models.SleepTimerSpec
import com.zhangwenkang.cinefin.player.core.domain.models.currentFirstShuffleOrder
import com.zhangwenkang.cinefin.player.core.domain.models.fullShuffleOrder
import com.zhangwenkang.cinefin.player.core.domain.models.isValidShuffleOrder
import com.zhangwenkang.cinefin.player.core.domain.models.priorityShuffleOrder
import com.zhangwenkang.cinefin.player.local.audio.MUSIC_DEFAULT_PLAYBACK_SPEED
import com.zhangwenkang.cinefin.player.local.audio.MusicAudioEffectsController
import com.zhangwenkang.cinefin.player.local.audio.MusicCrossfadeEligibility
import com.zhangwenkang.cinefin.player.local.audio.MusicCrossfadeMath
import com.zhangwenkang.cinefin.player.local.audio.ReplayGainMode
import com.zhangwenkang.cinefin.player.local.audio.ReplayGainReadResult
import com.zhangwenkang.cinefin.player.local.audio.ReplayGainTagReader
import com.zhangwenkang.cinefin.player.local.presentation.PlayerHolder
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    private val audioEffects: MusicAudioEffectsController,
    private val replayGainReader: ReplayGainTagReader,
    private val sleepTimer: SleepTimerController,
) : MusicPlaybackController, MusicPlaybackStateSource, MusicQueueEditor {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _queue = MutableStateFlow<MusicQueue?>(null)
    override val queue: StateFlow<MusicQueue?> = _queue.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    override val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    override val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    /** W5-T8（MUSIC-48）：播放失败事件（重试仍失败 / 本地文件不可用 → 跳曲或停播）。 */
    private val _playbackFailures = MutableSharedFlow<MusicPlaybackFailure>(extraBufferCapacity = 8)
    override val playbackFailures: SharedFlow<MusicPlaybackFailure> =
        _playbackFailures.asSharedFlow()

    /** W5-T8（MUSIC-49）：缓冲态轻量指示；缓冲中 `isPlaying` 仍为 true。 */
    private val _isBuffering = MutableStateFlow(false)
    override val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    /** W5-T12（MUSIC-14 Auto DJ）：队列自然播完事件（STATE_ENDED 时发出一次）。 */
    private val _queueEnded = MutableSharedFlow<QueueEndedEvent>(extraBufferCapacity = 4)
    override val queueEnded: SharedFlow<QueueEndedEvent> = _queueEnded.asSharedFlow()

    /** W5-T12：队尾成因标志（T8 错误跳曲置位，自然 AUTO 推进复位）。 */
    private val queueEndFlag = MusicQueueEndFlag()

    /** W5-T8：同一曲目只重试一次的纯状态机（见 [MusicPlaybackRecovery]）。 */
    private val playbackRecovery = MusicPlaybackRecovery()
    private var errorRetryJob: Job? = null

    private var playbackJob: Job? = null
    private var attachedPlayer: Player? = null

    /** 已上报 Start、尚未上报 Stop 的曲目（MU-9 上报状态机）。 */
    private var activeItemId: UUID? = null
    private var lastKnownPositionMs = 0L
    private var lastKnownDurationMs = 0L
    private var lastProgressReportAtMs = 0L
    private var lastCrossfadeLogAtMs = 0L
    private var lastSleepFadeLogAtMs = 0L

    /** W5-T7：本曲是否允许淡化（只有队列自然推进进入的曲目为 true）。 */
    private val crossfadeEligibility = MusicCrossfadeEligibility()

    private val playerListener =
        object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // W5-T7：先登记资格——AUTO 切歌可淡化，手动切歌（SEEK）/ 换队列（PLAYLIST_CHANGED）不可；
                // 后面的早退分支不能漏掉这条状态。
                crossfadeEligibility.onMediaItemTransition(reason)
                // W5-T12：队尾成因只在自然推进（AUTO）时复位为"自然播完"。
                queueEndFlag.onMediaItemTransition(reason)
                if (!playerHolder.musicSessionActive) return
                val player = playerHolder.existingPlayer ?: return
                if (!player.isPlayingMusicItem()) return
                // 自己 setMediaItems 造成的整体替换不是"切歌"，也不能用它的索引回写队列
                // （过渡窗口里播放器还在旧队列，索引会越界；见 setQueue 的注释）
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                    // W5-T12 风险①取证：Auto DJ 追加后若以 PLAYLIST_CHANGED 过渡进入续播曲，这条日志会显示
                    // "未上报"（active 为空），K60 据此判定 PlaybackStart 是否被早退吞掉。
                    Timber.i(
                        "音乐切歌过渡（PLAYLIST_CHANGED）：item=%s active=%s 未走上报",
                        mediaItem?.mediaId,
                        activeItemId,
                    )
                    return
                }
                // W5-T6：睡眠定时「播完当前曲目停止」= AUTO 切歌后暂停（只消费一次）
                if (
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
                        sleepTimer.consumeStopAfterCurrentTrack()
                ) {
                    player.pause()
                }
                syncQueueWithPlayer(player)
                val nextItemId = mediaItem?.mediaId.toItemId()
                playbackRecovery.onItemChanged(nextItemId)
                onTrackChanged(nextItemId)
            }

            /**
             * W5-T8（MUSIC-48）：音乐会话内的播放错误恢复链。
             *
             * 本地 `content://` 源确认不可读 → 标记条目「不可用」并跳过（不重试）； 其余错误：同曲第一次延迟 1s 重试，第二次失败提示 +
             * 跳下一首，队尾则停播清态。
             */
            override fun onPlayerError(error: PlaybackException) {
                if (!playerHolder.musicSessionActive) return
                val player = playerHolder.existingPlayer ?: return
                if (!player.isPlayingMusicItem()) return
                onMusicPlayerError(player, error)
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
                _isPlaying.value = MusicPlaybackSurfaceState.from(player).isPlaybackActive
                val itemId = activeItemId ?: return
                // 暂停 / 恢复 / 耳机拔出：立即上报，不等 10s 周期
                reportProgress(
                    itemId,
                    player.currentPosition.coerceAtLeast(0L),
                    isPaused = !playWhenReady,
                )
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                // W5-T7：用户拖进度 → 本曲资格清除（本次播放内不再淡化，含曲尾）
                crossfadeEligibility.onPositionDiscontinuity(reason)
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                if (!playerHolder.musicSessionActive) return
                val player = playerHolder.existingPlayer ?: return
                if (!player.isPlayingMusicItem()) return
                _queue.update { queue ->
                    queue?.let { current ->
                        player.captureShuffleSnapshot(
                            current.copy(
                                shuffleEnabled = shuffleModeEnabled,
                                shuffleKeepCurrentFirst =
                                    shuffleModeEnabled && current.shuffleKeepCurrentFirst,
                            )
                        )
                    }
                }
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                if (!playerHolder.musicSessionActive) return
                val player = playerHolder.existingPlayer ?: return
                if (!player.isPlayingMusicItem()) return
                _queue.update { queue -> queue?.copy(repeatMode = repeatMode.toMusicRepeatMode()) }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val player = playerHolder.existingPlayer
                if (
                    player != null && playerHolder.musicSessionActive && player.isPlayingMusicItem()
                ) {
                    _isBuffering.value = isMusicBuffering(playbackState, player.playWhenReady)
                }
                if (playbackState != Player.STATE_ENDED) return
                if (!playerHolder.musicSessionActive) return
                // 队列播完：按"播到结尾"收尾，避免服务器把最后位置当成未听完
                val itemId = activeItemId
                // W5-T12：队尾成因只消费一次（错误跳曲置位 / 自然播完复位）
                val endedByError = queueEndFlag.consume()
                if (itemId == null) return
                activeItemId = null
                reportStop(itemId, lastKnownDurationMs, lastKnownDurationMs)
                Timber.i("音乐队列播完：item=%s endedByError=%s", itemId, endedByError)
                _queueEnded.tryEmit(QueueEndedEvent(itemId = itemId, endedByError = endedByError))
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
                    _durationMs.value = lastKnownDurationMs
                    _isPlaying.value = MusicPlaybackSurfaceState.from(player).isPlaybackActive
                    _isBuffering.value =
                        isMusicBuffering(player.playbackState, player.playWhenReady)
                    // 队列可能被移动 / 删除 / 自动切换，索引以播放器为准最可靠
                    syncQueueWithPlayer(player)
                    reportPeriodicProgressIfDue(player, position)
                } else {
                    _positionMs.value = 0L
                    _durationMs.value = 0L
                    _isPlaying.value = false
                    _isBuffering.value = false
                    // 视频起播等外部路径停掉音乐：上报由仲裁器负责，这里只清理本地状态
                    if (!playerHolder.musicSessionActive) activeItemId = null
                }
            }
        }
        // W30-MUSIC-FX：交叉淡化音量包络（100ms 采样；未开启时降频空转，避免常驻高频唤醒）。
        // W5-T6：同一包络通路叠加睡眠定时结束前淡出（乘算，末 5s 自动切到 100ms 采样）。
        scope.launch {
            while (isActive) {
                val crossfadeActive =
                    playerHolder.musicSessionActive && audioEffects.crossfadeSeconds.value > 0
                val sleepFadeActive = playerHolder.musicSessionActive && sleepTimer.fadeActive()
                applyVolumeEnvelope()
                delay(
                    if (crossfadeActive || sleepFadeActive) CROSSFADE_TICK_MS
                    else CROSSFADE_IDLE_TICK_MS
                )
            }
        }
        // W5-T6：音乐倍速（进程级，不落偏好）——只在音乐会话内写共享播放器。
        scope.launch { audioEffects.playbackSpeed.collect { speed -> applyPlaybackSpeed(speed) } }
        // ReplayGain 模式切换（UI 面板）时立刻按当前曲目重算增益。
        scope.launch { audioEffects.replayGainMode.collect { refreshReplayGain() } }
        // W35：本机增益覆盖写入 / 清除后重读标签（覆盖文件优先级最高，立即生效）。
        scope.launch { audioEffects.overrideRevision.collect { if (it > 0L) refreshReplayGain() } }
    }

    override fun setQueue(queue: MusicQueue, startIndex: Int) {
        if (queue.items.isEmpty()) {
            stop()
            return
        }
        val normalized =
            queue.copy(currentIndex = normalizeStartIndex(startIndex, queue.items.size))
        _queue.value = normalized
        // W5-T7：换队列重新开始 → 淡化资格复位（随后由 PLAYLIST_CHANGED 过渡保持"不可淡化"）
        crossfadeEligibility.reset()
        playbackJob?.cancel()
        // W5-T8：换队列 = 新一轮播放，清掉上一轮的重试记忆与挂起的重试任务
        errorRetryJob?.cancel()
        errorRetryJob = null
        playbackRecovery.reset()
        // W5-T12：换队列 = 新一轮队尾成因从"自然播完"起算
        queueEndFlag.reset()
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
                // W23-MUSIC 真机缺陷修复：换队列的过渡窗口里 ticker 仍按**旧**播放器状态回写
                // currentIndex（例如从 100 首随机队列切到 6 首最近播放时是 84），直接喂给
                // setMediaItems 会抛 IllegalSeekPositionException → 起播失败。这里统一归一化。
                // W5 T5：时间线 = 主队列 + 接下来播放（见 MusicQueue.playbackItems），起播位取时间线下标。
                val timeline = latest.playbackItems
                val requestedIndex = latest.playbackIndex.coerceIn(timeline.indices)
                // W5-T8（MUSIC-49）：起播位落在「不可用」条目上时向前跳过；整段都不可用则保留队列但不出声。
                val startIndex = firstPlayableQueueIndex(timeline, requestedIndex)
                player.setMediaItems(
                    timeline.map { it.toMusicMediaItem(playerHolder.musicPlaceholderArtworkUri) },
                    startIndex ?: requestedIndex,
                    0L,
                )
                // 恢复顺序必须在 setMediaItems 之后、prepare/play 之前安装；只有完整置换才可表达。
                val queueWithSeed = player.installPersistedShuffleOrder(latest)
                player.repeatMode = latest.repeatMode.toPlayerRepeatMode()
                player.shuffleModeEnabled = latest.shuffleEnabled
                val published =
                    player
                        .captureShuffleSnapshot(queueWithSeed)
                        .withTimelineIndex(startIndex ?: requestedIndex)
                _queue.value = published
                if (startIndex == null) {
                    // 整段队列都没有可播条目（本地文件缺失）：保留队列供用户手动移除，不 prepare / 不报 Start。
                    Timber.w("音乐队列没有可播放条目（本地文件缺失）")
                    _isPlaying.value = false
                    _isBuffering.value = false
                    activeItemId = null
                    audioEffects.clearCurrentTrack()
                    return@launch
                }
                player.prepare()
                // 交叉淡化可能把音量留在 0（异常路径）；起播前复位到 1，随后由包络循环接管。
                player.volume = 1f
                // W5-T6：音乐会话起播强制 1.0×（与视频倍速互斥，不继承上个会话）
                audioEffects.resetPlaybackSpeedForSessionStart()
                player.setPlaybackSpeed(MUSIC_DEFAULT_PLAYBACK_SPEED)
                // 6) 续播（MU-9）：优先使用服务器记录的位置
                published.currentItem
                    ?.playbackPosition
                    ?.takeIf { it > 0L }
                    ?.let { position -> player.seekTo(startIndex, position) }
                player.play()
                _isPlaying.value = MusicPlaybackSurfaceState.from(player).isPlaybackActive
                published.currentItem?.let { item ->
                    activeItemId = item.itemId
                    lastKnownPositionMs = item.playbackPosition.coerceAtLeast(0L)
                    lastKnownDurationMs = 0L
                    lastProgressReportAtMs = SystemClock.elapsedRealtime()
                    audioEffects.setCurrentTrack(item.itemId)
                    reportStartNow(item.itemId)
                }
                refreshReplayGain()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "音乐起播失败")
                playerHolder.musicSessionActive = false
                activeItemId = null
                audioEffects.clearCurrentTrack()
            }
        }
    }

    override fun playPause() {
        val player = musicPlayer() ?: return
        _isPlaying.value = player.toggleMusicPlaybackTransport().isPlaybackActive
        _isBuffering.value = isMusicBuffering(player.playbackState, player.playWhenReady)
    }

    override fun play() {
        val player = musicPlayer() ?: return
        player.play()
        _isPlaying.value = MusicPlaybackSurfaceState.from(player).isPlaybackActive
        _isBuffering.value = isMusicBuffering(player.playbackState, player.playWhenReady)
    }

    override fun pause() {
        val player = musicPlayer() ?: return
        player.pause()
        _isPlaying.value = false
        // 缓冲中按暂停 = 立即退出缓冲态（缓冲不渲染成暂停，暂停也不残留缓冲指示）
        _isBuffering.value = false
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
        setShuffleMode(if (enabled) ShuffleMode.ALL else ShuffleMode.OFF)
    }

    /**
     * W5 T5（MUSIC-12）：三种随机模式。
     *
     * 「全随机」与「居首随机」都安装一条显式随机序（纯函数可单测）；有「接下来播放」条目时， 随机序把当前曲与手动插入项钉在队首，保证手动插入优先于主队列消费。
     */
    override fun setShuffleMode(shuffleMode: ShuffleMode) {
        val current = _queue.value ?: return
        val updated =
            current.copy(
                shuffleEnabled = shuffleMode != ShuffleMode.OFF,
                shuffleKeepCurrentFirst = shuffleMode == ShuffleMode.KEEP_CURRENT_FIRST,
                shuffleOrder = null,
                shuffleSeed = null,
            )
        val player = musicPlayer()
        if (player == null) {
            _queue.value = updated
            return
        }
        player.shuffleModeEnabled = updated.shuffleEnabled
        if (updated.shuffleEnabled) player.installShuffleOrder(updated)
        _queue.value = player.captureShuffleSnapshot(updated)
    }

    override fun move(fromIndex: Int, toIndex: Int) {
        val current = _queue.value ?: return
        val moved = current.move(fromIndex, toIndex)
        if (moved == current) return
        val player = musicPlayer()
        if (player == null) {
            _queue.value = moved
            return
        }
        if (player.mediaItemCount == current.playbackItems.size) {
            val offset = current.upNextCount
            val from = current.mainToTimelineIndex(fromIndex, offset)
            val to =
                current.mainToTimelineIndex(toIndex, offset).coerceAtMost(player.mediaItemCount - 1)
            player.moveMediaItem(from, to)
        }
        _queue.value = player.captureShuffleSnapshot(moved)
    }

    /** W5 T5：播放下一首 → 「接下来播放」队首（播完即出，不再残留在主队列里）。 */
    override fun insertNext(item: PlayerItem) {
        insertUpNext(listOf(item), atHead = true)
    }

    /** W5 T5：加到「接下来播放」队尾。 */
    override fun addToUpNext(item: PlayerItem) {
        insertUpNext(listOf(item), atHead = false)
    }

    /**
     * 批量追加到队尾（不依赖当前曲目索引）。
     *
     * 补队列走这里而不是 `insertNext` + `move`：后者以当前曲目索引为锚点，播放器自动切歌时会把条目 摆到错误位置；批量追加只认队尾。随机模式下
     * `addMediaItems` 由内核自行把新条目插进 shuffle 顺序， 队列语义与随机语义都不受影响。
     */
    override fun appendToQueue(items: List<PlayerItem>) {
        if (items.isEmpty()) return
        val current = _queue.value ?: return
        val player = musicPlayer() ?: return
        player.addMediaItems(
            items.map { it.toMusicMediaItem(playerHolder.musicPlaceholderArtworkUri) }
        )
        _queue.value = player.captureShuffleSnapshot(current.appendItems(items))
    }

    /**
     * W5-T12（MUSIC-14 Auto DJ）：从主队列第 [index] 首起播。
     *
     * 队列播完（`STATE_ENDED`）后 `play()` 只作用于当前项（已播完的队尾曲），不会前进到刚追加的条目； 这里显式 seek 到目标条目再起播，
     * 让追加批次的第一首走正常切歌路径（SEEK 过渡 → Stop/Start 上报）。
     */
    override fun playFromQueueIndex(index: Int) {
        val current = _queue.value ?: return
        if (index !in current.items.indices) return
        val player = musicPlayer() ?: return
        if (player.mediaItemCount != current.playbackItems.size) return
        val timelineIndex = current.mainToTimelineIndex(index)
        // 从头播放新批次（追加的条目没有续播位置）
        player.seekTo(timelineIndex, 0L)
        player.play()
        Timber.i("音乐从队列第 %d 首起播（时间线下标 %d）", index, timelineIndex)
    }

    /** W78：批量前插到队首；当前曲目索引整体后移（内核会同步移动当前媒体项，位置轮询只做一致性回写）。 */
    override fun prependToQueue(items: List<PlayerItem>) {
        if (items.isEmpty()) return
        val current = _queue.value ?: return
        val player = musicPlayer() ?: return
        player.addMediaItems(
            0,
            items.map { it.toMusicMediaItem(playerHolder.musicPlaceholderArtworkUri) },
        )
        _queue.value = player.captureShuffleSnapshot(current.prependItems(items))
    }

    override fun jumpTo(index: Int) {
        val current = _queue.value ?: return
        if (index !in current.items.indices) return
        val player = musicPlayer() ?: return
        // W5-T8（MUSIC-49）：目标条目本地缺失时向前跳到下一首可播曲目（保留条目，可手动移除）。
        val playableIndex = firstPlayableQueueIndex(current.items, index) ?: return
        val clean = current.copy(currentIndex = playableIndex, upNextPlaying = null)
        if (current.upNextPlaying == null && current.upNext.isEmpty()) {
            player.seekTo(playableIndex, 0L)
            player.play()
            return
        }
        // 有接下来播放条目时时间线与主队列下标不再一一对应：重建时间线（跳播本来就重启目标曲目）。
        val target = clean.copy(shuffleOrder = null, shuffleSeed = null)
        val timeline = target.playbackItems
        val startIndex = target.playbackIndex.coerceIn(timeline.indices)
        player.setMediaItems(
            timeline.map { it.toMusicMediaItem(playerHolder.musicPlaceholderArtworkUri) },
            startIndex,
            0L,
        )
        val withOrder = player.installPersistedShuffleOrder(target)
        player.repeatMode = withOrder.repeatMode.toPlayerRepeatMode()
        player.shuffleModeEnabled = withOrder.shuffleEnabled
        player.prepare()
        player.play()
        _queue.value = player.captureShuffleSnapshot(withOrder)
    }

    override fun jumpToUpNext(index: Int) {
        val current = _queue.value ?: return
        if (index !in current.upNext.indices) return
        val player = musicPlayer() ?: return
        if (player.mediaItemCount != current.playbackItems.size) return
        val target = current.playbackIndex + 1 + index
        if (target !in 0 until player.mediaItemCount) return
        player.seekTo(target, 0L)
        player.play()
    }

    override fun removeAt(index: Int) {
        val current = _queue.value ?: return
        if (index !in current.items.indices) return
        val player = musicPlayer() ?: return
        if (player.mediaItemCount == current.playbackItems.size) {
            val offset = current.upNextCount
            player.removeMediaItem(current.mainToTimelineIndex(index, offset))
        }
        val removed = current.removeAt(index)
        _queue.value = player.captureShuffleSnapshot(removed)
    }

    override fun removeUpNextAt(index: Int) {
        val current = _queue.value ?: return
        if (index !in current.upNext.indices) return
        val player = musicPlayer() ?: return
        if (player.mediaItemCount == current.playbackItems.size) {
            // 待播段起点 = 当前时间线下标 + 1（正在播放的接下来播放条目排在它前面）
            player.removeMediaItem(current.playbackIndex + 1 + index)
        }
        val updated = current.removeUpNextAt(index)
        if (updated.shuffleEnabled) player.installShuffleOrder(updated)
        _queue.value = player.captureShuffleSnapshot(updated)
    }

    override fun clearUpNext() {
        val current = _queue.value ?: return
        if (current.upNext.isEmpty()) return
        val player = musicPlayer() ?: return
        if (player.mediaItemCount == current.playbackItems.size) {
            val from = current.playbackIndex + 1
            player.removeMediaItems(from, from + current.upNext.size)
        }
        val updated = current.clearUpNext()
        if (updated.shuffleEnabled) player.installShuffleOrder(updated)
        _queue.value = player.captureShuffleSnapshot(updated)
    }

    /** 「接下来播放」入队：模型 + 播放器时间线同时插入（队首 = 手动优先）。 */
    private fun insertUpNext(items: List<PlayerItem>, atHead: Boolean) {
        if (items.isEmpty()) return
        val current = _queue.value ?: return
        val player = musicPlayer() ?: return
        val insertAt =
            if (atHead) current.playbackIndex + 1
            else (current.playbackIndex + 1 + current.upNext.size)
        if (player.mediaItemCount == current.playbackItems.size) {
            player.addMediaItems(
                insertAt.coerceIn(0, player.mediaItemCount),
                items.map { it.toMusicMediaItem(playerHolder.musicPlaceholderArtworkUri) },
            )
        }
        val updated = if (atHead) current.prependUpNext(items) else current.appendUpNext(items)
        if (updated.shuffleEnabled) player.installShuffleOrder(updated)
        _queue.value = player.captureShuffleSnapshot(updated)
    }

    override fun stop() {
        playbackJob?.cancel()
        playbackJob = null
        // W5-T8：停止 = 本轮播放结束，清掉挂起的错误重试与重试记忆
        errorRetryJob?.cancel()
        errorRetryJob = null
        playbackRecovery.reset()
        // W5-T12：停止 = 队尾成因归零（下次起播从"自然播完"起算）
        queueEndFlag.reset()
        val itemId = activeItemId
        val positionMs = lastKnownPositionMs
        val durationMs = lastKnownDurationMs
        activeItemId = null
        _queue.value = null
        _positionMs.value = 0L
        _durationMs.value = 0L
        _isPlaying.value = false
        _isBuffering.value = false
        audioEffects.clearCurrentTrack()
        // W5-T7：会话结束，淡化资格归零
        crossfadeEligibility.reset()
        // W5-T6：音乐会话结束，清掉「播完当前曲目停止」的挂起状态
        sleepTimer.clearStopAfterCurrentTrack()

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
        // 倍速是音乐会话内状态：会话结束复位 1.0，避免共享实例带进视频会话
        audioEffects.resetPlaybackSpeedForSessionStart()
        player.setPlaybackSpeed(MUSIC_DEFAULT_PLAYBACK_SPEED)
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

    /**
     * 音乐播放错误恢复（W5-T8，MUSIC-48/49）。
     *
     * - 本地 `content://` 源确认不可读：标记队列条目「不可用」→ 跳过（不重试）；
     * - 同一曲目第一次错误：延迟 [MUSIC_ERROR_RETRY_DELAY_MS] 后 `prepare()`，`playWhenReady` 保持；
     * - 同一曲目再次错误：发失败事件 → `seekToNextMediaItem`；队尾没有下一首则按 [stop] 收尾。
     */
    private fun onMusicPlayerError(player: Player, error: PlaybackException) {
        val itemId = player.currentMediaItem?.mediaId.toItemId()
        val item = queueItem(itemId)
        val hasNext = player.hasNextDistinctMusicItem()
        _isBuffering.value = false
        val localUnavailable =
            isLocalSourceUnavailableError(
                mediaSourceUri = player.currentMediaItem?.localConfiguration?.uri?.toString(),
                errorCode = error.errorCode,
            )
        if (localUnavailable) {
            Timber.w(error, "音乐本地源不可用，跳过：%s", item?.name ?: itemId)
            markQueueItemUnavailable(itemId)
            emitPlaybackFailure(item, itemId, skippedToNext = hasNext)
            if (hasNext) skipToNextMusicItem(player) else stop()
            return
        }
        when (playbackRecovery.onError(itemId = itemId, hasNext = hasNext)) {
            MusicErrorRecoveryAction.RETRY -> {
                Timber.w(error, "音乐播放失败，%d ms 后重试：%s", MUSIC_ERROR_RETRY_DELAY_MS, itemId)
                // 重试窗口内保留播放意图（缓冲不算暂停，重试同样不算）
                _isPlaying.value = player.playWhenReady
                scheduleErrorRetry(player, itemId)
            }
            MusicErrorRecoveryAction.SKIP_TO_NEXT -> {
                Timber.w(error, "音乐播放重试仍失败，跳到下一首：%s", itemId)
                emitPlaybackFailure(item, itemId, skippedToNext = true)
                skipToNextMusicItem(player)
            }
            MusicErrorRecoveryAction.STOP -> {
                Timber.w(error, "音乐播放重试仍失败且没有下一首，停止：%s", itemId)
                emitPlaybackFailure(item, itemId, skippedToNext = false)
                stop()
            }
        }
    }

    /** 延迟重试当前曲目；延迟期间切歌 / 换队列 / 停止都会让这次重试作废。 */
    private fun scheduleErrorRetry(player: Player, itemId: UUID?) {
        errorRetryJob?.cancel()
        errorRetryJob = scope.launch {
            delay(MUSIC_ERROR_RETRY_DELAY_MS)
            val current = musicPlayer() ?: return@launch
            if (current !== player) return@launch
            if (current.currentMediaItem?.mediaId.toItemId() != itemId) return@launch
            val resume = current.playWhenReady
            runCatching {
                current.prepare()
                if (resume) current.play()
            }
                .onFailure { Timber.w(it, "音乐错误重试失败") }
        }
    }

    /** 跳过当前条目（重试仍失败 / 本地不可用）：保持播放意图，队尾无下一首时由调用方走 stop。 */
    private fun skipToNextMusicItem(player: Player) {
        // W5-T12：错误跳曲置位——若这次跳曲之后队列走到结尾，Auto DJ 必须静默 no-op（风险③）。
        queueEndFlag.onErrorSkip()
        val resume = player.playWhenReady
        errorRetryJob?.cancel()
        errorRetryJob = null
        runCatching {
            player.seekToNextMediaItem()
            player.prepare()
            if (resume) player.play()
        }
            .onFailure { Timber.w(it, "音乐播放失败后跳下一首失败") }
        if (resume) _isPlaying.value = true
    }

    /** 把队列里同一 itemId 的条目（主队列 / 接下来播放 / 正在播放）标记为「不可用」。 */
    private fun markQueueItemUnavailable(itemId: UUID?) {
        if (itemId == null) return
        _queue.update { queue ->
            queue?.let { current ->
                val items =
                    current.items.map { item ->
                        if (item.itemId == itemId) item.copy(unavailable = true) else item
                    }
                val upNext =
                    current.upNext.map { item ->
                        if (item.itemId == itemId) item.copy(unavailable = true) else item
                    }
                val upNextPlaying =
                    current.upNextPlaying?.let { item ->
                        if (item.itemId == itemId) item.copy(unavailable = true) else item
                    }
                if (
                    items == current.items &&
                        upNext == current.upNext &&
                        upNextPlaying == current.upNextPlaying
                ) {
                    current
                } else {
                    current.copy(
                        items = items,
                        upNext = upNext,
                        upNextPlaying = upNextPlaying,
                    )
                }
            }
        }
    }

    /** 队列内按 itemId 找条目（当前曲目优先，其次主队列 / 接下来播放）。 */
    private fun queueItem(itemId: UUID?): PlayerItem? {
        if (itemId == null) return null
        val queue = _queue.value ?: return null
        return queue.currentItem?.takeIf { it.itemId == itemId }
            ?: queue.items.firstOrNull { it.itemId == itemId }
            ?: queue.upNext.firstOrNull { it.itemId == itemId }
    }

    private fun emitPlaybackFailure(item: PlayerItem?, itemId: UUID?, skippedToNext: Boolean) {
        _playbackFailures.tryEmit(
            MusicPlaybackFailure(
                itemId = itemId ?: item?.itemId,
                name = item?.name.orEmpty(),
                artist = item?.artist,
                skippedToNext = skippedToNext,
            )
        )
    }

    /**
     * 用「当前曲目 + 接下来播放 + 其余洗牌」安装固定随机序（W5 T5，MUSIC-12）。
     *
     * 有手动插入项时优先序永远压过随机（[priorityShuffleOrder]）；否则按模式取整队随机 / 当前居首随机。 关闭随机或队列不足两首时不安装。
     */
    private fun Player.installShuffleOrder(queue: MusicQueue): MusicQueue {
        val exo = this as? ExoPlayer ?: return queue
        val itemCount = queue.playbackItems.size
        if (itemCount <= 1) return queue
        val seed = queue.shuffleSeed ?: nextShuffleSeed()
        val priority = queue.upNextCount
        val order =
            when {
                priority > 0 ->
                    priorityShuffleOrder(itemCount, queue.playbackIndex, priority, Random(seed))
                queue.shuffleMode == ShuffleMode.KEEP_CURRENT_FIRST ->
                    currentFirstShuffleOrder(itemCount, queue.playbackIndex, Random(seed))
                else -> fullShuffleOrder(itemCount, Random(seed))
            }
        exo.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(order.toIntArray(), seed))
        return queue.copy(shuffleSeed = seed)
    }

    /**
     * 安装持久实际序。安装前要求完整置换；安装后不在这里宣称完成，调用方统一从 player 回读并发布。
     *
     * `setShuffleOrder` 是同步 API，没有异步 ack；回读实际序是唯一完成锚点。无持久序时保留内核为 `setMediaItems` 生成的新序，供旧 v2
     * 快照显式重建一次。
     */
    private fun Player.installPersistedShuffleOrder(queue: MusicQueue): MusicQueue {
        val exo = this as? ExoPlayer ?: return queue.copy(shuffleOrder = null, shuffleSeed = null)
        val order = queue.shuffleOrder
        if (!isValidShuffleOrder(order, queue.playbackItems.size) || order == null) return queue
        val seed = queue.shuffleSeed ?: nextShuffleSeed()
        exo.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(order.toIntArray(), seed))
        return queue.copy(shuffleSeed = seed)
    }

    /** 从同一音频 player 回读顺序，形成 Persister 可直接消费的单代快照（索引由 [syncQueueWithPlayer] 对齐）。 */
    private fun Player.captureShuffleSnapshot(queue: MusicQueue): MusicQueue {
        val timeline = queue.playbackItems
        val timelineMatches =
            mediaItemCount == timeline.size &&
                timeline.indices.all { index ->
                    getMediaItemAt(index).mediaId == timeline[index].itemId.toString()
                }
        val exo = this as? ExoPlayer
        val order =
            if (timelineMatches && exo != null) readShuffleOrder(exo.shuffleOrder, timeline.size)
            else null
        return if (order == null) {
            queue.copy(shuffleOrder = null, shuffleSeed = null)
        } else {
            queue.copy(shuffleOrder = order, shuffleSeed = queue.shuffleSeed ?: nextShuffleSeed())
        }
    }

    /**
     * 播放器时间线 → 队列（W5 T5 双队列的唯一对齐入口）。
     *
     * 播完即出：离开「接下来播放」条目时把已播出 / 被跳过的条目从时间线摘掉，队列与时间线始终同构，索引不会漂移。
     */
    private fun syncQueueWithPlayer(player: Player) {
        val previous = _queue.value ?: return
        if (player.mediaItemCount != previous.playbackItems.size) return
        if (player.currentMediaItemIndex !in 0 until player.mediaItemCount) return
        val sync = previous.reconcileWithTimeline(player.currentMediaItemIndex) ?: return
        // 旧下标降序摘除：已播出 / 被跳过的「接下来播放」条目，主队列条目不在这里
        for (index in sync.removalsDescending) {
            if (index < player.mediaItemCount) player.removeMediaItem(index)
        }
        _queue.value = player.captureShuffleSnapshot(sync.queue)
    }

    /** 主队列下标 → 时间线下标（接下来播放段插在主队列当前曲之后，主队列后半段整体后移）。 */
    private fun MusicQueue.mainToTimelineIndex(mainIndex: Int, offset: Int = upNextCount): Int =
        musicQueueTimelineIndex(currentIndex, offset, mainIndex)

    private fun nextShuffleSeed(): Long = Random.nextLong()

    private fun Int.toMusicRepeatMode(): RepeatMode =
        when (this) {
            Player.REPEAT_MODE_ONE -> RepeatMode.ONE
            Player.REPEAT_MODE_ALL -> RepeatMode.ALL
            else -> RepeatMode.OFF
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
        // W5-T12 风险①取证：每次切歌上报都留一行，K60 用来核对续播曲是否逐首 PlaybackStart。
        Timber.i("音乐切歌上报：prev=%s next=%s", previousItemId, nextItemId)
        activeItemId = nextItemId
        lastKnownPositionMs = 0L
        lastKnownDurationMs = 0L
        lastProgressReportAtMs = SystemClock.elapsedRealtime()
        if (nextItemId != null) {
            // W35：与 ReplayGain 模式无关地登记当前曲目（音效面板的「本机增益覆盖」要用）。
            audioEffects.setCurrentTrack(nextItemId)
            refreshReplayGain()
        } else {
            audioEffects.clearCurrentTrack()
        }
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
        // W5-T12 风险①取证：PlaybackStart 的单一出口，K60 逐曲核对续播批次上报完整性。
        Timber.i("音乐 PlaybackStart 上报：item=%s", itemId)
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

    /**
     * 音量包络（W30-MUSIC-FX 交叉淡化 + W5-T6 睡眠定时淡出）。
     *
     * - 音乐会话结束 / 无任何淡出：复位为 1（不残留）；
     * - 交叉淡化：`Player.volume` = [MusicCrossfadeMath.crossfadeVolume]（等功率，与 gapless 不冲突），暂停中保持当前值；
     *   W5-T7：只有队列自然推进进入的曲目（[MusicCrossfadeEligibility]）才淡化，手动切歌 / 拖进度 / 短曲恒 1；
     * - 睡眠定时：末 [SleepTimerSpec.FADE_OUT_MS] 秒叠乘 [SleepTimerSpec.fadeOutGain]（同一 `Player.volume`
     *   包络通路），到点暂停后由 [SleepTimerController] 复位为 1。
     */
    private fun applyVolumeEnvelope() {
        val player = playerHolder.existingPlayer ?: return
        if (!playerHolder.musicSessionActive || !player.isPlayingMusicItem()) {
            if (player.volume != 1f) player.volume = 1f
            return
        }
        val fadeMs = audioEffects.crossfadeSeconds.value * 1_000L
        val eligible = crossfadeEligibility.eligible
        val sleepGain = sleepTimer.volumeGain()
        if (!player.isPlaying && sleepGain >= 0.999f) {
            // 交叉淡化暂停中保持当前包络值（旧行为）；关闭淡化 / 本曲无资格时复位 1
            if ((fadeMs <= 0L || !eligible) && player.volume != 1f) player.volume = 1f
            return
        }
        val crossfadeGain =
            if (fadeMs > 0L && player.isPlaying) {
                val duration = player.duration
                if (duration == C.TIME_UNSET || duration <= 0L) {
                    1f
                } else {
                    MusicCrossfadeMath.crossfadeVolume(
                        positionMs = player.currentPosition.coerceAtLeast(0L),
                        durationMs = duration,
                        fadeMs = fadeMs,
                        eligible = eligible,
                    )
                }
            } else {
                1f
            }
        val target = crossfadeGain * sleepGain
        if (abs(player.volume - target) <= 0.002f) return
        player.volume = target
        val now = SystemClock.elapsedRealtime()
        if (sleepGain < 0.999f) {
            if (now - lastSleepFadeLogAtMs >= CROSSFADE_LOG_INTERVAL_MS || target <= 0.001f) {
                lastSleepFadeLogAtMs = now
                Timber.i(
                    "睡眠定时淡出：剩余 %d ms → 音量 %.2f",
                    sleepTimer.remainingMs(),
                    target,
                )
            }
            return
        }
        if (fadeMs > 0L && player.isPlaying) {
            val duration = player.duration
            if (
                now - lastCrossfadeLogAtMs >= CROSSFADE_LOG_INTERVAL_MS ||
                    target <= 0.001f ||
                    target >= 0.999f
            ) {
                lastCrossfadeLogAtMs = now
                Timber.i(
                    "交叉淡化：位置 %d ms / 剩余 %d ms → 音量 %.2f",
                    player.currentPosition.coerceAtLeast(0L),
                    if (duration == C.TIME_UNSET) 0L
                    else (duration - player.currentPosition).coerceAtLeast(0L),
                    target,
                )
            }
        }
    }

    /** 音乐会话内应用倍速（Exo Sonics 保调）；视频会话 / 非音乐条目一律不碰。 */
    private fun applyPlaybackSpeed(speed: Float) {
        val player = playerHolder.existingPlayer ?: return
        if (!playerHolder.musicSessionActive || !player.isPlayingMusicItem()) return
        if (abs(player.playbackParameters.speed - speed) <= 0.001f) return
        player.setPlaybackSpeed(speed)
        Timber.i("音乐倍速：%.2f×", speed)
    }

    /** 读取当前曲目的 ReplayGain 标签并按模式应用；异步 IO，回到主线程后核对曲目未变。 */
    private fun refreshReplayGain() {
        if (!playerHolder.musicSessionActive) return
        val item = _queue.value?.currentItem ?: return
        if (audioEffects.replayGainMode.value == ReplayGainMode.OFF) {
            audioEffects.clearReplayGainTags()
            return
        }
        scope.launch {
            // 换曲 / 重读前先清掉上一首标签：避免旧增益瞬时套到新曲（读取完成后立即回填）
            audioEffects.clearReplayGainTags()
            var result = replayGainReader.read(item)
            if (result is ReplayGainReadResult.Failed) {
                // 网络抖动：失败不写缓存，3 秒后重试一次
                delay(REPLAYGAIN_RETRY_DELAY_MS)
                if (_queue.value?.currentItem?.itemId != item.itemId) return@launch
                result = replayGainReader.read(item)
            }
            if (_queue.value?.currentItem?.itemId != item.itemId) return@launch
            (result as? ReplayGainReadResult.Value)?.let { value ->
                audioEffects.setCurrentTrackReplayGain(item.itemId, value.tags)
            }
        }
    }

    private companion object {
        /** 位置轮询间隔：喂 UI 进度条 + 采样上报位置，够用且不费电。 */
        const val POSITION_TICK_MS = 500L

        /** 交叉淡化包络采样间隔（开启时）。 */
        const val CROSSFADE_TICK_MS = 100L

        /** 未开启交叉淡化时的空转间隔（只做"是否需要复位音量"的轻检查）。 */
        const val CROSSFADE_IDLE_TICK_MS = 500L

        /** 淡化音量序列日志的最小间隔（毫秒），避免 100ms 采样刷屏。 */
        const val CROSSFADE_LOG_INTERVAL_MS = 400L

        /** ReplayGain 拉取失败后的一次重试延迟（毫秒）。 */
        const val REPLAYGAIN_RETRY_DELAY_MS = 3_000L

        /** W5-T8：音乐播放错误后的第一次重试延迟（毫秒，MUSIC-48 定稿 1s）。 */
        const val MUSIC_ERROR_RETRY_DELAY_MS = 1_000L
    }
}

/**
 * 主队列下标 → 播放器时间线下标（W5-T12 抽成纯函数，单测钉住"追加批次第一首"的定位）。
 *
 * 「接下来播放」段插在主队列当前曲之后：`mainIndex <= currentIndex` 原样返回（与既有 move / removeAt 的用法一致， 起播路径只用到
 * `mainIndex > currentIndex` 的追加批次），其后的主队列条目整体后移 [upNextCount]。
 */
internal fun musicQueueTimelineIndex(
    currentIndex: Int,
    upNextCount: Int,
    mainIndex: Int,
): Int = if (mainIndex <= currentIndex) mainIndex else mainIndex + upNextCount
