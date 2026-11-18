package com.zhangwenkang.cinefin.player.local.engine.mpv

import androidx.media3.common.Player
import com.zhangwenkang.cinefin.player.core.playback.EngineCapabilities
import com.zhangwenkang.cinefin.player.core.playback.PlaybackEngine
import com.zhangwenkang.cinefin.player.local.engine.Media3PlaybackEngine
import com.zhangwenkang.cinefin.player.local.mpv.MPVPlayer

/**
 * mpv 引擎适配器（W2-T4）。
 *
 * 只做逐一直接委托，不改变任何语义：`MPVPlayer` 本身就是 media3 `Player` 的实现，监听与事件仍按既有路径走。
 *
 * [media3Player] 是 MediaSession、UI 与尚未迁移的既有代码的接入点。
 *
 * 能力位取值见 ADR-0010 的「能力位取值表」。
 */
internal class MpvPlaybackEngine(private val mpvPlayer: MPVPlayer) : Media3PlaybackEngine {
    override val capabilities: EngineCapabilities
        get() = CAPABILITIES

    override val backendName: String
        get() = PlaybackEngine.BACKEND_MPV

    /** 底层 media3 实例；与委托目标同一个对象。 */
    override val media3Player: Player
        get() = mpvPlayer

    /**
     * 直接委托 [MPVPlayer.prepare]。
     *
     * W2-T5-β 已根治 F2 怪癖：prepare 的第一条 loadfile 直接指向目标条目。
     *
     * 新会话路径按目标序号规划命令，0 号条不再被先加载一遍。
     *
     * 保源路径不碰 loadfile。
     *
     * 内核怪癖按约定仍在实现内部收口，接口面不出现「首条 / 重载」语义（ADR-0010）。
     */
    override fun prepare() = mpvPlayer.prepare()

    override fun play() = mpvPlayer.play()

    override fun pause() = mpvPlayer.pause()

    override fun seekTo(positionMs: Long) = mpvPlayer.seekTo(positionMs)

    override fun seekTo(itemIndex: Int, positionMs: Long) = mpvPlayer.seekTo(itemIndex, positionMs)

    override fun setRate(rate: Float) = mpvPlayer.setPlaybackSpeed(rate)

    override fun release() = mpvPlayer.release()

    internal companion object {
        /**
         * mpv 的能力位（2026-11-18 现状）。
         *
         * HLS 原生支持；软解靠 `hwdec=no`；字幕外观走 libass 的 `applySubtitleStyle`；音频延迟写 `audio-delay` 属性；侧载字幕走
         * `addSubtitleFile`。
         *
         * 随机播放不支持：`MPVPlayer.setShuffleModeEnabled` 仍是 `TODO`，`getAvailableCommands()` 也不含
         * `COMMAND_SET_SHUFFLE_MODE`，ViewModel 据此禁用随机（与 `PLAYER_PLAN.md` 记录的现状一致）。
         */
        val CAPABILITIES =
            EngineCapabilities(
                canPlayHls = true,
                supportsSoftwareDecode = true,
                supportsSubtitleStyling = true,
                supportsAudioDelay = true,
                supportsSideloadedSubtitles = true,
                supportsShuffleMode = false,
            )
    }
}
