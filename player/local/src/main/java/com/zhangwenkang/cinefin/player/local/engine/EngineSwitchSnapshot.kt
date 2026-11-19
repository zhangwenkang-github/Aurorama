package com.zhangwenkang.cinefin.player.local.engine

import com.zhangwenkang.cinefin.player.core.domain.models.SubtitleStyle
import com.zhangwenkang.cinefin.player.core.playback.EngineTrack

/**
 * 原地切换内核的状态快照（W4-T3a-2，设计定稿 §2）。
 *
 * **必须在同一会话内、切换之前采集**：Exo 的轨道 id 随轨道重建漂移，跨会话的快照没有意义。 快照只携带字段，不携带内核 id 的可信性——新内核上的落点由
 * [EngineTrackMatcher] 重新认轨。
 *
 * `positionMs` 取 `PlayerViewModel.confirmedPosition` 口径（mpv 用内核确认位置，Exo 用可播放窗口内的当前位置）。
 */
internal data class EngineSwitchSnapshot(
    /** 切换瞬间的播放位置（毫秒）；[androidx.media3.common.C.TIME_UNSET] / 负数表示未知，恢复时跳过 seek。 */
    val positionMs: Long,
    /** 切换前的播放意图（= `playWhenReady`）；缓冲中的暂停态由上层起播窗口决定，这里不做二次判断。 */
    val isPlaying: Boolean,
    val playbackRate: Float,
    /** media3 `Player.REPEAT_MODE_*`；两个内核都支持，直接写回。 */
    val repeatMode: Int,
    /** `AspectRatioFrameLayout.RESIZE_MODE_*`（画面比例）；恢复时由宿主同时落 PlayerView 与内核。 */
    val resizeMode: Int,
    val subtitleDelayMs: Long,
    val audioDelayMs: Long,
    val subtitleStyle: SubtitleStyle,
    /** 切换前选中的音轨；null = 内核未启用 / 读不到，新内核沿用自身选择。 */
    val audioTrack: EngineTrack?,
    /** 切换前选中的字幕轨；null = 内核没有可读的原生字幕轨（含 Exo 自研渲染接管的情况）。 */
    val subtitleTrack: EngineTrack?,
    /**
     * 切换前字幕是否处于「关闭」语义（自研管线没有主字幕）。
     *
     * 只有它为 true 时新内核才写「关闭」；否则 [subtitleTrack] 为 null 只表示「没有可映射的原生轨」 （Exo 自研 libass
     * 接管时正是这种状态），此时不能把新内核的字幕关掉——交给上层字幕路由恢复。
     */
    val subtitlesOff: Boolean,
)

/** 原地切换的执行结果。 */
internal sealed interface EngineSwitchOutcome {
    /** 新内核已完成恢复并接管实例。 */
    data class Success(val backend: String) : EngineSwitchOutcome

    /**
     * 切换失败且旧内核实例已回滚（`rolledBack = true`）或无法回滚（`false`，调用方走重启兜底）。
     *
     * [reason] 是稳定的短标识（日志 / 兜底决策用），不是给用户看的文案。
     */
    data class Failure(
        val reason: String,
        val rolledBack: Boolean,
        val cause: Throwable? = null,
    ) : EngineSwitchOutcome
}
