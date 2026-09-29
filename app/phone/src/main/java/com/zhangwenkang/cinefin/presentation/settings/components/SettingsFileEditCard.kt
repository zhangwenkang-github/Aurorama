package com.zhangwenkang.cinefin.presentation.settings.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.settings.R as SettingsR
import com.zhangwenkang.cinefin.settings.presentation.models.PreferenceFileEdit

@Composable
fun SettingsFileEditCard(preference: PreferenceFileEdit, modifier: Modifier = Modifier) {
    val fileName = preference.filePath.split("/").last()

    SettingsBaseCard(
        preference = preference,
        onClick = { preference.onClick(preference) },
        modifier = modifier,
    ) {
        SettingsRow(
            title = stringResource(preference.nameStringResource, fileName),
            description = preference.descriptionStringRes?.let { stringResource(it) },
            iconRes = preference.iconDrawableId,
            showChevron = true,
        )
    }
}

@Preview
@Composable
private fun SettingsFileEditCardPreview() {
    CinefinTheme {
        SettingsFileEditCard(
            preference =
                PreferenceFileEdit(
                    nameStringResource = SettingsR.string.settings_category_player,
                    filePath = "",
                )
        )
    }
}

@Preview
@Composable
private fun SettingsFileEditCardDescriptionPreview() {
    CinefinTheme {
        SettingsFileEditCard(
            preference =
                PreferenceFileEdit(
                    nameStringResource = SettingsR.string.settings_category_player,
                    descriptionStringRes = SettingsR.string.settings_category_player,
                    filePath = "",
                )
        )
    }
}

@Preview
@Composable
private fun SettingsFileEditCardIconPreview() {
    CinefinTheme {
        SettingsFileEditCard(
            preference =
                PreferenceFileEdit(
                    nameStringResource = SettingsR.string.settings_category_player,
                    iconDrawableId = CoreR.drawable.ic_play,
                    filePath = "",
                )
        )
    }
}
