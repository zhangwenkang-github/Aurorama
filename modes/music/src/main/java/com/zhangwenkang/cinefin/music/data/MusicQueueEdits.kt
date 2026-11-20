package com.zhangwenkang.cinefin.music.data

import com.zhangwenkang.cinefin.player.core.domain.models.MusicQueue

/**
 * 队列本地编辑纯函数（W21-R2）。
 *
 * 恢复态（播放器尚未装载队列）下用户仍可拖拽 / 移除队列条目，这些编辑只落在恢复快照上。索引换算由 [MusicQueue.removeAt] 统一负责（W5-fixpack
 * 起实时路径共用同一语义）；删空队列返回 null，调用方据此清掉恢复快照。
 */
fun musicQueueRemoveAt(queue: MusicQueue, index: Int): MusicQueue? {
    if (index !in queue.items.indices) return queue
    return queue.removeAt(index).takeIf { it.items.isNotEmpty() }
}
