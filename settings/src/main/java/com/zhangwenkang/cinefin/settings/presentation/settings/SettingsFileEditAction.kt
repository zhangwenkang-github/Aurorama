package com.zhangwenkang.cinefin.settings.presentation.settings

sealed interface SettingsFileEditAction {
    data object OnBackClick : SettingsFileEditAction

    data class OnSave(val text: String) : SettingsFileEditAction
}
