package com.zhangwenkang.cinefin.player.local.domain

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerItem
import java.util.UUID

/** 音乐条目出错后的恢复动作（W5-T8，MUSIC-48）。 */
enum class MusicErrorRecoveryAction {
    /** 同一曲目第一次出错：延迟后重新 prepare（不换曲）。 */
    RETRY,
    /** 同一曲目再次出错：提示用户并自动跳到下一首。 */
    SKIP_TO_NEXT,
    /** 同一曲目再次出错且没有下一首：按停止语义收尾。 */
    STOP,
}

/**
 * 音乐错误重试策略（W5-T8，MUSIC-48）。
 *
 * 纯状态机：同一曲目只重试一次，第二次照样失败就跳下一首 / 停播；切到别的曲目后重置， 因此「同一曲目每轮播放最多重试一次，永不循环重试」。不持有播放器，控制器只把
 * `onPlayerError` 的结果喂进来。
 */
class MusicPlaybackRecovery {
    private var retriedItemId: UUID? = null

    /** 当前曲目出错 → 下一步动作；[hasNext] 表示存在**不同的**下一首（repeat one 不算）。 */
    fun onError(itemId: UUID?, hasNext: Boolean): MusicErrorRecoveryAction {
        if (itemId == null) return MusicErrorRecoveryAction.STOP
        if (retriedItemId != itemId) {
            retriedItemId = itemId
            return MusicErrorRecoveryAction.RETRY
        }
        return if (hasNext) MusicErrorRecoveryAction.SKIP_TO_NEXT else MusicErrorRecoveryAction.STOP
    }

    /** 播放器当前曲目已变化：清掉上一首的重试记忆（同一曲目再回来仍可重试一次）。 */
    fun onItemChanged(itemId: UUID?) {
        if (retriedItemId != null && itemId != retriedItemId) retriedItemId = null
    }

    /** 停止 / 换队列：整轮播放的重试记忆复位。 */
    fun reset() {
        retriedItemId = null
    }
}

/** 是否还有「不同的」下一首；repeat-one 的下一首仍是自己，按没有下一首处理（避免坏源死循环）。 */
internal fun Player.hasNextDistinctMusicItem(): Boolean {
    val next = nextMediaItemIndex
    return next != C.INDEX_UNSET && next != currentMediaItemIndex
}

/**
 * 播放错误是否可判定为「本地文件不可用」（MUSIC-49）。
 *
 * 只有本地 `content://` 源 + 文件不存在 / 无权限才算确认不可用；网络曲目的同类错误仍走重试链。
 */
internal fun isLocalSourceUnavailableError(mediaSourceUri: String?, errorCode: Int): Boolean =
    mediaSourceUri?.startsWith("content://", ignoreCase = true) == true &&
        (errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
            errorCode == PlaybackException.ERROR_CODE_IO_NO_PERMISSION)

/** 队列从 [fromIndex] 起第一首可播条目；越界或全部不可用返回 null（保留队列但不自动出声）。 */
internal fun firstPlayableQueueIndex(items: List<PlayerItem>, fromIndex: Int): Int? {
    if (items.isEmpty() || fromIndex > items.lastIndex) return null
    val start = fromIndex.coerceAtLeast(0)
    for (index in start..items.lastIndex) {
        if (!items[index].unavailable) return index
    }
    return null
}

/** 缓冲指示口径（MUSIC-49）：有播放意图且内核正在缓冲。缓冲不算暂停。 */
internal fun isMusicBuffering(playbackState: Int, playWhenReady: Boolean): Boolean =
    playWhenReady && playbackState == Player.STATE_BUFFERING
