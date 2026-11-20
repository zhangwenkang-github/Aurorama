package com.zhangwenkang.cinefin.player.core.domain.models

import kotlin.random.Random

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

/**
 * 随机播放模式（W5 T5，MUSIC-12 双子模式）。
 *
 * - [OFF]：关闭随机；
 * - [ALL]：随机整个队列（当前曲目在随机序里位置不定）；
 * - [KEEP_CURRENT_FIRST]：随机并保持当前曲目居首（当前曲固定后洗牌其余）。
 */
enum class ShuffleMode {
    OFF,
    ALL,
    KEEP_CURRENT_FIRST,
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
 *
 * W5 T5 双队列（MUSIC-09/10/11）：[items] 是「当前播放队列」，[upNext] 是独立的「接下来播放」队列。 ExoPlayer 时间线 = 主队列当前曲之前的部分 +
 * 正在播放的接下来播放条目 + 待播的接下来播放条目 + 主队列剩余； 消费顺序恒为「当前曲 → 接下来播放（FIFO）→ 主队列剩余」。播完的接下来播放条目立即出列
 * （[withTimelineIndex]），不再残留在任何队列里。
 */
data class MusicQueue(
    val items: List<PlayerItem>,
    val currentIndex: Int = 0,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val shuffleEnabled: Boolean = false,
    val source: QueueSource,
    val sourceId: String? = null,
    /** ExoPlayer 当前实际 shuffle 顺序；null 表示旧快照或结构变化后待重建。 */
    val shuffleOrder: List<Int>? = null,
    /** 最近一次实际序的恢复种子；实际序仍是恢复的第一依据。 */
    val shuffleSeed: Long? = null,
    /** 「接下来播放」待播条目（FIFO）：手动插入，优先于主队列消费，播完即出。 */
    val upNext: List<PlayerItem> = emptyList(),
    /** 正在播放的「接下来播放」条目（已出列）；null 表示当前曲目来自主队列。 */
    val upNextPlaying: PlayerItem? = null,
    /** 随机细分（MUSIC-12）：true = 当前曲目固定在随机序首位。 */
    val shuffleKeepCurrentFirst: Boolean = false,
) {
    /** 当前曲目；队列为空时返回 null。 */
    val currentItem: PlayerItem?
        get() = upNextPlaying ?: items.getOrNull(currentIndex)

    /** 随机模式（[shuffleEnabled] + [shuffleKeepCurrentFirst] 的合成视图）。 */
    val shuffleMode: ShuffleMode
        get() =
            when {
                !shuffleEnabled -> ShuffleMode.OFF
                shuffleKeepCurrentFirst -> ShuffleMode.KEEP_CURRENT_FIRST
                else -> ShuffleMode.ALL
            }

    /** 「接下来播放」侧在时间线上占用的条目数（正在播放的 + 待播的）。 */
    val upNextCount: Int
        get() = upNext.size + if (upNextPlaying != null) 1 else 0

    /**
     * 交给 ExoPlayer 的完整时间线：主队列前缀 + [upNextPlaying] + 待播 [upNext] + 主队列剩余。
     *
     * 没有接下来播放入队时就是主队列本身（零拷贝、与旧行为完全一致）。
     */
    val playbackItems: List<PlayerItem>
        get() {
            if (upNextPlaying == null && upNext.isEmpty()) return items
            val anchor = if (items.isEmpty()) 0 else currentIndex.coerceIn(0, items.lastIndex)
            val head = items.take(anchor + 1)
            val tail = items.drop(anchor + 1)
            return head + listOfNotNull(upNextPlaying) + upNext + tail
        }

    /** 当前曲目在 [playbackItems] 中的下标。 */
    val playbackIndex: Int
        get() {
            if (items.isEmpty()) return 0
            val anchor = currentIndex.coerceIn(0, items.lastIndex)
            return anchor + if (upNextPlaying != null) 1 else 0
        }

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
        return copy(
            items = reordered,
            currentIndex = newCurrentIndex,
            shuffleOrder = null,
            shuffleSeed = null,
        )
    }

    /**
     * 播放下一首：「接下来播放」**队首**插入（MU-3 新语义，W5 T5）。
     *
     * 旧实现直接插进主队列当前曲目之后，播完残留在队列里；现在改由独立的 [upNext] 承载， 播完即出。多次「播放下一首」= 后点的那首排在前面（FIFO 语义），与主流播放器一致。
     */
    fun insertNext(item: PlayerItem): MusicQueue = prependUpNext(listOf(item))

    /** 批量插到「接下来播放」队首（保持给定顺序；后插入的批次排在更前面）。 */
    fun prependUpNext(added: List<PlayerItem>): MusicQueue =
        if (added.isEmpty()) this
        else copy(upNext = added + upNext, shuffleOrder = null, shuffleSeed = null)

    /** 加到「接下来播放」队尾。 */
    fun appendUpNext(item: PlayerItem): MusicQueue = appendUpNext(listOf(item))

    /** 批量加到「接下来播放」队尾（保持给定顺序）。 */
    fun appendUpNext(added: List<PlayerItem>): MusicQueue =
        if (added.isEmpty()) this
        else copy(upNext = upNext + added, shuffleOrder = null, shuffleSeed = null)

    /** 移除「接下来播放」第 [index] 条；越界忽略。 */
    fun removeUpNextAt(index: Int): MusicQueue =
        if (index !in upNext.indices) this
        else
            copy(
                upNext = upNext.toMutableList().apply { removeAt(index) },
                shuffleOrder = null,
                shuffleSeed = null,
            )

    /** 清空「接下来播放」（不动主队列；正在播放的曲目不受影响）。 */
    fun clearUpNext(): MusicQueue =
        if (upNext.isEmpty()) this
        else copy(upNext = emptyList(), shuffleOrder = null, shuffleSeed = null)

    /**
     * 播放器时间线下标 → 队列状态（纯函数）。
     *
     * - 落在主队列段：对齐 [currentIndex] 并清掉 [upNextPlaying]；
     * - 落在接下来播放段：该条目成为 [upNextPlaying]（已出列），它前面的待播条目视为已播出、一并出列；
     * - 仍在播当前条目 / 越界：返回自身。
     */
    fun withTimelineIndex(timelineIndex: Int): MusicQueue {
        if (timelineIndex !in playbackItems.indices) return this
        if (items.isEmpty()) return this
        val anchor = currentIndex.coerceIn(0, items.lastIndex)
        val playingIndex = if (upNextPlaying != null) anchor + 1 else -1
        if (timelineIndex == playingIndex) return this
        val blockStart = anchor + 1 + if (upNextPlaying != null) 1 else 0
        return when {
            timelineIndex < blockStart ->
                copy(
                    currentIndex = timelineIndex.coerceIn(0, items.lastIndex),
                    upNextPlaying = null,
                )
            timelineIndex < blockStart + upNext.size -> {
                val consumed = timelineIndex - blockStart
                copy(
                    upNextPlaying = upNext[consumed],
                    upNext = upNext.drop(consumed + 1),
                )
            }
            else ->
                copy(
                    currentIndex =
                        (timelineIndex - upNext.size - if (upNextPlaying != null) 1 else 0)
                            .coerceIn(0, items.lastIndex),
                    upNextPlaying = null,
                    // 跳过了待播段：被跨过的条目按「播完即出」一并出列（时间线同步由 controller 负责）
                    upNext = emptyList(),
                )
        }
    }
}

/** 实际 shuffle 序必须是 `0 until itemCount` 的完整置换。 */
fun isValidShuffleOrder(order: List<Int>?, itemCount: Int): Boolean {
    if (itemCount < 0 || order == null || order.size != itemCount) return false
    if (order.any { index -> index !in 0 until itemCount }) return false
    return order.toSet().size == itemCount
}

/**
 * 随机整个队列（MUSIC-12 模式①）：全部时间线下标整队洗牌，当前曲目不固定位置。
 *
 * 纯函数 + 显式 [random]，同一 seed 可复现，单测直接钉死序列。
 */
fun fullShuffleOrder(itemCount: Int, random: Random): List<Int> =
    (0 until itemCount).shuffled(random)

/** 随机并保持当前曲目居首（MUSIC-12 模式②）：当前下标固定在首位，其余洗牌。 */
fun currentFirstShuffleOrder(itemCount: Int, currentIndex: Int, random: Random): List<Int> {
    if (itemCount <= 0) return emptyList()
    if (currentIndex !in 0 until itemCount) return fullShuffleOrder(itemCount, random)
    val rest = ((0 until itemCount) - currentIndex).shuffled(random)
    return listOf(currentIndex) + rest
}

/**
 * 手动插入优先的随机序：当前曲居首 → 紧随其后的 [priorityCount] 个条目保持 FIFO → 其余洗牌。
 *
 * 「接下来播放」的手动插入永远先于主队列消费，随机模式下也必须如此，所以在随机序里把这几项钉在队首。
 */
fun priorityShuffleOrder(
    itemCount: Int,
    currentIndex: Int,
    priorityCount: Int,
    random: Random,
): List<Int> {
    if (itemCount <= 0) return emptyList()
    val head = currentIndex.coerceIn(0, itemCount - 1)
    val priorityEnd = (head + 1 + priorityCount.coerceAtLeast(0)).coerceAtMost(itemCount)
    val priority = (head + 1 until priorityEnd).toList()
    val rest = ((0 until itemCount) - head - priority.toSet()).shuffled(random)
    return listOf(head) + priority + rest
}
