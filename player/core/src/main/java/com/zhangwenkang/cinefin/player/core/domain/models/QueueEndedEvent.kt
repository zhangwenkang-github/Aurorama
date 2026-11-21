package com.zhangwenkang.cinefin.player.core.domain.models

import java.util.UUID

/**
 * 音乐队列自然播完事件（W5-T12 Auto DJ，MUSIC-14）。
 *
 * 播放器进入 `Player.STATE_ENDED` 时发出一次，供 Auto DJ 以队列最后一首曲目为种子续播相似曲目。
 *
 * [endedByError] 区分两种收尾：T8 错误跳曲把队列耗到尾部（true，Auto DJ 必须静默跳过，避免抢占失败提示）， 与自然播完（false）。标志由播放控制器内置维护，详见
 * `MusicQueueEndFlag`。
 */
data class QueueEndedEvent(val itemId: UUID, val endedByError: Boolean)
