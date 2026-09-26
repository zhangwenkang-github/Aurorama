package com.zhangwenkang.cinefin.settings.presentation.models

import com.zhangwenkang.cinefin.settings.domain.models.Preference as PreferenceBackend
import com.zhangwenkang.cinefin.settings.presentation.enums.DeviceType

interface Preference {
    val nameStringResource: Int
    val descriptionStringRes: Int?
    val iconDrawableId: Int?
    val enabled: Boolean
    val dependencies: List<PreferenceBackend<Boolean>>
    val supportedDeviceTypes: List<DeviceType>
}
