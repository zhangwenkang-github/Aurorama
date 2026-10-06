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

    /**
     * 手动选定的字幕记忆（W74 #14②）：`媒体 id|主字幕序号|次字幕序号`，`-1` = 该位置显式关闭。
     *
     * 切码率 / 换内核 / 解码回退都会重启播放页（清 ViewModel + recreate），字幕源清单随之重建； 用户手动选过的字幕必须在这个窗口里保持，否则每次切档都要重选一遍。
     * 只记最后一次手动选择（单槽），按媒体 id 校验后才恢复。
     */
    val subtitleManualSelection = Preference("pref_player_subtitle_manual_selection", "")

    /**
     * 循环模式（W74 U2-B / 决策 D-F8）：与 `Player.REPEAT_MODE_*` 同值域，默认 0 = 顺序播放。
     *
     * 面板改档即写；进播放页 / 重建播放器实例（切内核、重开播放页、冷启动）时套用到实例上。
     */
    val repeatMode = Preference("pref_player_repeat_mode", 0)

    /** 随机播放开关（W74 U2-B / 决策 D-F8）：默认关。mpv 内核没有 `COMMAND_SET_SHUFFLE_MODE`，套用时跳过 */
    val shuffle = Preference("pref_player_shuffle", false)
}

/** 镜像模式的取值（与 [PlayerExtraPreferences.videoMirror] 对应） */
object VideoMirrorMode {
    const val OFF = 0
    const val HORIZONTAL = 1
    const val VERTICAL = 2
}
