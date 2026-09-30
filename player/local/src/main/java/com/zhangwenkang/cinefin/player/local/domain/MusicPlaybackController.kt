package com.zhangwenkang.cinefin.player.local.domain

import com.zhangwenkang.cinefin.player.core.domain.models.MusicQueue
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerItem
import com.zhangwenkang.cinefin.player.core.domain.models.RepeatMode
import kotlinx.coroutines.flow.StateFlow

/**
 * 音乐队列 → ExoPlayer 的唯一入口（W0 冻结接口，ARCHITECTURE §4.2 / §5.2）。
 *
 * 约定（R2 实现时必须遵守）：
 * - 复用 [com.zhangwenkang.cinefin.player.local.presentation.PlayerHolder] 的单实例与单个 MediaSession；
 * - 禁止 UI 直接调用 `player.addMediaItem()` / `player.setMediaItem()`；
 * - 起播前经 [PlaybackCoordinator] 与视频互斥，停止时按 MU-9 上报 `Sessions/Playing/Stopped`；
 * - 线程约定与 Media3 一致：所有调用在主线程。
 *
 * 实现由 W1（R2-SKELETON）提供；接口冻结后如有变更需回到项目负责人评审。
 */
interface MusicPlaybackController {
    /** 当前队列；从未设置过为 null。 */
    val queue: StateFlow<MusicQueue?>

    /** 当前播放位置（毫秒）；无会话时为 0。 */
    val positionMs: StateFlow<Long>

    /** 替换队列并从 [startIndex] 起播（换播放来源 = 替换队列 + 定位）。 */
    fun setQueue(queue: MusicQueue, startIndex: Int = queue.currentIndex)

    /** 播放 / 暂停切换。 */
    fun playPause()

    fun play()

    fun pause()

    fun next()

    fun previous()

    fun seekTo(positionMs: Long)

    fun setRepeatMode(mode: RepeatMode)

    fun setShuffleEnabled(enabled: Boolean)

    /** 队列内拖拽排序，语义见 [MusicQueue.move]。 */
    fun move(fromIndex: Int, toIndex: Int)

    /** 下一首播放，语义见 [MusicQueue.insertNext]。 */
    fun insertNext(item: PlayerItem)

    /** 停止音乐并清空当前队列（视频起播前的互斥调用也用它）。 */
    fun stop()
}
