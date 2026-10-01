package com.zhangwenkang.cinefin.presentation.settings.components

import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors
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
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
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
                    // W6-VIS：开关轨道 = 当前强调色（Lumen 区域即极光青），拇指用配对的深色前景；
                    // 未选中用雾灰轨道 + 发丝线描边，读作"关"而不是"灰色按钮"。
                    colors =
                        SwitchDefaults.colors(
                            checkedTrackColor = media.base,
                            checkedThumbColor = media.onBase,
                            checkedBorderColor = Color.Transparent,
                            uncheckedTrackColor = colors.surfaceContainerHigh,
                            uncheckedThumbColor = colors.onSurfaceVariant,
                            uncheckedBorderColor = colors.outline,
                            disabledCheckedTrackColor = media.base.copy(alpha = 0.38f),
                            disabledUncheckedTrackColor = colors.surfaceContainerHigh,
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
