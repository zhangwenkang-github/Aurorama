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
