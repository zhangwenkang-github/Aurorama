package com.zhangwenkang.cinefin.presentation.settings.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.settings.domain.models.Preference
import com.zhangwenkang.cinefin.settings.presentation.models.PreferenceIntSelect

/**
 * W51 整数单选项卡片：行尾显示当前值，点开复用通用单选项对话框。
 *
 * 与 [SettingsSelectCard] 同交互，只是值域为 Int（如「同时下载数」1–3）。
 */
@Composable
fun SettingsIntSelectCard(
    preference: PreferenceIntSelect,
    onUpdate: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options =
        remember(preference.optionValues) {
            preference.optionValues.map { value -> value.toString() to value.toString() }
        }
    val optionsMap = remember(options) { options.toMap() }
    val notSetString = stringResource(CoreR.string.not_set)

    var showDialog by remember { mutableStateOf(false) }

    SettingsBaseCard(
        preference = preference,
        onClick = { showDialog = true },
        modifier = modifier,
    ) {
        SettingsRow(
            title = stringResource(preference.nameStringResource),
            description = preference.descriptionStringRes?.let { stringResource(it) },
            iconRes = preference.iconDrawableId,
            value = optionsMap[preference.value.toString()] ?: notSetString,
            showChevron = true,
        )
    }

    if (showDialog) {
        SettingsOptionsDialog(
            titleRes = preference.nameStringResource,
            options = options,
            selectedValue = preference.value.toString(),
            onUpdate = { value ->
                showDialog = false
                value?.toIntOrNull()?.let(onUpdate)
            },
            onDismissRequest = { showDialog = false },
        )
    }
}

@Preview
@Composable
private fun SettingsIntSelectCardPreview() {
    CinefinTheme {
        SettingsIntSelectCard(
            preference =
                PreferenceIntSelect(
                    nameStringResource = CoreR.string.title_download,
                    backendPreference = Preference("", 2),
                    value = 2,
                ),
            onUpdate = {},
        )
    }
}
