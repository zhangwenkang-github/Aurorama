package com.zhangwenkang.cinefin.player.local.domain

/**
 * 队列编辑的附加接口（W2 R2 新增）。
 *
 * [MusicPlaybackController] 是 W0 冻结接口，不能再塞成员；"点队列里的某一首"与"从队列移除"这两个 操作单独拆出来，由同一个实现类提供（与
 * [MusicPlaybackStateSource] 同样的处理方式）。
 */
interface MusicQueueEditor {
    /** 跳到队列中的第 [index] 首并立即播放；越界忽略。 */
    fun jumpTo(index: Int)

    /** 从队列移除第 [index] 首；越界忽略。移除当前曲目时播放器自动接管下一首。 */
    fun removeAt(index: Int)
}
