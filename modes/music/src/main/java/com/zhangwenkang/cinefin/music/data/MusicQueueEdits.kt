package com.zhangwenkang.cinefin.music.data

import com.zhangwenkang.cinefin.player.core.domain.models.MusicQueue
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerItem

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

/**
 * W5-T3：把 [item] **原位插回**第 [index] 条（队列左滑移除的「撤销」）。
 *
 * 与 [musicQueueRemoveAt] 互逆：越界夹住，插在当前曲目之前时当前索引右移，保持「当前曲目」不变。随机序随之失效（与移除同口径）。
 */
fun musicQueueInsertAt(queue: MusicQueue, index: Int, item: PlayerItem): MusicQueue {
    val target = index.coerceIn(0, queue.items.size)
    val items = queue.items.toMutableList().apply { add(target, item) }
    val currentIndex =
        if (target <= queue.currentIndex) queue.currentIndex + 1 else queue.currentIndex
    return queue.copy(
        items = items,
        currentIndex = currentIndex,
        shuffleOrder = null,
        shuffleSeed = null,
    )
}
