package com.zhangwenkang.cinefin.settings.presentation.models

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.zhangwenkang.cinefin.settings.domain.models.Preference as PreferenceBackend
import com.zhangwenkang.cinefin.settings.presentation.enums.DeviceType

/**
 * W51 整数单选项（如「同时下载数 1–3」）：与 [PreferenceSelect] 同交互，值域是 Int。
 *
 * 选项文案默认取数值本身；需要单位 / 文案时由调用方在描述里说明。
 */
data class PreferenceIntSelect(
    @param:StringRes override val nameStringResource: Int,
    @param:StringRes override val descriptionStringRes: Int? = null,
    @param:DrawableRes override val iconDrawableId: Int? = null,
    override val enabled: Boolean = true,
    override val dependencies: List<PreferenceBackend<Boolean>> = emptyList(),
    override val supportedDeviceTypes: List<DeviceType> = listOf(DeviceType.PHONE, DeviceType.TV),
    val onUpdate: (Int) -> Unit = {},
    val backendPreference: PreferenceBackend<Int>,
    val optionValues: List<Int> = listOf(1, 2, 3),
    val value: Int = -1,
) : Preference
