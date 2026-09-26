package com.zhangwenkang.cinefin.settings.presentation.settings

import com.zhangwenkang.cinefin.settings.presentation.models.PreferenceGroup

data class SettingsState(
    val isLoading: Boolean = false,
    val preferenceGroups: List<PreferenceGroup> = emptyList(),
)
