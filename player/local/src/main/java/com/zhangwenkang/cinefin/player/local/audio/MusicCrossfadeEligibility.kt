package com.zhangwenkang.cinefin.player.local.audio

import androidx.media3.common.Player

/**
 * 交叉淡化触发资格（W5-T7 · MUSIC-06，纯逻辑，可 JVM 单测）。
 *
 * 只有**队列自然推进**（[Player.MEDIA_ITEM_TRANSITION_REASON_AUTO]）进入的曲目才淡化：
 * - AUTO 切歌 → 本曲可淡化（曲首淡入 + 曲尾淡出共用同一资格）；
 * - SEEK（手动上/下一首、点队列跳转）/ PLAYLIST_CHANGED / REPEAT 等 → 本曲不可淡化；
 * - 曲内拖进度（[Player.DISCONTINUITY_REASON_SEEK]）→ 本曲资格永久清除（含曲尾淡出）。
 *
 * 暂停 / 恢复不改变资格：恢复后位置已出淡入窗口，包络自然为 1。
 */
class MusicCrossfadeEligibility {

    var eligible: Boolean = false
        private set

    /** 换队列 / 停止播放：回到"无资格"的初始态。 */
    fun reset() {
        eligible = false
    }

    fun onMediaItemTransition(reason: Int) {
        eligible = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
    }

    fun onPositionDiscontinuity(reason: Int) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) eligible = false
    }
}
