package com.zhangwenkang.cinefin.player.local.domain

import com.zhangwenkang.cinefin.settings.domain.PlayerDecodeFallback

/**
 * mpv 色调映射算法（W4-T3b / VIDEO-09）。
 *
 * 三档取值与 mpv `tone-mapping` 属性同名，写偏好与写属性之间不做二次映射：偏好里存的字面量就是 mpv 的取值， 少一层映射就少一处「面板显示 X、内核实际 Y」的错位可能。
 *
 * 默认 `bt.2390`：mpv 自己的默认值（HDR → SDR 的高光滚降最保守的一档），也是本面板「（默认）」标注的依据。 只有 mpv 内核可调——ExoPlayer 侧的 HDR
 * 直通由设备/解码器决定，本 App 不介入色调映射，面板据此置灰。
 */
object PlayerToneMapping {
    const val BT_2390 = "bt.2390"
    const val MOBIUS = "mobius"
    const val HABLE = "hable"

    /** 面板展示顺序（默认档在最前） */
    val all: List<String> = listOf(BT_2390, MOBIUS, HABLE)

    /** 偏好里出现未知值（旧版本 / 手改）时回到默认档，不把非法值写进 mpv 属性。 */
    fun normalize(value: String?): String = value?.takeIf { it in all } ?: BT_2390

    /** 写 mpv 运行时属性 `tone-mapping` 的取值；与偏好同名，直传。 */
    fun mpvPropertyValue(value: String?): String = normalize(value)

    /**
     * 当前内核能否调色调映射：仅 mpv。
     *
     * Exo 面板把该组整行置灰（`PanelRow(enabled = false)` → `textFaint`），切换偏好也不重启播放——实现里没有 Exo 侧的对应动作。
     */
    fun isAvailableForBackend(backend: String?): Boolean =
        backend == PlayerDecodeFallback.BACKEND_MPV
}
