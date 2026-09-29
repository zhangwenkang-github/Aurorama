package com.zhangwenkang.cinefin.presentation.settings.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.settings.R as SettingsR
import com.zhangwenkang.cinefin.settings.domain.models.Preference
import com.zhangwenkang.cinefin.settings.presentation.models.PreferenceSwitch

/** 开关项：整行可点，尾部就是开关本身。 */
@Composable
fun SettingsSwitchCard(
    preference: PreferenceSwitch,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsBaseCard(preference = preference, onClick = onClick, modifier = modifier) {
        SettingsRow(
            title = stringResource(preference.nameStringResource),
            description = preference.descriptionStringRes?.let { stringResource(it) },
            iconRes = preference.iconDrawableId,
            trailing = {
                Switch(
                    checked = preference.enabled && preference.value,
                    onCheckedChange = { onClick() },
                    enabled = preference.enabled,
                    // 开关用温灰而不是朱砂：朱砂只留给"要播的内容"，
                    // 一屏开关全红会把强调色的分量摊薄。
                    colors =
                        SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.secondary,
                            checkedThumbColor = MaterialTheme.colorScheme.onSecondary,
                        ),
                )
            },
        )
    }
}

@Preview
@Composable
private fun SettingsSwitchCardPreview() {
    CinefinTheme {
        SettingsSwitchCard(
            preference =
                PreferenceSwitch(
                    nameStringResource = SettingsR.string.settings_use_cache_title,
                    backendPreference = Preference("", true),
                    value = false,
                ),
            onClick = {},
        )
    }
}
