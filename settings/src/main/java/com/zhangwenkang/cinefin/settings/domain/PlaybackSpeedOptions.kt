package com.zhangwenkang.cinefin.settings.domain

/**
 * 播放倍速档位（W78 统一为单一来源，用户需求 #39「倍速支持 1.75x」）。
 *
 * 收敛前三处互不一致的档位表：
 * - 播放页倍速面板（Compose 里的 `SpeedOptions`）：0.25 / 0.5 / 0.75 / 1 / 1.25 / 1.5 / 2 / 3 —— **缺 1.75×**；
 * - 旧倍速对话框（`SpeedSelectionDialogFragment`）：0.5 … 2（含 1.75×，但没有 0.25× / 3×）；
 * - 设置页「长按倍速档位」（资源数组 `gesture_speed_multiplier`）：只有 1.5 / 2 / 3。
 *
 * 现在：面板与对话框用 [PLAYBACK]，长按手势档位用 [LONG_PRESS]，偏好字符串一律经 [storageValue]。
 */
object PlaybackSpeedOptions {
    /** 播放页 / 倍速对话框的档位（升序）。 */
    val PLAYBACK: List<Float> = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f)

    /** 「长按倍速」手势档位（升序）。 */
    val LONG_PRESS: List<Float> = listOf(1.5f, 1.75f, 2f, 3f)

    /**
     * 偏好里落盘的值（`pref_player_gestures_speed`）。
     *
     * 必须用 `Float.toString()` 而不是 `String.format("%.1f")`：后者会把 1.75 写成 `"1.8"`，读回 `toFloatOrNull()`
     * 得到 1.8 —— 档位再也对不上。`Float.toString()` 给出 `"1.5"` / `"1.75"` / `"2.0"`， 与旧资源数组
     * `gesture_speed_multiplier_values` 的值逐字兼容（存量用户偏好无需迁移）。
     */
    fun storageValue(speed: Float): String = speed.toString()

    /** 档位显示文本：整数档不带小数（`2×`），小数档按原值（`1.75×`）。 */
    fun label(speed: Float): String = if (speed % 1f == 0f) "${speed.toInt()}×" else "$speed×"
}
