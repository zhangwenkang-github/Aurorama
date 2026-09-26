package com.zhangwenkang.cinefin.presentation.settings.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ClickableSurfaceScale
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.presentation.theme.spacings
import com.zhangwenkang.cinefin.settings.R as SettingsR
import com.zhangwenkang.cinefin.settings.domain.models.Preference
import com.zhangwenkang.cinefin.settings.presentation.models.PreferenceSwitch

@Composable
fun SettingsSwitchCard(
    preference: PreferenceSwitch,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = preference.enabled,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surface,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                disabledContainerColor = MaterialTheme.colorScheme.surface,
            ),
        border =
            ClickableSurfaceDefaults.border(
                focusedBorder =
                    Border(BorderStroke(4.dp, Color.White), shape = RoundedCornerShape(10.dp))
            ),
        scale = ClickableSurfaceScale.None,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(MaterialTheme.spacings.default),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
        ) {
            if (preference.iconDrawableId != null) {
                Icon(
                    painter = painterResource(id = preference.iconDrawableId!!),
                    contentDescription = null,
                )
                Spacer(modifier = Modifier.width(16.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(id = preference.nameStringResource),
                    style = MaterialTheme.typography.titleMedium,
                )
                preference.descriptionStringRes?.let {
                    Spacer(modifier = Modifier.height(MaterialTheme.spacings.extraSmall))
                    Text(
                        text = stringResource(id = it),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            Switch(checked = preference.value, onCheckedChange = null)
        }
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
                    iconDrawableId = null,
                    backendPreference = Preference("", false),
                ),
            onClick = {},
        )
    }
}

@Preview
@Composable
private fun SettingsSwitchCardDisabledPreview() {
    CinefinTheme {
        SettingsSwitchCard(
            preference =
                PreferenceSwitch(
                    nameStringResource = SettingsR.string.settings_use_cache_title,
                    iconDrawableId = null,
                    enabled = false,
                    backendPreference = Preference("", false),
                ),
            onClick = {},
        )
    }
}

@Preview
@Composable
private fun SettingsSwitchCardDescriptionPreview() {
    CinefinTheme {
        SettingsSwitchCard(
            preference =
                PreferenceSwitch(
                    nameStringResource = SettingsR.string.settings_use_cache_title,
                    descriptionStringRes = SettingsR.string.settings_use_cache_summary,
                    iconDrawableId = null,
                    backendPreference = Preference("", false),
                ),
            onClick = {},
        )
    }
}
