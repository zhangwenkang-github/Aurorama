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
import com.zhangwenkang.cinefin.settings.R as SettingsR
import com.zhangwenkang.cinefin.settings.domain.models.Preference
import com.zhangwenkang.cinefin.settings.presentation.models.PreferenceDynamicOption
import com.zhangwenkang.cinefin.settings.presentation.models.PreferenceDynamicSelect

/**
 * 运行时选项单选项（服务器媒体库选择）。
 *
 * 选项标签来自服务器数据，但「自动」等固定项仍是资源 ID，所以在这里统一解析成可显示文本。
 */
@Composable
fun SettingsDynamicSelectCard(
    preference: PreferenceDynamicSelect,
    onUpdate: (value: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val notSetString = stringResource(CoreR.string.not_set)
    val options =
        preference.options.map { option ->
            option.value to
                (option.label ?: option.labelStringResource?.let { stringResource(it) }.orEmpty())
        }
    val optionsMap = remember(options) { options.toMap() }

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
            value = optionsMap[preference.value] ?: notSetString,
            showChevron = true,
        )
    }

    if (showDialog) {
        SettingsOptionsDialog(
            titleRes = preference.nameStringResource,
            options = options,
            selectedValue = preference.value,
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
private fun SettingsDynamicSelectCardPreview() {
    CinefinTheme {
        SettingsDynamicSelectCard(
            preference =
                PreferenceDynamicSelect(
                    nameStringResource = SettingsR.string.settings_home_library,
                    backendPreference = Preference("", null),
                    options =
                        listOf(
                            PreferenceDynamicOption(
                                value = null,
                                labelStringResource = SettingsR.string.settings_library_auto,
                            ),
                            PreferenceDynamicOption(value = "1", label = "电影"),
                        ),
                ),
            onUpdate = {},
        )
    }
}
