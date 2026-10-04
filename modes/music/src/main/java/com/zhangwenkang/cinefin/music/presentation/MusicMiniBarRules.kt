package com.zhangwenkang.cinefin.music.presentation

/**
 * W56：迷你播放条「关闭面板」（×）的落点判定（纯函数，单测覆盖）。
 *
 * 用户 2026-10-04 拍板：行为 = 停止播放并收起面板；队列保留（[MusicQueuePersister] 的存档只写不清），收起后再次选择曲目即可恢复播放。
 */
internal enum class MusicMiniBarDismissTarget {
    /** 没有可关闭的会话（理论上面板此时不可见）。 */
    None,
    /** 活动会话：停止播放（清内存队列；磁盘存档保留）。 */
    StopPlayback,
    /** 恢复态（只有内存快照、没有播放会话）：只丢弃本次进页面的展示快照，磁盘存档保留。 */
    DropRestoredQueue,
}

internal fun musicMiniBarDismissTarget(
    hasLiveQueue: Boolean,
    hasRestoredQueue: Boolean,
): MusicMiniBarDismissTarget =
    when {
        hasLiveQueue -> MusicMiniBarDismissTarget.StopPlayback
        hasRestoredQueue -> MusicMiniBarDismissTarget.DropRestoredQueue
        else -> MusicMiniBarDismissTarget.None
    }

/**
 * W66b：迷你条时间文本（纯函数，单测覆盖）——`02:31 / 04:56`；总时长未知（0）时回 `--:--`。
 *
 * 以等宽字体（`CinefinType.MonoDataSmall`）单独占位展示、永不被状态文本挤掉（用户 2026-10-04 口径： 时间必须完整显示；状态「正在播放 / 已暂停 /
 * 上次播放」放不下时省略）。
 */
internal fun musicMiniBarTimeText(currentMs: Long, totalMs: Long): String = buildString {
    append(formatPositionMs(currentMs))
    append(" / ")
    append(if (totalMs > 0L) formatPositionMs(totalMs) else "--:--")
}
