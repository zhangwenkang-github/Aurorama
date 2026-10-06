package com.zhangwenkang.cinefin.player.local.domain

import androidx.media3.common.Player

/**
 * 循环面板的四档选择（W74 U2-B / 决策 D-F8）：顺序播放 / 列表循环 / 单集循环 / 随机播放。
 *
 * 面板 UI、偏好键与内核 `repeatMode` / `shuffleModeEnabled` 之间的换算集中在这里， 避免「随机」这类 (repeatMode, shuffle) 组合散落在
 * UI 里各写一遍，也便于单测回归持久化取值。
 */
enum class PlayerRepeatChoice {
    OFF,
    ALL,
    ONE,
    SHUFFLE;

    /** 内核 repeatMode 取值：随机 = 一轮播完接着下一轮（列表循环），顺序由内核 shuffle 打乱 */
    fun toRepeatMode(): Int =
        when (this) {
            OFF -> Player.REPEAT_MODE_OFF
            ALL -> Player.REPEAT_MODE_ALL
            SHUFFLE -> Player.REPEAT_MODE_ALL
            ONE -> Player.REPEAT_MODE_ONE
        }

    /** 随机开关：只有 [SHUFFLE] 打开 */
    fun toShuffleEnabled(): Boolean = this == SHUFFLE

    companion object {
        /** 由内核 / 偏好里的 (repeatMode, shuffle) 反推面板档位；shuffle 优先（与面板选中判定一致） */
        fun of(
            repeatMode: Int,
            shuffleEnabled: Boolean,
        ): PlayerRepeatChoice =
            when {
                shuffleEnabled -> SHUFFLE
                repeatMode == Player.REPEAT_MODE_ONE -> ONE
                repeatMode == Player.REPEAT_MODE_ALL -> ALL
                else -> OFF
            }
    }
}
