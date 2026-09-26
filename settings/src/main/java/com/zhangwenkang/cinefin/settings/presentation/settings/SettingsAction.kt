package com.zhangwenkang.cinefin.settings.presentation.settings

import com.zhangwenkang.cinefin.settings.presentation.models.Preference

sealed interface SettingsAction {
    data object OnBackClick : SettingsAction

    data class OnUpdate(val preference: Preference) : SettingsAction
}
