package com.zhangwenkang.cinefin.music.data

/**
 * 点歌起播后"补队列"的**补入次序**（W3-R3b，纯函数，可 JVM 单测）。
 *
 * 起播时队列里只有被点的那一首（`currentIndex = 0`），其余曲目按下标顺序逐首补入： 先补"当前之后"的曲目（列表顺序，下一首最先就位，经 `insertNext` +
 * `move` 追加到队尾），再补"当前之前"的曲目（倒序，经 `insertNext` + `move` 前移到队首）。
 *
 * 两段都必须用 `move` 把 `insertNext` 插进来的曲目摆到最终位置：`insertNext` 是"下一首播放"语义（固定插到当前曲目之后），
 * 连续插入会把先后顺序倒过来（`MusicQueueFillTest` 有回归用例）。全部补完后，队列顺序与浏览列表完全一致。
 *
 * 越界下标返回空列表（调用方按"队列已变、放弃补齐"处理）。
 */
internal fun musicQueueFillOrder(playedIndex: Int, size: Int): List<Int> {
    if (playedIndex !in 0 until size) return emptyList()
    return (playedIndex + 1 until size).toList() + (playedIndex - 1 downTo 0).toList()
}

/**
 * 把 [musicQueueFillOrder] 的补入次序切成"批量入队"的批次（W78，纯函数，可 JVM 单测）。
 *
 * 批量入队（`appendToQueue` / `prependToQueue`）不再逐首 `insertNext` + `move`，所以"批次内部顺序"
 * 就是最终队列顺序的一部分，写反会悄悄产出错序队列（真机上只表现为"随机播放的曲目顺序很怪"，极难定位）：
 *
 * - `append`：`(playedIndex+1 until size)` 升序分批 —— 按批次顺序追加到队尾即得列表序；
 * - `prepend`：`(playedIndex-1 downTo 0)` **降序分批**（先补靠近当前曲目的那批），**每批内部再升序** ——
 *   整批前插到队首后批内仍是列表序，逐批前插后批次之间也保持列表序。
 */
internal data class MusicQueueFillBatches(
    val append: List<List<Int>>,
    val prepend: List<List<Int>>,
)

internal fun musicQueueFillBatches(
    playedIndex: Int,
    size: Int,
    batchSize: Int,
): MusicQueueFillBatches {
    require(batchSize > 0) { "batchSize 必须为正数" }
    val order = musicQueueFillOrder(playedIndex, size)
    return MusicQueueFillBatches(
        append = order.filter { index -> index > playedIndex }.chunked(batchSize),
        prepend =
            order
                .filter { index -> index < playedIndex }
                .chunked(batchSize)
                .map { batch -> batch.reversed() },
    )
}
