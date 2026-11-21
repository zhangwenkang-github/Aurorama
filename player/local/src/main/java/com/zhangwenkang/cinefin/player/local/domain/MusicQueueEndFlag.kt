package com.zhangwenkang.cinefin.player.local.domain

import androidx.media3.common.Player

/**
 * 队列结尾「是否由播放错误导致」的标志（W5-T12 Auto DJ 闸门第 4 格）。
 *
 * 规则（设计定稿五 §2）：
 * - T8 错误跳曲路径（重试仍失败 / 本地源不可用 → 跳下一首）**置位**；
 * - 队列自然推进（`MEDIA_ITEM_TRANSITION_REASON_AUTO`）**复位**——手动切歌（SEEK）与换队列（PLAYLIST_CHANGED）都不算自然推进，
 *   保持置位；
 * - `STATE_ENDED` 收尾时 [consume] 一次，事件里的 `endedByError` 只反映本次队尾的成因。
 *
 * 纯状态，不持有播放器，由 `MusicPlaybackControllerImpl` 喂养，可直接单测。
 */
class MusicQueueEndFlag {
    private var endedByError = false

    /** 错误跳曲：本次队列若走到队尾，成因记为错误。 */
    fun onErrorSkip() {
        endedByError = true
    }

    /** 曲目过渡：只有队列自然推进（AUTO）才把成因复位为自然播完。 */
    fun onMediaItemTransition(reason: Int) {
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) endedByError = false
    }

    /** 队列结束：返回本次成因并复位（只返回一次 true）。 */
    fun consume(): Boolean {
        val value = endedByError
        endedByError = false
        return value
    }

    /** 换队列 / 停止：整轮播放的标志归零。 */
    fun reset() {
        endedByError = false
    }
}
