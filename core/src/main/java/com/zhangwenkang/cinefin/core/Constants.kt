package com.zhangwenkang.cinefin.core

object Constants {
    // player
    const val GESTURE_EXCLUSION_AREA_VERTICAL = 48
    const val GESTURE_EXCLUSION_AREA_HORIZONTAL = 24
    const val FULL_SWIPE_RANGE_SCREEN_RATIO = 0.66f
    const val ZOOM_SCALE_BASE = 1f
    const val ZOOM_SCALE_THRESHOLD = 0.01f

    /** 横向滑动快进/快退：滑满整屏宽度对应的视频时长比例 */
    const val SEEK_FULL_SWIPE_DURATION_RATIO = 0.12f

    /** 滑满整屏对应的最小时长（毫秒），避免短片一滑就到底 */
    const val SEEK_FULL_SWIPE_MIN_MS = 60_000f

    /** 滑满整屏对应的最大时长（毫秒），避免长片滑动过于迟钝 */
    const val SEEK_FULL_SWIPE_MAX_MS = 600_000f

    /** 滑动加速指数：大于 1 时，短距离滑动更精细、长距离跨度增长更快， 即“滑得越远，快进越多”。 */
    const val SEEK_ACCELERATION_EXPONENT = 1.4f

    /** 手势灵敏度档位：影响横向快进幅度与纵向亮度/音量滑动距离 */
    object GestureSensitivity {
        const val LOW = "low"
        const val STANDARD = "standard"
        const val HIGH = "high"

        /** 横向滑动幅度的倍率：档位越高，同样距离快进越多 */
        fun seekMultiplier(value: String?): Float =
            when (value) {
                LOW -> 0.6f
                HIGH -> 1.6f
                else -> 1f
            }

        /** 纵向滑动从最低到最高所需的屏幕比例：数值越小越灵敏 */
        fun verticalScreenRatio(value: String?): Float =
            when (value) {
                LOW -> 0.85f
                HIGH -> 0.5f
                else -> FULL_SWIPE_RANGE_SCREEN_RATIO
            }
    }

    // favorites
    const val FAVORITE_TYPE_MOVIES = 0
    const val FAVORITE_TYPE_SHOWS = 1
    const val FAVORITE_TYPE_EPISODES = 2
}
