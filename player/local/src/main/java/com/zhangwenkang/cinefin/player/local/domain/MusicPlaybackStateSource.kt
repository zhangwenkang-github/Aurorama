package com.zhangwenkang.cinefin.player.local.domain

import java.util.UUID
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 音乐播放失败事件（W5-T8，MUSIC-48）。
 *
 * 供音乐页非阻塞 snackbar 使用：曲名 / 艺术家用于文案，[skippedToNext] 区分「已跳到下一首」与 「队列结束已停止」两种收尾。
 */
data class MusicPlaybackFailure(
    val itemId: UUID?,
    val name: String,
    val artist: String?,
    val skippedToNext: Boolean,
)

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

    /**
     * 播放失败事件流（W5-T8，MUSIC-48）：重试仍失败 / 本地文件不可用时发出，UI 用非阻塞 snackbar 提示。
     *
     * 事件只在音乐会话内产生；没有订阅者时丢弃，不阻塞播放线程。
     */
    val playbackFailures: SharedFlow<MusicPlaybackFailure>

    /**
     * 是否正在缓冲（W5-T8，MUSIC-49）：`playbackState == STATE_BUFFERING && playWhenReady`。
     *
     * 缓冲中 [isPlaying] 仍为 true（播放意图不变），UI 只叠加轻量缓冲指示，不把播放键渲染成暂停。
     */
    val isBuffering: StateFlow<Boolean>
}
