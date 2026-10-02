package com.zhangwenkang.cinefin.player.local.domain

import com.zhangwenkang.cinefin.settings.domain.models.Preference

/**
 * W9 播放器波新增的偏好键（§1.6 画面调整 / §1.7 播放结束行为）。
 *
 * 为什么单独放这里：`settings` 模块的 `AppPreferences.kt` 在 W9 由阅读器会话持有（波次红线）， 播放器线需要的新键集中在本文件声明——键名沿用
 * `pref_player_*` 前缀、存储在同一个 `SharedPreferences` 里，`AppPreferences.getValue(preference)` 可直接读写。
 */
object PlayerExtraPreferences {
    /** 画面旋转：0 / 90 / 180 / 270（度）。两个内核都支持，默认 0 = 不旋转 */
    val videoRotation = Preference("pref_player_video_rotation", 0)

    /** 画面镜像：0 = 关，1 = 水平，2 = 垂直。默认 0 */
    val videoMirror = Preference("pref_player_video_mirror", 0)

    /** 画面裁剪：四边各裁掉的百分比（0 / 5 / 10 / 15 / 20）。默认 0 = 不裁 */
    val videoCropPercent = Preference("pref_player_video_crop_percent", 0)

    /** 去黑边：自动放大画面填满画面区，去掉与视频比例不一致留下的黑边。默认关 */
    val videoLetterboxCrop = Preference("pref_player_video_letterbox_crop", false)

    /**
     * 循环模式附加项「播完暂停」：当前一集播完停住，不自动跳下一集。默认关。
     *
     * @deprecated W27 起并入「自动下一集」开关（[autoNextEpisode] 取反）；本键只用于把旧选择迁过来， 迁移后置 false 不再参与判定。
     */
    val pauseAfterCurrentItem = Preference("pref_player_pause_after_item", false)

    /** 自动下一集（W27）：当前一集播完自动切下一集。默认开（保持既有行为） */
    val autoNextEpisode = Preference("pref_player_auto_next_episode", true)

    /** 停在结束帧（W27）：队列 / 整片播完停在最后一帧、不退出播放页。默认关（旧行为 = 关闭播放页） */
    val stayAtEndOfFrame = Preference("pref_player_stay_at_end_frame", false)
}

/** 镜像模式的取值（与 [PlayerExtraPreferences.videoMirror] 对应） */
object VideoMirrorMode {
    const val OFF = 0
    const val HORIZONTAL = 1
    const val VERTICAL = 2
}
