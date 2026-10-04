package com.zhangwenkang.cinefin.player.local.presentation

import android.app.Application
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.zhangwenkang.cinefin.player.local.R
import com.zhangwenkang.cinefin.player.local.audio.AudioDelayProcessor
import com.zhangwenkang.cinefin.player.local.audio.CinefinRenderersFactory
import com.zhangwenkang.cinefin.player.local.audio.MusicAudioEffectsController
import com.zhangwenkang.cinefin.player.local.domain.PlayerDecodeMode
import com.zhangwenkang.cinefin.player.local.domain.TrackSelectionEngine
import com.zhangwenkang.cinefin.player.local.domain.isPlayingMusicItem
import com.zhangwenkang.cinefin.player.local.mpv.MPVPlayer
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.settings.domain.PlayerDecodeFallback
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * 播放器实例的唯一持有者（阶段 4.1）。
 *
 * 播放器必须活得比播放页久：通知栏、锁屏、后台播放都要求 Activity 销毁后播放器继续存在。 所以创建与释放从 `PlayerViewModel` 挪到这里：播放页与
 * `CinefinPlaybackService` 共用同一实例， 通知栏上的播放/暂停/上下集/快退快进直接作用在这个实例上，不需要跨进程控制器往返。
 *
 * 线程约定：与 Media3 一致，所有调用都在主线程。
 */
@Singleton
class PlayerHolder
@Inject
constructor(
    private val application: Application,
    private val appPreferences: AppPreferences,
    private val musicAudioEffects: MusicAudioEffectsController,
) {
    companion object {
        /** 播放核心取值，与 `AppPreferences.playerBackend` 里存的一致 */
        const val BACKEND_EXOPLAYER = "exoplayer"
        const val BACKEND_MPV = "mpv"
    }

    private var instance: Player? = null
    private var instanceBackend: String? = null

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
        get() = instance

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
        get() = instance?.isPlayingMusicItem() == true

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
            val wanted = appPreferences.getValue(appPreferences.playerBackend)
            val existing = instance
            if (existing != null && instanceBackend == wanted) return existing
            if (existing != null) {
                Timber.i("播放内核切换为 %s，重建播放器实例", wanted)
                runCatching { existing.release() }
            }
            return create(wanted).also {
                instance = it
                instanceBackend = wanted
                applySavedAudioDelay()
            }
        }

    /**
     * 音乐会话入口（W0 冻结，MU-4 / MU-8）：音频强制 ExoPlayer —— 音乐永远不用 mpv。
     *
     * 与 [player] 共用唯一实例与单个 MediaSession：若当前实例是 mpv，会先释放再重建为 ExoPlayer；音乐会话结束后，[player]
     * 仍按用户偏好恢复视频内核。
     */
    fun audioSession(): Player {
        val existing = instance
        if (existing != null && instanceBackend == BACKEND_EXOPLAYER) return existing
        if (existing != null) {
            Timber.i("音乐会话要求 ExoPlayer，重建播放器实例")
            runCatching { existing.release() }
        }
        return create(BACKEND_EXOPLAYER).also {
            instance = it
            instanceBackend = BACKEND_EXOPLAYER
            applySavedAudioDelay()
        }
    }

    /**
     * 音乐会话的播放器微调（W2 R2，MU-4 gapless）。
     *
     * 视频分集依赖 `pauseAtEndOfMediaItems=true`（一集播完先停住、由播放页决定下一集）； 音乐必须关掉它：否则一首播完 `playWhenReady` 就被置
     * false，专辑连播与 gapless 全部失效。 音乐永远走 ExoPlayer（[audioSession]），非 ExoPlayer 实例（mpv）静默忽略。
     */
    fun applyMusicPlaybackTuning(inMusicSession: Boolean) {
        (instance as? ExoPlayer)?.setPauseAtEndOfMediaItems(!inMusicSession)
        // 音效只在音乐会话处理（EQ / ReplayGain）：视频会话透传，退出音乐时把交叉淡化可能留下的音量复位。
        musicAudioEffects.processor.sessionActive = inMusicSession
        if (!inMusicSession) {
            instance?.volume = 1f
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
        (instance as? MPVPlayer)?.setAudioDelay(clamped)
    }

    private fun applySavedAudioDelay() {
        val delayMs = appPreferences.getValue(appPreferences.playerAudioDelayMs)
        if (delayMs != 0L) setAudioDelay(delayMs)
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
        val player = instance ?: return
        instance = null
        instanceBackend = null
        runCatching { player.release() }
    }

    private fun create(backend: String): Player {
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
            BACKEND_EXOPLAYER -> {
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
                        .setExtensionRendererMode(
                            PlayerDecodeMode.extensionRendererMode(decodeMode)
                        )
                        .setEnableDecoderFallback(
                            PlayerDecodeMode.decoderFallbackEnabled(decodeMode)
                        )
                ExoPlayer.Builder(application, renderersFactory)
                    .setAudioAttributes(audioAttributes, true)
                    .setTrackSelector(trackSelector)
                    // 拔耳机 / 蓝牙断开时自动暂停：不这样做会突然外放（阶段 4.4）
                    .setHandleAudioBecomingNoisy(true)
                    .setSeekBackIncrementMs(
                        appPreferences.getValue(appPreferences.playerSeekBackInc)
                    )
                    .setSeekForwardIncrementMs(
                        appPreferences.getValue(appPreferences.playerSeekForwardInc)
                    )
                    .setPauseAtEndOfMediaItems(true)
                    .build()
            }

            BACKEND_MPV -> {
                // 回退链的「本地软解」档：mpv 强制 hwdec=no，不再问 hwdec 偏好
                val softwareForced =
                    PlayerDecodeFallback.forcesLocalSoftware(
                        appPreferences.getValue(appPreferences.playerDecodeFallbackStage)
                    )
                MPVPlayer.Builder(application)
                    .setAudioAttributes(audioAttributes, true)
                    .setTrackSelectionParameters(trackSelector.parameters)
                    .setSeekBackIncrementMs(
                        appPreferences.getValue(appPreferences.playerSeekBackInc)
                    )
                    .setSeekForwardIncrementMs(
                        appPreferences.getValue(appPreferences.playerSeekForwardInc)
                    )
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
                    .build()
            }

            else -> throw RuntimeException("$backend is not a valid player backend")
        }
    }
}
