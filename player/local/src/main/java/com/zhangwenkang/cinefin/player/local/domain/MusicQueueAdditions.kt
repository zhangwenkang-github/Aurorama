package com.zhangwenkang.cinefin.player.local.domain

import com.zhangwenkang.cinefin.player.core.domain.models.MusicQueue
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerItem

/**
 * 队列批量入队纯函数（W78）。
 *
 * 补队列不再走 `insertNext` + `move`：那套组合以"当前曲目索引"为锚点，而补队列期间播放器会**自动播到 下一首**（当前索引随之变化），`move`
 * 就会把刚插入的条目摆到错误位置。这里换成两条与当前曲目索引 无关的路径：
 *
 * - [appendItems]：把"当前之后"的曲目按列表序追加到队尾；
 * - [prependItems]：把"当前之前"的曲目按列表**倒序**逐批前插到队首 —— 每批前插后整体仍是列表序。
 *
 * 两者组合后队列顺序 = 浏览列表顺序，且**与"正在播第几首"完全无关**。
 */
internal fun MusicQueue.appendItems(items: List<PlayerItem>): MusicQueue =
    if (items.isEmpty()) this else copy(items = this.items + items)

internal fun MusicQueue.prependItems(items: List<PlayerItem>): MusicQueue =
    if (items.isEmpty()) this
    else copy(items = items + this.items, currentIndex = currentIndex + items.size)
