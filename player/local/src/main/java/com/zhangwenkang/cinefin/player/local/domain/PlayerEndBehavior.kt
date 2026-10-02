package com.zhangwenkang.cinefin.player.local.domain

import androidx.media3.common.Player

/** 一集播完后的去向（W27 播放结束行为） */
enum class PlayerItemEndAction {
    /** 单集循环：回到本集开头 */
    LOOP_CURRENT,
    /** 自动下一集 */
    PLAY_NEXT,
    /** 停在结束帧（自动下一集关闭，或队列已到末尾） */
    STAY_AT_END,
}

/** 队列 / 整片播完后的去向（W27） */
enum class PlayerQueueEndAction {
    /** 旧行为：退出播放页 */
    CLOSE_PLAYER,
    /** 停在最后一帧，不退出播放页 */
    STAY_AT_END,
}

/**
 * 播放结束行为的纯判定（W27）。
 *
 * 与循环模式正交：单集循环优先于「自动下一集」；队列末尾无论自动下一集开没开都停在结束帧， 是否退出播放页由「停在结束帧」单独决定（默认关 = 旧行为）。
 */
object PlayerEndBehavior {

    fun itemEndAction(
        repeatMode: Int,
        hasNextMediaItem: Boolean,
        autoNextEpisode: Boolean,
    ): PlayerItemEndAction =
        when {
            repeatMode == Player.REPEAT_MODE_ONE -> PlayerItemEndAction.LOOP_CURRENT
            !autoNextEpisode -> PlayerItemEndAction.STAY_AT_END
            hasNextMediaItem -> PlayerItemEndAction.PLAY_NEXT
            else -> PlayerItemEndAction.STAY_AT_END
        }

    fun queueEndAction(stayAtEndOfFrame: Boolean): PlayerQueueEndAction =
        if (stayAtEndOfFrame) {
            PlayerQueueEndAction.STAY_AT_END
        } else {
            PlayerQueueEndAction.CLOSE_PLAYER
        }
}
