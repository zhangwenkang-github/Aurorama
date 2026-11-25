package com.zhangwenkang.cinefin.settings.presentation.models

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.zhangwenkang.cinefin.settings.domain.models.Preference as PreferenceBackend
import com.zhangwenkang.cinefin.settings.presentation.enums.DeviceType

/**
 * 码率档位的一项。
 *
 * [value] 是 [com.zhangwenkang.cinefin.settings.domain.PlayerStreamingQuality] 的规范值（0 自动 / -1 原始 /
 * 具体 bps）； 标签要么来自资源（自动 / 原始），要么直接来自档位表（`1080p · 20 Mbps`）。
 */
data class StreamingBitrateOption(
    val value: Long,
    val label: String? = null,
    @param:StringRes val labelStringResource: Int? = null,
)

/**
 * 播放串流码率单选项（W4-T23）。
 *
 * 和 [PreferenceSelect] 的区别：值域是 Long 档位而不是字符串。写回**不能**走通用 `setValue(backendPreference)`—— 码率键有旧 Mbps
 * 迁移标记，必须统一走 `AppPreferences.setPlayerStreamingBitrate`（唯一状态源）。
 */
data class PreferenceStreamingBitrate(
    @param:StringRes override val nameStringResource: Int,
    @param:StringRes override val descriptionStringRes: Int? = null,
    @param:DrawableRes override val iconDrawableId: Int? = null,
    override val enabled: Boolean = true,
    override val dependencies: List<PreferenceBackend<Boolean>> = emptyList(),
    override val supportedDeviceTypes: List<DeviceType> = listOf(DeviceType.PHONE, DeviceType.TV),
    val options: List<StreamingBitrateOption> = emptyList(),
    val value: Long = 0L,
) : Preference
