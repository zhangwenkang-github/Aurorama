package com.zhangwenkang.cinefin.player.local.domain

import com.zhangwenkang.cinefin.player.core.domain.models.PlayerItem

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

    /**
     * W78：把 [items] **按给定顺序**批量追加到队尾。
     *
     * 为什么不用 `insertNext` + `move`：那套组合以"当前曲目索引"为锚点，补队列期间播放器**自动播到下一首**
     * （当前索引变化）会把条目摆到错误位置。批量追加只认队尾，与当前曲目无关；一次 `addMediaItems` 也让内核与媒体通知只重建一次。
     */
    fun appendToQueue(items: List<PlayerItem>)

    /**
     * W78：把 [items] **按给定顺序**批量前插到队首，当前曲目索引整体后移 `items.size`。
     *
     * 与 [appendToQueue] 配对：先补"当前之后"的曲目追加队尾、再补"当前之前"的曲目前插队首， 补完后的队列顺序 = 浏览列表顺序。
     */
    fun prependToQueue(items: List<PlayerItem>)
}
