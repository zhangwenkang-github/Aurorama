package com.zhangwenkang.cinefin.settings.presentation.models

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.zhangwenkang.cinefin.settings.domain.models.Preference as PreferenceBackend
import com.zhangwenkang.cinefin.settings.presentation.enums.DeviceType

data class PreferenceMultiSelect(
    @param:StringRes override val nameStringResource: Int,
    @param:StringRes override val descriptionStringRes: Int? = null,
    @param:DrawableRes override val iconDrawableId: Int? = null,
    override val enabled: Boolean = true,
    override val dependencies: List<PreferenceBackend<Boolean>> = emptyList(),
    override val supportedDeviceTypes: List<DeviceType> = listOf(DeviceType.PHONE, DeviceType.TV),
    val onUpdate: (Set<String>?) -> Unit = {},
    val backendPreference:
        PreferenceBackend<Set<String>>, // Backend preference stores a Set<String>
    val options: Int, // Resource ID for the array of entry strings
    val optionValues: Int, // Resource ID for the array of entry values
    val value: Set<String> = emptySet(), // The current value is a Set of strings
) : Preference
