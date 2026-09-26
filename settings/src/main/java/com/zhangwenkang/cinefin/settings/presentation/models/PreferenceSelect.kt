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
) : Preference
