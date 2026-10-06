package com.zhangwenkang.cinefin.settings.presentation.models

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.zhangwenkang.cinefin.settings.domain.models.Preference as PreferenceBackend
import com.zhangwenkang.cinefin.settings.presentation.enums.DeviceType

data class PreferenceSelect(
    @param:StringRes override val nameStringResource: Int,
    @param:StringRes override val descriptionStringRes: Int? = null,
    @param:DrawableRes override val iconDrawableId: Int? = null,
    override val enabled: Boolean = true,
    override val dependencies: List<PreferenceBackend<Boolean>> = emptyList(),
    override val supportedDeviceTypes: List<DeviceType> = listOf(DeviceType.PHONE, DeviceType.TV),
    val onUpdate: (String?) -> Unit = {},
    val backendPreference: PreferenceBackend<String?>,
    val options: Int,
    val optionValues: Int,
    val optionsIncludeNull: Boolean = false,
    val value: String? = null,
    /**
     * 显示值的动态来源（W74 U7）：非空时优先于直读 [backendPreference]。
     *
     * 「首选语言」这类读数要由另一个键（优先级列表 `pref_*_languages`）派生——手动选轨只改优先级列表， 直读遗留单值键会让设置页读数滞后。传 null（默认）时行为不变。
     */
    val valueProvider: (() -> String?)? = null,
) : Preference
