package com.zhangwenkang.cinefin.presentation.settings.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.settings.R as SettingsR
import com.zhangwenkang.cinefin.settings.domain.models.Preference
import com.zhangwenkang.cinefin.settings.presentation.models.PreferenceMultiSelect

@Composable
fun SettingsMultiSelectCard(
    preference: PreferenceMultiSelect,
    onUpdate: (value: Set<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val optionValues = stringArrayResource(preference.optionValues)
    val optionNames = stringArrayResource(preference.options)
    val noneString = stringResource(CoreR.string.none)

    val options = remember(preference.nameStringResource) { optionValues.zip(optionNames) }

    val optionsMap = remember(options) { options.toMap() }

    var showDialog by remember { mutableStateOf(false) }

    SettingsBaseCard(
        preference = preference,
        onClick = { showDialog = true },
        modifier = modifier,
    ) {
        SettingsRow(
            title = stringResource(preference.nameStringResource),
            iconRes = preference.iconDrawableId,
            value =
                preference.value
                    .joinToString("、") { key -> optionsMap[key].toString() }
                    .ifEmpty { noneString },
            showChevron = true,
        )
    }

    if (showDialog) {
        SettingsMultiSelectDialog(
            preference = preference,
            options = options,
            onUpdate = { value ->
                showDialog = false
                onUpdate(value)
            },
            onDismissRequest = { showDialog = false },
        )
    }
}

@Preview
@Composable
private fun SettingsSelectCardPreview() {
    CinefinTheme {
        SettingsMultiSelectCard(
            preference =
                PreferenceMultiSelect(
                    nameStringResource =
                        SettingsR.string.pref_player_media_segments_skip_button_type,
                    iconDrawableId = CoreR.drawable.ic_speaker,
                    backendPreference = Preference("", setOf("INTRO", "OUTRO")),
                    options = SettingsR.array.media_segments_type,
                    optionValues = SettingsR.array.media_segments_type_values,
                ),
            onUpdate = {},
        )
    }
}
