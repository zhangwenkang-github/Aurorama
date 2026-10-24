package com.zhangwenkang.cinefin.player.local.domain

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ShuffleOrder

/**
 * 从 Media3 读取实际 shuffle 序。
 *
 * `ShuffleOrder` 没有直接导出数组的 API；从 first 沿 next 走到 -1 是唯一可验证路径。只有条目数完全一致且得到 `0 until itemCount`
 * 的完整置换时才返回，任何中间异常都降级为 null。
 */
@UnstableApi
internal fun readShuffleOrder(order: ShuffleOrder, itemCount: Int): List<Int>? = runCatching {
    if (itemCount < 0 || order.length != itemCount) return null
    if (itemCount == 0) return emptyList()
    val result = ArrayList<Int>(itemCount)
    val seen = HashSet<Int>(itemCount)
    var index = order.firstIndex
    while (index != -1) {
        if (index !in 0 until itemCount || !seen.add(index) || result.size == itemCount) {
            return null
        }
        result += index
        index = order.getNextIndex(index)
    }
    if (result.size != itemCount) return null
    result
}
    .getOrNull()

internal fun isValidShuffleOrder(order: List<Int>?, itemCount: Int): Boolean =
    com.zhangwenkang.cinefin.player.core.domain.models.isValidShuffleOrder(order, itemCount)
