package com.zhangwenkang.cinefin.player.local.presentation

import android.app.Application
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.zhangwenkang.cinefin.player.core.playback.EngineTrack
import com.zhangwenkang.cinefin.player.core.playback.EngineTrackType
import com.zhangwenkang.cinefin.player.core.playback.PlaybackEngine
import com.zhangwenkang.cinefin.player.local.R
import com.zhangwenkang.cinefin.player.local.audio.AudioDelayProcessor
import com.zhangwenkang.cinefin.player.local.audio.CinefinRenderersFactory
import com.zhangwenkang.cinefin.player.local.audio.MusicAudioEffectsController
import com.zhangwenkang.cinefin.player.local.domain.PlayerDecodeMode
import com.zhangwenkang.cinefin.player.local.domain.PlayerExtraPreferences
import com.zhangwenkang.cinefin.player.local.domain.PlaylistManager
import com.zhangwenkang.cinefin.player.local.domain.SeekEngine
import com.zhangwenkang.cinefin.player.local.domain.TrackSelectionEngine
import com.zhangwenkang.cinefin.player.local.domain.isPlayingMusicItem
import com.zhangwenkang.cinefin.player.local.engine.EngineSwitchOutcome
import com.zhangwenkang.cinefin.player.local.engine.EngineSwitchSnapshot
import com.zhangwenkang.cinefin.player.local.engine.EngineTrackMatcher
import com.zhangwenkang.cinefin.player.local.engine.Media3PlaybackEngine
import com.zhangwenkang.cinefin.player.local.engine.PlaybackEngineFactory
import com.zhangwenkang.cinefin.player.local.engine.exo.ExoPlaybackEngine
import com.zhangwenkang.cinefin.player.local.engine.exo.ExoSubtitleHooks
import com.zhangwenkang.cinefin.player.local.mpv.MPVPlayer
import com.zhangwenkang.cinefin.player.local.network.PlaybackServerCredentials
import com.zhangwenkang.cinefin.player.local.network.ServerAuthDataSourceFactory
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.settings.domain.PlayerDecodeFallback
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * 播放器实例的唯一持有者（阶段 4.1）。
 *
 * 播放器必须活得比播放页久：通知栏、锁屏、后台播放都要求 Activity 销毁后播放器继续存在。 所以创建与释放从 `PlayerViewModel` 挪到这里：播放页与
 * `CinefinPlaybackService` 共用同一实例， 通知栏上的播放/暂停/上下集/快退快进直接作用在这个实例上，不需要跨进程控制器往返。
 *
 * W2-T4：实例改为经 [PlaybackEngineFactory] 创建并包成 [PlaybackEngine]；[existingPlayer] / [player] 仍返回同一个
 * media3 实例，既有调用方零改动。
 *
 * 线程约定：与 Media3 一致，所有调用都在主线程。
 */
@Singleton
class PlayerHolder
@Inject
constructor(
    private val application: Application,
    val playlistManager: PlaylistManager,
    private val appPreferences: AppPreferences,
    private val musicAudioEffects: MusicAudioEffectsController,
    private val repository: JellyfinRepository,
) {
    companion object {
        /** 播放核心取值，与 `AppPreferences.playerBackend` 里存的一致 */
        const val BACKEND_EXOPLAYER = PlaybackEngine.BACKEND_EXOPLAYER
        const val BACKEND_MPV = PlaybackEngine.BACKEND_MPV

        /** 原地切换后等新内核交出轨道列表的上限；超时只跳过轨道映射，不回滚。 */
        private const val TRACKS_READY_TIMEOUT_MS = 8_000L
    }

    private var engine: Media3PlaybackEngine? = null
    private var instanceBackend: String? = null

    /**
     * 内核切换熔断器（2.0.3-A Bug 11）：进程级，跨播放页 `recreate()` 记忆失败次数。
     *
     * 由宿主在切换失败时记录、在真的出画面 / 用户显式重试时复位。
     */
    val backendSwitchCircuitBreaker = BackendSwitchCircuitBreaker()

    private val _backendSelfHeal = MutableStateFlow<BackendSelfHeal?>(null)

    /** 内核自愈事件（2.0.3-F1 P3-6）：播放页订阅后用既有的内核切换提示条告知用户。 */
    val backendSelfHeal: StateFlow<BackendSelfHeal?> = _backendSelfHeal.asStateFlow()

    /**
     * 原地切换内核进行中（W4-T3a-2）。
     *
     * 切换期间偏好 / 实例会短暂不一致：`player` getter 若照常按偏好比对，会在管线中途把**旧实例**当成过期实例重建， 位置与轨道全丢。窗口内一律返回当前实例，由
     * [switchBackendInPlace] 独占实例的替换。
     */
    private var backendSwitchInProgress: Boolean = false

    /**
     * Exo 字幕延迟 / 样式的出口（W4-T3a-1）。
     *
     * 由 `PlayerViewModel` 注入并在销毁时清空：进程级 holder 不能长期持有 ViewModel 的引用。
     */
    private var exoSubtitleHooks: ExoSubtitleHooks? = null

    /**
     * 原地切内核完成后的回调位（W4-T8d）。
     *
     * SyncPlay 协调器在 `start()` 时挂上自己的重绑逻辑：入组期间切内核要重建播放口与漂移档。用监听位而不是把协调器注入
     * `PlayerViewModel`，是为了让协调器（它依赖本 holder）不被 holder 反向依赖。
     */
    private val backendReboundListeners = mutableListOf<() -> Unit>()

    internal fun addBackendReboundListener(listener: () -> Unit) {
        if (backendReboundListeners.none { it === listener }) backendReboundListeners += listener
    }

    /** 内核实例与上层绑定完成后触发（成功与回滚两条路径都会走到）。 */
    fun notifyBackendRebound() {
        backendReboundListeners.forEach { runCatching(it) }
    }

    /** 装配层注入 / 解除 Exo 字幕钩子；已有实例同步生效。 */
    internal fun installExoSubtitleHooks(hooks: ExoSubtitleHooks?) {
        exoSubtitleHooks = hooks
        (engine as? ExoPlaybackEngine)?.subtitleHooks = hooks
    }

    private val processSeekSessionBridge =
        object : SeekSessionBridge {
            override suspend fun rebuildForSeek(
                itemId: String,
                mediaSourceId: String,
                targetMs: Long,
            ): SeekSessionReplacement? {
                val parsedItemId =
                    runCatching { UUID.fromString(itemId) }.getOrNull() ?: return null
                val rebuilt =
                    withContext(Dispatchers.IO) {
                        playlistManager.rebuildPlayerItemForPosition(
                            parsedItemId,
                            targetMs,
                            mediaSourceId,
                        )
                    } ?: return null
                val mediaItem = rebuilt.toMediaItem(application)
                val mediaIdentity = seekMediaIdentityFromItem(mediaItem) ?: return null
                return SeekSessionReplacement(mediaItem, mediaIdentity)
            }

            override fun onSessionReplaced(replacement: SeekSessionReplacement) = Unit
        }

    /**
     * 进程级协程作用域（W4-T8d）：SyncPlay 的时钟循环、状态机循环与 WS 收集都挂这里。
     *
     * 与 [seekSink] 同级——活得比播放页久，播放页重建不会重订阅 WS（`SyncPlayGateway` 的 Flow 每次收集都会重订阅）。
     * 主线程立即派发：`SyncPlayController` 的入口与 `run()` 必须同线程。
     */
    val processScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Process-scoped, shared by MediaSession and every playback ViewModel. */
    val seekSink: PlaybackSeekSink =
        PlaybackSeekSink(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            engineProvider = {
                if (backend == BACKEND_MPV) SeekEngine.MPV else SeekEngine.EXOPLAYER
            },
            sessionBridge = processSeekSessionBridge,
        )

    private val trackSelectionEngine = TrackSelectionEngine(appPreferences)

    /** 音轨延迟（§1.2）：ExoPlayer 音频链上挂它；mpv 走原生 audio-delay 属性 */
    private val audioDelayProcessor = AudioDelayProcessor()

    /** 当前是否处于音乐会话（由 `MusicPlaybackController` 维护，W1 R2）。 */
    var musicSessionActive: Boolean = false

    /**
     * 已经创建好的播放器实例；没有实例时返回 null（**不**按偏好创建）。
     *
     * 播放会话服务与音乐控制器用它拿"活动实例"：音乐会话期间实例已被 [audioSession] 固定为 ExoPlayer， 若这里再走 [player]
     * 的偏好重建逻辑，会把正在播放的音乐实例换掉。
     */
    val existingPlayer: Player?
        get() = engine?.media3Player

    /** 当前实例的引擎适配器；没有实例时返回 null（**不**按偏好创建）。W2-T4 新增的只读暴露。 */
    val currentEngine: PlaybackEngine?
        get() = engine

    /** 当前实例使用的内核；还没创建实例时返回偏好里的值 */
    val backend: String
        get() = instanceBackend ?: appPreferences.getValue(appPreferences.playerBackend)

    /**
     * 当前实例的媒体项是否为音乐条目（W68：通知 / 锁屏 / 蓝牙 / 车机点击按媒体类型分派路由）。
     *
     * 以 MediaItem 里的 [com.zhangwenkang.cinefin.player.local.domain.MUSIC_MEDIA_EXTRA] 标记为准， 不信任
     * [musicSessionActive]——视频路径直接 setMediaItems 抢用实例时，标志可能还没清零。
     */
    val isCurrentItemMusic: Boolean
        get() = existingPlayer?.isPlayingMusicItem() == true

    /**
     * W68：音乐条目没有封面时，给媒体会话（系统桌面媒体胶囊 / 锁屏）的通用音符占位图 URI。
     *
     * 用本模块的 PNG 资源（系统与 Coil 都能按流解码），避免 `artworkUri == null` 时系统卡片空白 / 黑图；
     * 有真实专辑图（[com.zhangwenkang.cinefin.player.core.domain.models.PlayerItem.thumbnailUri]）时优先真实图。
     */
    val musicPlaceholderArtworkUri: String =
        "android.resource://${application.packageName}/${R.drawable.ic_music_placeholder}"

    /** 播放器实例。按当前偏好创建；偏好里的内核变了（换内核重开播放页）会自动重建， 调用方拿到的永远是"对的内核 + 活的实例"。 */
    val player: Player
        get() {
            // 音乐会话期间固定 ExoPlayer：偏好是 mpv 时，后台视频页对 player 的访问
            // （每秒的进度/片段任务）也不能把正在播放的音乐实例换回 mpv。
            // 音乐会话结束后（musicSessionActive=false）恢复下面的偏好重建逻辑。
            if (musicSessionActive) {
                return audioSession()
            }
            // 原地切换窗口：实例正由 switchBackendInPlace 独占替换，这里不能再按偏好比对重建。
            if (backendSwitchInProgress) {
                engine?.let {
                    return it.media3Player
                }
                val pending = appPreferences.getValue(appPreferences.playerBackend)
                return installSelfHealing(pending)
            }
            val wanted = appPreferences.getValue(appPreferences.playerBackend)
            val existing = engine
            if (existing != null && instanceBackend == wanted) return existing.media3Player
            if (existing != null) {
                Timber.i("播放内核切换为 %s，重建播放器实例", wanted)
                seekSink.onPlayerReleased()
                runCatching { existing.release() }
            }
            return installSelfHealing(wanted)
        }

    /**
     * 按偏好装实例，建不出来时自愈（2.0.3-A Bug 11）。
     *
     * 持久化偏好落在本机建不出来的内核上（libmpv 初始化失败、vo 配置坏档）时，如果原样抛异常， 播放页每次起播都失败——整机就是"任何条目都播不了"。这里回落到 ExoPlayer
     * 并**复位偏好**， 让下一次起播（含重启后的播放页）不再回到坏内核；ExoPlayer 也失败才原样抛出。
     *
     * 2.0.3-F1（P3-6）：自愈不能静默——改写的是用户自己的内核选择，必须让他知道。事件经 [backendSelfHeal]
     * 交给播放页，用既有的内核切换提示条（[BackendSwitchOverlay]）告知。
     */
    private fun installSelfHealing(wanted: String): Player =
        try {
            install(wanted, create(wanted))
        } catch (error: Throwable) {
            val fallback = backendFallbackAfterCreateFailure(wanted) ?: throw error
            Timber.e(error, "播放内核 %s 创建失败，自愈回落到 %s 并复位偏好", wanted, fallback)
            appPreferences.setValue(appPreferences.playerBackend, fallback)
            val player = install(fallback, create(fallback))
            publishBackendSelfHeal(failedBackend = wanted, recoveredBackend = fallback)
            player
        }

    private var backendSelfHealSeq = 0L

    private fun publishBackendSelfHeal(failedBackend: String, recoveredBackend: String) {
        backendSelfHealSeq += 1
        _backendSelfHeal.value =
            BackendSelfHeal(
                id = backendSelfHealSeq,
                failedBackend = failedBackend,
                recoveredBackend = recoveredBackend,
            )
    }

    /** 播放页已提示过该事件：只清掉匹配的那一条，避免把更新的自愈事件一起吞掉。 */
    fun consumeBackendSelfHeal(id: Long) {
        _backendSelfHeal.update { current -> if (current?.id == id) null else current }
    }

    /**
     * 原地切换内核（W4-T3a-2）：不 recreate 播放页、不打断会话。
     *
     * 时序（设计定稿 §2）：
     * 1. 复制旧实例的队列与当前条目序号，新建目标内核实例并 install（seek sink / 字幕钩子随之换绑）；
     * 2. `setMediaItems(items, index, snapshot.positionMs)` → `prepare()` → 等轨道就绪（超时只告警）；
     * 3. seek 快照位置 → 倍速 / 字幕延迟 / 音频延迟 / 字幕样式 / 循环模式 / 画面比例；
     * 4. 轨道映射表重选音轨与字幕轨（[EngineTrackMatcher] 三级命中；原字幕关闭保持关闭）；
     * 5. 释放旧实例。
     *
     * 通知 / 锁屏的 `MediaSession` 包的是「实例 + seek 门面」这一对，换实例后由宿主沿用服务既有的 「实例不一致就重建会话」路径重新对齐（见
     * `CinefinPlaybackService.rebuildSessionIfPlayerChanged`）， 不重启服务、不重建播放页。
     *
     * 任何一步抛异常都回滚到旧实例（`rolledBack = true`），由调用方决定是否走重启兜底。 切换期间 [player] 不再按偏好重建实例。
     *
     * 主线程调用（与 media3 约定一致）；挂起等轨道就绪，不阻塞主线程。
     */
    internal suspend fun switchBackendInPlace(
        target: String,
        snapshot: EngineSwitchSnapshot,
        onEngineInstalled: ((Player) -> Unit)? = null,
    ): EngineSwitchOutcome {
        if (target != BACKEND_EXOPLAYER && target != BACKEND_MPV) {
            return EngineSwitchOutcome.Failure("unknown-backend", rolledBack = true)
        }
        if (musicSessionActive) {
            // 音乐会话固定 ExoPlayer（MU-4）：这里换实例会把正在播放的音乐换掉。
            return EngineSwitchOutcome.Failure("music-session", rolledBack = true)
        }
        val previous = engine ?: return EngineSwitchOutcome.Failure("no-engine", rolledBack = false)
        if (instanceBackend == target) return EngineSwitchOutcome.Success(target)

        val previousBackend = instanceBackend
        val previousPlayer = previous.media3Player
        val mediaItems =
            (0 until previousPlayer.mediaItemCount).map { previousPlayer.getMediaItemAt(it) }
        if (mediaItems.isEmpty()) {
            return EngineSwitchOutcome.Failure("empty-queue", rolledBack = true)
        }
        val startIndex = previousPlayer.currentMediaItemIndex.coerceIn(0, mediaItems.lastIndex)

        /*
         * 目标内核先建出来再动状态（2.0.3-A Bug 11）：mpv 初始化失败（`MPVLib.create() == null`）时
         * 直接返回「内核不可用」，旧实例、seek 绑定、字幕钩子都不动——这条失败是可恢复的，
         * 宿主据此保持原内核而不是重启播放页。
         */
        val created: Media3PlaybackEngine =
            try {
                create(target)
            } catch (error: Throwable) {
                Timber.w(error, "目标内核 %s 建不出来（旧内核保持不变）", target)
                return EngineSwitchOutcome.Failure(
                    "engine-unavailable",
                    rolledBack = true,
                    cause = error,
                )
            }

        backendSwitchInProgress = true
        val switchStartedAtMs = SystemClock.elapsedRealtime()
        var replacement: Media3PlaybackEngine? = null
        try {
            replacement = created
            install(target, created)
            val newPlayer = created.media3Player
            // 宿主先把画面输出（PlayerView / TextureView）换到新实例，**再** prepare：
            // mpv 的 vo 在 load 时就绑定输出，晚挂 surface 会卡在 aimagereader 等帧超时（K60 实测）。
            onEngineInstalled?.invoke(newPlayer)
            // 位置未知时按 0 起点载入（不能把 TIME_UNSET 传给内核）
            newPlayer.setMediaItems(
                mediaItems.toMutableList(),
                startIndex,
                snapshot.positionMs.coerceAtLeast(0L),
            )
            // 旧实例不再出声：双实例并行时会双声轨。
            previousPlayer.playWhenReady = false
            created.prepare()
            awaitTracksReady(newPlayer)
            restoreSnapshot(created, snapshot)
            runCatching { previous.release() }
                .onFailure { Timber.w(it, "旧内核实例释放失败（已切到 %s）", target) }
            Timber.i(
                "原地切换内核完成：%s -> %s，条目 %d/%d，快照位置 %d ms，切换后位置 %d ms，耗时 %d ms",
                previousBackend,
                target,
                startIndex,
                mediaItems.size,
                snapshot.positionMs,
                newPlayer.currentPosition,
                SystemClock.elapsedRealtime() - switchStartedAtMs,
            )
            return EngineSwitchOutcome.Success(target)
        } catch (cancellation: CancellationException) {
            rollback(previous, previousBackend, replacement, snapshot.isPlaying)
            throw cancellation
        } catch (error: Throwable) {
            Timber.w(error, "原地切换内核失败：%s -> %s，回滚旧实例", previousBackend, target)
            rollback(previous, previousBackend, replacement, snapshot.isPlaying)
            return EngineSwitchOutcome.Failure("pipeline-error", rolledBack = true, cause = error)
        } finally {
            backendSwitchInProgress = false
        }
    }

    /** 回滚：释放没接管成功的新实例，把旧实例重新放回唯一实例位（播放意图按快照还原）。 */
    private fun rollback(
        previous: Media3PlaybackEngine,
        previousBackend: String?,
        replacement: Media3PlaybackEngine?,
        wasPlaying: Boolean,
    ) {
        if (replacement != null && replacement !== previous) {
            runCatching { replacement.release() }
        }
        engine = previous
        instanceBackend = previousBackend
        (previous as? ExoPlaybackEngine)?.subtitleHooks = exoSubtitleHooks
        seekSink.attach(previous.media3Player)
        runCatching { previous.media3Player.playWhenReady = wasPlaying }
    }

    /**
     * 等新内核把轨道交上来（Exo 要等 media source prepare，mpv 要等 `track-list` 事件）。
     *
     * 超时不视为失败：轨道映射会退化为「沿用新内核的选择」，播放位置与播放态照常恢复。
     */
    private suspend fun awaitTracksReady(player: Player) {
        if (player.currentTracks.groups.isNotEmpty()) return
        val ready =
            withTimeoutOrNull(TRACKS_READY_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    val listener =
                        object : Player.Listener {
                            override fun onTracksChanged(tracks: Tracks) {
                                if (tracks.groups.isEmpty() || !continuation.isActive) return
                                player.removeListener(this)
                                continuation.resume(Unit)
                            }
                        }
                    player.addListener(listener)
                    continuation.invokeOnCancellation { player.removeListener(listener) }
                    // addListener 的回放既可能是同步的，也可能被内核吞掉：补一次现读。
                    if (player.currentTracks.groups.isNotEmpty()) {
                        player.removeListener(listener)
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                }
            }
        if (ready == null) {
            Timber.w("切换内核后 %d ms 内没等到轨道，轨道映射跳过", TRACKS_READY_TIMEOUT_MS)
        }
    }

    /**
     * 恢复顺序按设计定稿：定位 → 倍速 / 延迟 / 样式 → 轨道 → 播放态。
     *
     * 定位**只靠** `setMediaItems(..., startPositionMs)`：两个内核都在 load 时按起点位置落点。 额外再发一次 seek 会让 mpv
     * 的位置跟踪进入「已发起 seek、未收到 restart」的中间态（`getCurrentPosition` 变未知、进度条冻住，K60 实测）。
     */
    private fun restoreSnapshot(engine: Media3PlaybackEngine, snapshot: EngineSwitchSnapshot) {
        engine.setRate(snapshot.playbackRate)
        engine.setSubtitleDelay(snapshot.subtitleDelayMs)
        engine.setAudioDelay(snapshot.audioDelayMs)
        runCatching { engine.setSubtitleStyle(snapshot.subtitleStyle) }
            .onFailure { Timber.w(it, "切换后恢复字幕样式失败") }
        engine.media3Player.repeatMode = snapshot.repeatMode
        (engine.media3Player as? MPVPlayer)?.applyResizeMode(snapshot.resizeMode)
        remapTrack(engine, EngineTrackType.AUDIO, snapshot.audioTrack)
        remapTrack(engine, EngineTrackType.SUBTITLE, snapshot.subtitleTrack, snapshot.subtitlesOff)
        if (snapshot.isPlaying) engine.play() else engine.pause()
    }

    /**
     * 用映射表在新内核里重选轨。
     *
     * 字幕：只有「切换前就是关闭」才写一次关闭（mpv `sid=no` / Exo 禁用文字轨）；其余情况要么按映射表重选， 要么留给上层字幕路由做首次参数下发（Exo
     * 自研渲染接管时没有原生轨可选，不能因此把新内核的字幕关掉）。
     */
    private fun remapTrack(
        engine: Media3PlaybackEngine,
        type: EngineTrackType,
        snapshotTrack: EngineTrack?,
        subtitlesOff: Boolean = false,
    ) {
        if (snapshotTrack == null) {
            if (type == EngineTrackType.SUBTITLE && subtitlesOff) engine.selectTrack(type, null)
            return
        }
        val candidates = runCatching {
            engine.availableTracks(type)
        }
            .onFailure { Timber.w(it, "切换后枚举 %s 轨道失败", type) }
            .getOrDefault(emptyList())
        val matched = EngineTrackMatcher.match(snapshotTrack, candidates) ?: return
        if (engine.selectedTrack(type) == matched.id) return
        engine.selectTrack(type, matched.id)
        Timber.d("切换后轨道重选：type=%s id=%s lang=%s", type, matched.id, matched.language)
    }

    /**
     * 音乐会话入口（W0 冻结，MU-4 / MU-8）：音频强制 ExoPlayer —— 音乐永远不用 mpv。
     *
     * 与 [player] 共用唯一实例与单个 MediaSession：若当前实例是 mpv，会先释放再重建为 ExoPlayer；音乐会话结束后，[player]
     * 仍按用户偏好恢复视频内核。
     */
    fun audioSession(): Player {
        val existing = engine
        if (existing != null && instanceBackend == BACKEND_EXOPLAYER) return existing.media3Player
        if (existing != null) {
            Timber.i("音乐会话要求 ExoPlayer，重建播放器实例")
            seekSink.onPlayerReleased()
            runCatching { existing.release() }
        }
        return install(BACKEND_EXOPLAYER, create(BACKEND_EXOPLAYER))
    }

    /**
     * 音乐会话的播放器微调（W2 R2，MU-4 gapless）。
     *
     * 视频分集依赖 `pauseAtEndOfMediaItems=true`（一集播完先停住、由播放页决定下一集）； 音乐必须关掉它：否则一首播完 `playWhenReady` 就被置
     * false，专辑连播与 gapless 全部失效。 音乐永远走 ExoPlayer（[audioSession]），非 ExoPlayer 实例（mpv）静默忽略。
     */
    fun applyMusicPlaybackTuning(inMusicSession: Boolean) {
        (engine?.media3Player as? ExoPlayer)?.setPauseAtEndOfMediaItems(!inMusicSession)
        // 音效只在音乐会话处理（EQ / ReplayGain）：视频会话透传，退出音乐时把交叉淡化可能留下的音量复位。
        musicAudioEffects.processor.sessionActive = inMusicSession
        if (!inMusicSession) {
            engine?.media3Player?.volume = 1f
        }
    }

    /**
     * 设置音轨延迟（毫秒）；正 = 声音延后。
     *
     * ExoPlayer 下改处理器里的目标值即可（播放中即时生效）；mpv 下写 `audio-delay` 属性。
     */
    fun setAudioDelay(delayMs: Long) {
        val clamped =
            delayMs.coerceIn(-AudioDelayProcessor.MAX_DELAY_MS, AudioDelayProcessor.MAX_DELAY_MS)
        audioDelayProcessor.delayMs = clamped
        (engine?.media3Player as? MPVPlayer)?.setAudioDelay(clamped)
    }

    private fun applySavedAudioDelay() {
        val delayMs = appPreferences.getValue(appPreferences.playerAudioDelayMs)
        if (delayMs != 0L) setAudioDelay(delayMs)
    }

    /**
     * W74 U6：把偏好里现读的 seek 步进推给活动实例。
     *
     * 步进原先只在 [create] 构造期注入：设置页改完，内核自己的 `seekBack()` / `seekForward()`（通知栏 / 媒体会话 / 车机的 ±） 仍按旧值走。±
     * 按钮走 `PlayerViewModel` 的相对 seek，不受这里影响；本方法补齐「内核 seek 命令」这条通路。 实例不存在时什么都不做（下次创建时自然读到新值）。主线程调用，与
     * Media3 约定一致。
     */
    fun applySeekIncrements() {
        val backMs = appPreferences.getValue(appPreferences.playerSeekBackInc)
        val forwardMs = appPreferences.getValue(appPreferences.playerSeekForwardInc)
        val player = engine?.media3Player
        (player as? ExoPlayer)?.apply {
            setSeekBackIncrementMs(backMs)
            setSeekForwardIncrementMs(forwardMs)
        }
        (player as? MPVPlayer)?.setSeekIncrements(backMs, forwardMs)
    }

    /** 释放实例。播放页关闭且不允许后台播放、或服务停止时调用。 */
    fun release() {
        /*
         * W68：音乐会话独立于视频页的「后台播放」开关。
         *
         * 音乐在后台播放时，用户可能经锁屏通知误入视频播放页；视频页退出 / 服务重建的释放请求
         * 不能释放音乐正在使用的实例——否则队列与媒体会话失联，迷你条点播放无响应（P1 缺陷）。
         */
        if (musicSessionActive) {
            Timber.w("音乐会话进行中，忽略释放播放器实例的请求")
            return
        }
        musicSessionActive = false
        musicAudioEffects.processor.sessionActive = false
        val activeEngine = engine ?: return
        engine = null
        instanceBackend = null
        seekSink.onPlayerReleased()
        runCatching { activeEngine.release() }
    }

    private fun install(backend: String, engine: Media3PlaybackEngine): Player {
        this.engine = engine
        instanceBackend = backend
        (engine as? ExoPlaybackEngine)?.subtitleHooks = exoSubtitleHooks
        applySavedAudioDelay()
        seekSink.attach(engine.media3Player)
        return engine.media3Player
    }

    private fun create(backend: String): Media3PlaybackEngine {
        val audioAttributes =
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .setUsage(C.USAGE_MEDIA)
                .build()

        // 每次创建实例都用独立的 trackSelector：实例重建（音乐强制 ExoPlayer）时，
        // 共享的 selector 会在旧实例的释放线程上被 release，触发 "accessed on the wrong thread"
        val trackSelector = DefaultTrackSelector(application)
        trackSelector.setParameters(
            trackSelector
                .buildUponParameters()
                .setTunnelingEnabled(true)
                // 使用用户设定的语言优先级列表（越靠前越优先）
                .setPreferredAudioLanguages(*trackSelectionEngine.audioPriority.toTypedArray())
                .setPreferredTextLanguages(*trackSelectionEngine.subtitlePriority.toTypedArray())
        )

        return when (backend) {
            BACKEND_EXOPLAYER ->
                PlaybackEngineFactory.exo(
                    createExoPlayer(audioAttributes, trackSelector),
                    audioDelayProcessor,
                )
            BACKEND_MPV ->
                PlaybackEngineFactory.mpv(createMpvPlayer(audioAttributes, trackSelector))
            else -> throw RuntimeException("$backend is not a valid player backend")
        }
    }

    /**
     * 播放请求的服务器凭据（W5-T4r）：**每次请求时现读** baseUrl / token。
     *
     * 走 `JellyfinRepository`（`JellyfinApi` 的实时会话状态）而不是缓存快照：登录 / 切换账号 / 换服务器后 不需要重建播放器实例。鉴权头构造与同源判定见
     * `ServerCredentialScope`。
     */
    private val serverAuthDataSourceFactory = ServerAuthDataSourceFactory {
        PlaybackServerCredentials(
            baseUrl = repository.getBaseUrl(),
            accessToken = repository.getAccessToken(),
        )
    }

    private fun createExoPlayer(
        audioAttributes: AudioAttributes,
        trackSelector: DefaultTrackSelector,
    ): ExoPlayer {
        /*
         * 解码策略（W12 反馈 B；W16 优先级调整为 本地硬解 → 服务器解码/转码 → 本地软解）：
         * 硬解优先 = 扩展渲染器兜底 + 解码器自动回退；仅软解 = 扩展渲染器优先。
         * 回退链升到「本地软解」档时强制软解（偏好本身不动，面板显示不变）。
         */
        val decodeMode =
            PlayerDecodeMode.effectiveMode(
                appPreferences.getValue(appPreferences.playerDecodeMode),
                appPreferences.getValue(appPreferences.playerDecodeFallbackStage),
            )
        val renderersFactory =
            CinefinRenderersFactory(
                    application,
                    audioDelayProcessor,
                    musicAudioEffects.processor,
                )
                .setExtensionRendererMode(PlayerDecodeMode.extensionRendererMode(decodeMode))
                .setEnableDecoderFallback(PlayerDecodeMode.decoderFallbackEnabled(decodeMode))
        return ExoPlayer.Builder(application, renderersFactory)
            .setAudioAttributes(audioAttributes, true)
            /*
             * W5-T4r：HTTP 数据源注入 Jellyfin 鉴权头（仅当前服务器同源）。
             * `/Audio/{itemId}/universal` 在 Jellyfin 12 上无鉴权 401、api_key 也 401，只有
             * `Authorization: MediaBrowser Token="…"` 头能 200（见 `network/JellyfinAuth.kt`）。
             */
            .setMediaSourceFactory(DefaultMediaSourceFactory(serverAuthDataSourceFactory))
            /*
             * W68：锁屏 / 后台继续播放（默认开启）必须持有 partial wake lock + wifi lock。
             * 真机实测（K60，锁屏 Dozing）未设置时播放会在缓冲中停滞（speed=0），唤醒才恢复；
             * setWakeMode 由 ExoPlayer 在播放期间自动申请 / 释放（WAKE_LOCK 权限见 AndroidManifest）。
             */
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setTrackSelector(trackSelector)
            // 拔耳机 / 蓝牙断开时自动暂停：不这样做会突然外放（阶段 4.4）
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(appPreferences.getValue(appPreferences.playerSeekBackInc))
            .setSeekForwardIncrementMs(appPreferences.getValue(appPreferences.playerSeekForwardInc))
            .setPauseAtEndOfMediaItems(true)
            .build()
    }

    private fun createMpvPlayer(
        audioAttributes: AudioAttributes,
        trackSelector: DefaultTrackSelector,
    ): MPVPlayer {
        // 回退链的「本地软解」档：mpv 强制 hwdec=no，不再问 hwdec 偏好
        val softwareForced =
            PlayerDecodeFallback.forcesLocalSoftware(
                appPreferences.getValue(appPreferences.playerDecodeFallbackStage)
            )
        return MPVPlayer.Builder(application)
            .setAudioAttributes(audioAttributes, true)
            .setTrackSelectionParameters(trackSelector.parameters)
            .setSeekBackIncrementMs(appPreferences.getValue(appPreferences.playerSeekBackInc))
            .setSeekForwardIncrementMs(appPreferences.getValue(appPreferences.playerSeekForwardInc))
            .setPauseAtEndOfMediaItems(true)
            .setVideoOutput(appPreferences.getValue(appPreferences.playerMpvVo))
            .setAudioOutput(appPreferences.getValue(appPreferences.playerMpvAo))
            .setHwDec(
                if (softwareForced) {
                    PlayerDecodeMode.mpvHwDec(PlayerDecodeMode.SOFTWARE)
                } else {
                    appPreferences.getValue(appPreferences.playerMpvHwdec)
                }
            )
            // W4-T3b：色调映射算法只在构造期写一次选项，播放中改档走 MPVPlayer.applyToneMapping
            .setToneMapping(appPreferences.getValue(PlayerExtraPreferences.toneMapping))
            .build()
    }
}
