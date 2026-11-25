package com.zhangwenkang.cinefin.music.presentation

import java.util.UUID

/**
 * W3-T17：点单曲时的队列计划（§4.4 / V2-MUSIC-50）。
 *
 * 只描述「跳到既有位置」还是「追加到队尾再起播」，不涉及解析与播放器调用。
 */
internal sealed interface MusicPlaySongPlan {
    /** 该曲已在队列中：不重复追加，直接跳到队列里的既有位置。 */
    data class JumpToExisting(val index: Int) : MusicPlaySongPlan

    /** 不在队列中：把 [enqueueItemIds] 追加到队尾，并从目标曲在「现有队列 + 追加」中的下标起播。 */
    data class AppendAndPlay(
        val enqueueItemIds: List<UUID>,
        val targetIndex: Int,
    ) : MusicPlaySongPlan
}

/**
 * W3-T17：点单曲 → 队列计划（纯函数）。
 *
 * - `existingItemIds` = 当前活动队列（无会话时用恢复快照）的条目 id 序列；
 * - `tailItemIds` = 本次希望放进队列的曲目：库列表只放被点的一首；专辑 / 艺术家 / 歌单等详情层放「被点曲到列表结尾」， 用于保持列表连续播放（不覆盖已有点击序列）；
 * - 目标曲已在队列 → [MusicPlaySongPlan.JumpToExisting]（按首次出现位置）；
 * - 否则追加 [tailItemIds]（若其中不含目标曲则补到最前），目标下标 = 现有队列长度 + 目标在追加批次中的位置。
 */
internal fun musicPlaySongPlan(
    existingItemIds: List<UUID>,
    clickedItemId: UUID,
    tailItemIds: List<UUID>,
): MusicPlaySongPlan {
    val existingIndex = existingItemIds.indexOfFirst { itemId -> itemId == clickedItemId }
    if (existingIndex >= 0) return MusicPlaySongPlan.JumpToExisting(existingIndex)
    val enqueue =
        if (tailItemIds.contains(clickedItemId)) {
            tailItemIds
        } else {
            listOf(clickedItemId) + tailItemIds
        }
    val targetIndex =
        existingItemIds.size + enqueue.indexOfFirst { itemId -> itemId == clickedItemId }
    return MusicPlaySongPlan.AppendAndPlay(
        enqueueItemIds = enqueue,
        targetIndex = targetIndex,
    )
}
