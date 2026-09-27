package com.zhangwenkang.cinefin.player.local.presentation

import android.app.Application
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.zhangwenkang.cinefin.player.local.domain.TrackSelectionEngine
import com.zhangwenkang.cinefin.player.local.mpv.MPVPlayer
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * 播放器实例的唯一持有者（阶段 4.1）。
 *
 * 播放器必须活得比播放页久：通知栏、锁屏、后台播放都要求 Activity 销毁后播放器继续存在。
 * 所以创建与释放从 `PlayerViewModel` 挪到这里：播放页与 `CinefinPlaybackService` 共用同一实例，
 * 通知栏上的播放/暂停/上下集/快退快进直接作用在这个实例上，不需要跨进程控制器往返。
 *
 * 线程约定：与 Media3 一致，所有调用都在主线程。
 */
@Singleton
class PlayerHolder
@Inject
constructor(
    private val application: Application,
    private val appPreferences: AppPreferences,
) {
    companion object {
        /** 播放核心取值，与 `AppPreferences.playerBackend` 里存的一致 */
        const val BACKEND_EXOPLAYER = "exoplayer"
        const val BACKEND_MPV = "mpv"
    }

    private var instance: Player? = null
    private var instanceBackend: String? = null

    private val trackSelector = DefaultTrackSelector(application)
    private val trackSelectionEngine = TrackSelectionEngine(appPreferences)

    /** 当前实例使用的内核；还没创建实例时返回偏好里的值 */
    val backend: String
        get() = instanceBackend ?: appPreferences.getValue(appPreferences.playerBackend)

    /**
     * 播放器实例。按当前偏好创建；偏好里的内核变了（换内核重开播放页）会自动重建，
     * 调用方拿到的永远是"对的内核 + 活的实例"。
     */
    val player: Player
        get() {
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
            }
        }

    /** 释放实例。播放页关闭且不允许后台播放、或服务停止时调用。 */
    fun release() {
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
                val renderersFactory =
                    DefaultRenderersFactory(application)
                        .setExtensionRendererMode(
                            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
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

            BACKEND_MPV ->
                MPVPlayer.Builder(application)
                    .setAudioAttributes(audioAttributes, true)
                    .setTrackSelectionParameters(trackSelector.parameters)
                    .setSeekBackIncrementMs(appPreferences.getValue(appPreferences.playerSeekBackInc))
                    .setSeekForwardIncrementMs(
                        appPreferences.getValue(appPreferences.playerSeekForwardInc)
                    )
                    .setPauseAtEndOfMediaItems(true)
                    .setVideoOutput(appPreferences.getValue(appPreferences.playerMpvVo))
                    .setAudioOutput(appPreferences.getValue(appPreferences.playerMpvAo))
                    .setHwDec(appPreferences.getValue(appPreferences.playerMpvHwdec))
                    .build()

            else -> throw RuntimeException("$backend is not a valid player backend")
        }
    }
}
