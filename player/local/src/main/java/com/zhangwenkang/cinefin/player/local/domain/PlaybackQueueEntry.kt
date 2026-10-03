package com.zhangwenkang.cinefin.player.local.domain

import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemKind

/**
 * W58b 视频多选批量播放：播放页「显式队列」中的一个条目。
 *
 * 条目一律是**具体可播放条目**（电影 / 单集）——剧集 / 季在选择侧已解析成下一集 / 第一集， 这样播放页回退重启（Intent 里换的是实际播放条目）也能在队列里按 id 找到同一项。
 */
data class PlaybackQueueEntry(val itemId: UUID, val kind: BaseItemKind)

/** 显式队列只接受这两类条目（视频 / 单集）。 */
private val SupportedQueueKinds = setOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE)

/**
 * 解析 Intent 携带的「条目 id + 类型」并行数组（W58b）。
 *
 * 规则：两个数组按位配对；无法解析的 id / 类型直接丢弃；同一个 id 只保留第一次出现（保持列表序）。
 */
fun parsePlaybackQueueEntries(ids: List<String>?, kinds: List<String>?): List<PlaybackQueueEntry> {
    if (ids.isNullOrEmpty() || kinds.isNullOrEmpty()) return emptyList()
    val entries = mutableListOf<PlaybackQueueEntry>()
    val seen = mutableSetOf<UUID>()
    for ((index, rawId) in ids.withIndex()) {
        val rawKind = kinds.getOrNull(index) ?: break
        val itemId = runCatching { UUID.fromString(rawId) }.getOrNull() ?: continue
        val kind = runCatching { BaseItemKind.fromName(rawKind) }.getOrNull() ?: continue
        if (kind !in SupportedQueueKinds) continue
        if (!seen.add(itemId)) continue
        entries += PlaybackQueueEntry(itemId = itemId, kind = kind)
    }
    return entries
}
