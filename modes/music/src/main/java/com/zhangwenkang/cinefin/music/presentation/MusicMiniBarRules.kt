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
