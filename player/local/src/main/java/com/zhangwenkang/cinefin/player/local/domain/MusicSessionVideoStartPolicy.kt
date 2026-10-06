package com.zhangwenkang.cinefin.player.local.domain

/**
 * W76-B10（= D1）：视频「实际起播」时对音乐会话标志要执行的动作。
 *
 * 音乐后台播放会把 [com.zhangwenkang.cinefin.player.local.presentation.PlayerHolder.musicSessionActive]
 * 置真，该标志把 `PlayerHolder.player` 钉死在音频实例上；视频起播链路若不清掉它，同进程内「换内核 / 回退链 / 错误卡片兜底」 会整条被吞（真机 = 解码面板选 mpv
 * 被回弹、错误卡片「改用 mpv 内核」后卡 `00:00 / 00:00`）。
 */
internal enum class MusicSessionVideoStartAction {
    /** 没有音乐会话：视频起播无需处理，播放器按当前偏好走。 */
    NONE,

    /** 音乐仍在播：按协议停音乐（本地即时停 + 异步补发 `Playing/Stopped`）再清标志。 */
    STOP_MUSIC_AND_CLEAR_FLAG,

    /** 标志粘住、但实例上已经是别的条目（视频或空实例）：只清标志，不重复停播—— 实例可能正在放视频，`stop()` 会把视频一起停掉。 */
    CLEAR_STALE_FLAG,
}

/**
 * W76-B10 纯判定：视频起播前如何收掉音乐会话（状态迁移，无副作用，便于单测锁定）。
 *
 * 调用方（[PlaybackCoordinatorImpl.onVideoStartRequested]）必须在**任何** `PlayerHolder.player` 访问之前执行，
 * 否则粘住态会把视频钉在音频实例上。
 *
 * - 标志为 false：无事可做；
 * - 标志为 true 且当前媒体项仍是音乐：音乐真在播 → 停播 + 清标志；
 * - 标志为 true 但当前媒体项不是音乐（含实例已不存在）：标志粘住 → 只清标志。
 */
internal fun musicSessionActionOnVideoStart(
    musicSessionActive: Boolean,
    currentItemIsMusic: Boolean,
): MusicSessionVideoStartAction =
    when {
        !musicSessionActive -> MusicSessionVideoStartAction.NONE
        currentItemIsMusic -> MusicSessionVideoStartAction.STOP_MUSIC_AND_CLEAR_FLAG
        else -> MusicSessionVideoStartAction.CLEAR_STALE_FLAG
    }
