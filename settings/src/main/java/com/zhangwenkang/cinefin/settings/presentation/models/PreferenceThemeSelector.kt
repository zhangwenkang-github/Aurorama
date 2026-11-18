package com.zhangwenkang.cinefin.settings.presentation.models

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.zhangwenkang.cinefin.settings.domain.models.Preference as PreferenceBackend
import com.zhangwenkang.cinefin.settings.presentation.enums.DeviceType

/**
 * 外观 → 主题选择器（W1-T5）：一张卡并列展示可选主题，每行自带实时预览块。
 *
 * [value] 是 `pref_theme` 的存量原值（未迁移），[dynamicColorsEnabled] 是遗留开关 `pref_dynamic_colors` 的读数，
 * 两者一起决定选中项（解析见 core 的 `resolveThemeSelectorSelection`）。点击只回调 [onSelect]：写 `pref_theme`、清除遗留
 * `pref_dynamic_colors` 键与夜间模式联动均由 `SettingsViewModel` 接线。
 */
data class PreferenceThemeSelector(
    @param:StringRes override val nameStringResource: Int,
    @param:StringRes override val descriptionStringRes: Int? = null,
    @param:DrawableRes override val iconDrawableId: Int? = null,
    override val enabled: Boolean = true,
    override val dependencies: List<PreferenceBackend<Boolean>> = emptyList(),
    override val supportedDeviceTypes: List<DeviceType> = listOf(DeviceType.PHONE),
    val backendPreference: PreferenceBackend<String?>,
    val value: String? = null,
    val dynamicColorsEnabled: Boolean = false,
    val onSelect: (String) -> Unit = {},
) : Preference
