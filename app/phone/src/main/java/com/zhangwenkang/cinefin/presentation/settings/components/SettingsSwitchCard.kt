package com.zhangwenkang.cinefin.presentation.settings.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
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
                // W42：开关视觉宽度收到 44dp，与「当前值 / 箭头」共用同一个右侧控制位；
                // 行本身整行可点（SettingsBaseCard），因此缩小视觉尺寸不影响触控目标。
                Box(
                    modifier = Modifier.width(SettingsTrailingWidth),
                    contentAlignment = Alignment.Center,
                ) {
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
