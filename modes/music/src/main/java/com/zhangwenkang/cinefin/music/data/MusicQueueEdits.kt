package com.zhangwenkang.cinefin.music.data

import com.zhangwenkang.cinefin.player.core.domain.models.MusicQueue

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
    return queue.copy(items = items, currentIndex = currentIndex)
}
