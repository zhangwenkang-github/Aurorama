package com.zhangwenkang.cinefin.settings.presentation.models

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.zhangwenkang.cinefin.settings.domain.models.Preference as PreferenceBackend
import com.zhangwenkang.cinefin.settings.presentation.enums.DeviceType

/**
 * 动态选项条目（例如服务器返回的媒体库列表）。
 *
 * 与 [PreferenceSelect] 的区别：选项来自运行时数据而不是资源数组，所以标签是字符串； 需要本地化的固定项（如「自动」）用 [labelStringResource] 交给 UI
 * 层解析。
 */
data class PreferenceDynamicOption(
    val value: String?,
    val label: String? = null,
    @param:StringRes val labelStringResource: Int? = null,
)

/** 选项在运行时生成的单选项（服务器媒体库选择）。 */
data class PreferenceDynamicSelect(
    @param:StringRes override val nameStringResource: Int,
    @param:StringRes override val descriptionStringRes: Int? = null,
    @param:DrawableRes override val iconDrawableId: Int? = null,
    override val enabled: Boolean = true,
    override val dependencies: List<PreferenceBackend<Boolean>> = emptyList(),
    override val supportedDeviceTypes: List<DeviceType> = listOf(DeviceType.PHONE, DeviceType.TV),
    val backendPreference: PreferenceBackend<String?>,
    val options: List<PreferenceDynamicOption> = emptyList(),
    val value: String? = null,
) : Preference
