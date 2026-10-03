package com.zhangwenkang.cinefin.player.core.domain.models

/**
 * 音乐重复模式（对应 ExoPlayer 的 repeatMode）。
 *
 * W0 冻结模型（ARCHITECTURE §4.2）；实现由 W1 R2 提供，UI 只消费本模型。
 */
enum class RepeatMode {
    OFF,
    ALL,
    ONE,
}

/** 音乐队列来源，决定"继续播放"时的恢复语义（MU-3）。 */
enum class QueueSource {
    ALBUM,
    ARTIST,
    PLAYLIST,
    FAVORITES,
    SEARCH,
    MANUAL,
}

/**
 * 全局唯一的音乐队列（W0 冻结模型，ARCHITECTURE §4.2）。
 *
 * 复用 [PlayerItem]；"播放第 N 首"由 [currentIndex] 表达。 队列自身的顺序（未随机顺序）始终保留，随机 / 循环由 ExoPlayer 承担。 纯模型 +
 * 纯函数，可单测；UI 与 `MusicPlaybackController` 都只通过本模型描述队列。
 */
data class MusicQueue(
    val items: List<PlayerItem>,
    val currentIndex: Int = 0,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val shuffleEnabled: Boolean = false,
    val source: QueueSource,
    val sourceId: String? = null,
) {
    /** 当前曲目；队列为空时返回 null。 */
    val currentItem: PlayerItem?
        get() = items.getOrNull(currentIndex)

    /**
     * 拖拽排序 / 追加：把 [fromIndex] 处的曲目移到 [toIndex]，并保持"当前曲目"不变（MU-3）。 越界或原地移动返回自身。
     *
     * W58：`toIndex == items.size` 表示**追加到队尾**（拖拽排序不会用到，但批量入队要 把 `insertNext`
     * 插到当前曲目之后的条目摆到队尾）；其余越界值仍返回自身。
     */
    fun move(fromIndex: Int, toIndex: Int): MusicQueue {
        if (fromIndex !in items.indices || toIndex !in 0..items.size || fromIndex == toIndex) {
            return this
        }
        // 先摘出再插入：`toIndex == items.size`（追加队尾）时，摘出后的插入位上界是 size - 1。
        val insertAt = if (toIndex == items.size) items.size - 1 else toIndex
        val reordered = items.toMutableList().apply { add(insertAt, removeAt(fromIndex)) }
        val newCurrentIndex =
            when {
                currentIndex == fromIndex -> insertAt
                fromIndex < currentIndex && toIndex >= currentIndex -> currentIndex - 1
                fromIndex > currentIndex && toIndex <= currentIndex -> currentIndex + 1
                else -> currentIndex
            }
        return copy(items = reordered, currentIndex = newCurrentIndex)
    }

    /** 下一首播放：插到当前曲目之后（MU-3）。 */
    fun insertNext(item: PlayerItem): MusicQueue {
        val insertAt = (currentIndex + 1).coerceIn(0, items.size)
        return copy(items = items.toMutableList().apply { add(insertAt, item) })
    }
}
