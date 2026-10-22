package com.zhangwenkang.cinefin.player.local.domain

import com.zhangwenkang.cinefin.player.core.domain.models.PlaybackMediaIdentity
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerMediaInfo
import java.util.UUID

/**
 * 源媒体与当前流的独立快照。generation 每次新请求 / 切源 / 切码率 / 切内核 / 切轨都递增； 异步结果只有 identity 与 generation 同时匹配时才可提交。
 */
data class PlaybackSnapshotState(
    val identity: PlaybackMediaIdentity? = null,
    val generation: Long = 0L,
    val sourceMediaInfo: PlayerMediaInfo? = null,
    val currentStreamMediaInfo: PlayerMediaInfo? = null,
)

data class PlaybackRequestToken(
    val identity: PlaybackMediaIdentity,
    val generation: Long,
)

fun playbackMediaIdentity(
    itemId: UUID?,
    mediaSourceId: String?,
): PlaybackMediaIdentity? {
    if (itemId == null || mediaSourceId.isNullOrBlank()) return null
    return PlaybackMediaIdentity(itemId = itemId, mediaSourceId = mediaSourceId)
}

fun beginPlaybackRequest(
    previous: PlaybackSnapshotState,
    identity: PlaybackMediaIdentity,
): Pair<PlaybackSnapshotState, PlaybackRequestToken> {
    val generation = previous.generation + 1L
    val next =
        PlaybackSnapshotState(
            identity = identity,
            generation = generation,
            sourceMediaInfo = null,
            currentStreamMediaInfo = null,
        )
    return next to PlaybackRequestToken(identity, generation)
}

fun bindSourceSnapshot(
    state: PlaybackSnapshotState,
    token: PlaybackRequestToken,
    source: PlayerMediaInfo?,
): PlaybackSnapshotState {
    if (!state.matches(token)) return state
    return state.copy(sourceMediaInfo = source)
}

fun acceptCurrentStreamSnapshot(
    state: PlaybackSnapshotState,
    token: PlaybackRequestToken,
    current: PlayerMediaInfo?,
): PlaybackSnapshotState {
    if (!state.matches(token)) return state
    return state.copy(currentStreamMediaInfo = current)
}

fun clearCurrentStreamSnapshot(
    state: PlaybackSnapshotState,
    identity: PlaybackMediaIdentity? = state.identity,
    generation: Long = state.generation,
): PlaybackSnapshotState {
    if (state.identity != identity || state.generation != generation) return state
    return state.copy(currentStreamMediaInfo = null)
}

private fun PlaybackSnapshotState.matches(token: PlaybackRequestToken): Boolean =
    identity == token.identity && generation == token.generation
