package com.zhangwenkang.cinefin.settings.presentation.models

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.zhangwenkang.cinefin.settings.domain.models.Preference as PreferenceBackend
import com.zhangwenkang.cinefin.settings.presentation.enums.DeviceType

data class PreferenceIntInput(
    @param:StringRes override val nameStringResource: Int,
    @param:StringRes override val descriptionStringRes: Int? = null,
    @param:DrawableRes override val iconDrawableId: Int? = null,
    override val enabled: Boolean = true,
    override val dependencies: List<PreferenceBackend<Boolean>> = emptyList(),
    override val supportedDeviceTypes: List<DeviceType> = listOf(DeviceType.PHONE, DeviceType.TV),
    val onClick: (Preference) -> Unit = {},
    val backendPreference: PreferenceBackend<Int>,
    @param:StringRes val prefixRes: Int? = null,
    @param:StringRes val suffixRes: Int? = null,
    val value: Int = -1,
    /** 可选输入范围（W57 下载设置）：越界 / 非法输入在写入前自动钳制。 */
    val valueRange: IntRange? = null,
) : Preference {
    fun coerceValue(value: Int): Int =
        valueRange?.let { value.coerceIn(it.first, it.last) } ?: value
}
