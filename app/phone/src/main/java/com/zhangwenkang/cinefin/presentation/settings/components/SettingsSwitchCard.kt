package com.zhangwenkang.cinefin.presentation.settings.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.zhangwenkang.cinefin.core.presentation.components.CinefinSwitch
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
                // W42：开关视觉宽度收到 44dp，与「当前值 / 箭头」共用同一个右侧控制位；
                // 行本身整行可点（SettingsBaseCard），因此缩小视觉尺寸不影响触控目标。
                Box(
                    modifier = Modifier.width(SettingsTrailingWidth),
                    contentAlignment = Alignment.Center,
                ) {
                    // W44：配色统一走 core `CinefinSwitch`（关闭态拇指提亮 + 轨道描边），本页不再自拼颜色。
                    CinefinSwitch(
                        checked = preference.enabled && preference.value,
                        onCheckedChange = { onClick() },
                        enabled = preference.enabled,
                        modifier = Modifier.scale(SettingsSwitchVisualScale),
                    )
                }
            },
        )
    }
}

/** M3 开关原生宽 52dp → 视觉缩到 44dp（0.846），与右侧控制位对齐（W42）。 */
private const val SettingsSwitchVisualScale = 0.846f

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
