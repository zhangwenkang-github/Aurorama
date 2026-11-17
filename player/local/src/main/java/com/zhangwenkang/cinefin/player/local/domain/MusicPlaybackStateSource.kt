package com.zhangwenkang.cinefin.player.local.domain

import kotlinx.coroutines.flow.StateFlow

/**
 * 音乐播放状态观察（W1 R2 新增的附加接口）。
 *
 * [MusicPlaybackController] 的接口是 W0 冻结的，不能再塞成员；UI 又需要"播放意图 / 可暂停"
 * 来渲染播放按钮，所以把这条只读观察单独拆成一个接口，由同一个实现类提供。
 */
interface MusicPlaybackStateSource {
    /**
     * 是否有持续播放意图；起播 / 缓冲但 `Player.playWhenReady` 为 true 时仍为 true。
     *
     * 暂停、停止、结束或没有会话时为 false。这样 UI、通知与 MediaSession 不会把缓冲窗口误报为“已暂停”。
     */
    val isPlaying: StateFlow<Boolean>

    /**
     * 当前曲目总时长（毫秒），没有会话 / 内核还没给出时长时为 0。
     *
     * W23-MUSIC 追加：底栏与全屏播放页要显示"当前时间 / 总时长"，[MusicPlaybackStateSource]
     * 是音乐线专门用来暴露只读播放状态的附加接口，照旧往这里加，不动 W0 冻结的 [MusicPlaybackController]。
     */
    val durationMs: StateFlow<Long>
}
