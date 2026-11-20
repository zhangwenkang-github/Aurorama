package com.zhangwenkang.cinefin.music.data

import com.zhangwenkang.cinefin.player.core.domain.models.MusicQueue
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerItem

/**
 * 队列本地编辑纯函数（W21-R2）。
 *
 * 恢复态（播放器尚未装载队列）下用户仍可拖拽 / 移除队列条目，这些编辑只落在恢复快照上； `MusicQueue` 自身没有 removeAt，这里补齐并保持「当前曲目」语义：
 * 删除当前曲目时顺延到同索引（末尾则前移），删除前面的曲目时索引左移。
 */
fun musicQueueRemoveAt(queue: MusicQueue, index: Int): MusicQueue? {
    if (index !in queue.items.indices) return queue
    val items = queue.items.toMutableList().apply { removeAt(index) }
    if (items.isEmpty()) return null
    val currentIndex =
        when {
            index < queue.currentIndex -> queue.currentIndex - 1
            index == queue.currentIndex -> queue.currentIndex.coerceAtMost(items.lastIndex)
            else -> queue.currentIndex
        }
    return queue.copy(
        items = items,
        currentIndex = currentIndex,
        shuffleOrder = null,
        shuffleSeed = null,
    )
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
